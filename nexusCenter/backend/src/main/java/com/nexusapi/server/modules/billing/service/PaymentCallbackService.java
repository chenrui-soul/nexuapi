package com.nexusapi.server.modules.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.config.PaymentProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.billing.entity.PaymentOrderRow;
import com.nexusapi.server.modules.billing.enums.CreditBucket;
import com.nexusapi.server.modules.billing.enums.CreditType;
import com.nexusapi.server.modules.billing.mapper.PaymentMapper;
import com.nexusapi.server.modules.billing.provider.PaymentCallback;
import com.nexusapi.server.modules.billing.provider.PaymentProviderRegistry;
import com.nexusapi.server.modules.billing.provider.PaymentQueryResult;
import com.nexusapi.server.modules.subscription.service.SubscriptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** 支付成功通知处理：先由渠道验签，再校验订单、渠道和金额，最后幂等入账。 */
@Service
public class PaymentCallbackService {
    private final PaymentProviderRegistry registry;
    private final PaymentMapper mapper;
    private final BillingService billingService;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final SubscriptionService subscriptionService;

    public PaymentCallbackService(PaymentProviderRegistry registry, PaymentMapper mapper,
                                  BillingService billingService, PaymentProperties paymentProperties,
                                  ObjectMapper objectMapper, SubscriptionService subscriptionService) {
        this.registry = registry; this.mapper = mapper; this.billingService = billingService;
        this.paymentProperties = paymentProperties; this.objectMapper = objectMapper;
        this.subscriptionService = subscriptionService;
    }

    @Transactional
    public void processPayment(String provider, Map<String, String> parameters) {
        PaymentCallback callback = registry.get(provider).verifyPaymentCallback(parameters);
        if (!"TRADE_SUCCESS".equals(callback.tradeStatus()) && !"TRADE_FINISHED".equals(callback.tradeStatus())) return;
        PaymentOrderRow order = mapper.lockOrderByNo(callback.outTradeNo());
        if (order == null || !provider.equalsIgnoreCase(order.getPaymentProvider())) throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        if (order.getAmount().setScale(2, RoundingMode.HALF_UP).compareTo(callback.amount().setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_AMOUNT_MISMATCH);
        }
        String eventType = "payment_succeeded";
        if (mapper.insertEvent(UUID.randomUUID(), provider, callback.eventId(), eventType, hash(parameters), json(Map.of(
                "order_no", order.getOrderNo(), "provider_order_id", callback.providerOrderId()
        ))) == 0) return;
        if ("paid".equals(order.getStatus())) { mapper.finishEvent(provider, callback.eventId(), "ignored", null); return; }
        if (!"pending".equals(order.getStatus())) throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        if ("recharge".equals(order.getOrderType())) {
            billingService.credit(order.getUserId(), order.getAmount().multiply(paymentProperties.pointsPerCurrency()),
                    CreditBucket.PERMANENT, CreditType.RECHARGE, "recharge:order:" + order.getId(),
                    "order", order.getOrderNo(), null, Map.of("payment_provider", provider, "order_no", order.getOrderNo()));
        } else if ("subscription".equals(order.getOrderType())) {
            subscriptionService.activatePaidOrder(order);
        } else {
            throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        }
        if (mapper.markPaid(order.getId(), callback.providerOrderId()) != 1) throw new BusinessException(ErrorCode.RECHARGE_ORDER_STATE_CONFLICT);
        mapper.upsertReconciliation(provider, callback.providerOrderId(), order.getId(), callback.amount(), order.getAmount(), "matched",
                json(Map.of("event_id", callback.eventId(), "order_no", order.getOrderNo())));
        mapper.finishEvent(provider, callback.eventId(), "processed", null);
    }

    /** 支付通知丢失时的主动查询收口，与异步通知共用事件幂等和金额校验。 */
    @Transactional
    public boolean processQueriedPayment(String provider, PaymentOrderRow order, PaymentQueryResult query) {
        if (!query.paid() || query.amount() == null || order.getAmount().setScale(2).compareTo(query.amount().setScale(2)) != 0) return false;
        String eventId = "query:" + query.providerOrderId() + ":" + query.tradeStatus();
        if (mapper.insertEvent(UUID.randomUUID(), provider, eventId, "payment_reconciled", hash(Map.of("order_no", order.getOrderNo())), "{}") == 0) return false;
        if ("recharge".equals(order.getOrderType())) {
            billingService.credit(order.getUserId(), order.getAmount().multiply(paymentProperties.pointsPerCurrency()), CreditBucket.PERMANENT,
                    CreditType.RECHARGE, "recharge:order:" + order.getId(), "order", order.getOrderNo(), null,
                    Map.of("payment_provider", provider, "reconciled", true));
        } else if ("subscription".equals(order.getOrderType())) {
            subscriptionService.activatePaidOrder(order);
        }
        if (mapper.markPaid(order.getId(), query.providerOrderId()) != 1) return false;
        mapper.upsertReconciliation(provider, query.providerOrderId(), order.getId(), query.amount(), order.getAmount(), "matched", "{}");
        mapper.finishEvent(provider, eventId, "processed", null);
        return true;
    }

    /** 支持提供退款异步通知的渠道；支付宝标准退款不触发此入口，使用退款查询补偿。 */
    @Transactional
    public void processRefund(String provider, Map<String, String> parameters) {
        var callback = registry.get(provider).verifyRefundCallback(parameters);
        if (callback == null || callback.eventId() == null) throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        if (!callback.succeeded()) return;
        var refund = mapper.lockRefundByRequestNo(callback.outRequestNo());
        if (refund == null || !provider.equalsIgnoreCase(refund.provider())) throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        if (callback.amount() != null && refund.amount().setScale(2).compareTo(callback.amount().setScale(2)) != 0) {
            throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_AMOUNT_MISMATCH);
        }
        if (mapper.insertEvent(UUID.randomUUID(), provider, callback.eventId(), "refund_succeeded", hash(parameters), "{}") == 0) return;
        if (mapper.markRefundSucceeded(refund.refundId(), callback.providerRefundId()) == 1) {
            mapper.markSubscriptionRefunded(refund.subscriptionId());
            mapper.markOrderRefunded(refund.orderId());
        }
        mapper.finishEvent(provider, callback.eventId(), "processed", null);
    }

    private String hash(Map<String, String> value) { try { String canonical = new TreeMap<>(value).toString(); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
}
