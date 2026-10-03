package com.nexusapi.server.modules.billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.config.PaymentProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.billing.dto.RechargeOrderCreateRequest;
import com.nexusapi.server.modules.billing.entity.RechargeOrderRow;
import com.nexusapi.server.modules.billing.enums.CreditBucket;
import com.nexusapi.server.modules.billing.enums.CreditType;
import com.nexusapi.server.modules.billing.mapper.RechargeOrderMapper;
import com.nexusapi.server.modules.billing.vo.RechargeOrderResponse;
import com.nexusapi.server.modules.billing.provider.PaymentOrderResult;
import com.nexusapi.server.modules.billing.provider.PaymentProvider;
import com.nexusapi.server.modules.billing.provider.PaymentProviderRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class RechargeOrderService {
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("10.00");

    private final RechargeOrderMapper orderMapper;
    private final BillingService billingService;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final PaymentProviderRegistry providerRegistry;

    public RechargeOrderService(
            RechargeOrderMapper orderMapper,
            BillingService billingService,
            PaymentProperties paymentProperties,
            ObjectMapper objectMapper,
            PaymentProviderRegistry providerRegistry
    ) {
        this.orderMapper = orderMapper;
        this.billingService = billingService;
        this.paymentProperties = paymentProperties;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
    }

    @Transactional
    public RechargeOrderResponse create(UUID userId, RechargeOrderCreateRequest request, String rawIdempotencyKey) {
        BigDecimal amount = normalizeAmount(request.amount());
        String provider = normalizeProvider(request.paymentProvider());
        String idempotencyKey = userId + ":" + normalizeIdempotencyKey(rawIdempotencyKey);
        RechargeOrderRow existing = orderMapper.findByIdempotency(userId, idempotencyKey);
        if (existing != null) {
            if (existing.getAmount().compareTo(amount) != 0 || !provider.equals(existing.getPaymentProvider())) {
                throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
            }
            return toResponse(existing);
        }
        RechargeOrderRow row = new RechargeOrderRow();
        row.setId(UUID.randomUUID());
        row.setUserId(userId);
        row.setOrderNo("R" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        row.setAmount(amount);
        row.setCurrency("CNY");
        row.setStatus("pending");
        row.setPaymentProvider(provider);
        row.setIdempotencyKey(idempotencyKey);
        PaymentOrderResult payment = new PaymentOrderResult(null, null);
        if ("mock".equals(provider)) {
            assertMockEnabled();
        } else {
            PaymentProvider paymentProvider = providerRegistry.get(provider);
            payment = paymentProvider.createPayment(row.getOrderNo(), amount, "NEXUS 账户充值", "CNY");
            row.setProviderOrderId(payment.providerOrderId());
        }
        row.setMetadataJson(json(Map.of(
                "credited_points", creditedPoints(amount),
                "payment_mode", "mock".equals(provider) ? "mock" : "formal",
                "pay_url", payment.payUrl() == null ? "" : payment.payUrl()
        )));
        row.setCreatedAt(Instant.now());
        orderMapper.insert(row);
        return toResponse(row);
    }

    @Transactional(readOnly = true)
    public RechargeOrderResponse get(UUID userId, UUID orderId) {
        RechargeOrderRow row = orderMapper.findById(orderId, userId);
        if (row == null) throw new BusinessException(ErrorCode.RECHARGE_ORDER_NOT_FOUND);
        return toResponse(row);
    }

    @Transactional
    public RechargeOrderResponse mockPay(UUID userId, UUID orderId) {
        assertMockEnabled();
        RechargeOrderRow row = orderMapper.lockById(orderId, userId);
        if (row == null) throw new BusinessException(ErrorCode.RECHARGE_ORDER_NOT_FOUND);
        if ("paid".equals(row.getStatus())) return toResponse(row);
        if (!"pending".equals(row.getStatus())) {
            throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        }
        String providerOrderId = "mock_" + UUID.randomUUID().toString().replace("-", "");
        billingService.credit(
                userId,
                creditedPoints(row.getAmount()),
                CreditBucket.PERMANENT,
                CreditType.RECHARGE,
                "recharge:order:" + row.getId(),
                "order",
                row.getOrderNo(),
                null,
                Map.of("order_no", row.getOrderNo(), "payment_provider", "mock")
        );
        if (orderMapper.markPaid(row.getId(), userId, providerOrderId) != 1) {
            throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        }
        row.setStatus("paid");
        row.setProviderOrderId(providerOrderId);
        row.setPaidAt(Instant.now());
        return toResponse(row);
    }

    private BigDecimal creditedPoints(BigDecimal amount) {
        return amount.multiply(paymentProperties.pointsPerCurrency()).setScale(12, RoundingMode.UNNECESSARY);
    }

    private RechargeOrderResponse toResponse(RechargeOrderRow row) {
        String payUrl = null;
        try { payUrl = objectMapper.readTree(row.getMetadataJson()).path("pay_url").asText(null); } catch (Exception ignored) { }
        return new RechargeOrderResponse(
                row.getId(), row.getOrderNo(), row.getAmount(), row.getCurrency(), row.getStatus(),
                row.getPaymentProvider(), row.getProviderOrderId(), creditedPoints(row.getAmount()), payUrl,
                row.getPaidAt(), row.getCreatedAt()
        );
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "充值金额最低 10 元", null);
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "充值金额最多保留 2 位小数", null);
        }
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "支付渠道不能为空", null);
        }
        return provider.strip().toLowerCase();
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key 格式无效", null);
        }
        return key.strip();
    }

    private void assertMockEnabled() {
        if (!paymentProperties.mockEnabled()) {
            throw new BusinessException(ErrorCode.PAYMENT_MOCK_DISABLED);
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize recharge metadata", exception);
        }
    }
}
