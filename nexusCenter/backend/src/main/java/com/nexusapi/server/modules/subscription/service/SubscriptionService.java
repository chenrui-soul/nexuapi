package com.nexusapi.server.modules.subscription.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.config.PaymentProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.billing.enums.CreditBucket;
import com.nexusapi.server.modules.billing.enums.CreditType;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.modules.billing.entity.PaymentOrderRow;
import com.nexusapi.server.modules.billing.mapper.PaymentMapper;
import com.nexusapi.server.modules.billing.provider.PaymentOrderResult;
import com.nexusapi.server.modules.billing.provider.PaymentProviderRegistry;
import com.nexusapi.server.modules.subscription.entity.PlanRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionCreditSummaryRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionModelRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionRefundContextRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionServiceGroupRow;
import com.nexusapi.server.modules.subscription.dto.SubscriptionCancelRequest;
import com.nexusapi.server.modules.subscription.dto.SubscriptionOrderCreateRequest;
import com.nexusapi.server.modules.subscription.mapper.SubscriptionMapper;
import com.nexusapi.server.modules.subscription.vo.SubscriptionCancellationResponse;
import com.nexusapi.server.modules.subscription.vo.SubscriptionOverviewResponse;
import com.nexusapi.server.modules.subscription.vo.SubscriptionOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 订阅目录、当前周期和无支付渠道测试开通的业务编排层。 */
@Service
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final SubscriptionMapper mapper;
    private final BillingService billingService;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final PaymentMapper paymentMapper;
    private final PaymentProviderRegistry paymentProviderRegistry;

    public SubscriptionService(
            SubscriptionMapper mapper,
            BillingService billingService,
            PaymentProperties paymentProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            PaymentMapper paymentMapper,
            PaymentProviderRegistry paymentProviderRegistry
    ) {
        this.mapper = mapper;
        this.billingService = billingService;
        this.paymentProperties = paymentProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.paymentMapper = paymentMapper;
        this.paymentProviderRegistry = paymentProviderRegistry;
    }

    @Transactional
    public SubscriptionOverviewResponse overview(UUID userId) {
        mapper.expireDue(userId);
        billingService.expireDueBatches(userId);
        List<SubscriptionOverviewResponse.PlanItem> plans = mapper.findActivePlans().stream()
                .map(this::planResponse).toList();
        SubscriptionRow current = mapper.findCurrent(userId);
        if (current != null && "refund_pending".equals(current.getSubscriptionStatus())) {
            finalizeRefund(userId, current.getSubscriptionId());
            current = mapper.findCurrent(userId);
        }
        return new SubscriptionOverviewResponse(
                plans,
                current == null ? null : currentResponse(current),
                paymentProperties.mockEnabled()
        );
    }

    /** 无正式支付渠道时只允许显式开启的 mock 环境执行开通，正式环境默认拒绝。 */
    @Transactional
    public SubscriptionOverviewResponse activateMock(UUID userId, UUID planId) {
        if (!paymentProperties.mockEnabled()) throw new BusinessException(ErrorCode.PAYMENT_MOCK_DISABLED);
        mapper.expireDue(userId);
        billingService.expireDueBatches(userId);
        PlanRow plan = mapper.findActivePlan(planId);
        if (plan == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "订阅套餐不存在或未开放", null);
        List<SubscriptionServiceGroupRow> planGroups = mapper.findPlanServiceGroups(planId);
        if (planGroups.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "订阅套餐尚未配置可用服务分组", null);
        }
        List<SubscriptionModelRow> planModels = mapper.findPlanModels(planId);
        if (planModels.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "订阅套餐尚未配置可用模型", null);
        }
        // 开通只锁定 active 订阅；退款中的历史订阅仍需在概览中展示，但不能阻塞用户重新开通。
        SubscriptionRow current = mapper.lockCurrent(userId);
        if (current != null && current.getPlanId().equals(planId)) return overview(userId);

        if (current != null) {
            billingService.expireSubscriptionCredits(
                    userId, current.getSubscriptionId(), "subscription_plan_changed"
            );
        }
        mapper.cancelCurrent(userId);
        Instant startsAt = Instant.now();
        Instant expiresAt = expiresAt(startsAt, plan.getBillingCycle());
        UUID subscriptionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String orderNo = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String providerOrderId = "mock_sub_" + UUID.randomUUID().toString().replace("-", "");
        mapper.insertSubscriptionOrder(
                orderId, userId, orderNo, plan.getPrice(), providerOrderId,
                "subscription:order:" + subscriptionId,
                json(Map.of("plan_id", planId.toString(), "plan_code", plan.getCode(),
                        "included_credits", plan.getIncludedCredits(), "payment_mode", "mock"))
        );
        mapper.insert(subscriptionId, userId, planId, orderId, startsAt, expiresAt,
                plan.getConcurrencyLimit(), "mock_payment");
        if (mapper.snapshotPlanServiceGroups(subscriptionId, planId) != planGroups.size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "订阅套餐服务分组配置发生变化，请重试", null);
        }
        if (mapper.snapshotPlanModels(subscriptionId, planId) != planModels.size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "订阅套餐模型配置发生变化，请重试", null);
        }

        if (plan.getIncludedCredits() != null && plan.getIncludedCredits().signum() > 0) {
            billingService.credit(
                    userId,
                    plan.getIncludedCredits(),
                    CreditBucket.EXPIRING,
                    CreditType.GRANT,
                    "subscription:grant:" + subscriptionId,
                    "subscription",
                    subscriptionId.toString(),
                    expiresAt,
                    Map.of("plan_id", planId.toString(), "plan_code", plan.getCode(), "mode", "mock")
            );
        }
        return overview(userId);
    }

    /** 创建正式订阅支付订单；价格、积分、周期和可用能力均以服务端下单快照为准。 */
    @Transactional
    public SubscriptionOrderResponse createOrder(UUID userId, UUID planId, SubscriptionOrderCreateRequest request,
                                                 String rawIdempotencyKey) {
        String provider = request.paymentProvider() == null ? "" : request.paymentProvider().strip().toLowerCase();
        if (!"alipay".equals(provider)) throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
        String key = userId + ":subscription:" + normalizeIdempotencyKey(rawIdempotencyKey);
        PaymentOrderRow existing = paymentMapper.findOrderByIdempotency(userId, key);
        if (existing != null) {
            JsonNode metadata = readJson(existing.getMetadataJson());
            if (!planId.toString().equals(metadata.path("plan_id").asText()) || !provider.equals(existing.getPaymentProvider())) {
                throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
            }
            return subscriptionOrderResponse(existing, metadata);
        }
        PlanRow plan = mapper.findActivePlan(planId);
        if (plan == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "订阅套餐不存在或未开放", null);
        List<SubscriptionServiceGroupRow> groups = mapper.findPlanServiceGroups(planId);
        List<SubscriptionModelRow> models = mapper.findPlanModels(planId);
        if (groups.isEmpty() || models.isEmpty()) throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "订阅套餐能力配置不完整", null);

        UUID orderId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        String orderNo = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        PaymentOrderResult payment = paymentProviderRegistry.get(provider).createPayment(
                orderNo, plan.getPrice(), "NEXUS 订阅 - " + plan.getName(), "CNY"
        );
        Instant placeholderStart = Instant.now();
        String metadata = json(Map.of(
                "plan_id", planId.toString(), "subscription_id", subscriptionId.toString(),
                "plan_code", plan.getCode(), "billing_cycle", plan.getBillingCycle(),
                "included_credits", plan.getIncludedCredits(), "pay_url", payment.payUrl()
        ));
        mapper.insertPendingOrder(orderId, userId, orderNo, plan.getPrice(), provider, key, metadata);
        mapper.insertPending(subscriptionId, userId, planId, orderId, placeholderStart,
                expiresAt(placeholderStart, plan.getBillingCycle()), plan.getConcurrencyLimit());
        if (mapper.snapshotPlanServiceGroups(subscriptionId, planId) != groups.size()
                || mapper.snapshotPlanModels(subscriptionId, planId) != models.size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "套餐配置在下单期间发生变化，请重试", null);
        }
        return new SubscriptionOrderResponse(orderId, subscriptionId, orderNo, money(plan.getPrice()), "CNY",
                "pending", provider, payment.payUrl());
    }

    /** 支付回调事务内激活下单时已固化权益的待支付订阅。 */
    public void activatePaidOrder(PaymentOrderRow order) {
        JsonNode metadata = readJson(order.getMetadataJson());
        UUID subscriptionId = mapper.lockPendingSubscriptionId(order.getId());
        if (subscriptionId == null) throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID, "待支付订阅不存在", null);
        SubscriptionRow current = mapper.lockCurrent(order.getUserId());
        if (current != null) {
            billingService.expireSubscriptionCredits(order.getUserId(), current.getSubscriptionId(), "subscription_plan_changed");
            mapper.cancelCurrent(order.getUserId());
        }
        Instant startsAt = Instant.now();
        Instant expiresAt = expiresAt(startsAt, metadata.path("billing_cycle").asText("monthly"));
        if (mapper.activatePending(subscriptionId, order.getUserId(), startsAt, expiresAt) != 1) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_VERSION_CONFLICT);
        }
        BigDecimal credits = metadata.path("included_credits").decimalValue();
        if (credits.signum() > 0) {
            billingService.credit(order.getUserId(), credits, CreditBucket.EXPIRING, CreditType.GRANT,
                    "subscription:grant:" + subscriptionId, "subscription", subscriptionId.toString(), expiresAt,
                    Map.of("plan_id", metadata.path("plan_id").asText(), "mode", order.getPaymentProvider()));
        }
    }

    /**
     * 取消当前订阅。取消先立即收回套餐权限和可用积分；若存在在途请求，退款会保持 pending，
     * 由后续请求或定时任务在冻结归零后完成 Mock 退款。
     */
    @Transactional
    public SubscriptionCancellationResponse cancel(UUID userId, SubscriptionCancelRequest request) {
        mapper.expireDue(userId);
        billingService.expireDueBatches(userId);
        SubscriptionRow visible = mapper.findCurrent(userId);
        if (visible != null && ("refund_pending".equals(visible.getSubscriptionStatus())
                || "refunded".equals(visible.getSubscriptionStatus()))) {
            return cancellationResponse(visible, mapper.summarizeCredits(visible.getSubscriptionId()).getFrozenCredits());
        }
        SubscriptionRow current = mapper.lockCurrent(userId);
        if (current == null) throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        if (current.getSubscriptionVersion() != request.version()) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_VERSION_CONFLICT);
        }
        billingService.expireSubscriptionCredits(userId, current.getSubscriptionId(), "user_cancelled");
        if (mapper.markRefundPending(
                current.getSubscriptionId(), userId, current.getSubscriptionVersion()
        ) != 1) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_VERSION_CONFLICT);
        }
        return finalizeRefund(userId, current.getSubscriptionId());
    }

    /** 定时任务和结算后的补偿入口：只处理已经没有在途冻结的退款。 */
    public int finalizePendingRefunds(int limit) {
        int completed = 0;
        for (UUID subscriptionId : mapper.findRefundPendingIds(Math.max(1, Math.min(limit, 100)))) {
            try {
                Boolean finalized = transactionTemplate.execute(status -> {
                    SubscriptionRefundContextRow context = mapper.lockRefundContextById(subscriptionId);
                    return context != null && finalizeRefundContext(context);
                });
                if (Boolean.TRUE.equals(finalized)) completed++;
            } catch (RuntimeException exception) {
                // 每条退款使用独立短事务；单条异常不会回滚或阻断同批次其他订阅。
                log.error("subscription_refund_finalize_failed type={}", exception.getClass().getName());
            }
        }
        return completed;
    }

    private SubscriptionCancellationResponse finalizeRefund(UUID userId, UUID subscriptionId) {
        SubscriptionRefundContextRow context = mapper.lockRefundContext(subscriptionId, userId);
        if (context == null) throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        finalizeRefundContext(context);
        SubscriptionRow row = mapper.findCurrent(userId);
        return cancellationResponse(row, context.getFrozenCredits());
    }

    private boolean finalizeRefundContext(SubscriptionRefundContextRow context) {
        if (context.getFrozenCredits() != null && context.getFrozenCredits().signum() > 0) return false;
        String existingRefundStatus = mapper.findRefundStatus(
                context.getSubscriptionId(), context.getUserId()
        );
        if ("succeeded".equals(existingRefundStatus)) return true;
        if (existingRefundStatus != null) return false;
        BigDecimal granted = credits(context.getGrantedCredits());
        BigDecimal refundable = credits(context.getRefundableCredits()).min(granted);
        BigDecimal refundAmount = BigDecimal.ZERO;
        boolean paidOrder = context.getOrderId() != null && "paid".equals(context.getOrderStatus());
        boolean mockPayment = "mock".equalsIgnoreCase(context.getPaymentProvider());
        if (context.getOrderAmount() != null && granted.signum() > 0 && paidOrder) {
            refundAmount = context.getOrderAmount().multiply(refundable)
                    .divide(granted, 2, RoundingMode.HALF_UP);
        }
        String key = "subscription:refund:" + context.getSubscriptionId();
        if (paidOrder && !mockPayment) {
            // 正式支付必须等待支付渠道回调，不能把“已计算退款金额”误当成渠道退款成功。
            mapper.insertPendingRefund(
                    UUID.randomUUID(), context.getSubscriptionId(), context.getUserId(), context.getOrderId(),
                    refundAmount, refundable, key,
                    json(Map.of("mode", "provider_pending",
                            "payment_provider", context.getPaymentProvider() == null
                                    ? "unknown" : context.getPaymentProvider(),
                            "formula", "paid_amount * refundable_credits / granted_credits"))
            );
            return false;
        }
        mapper.insertRefund(
                UUID.randomUUID(), context.getSubscriptionId(), context.getUserId(), context.getOrderId(),
                refundAmount, refundable, key,
                json(Map.of("mode", paidOrder ? "mock" : "no_paid_order",
                        "formula", "paid_amount * refundable_credits / granted_credits"))
        );
        if (paidOrder) {
            mapper.markSubscriptionOrderRefunded(context.getOrderId(), context.getUserId());
        }
        if (mapper.markRefunded(
                context.getSubscriptionId(), context.getUserId(), refundAmount, refundable
        ) != 1) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_VERSION_CONFLICT);
        }
        return true;
    }

    private SubscriptionCancellationResponse cancellationResponse(SubscriptionRow row, BigDecimal frozenCredits) {
        if (row == null) {
            return new SubscriptionCancellationResponse(null, "refund_pending", BigDecimal.ZERO,
                    BigDecimal.ZERO, credits(frozenCredits), true, null);
        }
        BigDecimal frozen = frozenCredits == null ? BigDecimal.ZERO : credits(frozenCredits);
        boolean pending = "refund_pending".equals(row.getSubscriptionStatus()) || frozen.signum() > 0;
        return new SubscriptionCancellationResponse(
                row.getSubscriptionId(), row.getSubscriptionStatus(), money(row.getRefundAmount()),
                credits(row.getRefundableCredits()), frozen, pending, row.getRefundCompletedAt()
        );
    }

    private SubscriptionOverviewResponse.PlanItem planResponse(PlanRow plan) {
        return new SubscriptionOverviewResponse.PlanItem(
                plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(), plan.getBillingCycle(),
                money(plan.getPrice()), credits(plan.getIncludedCredits()), plan.getConcurrencyLimit(),
                features(plan.getEntitlementsJson()), serviceGroups(mapper.findPlanServiceGroups(plan.getId())),
                models(mapper.findPlanModels(plan.getId())),
                plan.isFeatured()
        );
    }

    private SubscriptionOverviewResponse.CurrentSubscription currentResponse(SubscriptionRow row) {
        SubscriptionCreditSummaryRow summary = mapper.summarizeCredits(row.getSubscriptionId());
        BigDecimal included = credits(summary.getGrantedCredits());
        BigDecimal used = credits(summary.getUsedCredits());
        BigDecimal frozen = credits(summary.getFrozenCredits());
        BigDecimal remaining = credits(summary.getRemainingCredits());
        BigDecimal expired = credits(summary.getExpiredCredits());
        return new SubscriptionOverviewResponse.CurrentSubscription(
                row.getSubscriptionId(), row.getPlanId(), row.getName(), row.getBillingCycle(),
                row.getSubscriptionStatus(), row.getStartsAt(), row.getExpiresAt(), row.isAutoRenew(),
                row.getCancelledAt(), included, used, frozen, remaining, expired,
                row.getRefundRequestedAt(), row.getRefundCompletedAt(), money(row.getRefundAmount()),
                credits(row.getRefundableCredits()),
                features(row.getEntitlementsJson()),
                serviceGroups(mapper.findSubscriptionServiceGroups(row.getSubscriptionId())),
                models(mapper.findSubscriptionModels(row.getSubscriptionId())),
                row.getSubscriptionVersion()
        );
    }

    private List<SubscriptionOverviewResponse.ServiceGroupItem> serviceGroups(
            List<SubscriptionServiceGroupRow> rows
    ) {
        return rows.stream().map(row -> new SubscriptionOverviewResponse.ServiceGroupItem(
                row.getId(), row.getCode(), row.getName()
        )).toList();
    }

    private List<SubscriptionOverviewResponse.ModelItem> models(List<SubscriptionModelRow> rows) {
        return rows.stream().map(row -> new SubscriptionOverviewResponse.ModelItem(
                row.getId(), row.getPublicName(), row.getDisplayName(), row.getCapabilityType()
        )).toList();
    }

    private List<String> features(String json) {
        try {
            JsonNode node = objectMapper.readTree(json).path("features");
            if (!node.isArray()) return List.of();
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) result.add(item.asText());
            });
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid plan entitlements", exception);
        }
    }

    private Instant expiresAt(Instant startsAt, String cycle) {
        ZonedDateTime start = startsAt.atZone(BUSINESS_ZONE);
        return switch (cycle) {
            case "quarterly" -> start.plusMonths(3).toInstant();
            case "yearly" -> start.plusYears(1).toInstant();
            case "one_time" -> start.plusYears(100).toInstant();
            default -> start.plusMonths(1).toInstant();
        };
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal credits(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(12, RoundingMode.HALF_UP);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("订阅退款元数据序列化失败", exception);
        }
    }

    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value == null ? "{}" : value); }
        catch (Exception exception) { throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID); }
    }

    private SubscriptionOrderResponse subscriptionOrderResponse(PaymentOrderRow row, JsonNode metadata) {
        return new SubscriptionOrderResponse(row.getId(), UUID.fromString(metadata.path("subscription_id").asText()),
                row.getOrderNo(), money(row.getAmount()), row.getCurrency(), row.getStatus(),
                row.getPaymentProvider(), metadata.path("pay_url").asText(null));
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key 格式无效", null);
        }
        return key.strip();
    }
}
