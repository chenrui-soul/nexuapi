package com.nexusapi.server.modules.subscription.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.subscription.entity.PlanRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionAccessRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionCreditSummaryRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionModelRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionRefundContextRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionRow;
import com.nexusapi.server.modules.subscription.entity.SubscriptionServiceGroupRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 订阅计划目录和当前订阅的持久化边界。 */
@Mapper
public interface SubscriptionMapper {
    String PLAN_COLUMNS = "p.id, p.code, p.name, p.description, p.billing_cycle, p.price, "
            + "p.included_credits, p.concurrency_limit, p.entitlements::text AS entitlements_json, "
            + "p.status, p.display_order, p.featured, p.version, p.created_at, p.updated_at";

    @Select("SELECT " + PLAN_COLUMNS + " FROM plans p WHERE p.status = 'active' ORDER BY p.display_order, p.price, p.id")
    @Results(id = "planRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "entitlements_json", property = "entitlementsJson")
    })
    List<PlanRow> findActivePlans();

    @Select("SELECT " + PLAN_COLUMNS + " FROM plans p WHERE p.id = #{id} AND p.status = 'active' LIMIT 1")
    @ResultMap("planRow")
    PlanRow findActivePlan(@Param("id") UUID id);

    @Select("SELECT " + PLAN_COLUMNS + " FROM plans p WHERE p.id = #{id} LIMIT 1")
    @ResultMap("planRow")
    PlanRow findPlan(@Param("id") UUID id);

    @Select("""
            SELECT g.id, g.code, g.name
              FROM plan_service_groups psg
              JOIN routing_groups g ON g.id = psg.service_group_id
             WHERE psg.plan_id = #{planId}
               AND g.status = 'active'
               AND g.audience != 'internal'
             ORDER BY g.price_multiplier, g.name, g.id
            """)
    @Results(id = "subscriptionServiceGroupRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "code", property = "code"),
            @Result(column = "name", property = "name")
    })
    List<SubscriptionServiceGroupRow> findPlanServiceGroups(@Param("planId") UUID planId);

    @Select("""
            SELECT g.id, g.code, g.name
              FROM subscription_service_groups ssg
              JOIN routing_groups g ON g.id = ssg.service_group_id
             WHERE ssg.subscription_id = #{subscriptionId}
             ORDER BY g.price_multiplier, g.name, g.id
            """)
    @ResultMap("subscriptionServiceGroupRow")
    List<SubscriptionServiceGroupRow> findSubscriptionServiceGroups(
            @Param("subscriptionId") UUID subscriptionId
    );

    @Select("""
            SELECT m.id, m.public_name, m.display_name, m.capability_type
              FROM plan_models pm
              JOIN ai_models m ON m.id = pm.model_id
             WHERE pm.plan_id = #{planId}
             ORDER BY m.capability_type, m.display_name, m.id
            """)
    @Results(id = "subscriptionModelRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "public_name", property = "publicName"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "capability_type", property = "capabilityType")
    })
    List<SubscriptionModelRow> findPlanModels(@Param("planId") UUID planId);

    @Select("""
            SELECT m.id, m.public_name, m.display_name, m.capability_type
              FROM subscription_models sm
              JOIN ai_models m ON m.id = sm.model_id
             WHERE sm.subscription_id = #{subscriptionId}
             ORDER BY m.capability_type, m.display_name, m.id
            """)
    @ResultMap("subscriptionModelRow")
    List<SubscriptionModelRow> findSubscriptionModels(@Param("subscriptionId") UUID subscriptionId);

    @Update("""
            UPDATE subscriptions
               SET status = 'expired', version = version + 1
             WHERE user_id = #{userId} AND status = 'active' AND expires_at <= now()
            """)
    int expireDue(@Param("userId") UUID userId);

    @Select("""
            SELECT s.id AS subscription_id, s.user_id, s.plan_id, s.order_id,
                   s.status AS subscription_status, s.starts_at, s.expires_at, s.auto_renew,
                   s.cancelled_at, s.refund_requested_at, s.refund_completed_at,
                   s.concurrency_limit AS subscription_concurrency_limit,
                   s.refund_amount, s.refundable_credits, s.source,
                   s.version AS subscription_version,
                   """ + PLAN_COLUMNS + """
              FROM subscriptions s
             JOIN plans p ON p.id = s.plan_id
             WHERE s.user_id = #{userId}
               AND s.status IN ('active', 'refund_pending', 'refunded')
             ORDER BY CASE s.status WHEN 'active' THEN 0 WHEN 'refund_pending' THEN 1 ELSE 2 END,
                      s.starts_at DESC, s.id DESC
             LIMIT 1
            """)
    @Results(id = "subscriptionRow", value = {
            @Result(column = "subscription_id", property = "subscriptionId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "plan_id", property = "planId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "order_id", property = "orderId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "refund_requested_at", property = "refundRequestedAt"),
            @Result(column = "refund_completed_at", property = "refundCompletedAt"),
            @Result(column = "refund_amount", property = "refundAmount"),
            @Result(column = "refundable_credits", property = "refundableCredits"),
            @Result(column = "subscription_concurrency_limit", property = "concurrencyLimit"),
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "entitlements_json", property = "entitlementsJson")
    })
    SubscriptionRow findCurrent(@Param("userId") UUID userId);

    /** 锁定当前可处理的订阅，取消和退款流程必须在同一用户事务中串行化。 */
    @Select("""
            SELECT s.id AS subscription_id, s.user_id, s.plan_id, s.order_id,
                   s.status AS subscription_status, s.starts_at, s.expires_at, s.auto_renew,
                   s.cancelled_at, s.refund_requested_at, s.refund_completed_at,
                   s.concurrency_limit AS subscription_concurrency_limit,
                   s.refund_amount, s.refundable_credits, s.source,
                   s.version AS subscription_version,
                   """ + PLAN_COLUMNS + """
              FROM subscriptions s
              JOIN plans p ON p.id = s.plan_id
             WHERE s.user_id = #{userId}
               AND s.status = 'active'
             ORDER BY s.starts_at DESC, s.id DESC
             LIMIT 1
             FOR UPDATE OF s
            """)
    @ResultMap("subscriptionRow")
    SubscriptionRow lockCurrent(@Param("userId") UUID userId);

    @Select("""
            SELECT COALESCE(sum(granted_amount), 0) AS granted_credits,
                   COALESCE(sum(consumed_amount), 0) AS used_credits,
                   COALESCE(sum(frozen_amount), 0) AS frozen_credits,
                   COALESCE(sum(available_amount), 0) AS remaining_credits,
                   COALESCE(sum(expired_amount), 0) AS expired_credits
              FROM expiring_credit_batches
             WHERE subscription_id = #{subscriptionId}
            """)
    @Results(id = "subscriptionCreditSummaryRow", value = {
            @Result(column = "granted_credits", property = "grantedCredits"),
            @Result(column = "used_credits", property = "usedCredits"),
            @Result(column = "frozen_credits", property = "frozenCredits"),
            @Result(column = "remaining_credits", property = "remainingCredits"),
            @Result(column = "expired_credits", property = "expiredCredits")
    })
    SubscriptionCreditSummaryRow summarizeCredits(@Param("subscriptionId") UUID subscriptionId);

    @Update("""
            UPDATE subscriptions
               SET status = 'refund_pending', cancelled_at = now(), refund_requested_at = now(),
                   auto_renew = false, version = version + 1
             WHERE id = #{subscriptionId} AND user_id = #{userId}
               AND status = 'active' AND version = #{version}
            """)
    int markRefundPending(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("userId") UUID userId,
            @Param("version") long version
    );

    /** 套餐切换时关闭旧订阅，不触发用户退款流程。 */
    @Update("""
            UPDATE subscriptions
               SET status = 'cancelled', cancelled_at = now(), auto_renew = false, version = version + 1
             WHERE user_id = #{userId} AND status = 'active'
            """)
    int cancelCurrent(@Param("userId") UUID userId);

    @Insert("""
            INSERT INTO subscriptions (
                id, user_id, plan_id, order_id, status, starts_at, expires_at, auto_renew,
                concurrency_limit, source
            ) VALUES (
                #{id}, #{userId}, #{planId}, #{orderId}, 'active', #{startsAt}, #{expiresAt}, false,
                #{concurrencyLimit}, #{source}
            )
            """)
    int insert(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("planId") UUID planId,
            @Param("orderId") UUID orderId,
            @Param("startsAt") Instant startsAt,
            @Param("expiresAt") Instant expiresAt,
            @Param("concurrencyLimit") Integer concurrencyLimit,
            @Param("source") String source
    );

    /** 下单时创建待支付订阅并固化套餐关系，支付回调只负责激活。 */
    @Insert("""
            INSERT INTO subscriptions (
                id, user_id, plan_id, order_id, status, starts_at, expires_at, auto_renew,
                concurrency_limit, source
            ) VALUES (
                #{id}, #{userId}, #{planId}, #{orderId}, 'pending', #{startsAt}, #{expiresAt}, false,
                #{concurrencyLimit}, 'alipay_payment'
            )
            """)
    int insertPending(@Param("id") UUID id, @Param("userId") UUID userId, @Param("planId") UUID planId,
                      @Param("orderId") UUID orderId, @Param("startsAt") Instant startsAt,
                      @Param("expiresAt") Instant expiresAt, @Param("concurrencyLimit") Integer concurrencyLimit);

    @Select("SELECT id FROM subscriptions WHERE order_id = #{orderId} AND status = 'pending' FOR UPDATE")
    UUID lockPendingSubscriptionId(@Param("orderId") UUID orderId);

    @Update("""
            UPDATE subscriptions SET status = 'active', starts_at = #{startsAt}, expires_at = #{expiresAt}, version = version + 1
             WHERE id = #{subscriptionId} AND user_id = #{userId} AND status = 'pending'
            """)
    int activatePending(@Param("subscriptionId") UUID subscriptionId, @Param("userId") UUID userId,
                        @Param("startsAt") Instant startsAt, @Param("expiresAt") Instant expiresAt);

    @Insert("""
            INSERT INTO orders (id, user_id, order_no, order_type, amount, currency, status,
                                payment_provider, idempotency_key, metadata)
            VALUES (#{id}, #{userId}, #{orderNo}, 'subscription', #{amount}, 'CNY', 'pending',
                    #{provider}, #{idempotencyKey}, CAST(#{metadataJson} AS jsonb))
            """)
    int insertPendingOrder(@Param("id") UUID id, @Param("userId") UUID userId, @Param("orderNo") String orderNo,
                           @Param("amount") java.math.BigDecimal amount, @Param("provider") String provider,
                           @Param("idempotencyKey") String idempotencyKey, @Param("metadataJson") String metadataJson);

    /** 创建已支付的 Mock 订阅订单，保存历史价格快照供后续退款计算。 */
    @Insert("""
            INSERT INTO orders (
                id, user_id, order_no, order_type, amount, currency, status,
                payment_provider, provider_order_id, idempotency_key, paid_at, closed_at, metadata
            ) VALUES (
                #{id}, #{userId}, #{orderNo}, 'subscription', #{amount}, 'CNY', 'paid',
                'mock', #{providerOrderId}, #{idempotencyKey}, now(), now(), CAST(#{metadataJson} AS jsonb)
            )
            """)
    int insertSubscriptionOrder(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("orderNo") String orderNo,
            @Param("amount") java.math.BigDecimal amount,
            @Param("providerOrderId") String providerOrderId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("metadataJson") String metadataJson
    );

    /** 读取退款计算上下文并锁定订阅，确保同一订阅不会重复退款。 */
    @Select("""
            SELECT s.id AS subscription_id, s.user_id, s.order_id,
                   o.amount AS order_amount, o.status AS order_status, o.payment_provider,
                   COALESCE((SELECT sum(b.granted_amount) FROM expiring_credit_batches b
                              WHERE b.subscription_id = s.id), 0) AS granted_credits,
                   COALESCE((SELECT sum(b.expired_amount) FROM expiring_credit_batches b
                              WHERE b.subscription_id = s.id), 0) AS refundable_credits,
                   COALESCE((SELECT sum(b.frozen_amount) FROM expiring_credit_batches b
                              WHERE b.subscription_id = s.id), 0) AS frozen_credits
              FROM subscriptions s
              LEFT JOIN orders o ON o.id = s.order_id
             WHERE s.id = #{subscriptionId} AND s.user_id = #{userId}
             FOR UPDATE OF s
            """)
    @Results(id = "subscriptionRefundContext", value = {
            @Result(column = "subscription_id", property = "subscriptionId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "order_id", property = "orderId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "order_amount", property = "orderAmount"),
            @Result(column = "order_status", property = "orderStatus"),
            @Result(column = "payment_provider", property = "paymentProvider"),
            @Result(column = "granted_credits", property = "grantedCredits"),
            @Result(column = "refundable_credits", property = "refundableCredits"),
            @Result(column = "frozen_credits", property = "frozenCredits")
    })
    SubscriptionRefundContextRow lockRefundContext(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("userId") UUID userId
    );

    @Select("""
            SELECT s.id AS subscription_id, s.user_id, s.order_id,
                   o.amount AS order_amount, o.status AS order_status, o.payment_provider,
                   COALESCE((SELECT sum(b.granted_amount) FROM expiring_credit_batches b
                              WHERE b.subscription_id = s.id), 0) AS granted_credits,
                   COALESCE((SELECT sum(b.expired_amount) FROM expiring_credit_batches b
                              WHERE b.subscription_id = s.id), 0) AS refundable_credits,
                   COALESCE((SELECT sum(b.frozen_amount) FROM expiring_credit_batches b
                              WHERE b.subscription_id = s.id), 0) AS frozen_credits
              FROM subscriptions s
              LEFT JOIN orders o ON o.id = s.order_id
             WHERE s.id = #{subscriptionId} AND s.status = 'refund_pending'
             FOR UPDATE OF s
            """)
    @ResultMap("subscriptionRefundContext")
    SubscriptionRefundContextRow lockRefundContextById(@Param("subscriptionId") UUID subscriptionId);

    @Select("""
            SELECT id FROM subscriptions
             WHERE status = 'refund_pending'
             ORDER BY refund_requested_at, id
             LIMIT #{limit}
            """)
    List<UUID> findRefundPendingIds(@Param("limit") int limit);

    @Select("""
            SELECT status FROM subscription_refunds
             WHERE subscription_id = #{subscriptionId} AND user_id = #{userId}
             LIMIT 1
            """)
    String findRefundStatus(@Param("subscriptionId") UUID subscriptionId, @Param("userId") UUID userId);

    /** 为正式支付渠道建立待处理退款记录；唯一订阅约束保证重复扫描不会重复建单。 */
    @Insert("""
            INSERT INTO subscription_refunds (
                id, subscription_id, user_id, order_id, refund_amount, refundable_credits,
                status, idempotency_key, metadata
            ) VALUES (
                #{id}, #{subscriptionId}, #{userId}, #{orderId}, #{refundAmount}, #{refundableCredits},
                'pending', #{idempotencyKey}, CAST(#{metadataJson} AS jsonb)
            ) ON CONFLICT (subscription_id) DO NOTHING
            """)
    int insertPendingRefund(
            @Param("id") UUID id,
            @Param("subscriptionId") UUID subscriptionId,
            @Param("userId") UUID userId,
            @Param("orderId") UUID orderId,
            @Param("refundAmount") java.math.BigDecimal refundAmount,
            @Param("refundableCredits") java.math.BigDecimal refundableCredits,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("metadataJson") String metadataJson
    );

    @Insert("""
            INSERT INTO subscription_refunds (
                id, subscription_id, user_id, order_id, refund_amount, refundable_credits,
                status, idempotency_key, completed_at, metadata
            ) VALUES (
                #{id}, #{subscriptionId}, #{userId}, #{orderId}, #{refundAmount}, #{refundableCredits},
                'succeeded', #{idempotencyKey}, now(), CAST(#{metadataJson} AS jsonb)
            )
            """)
    int insertRefund(
            @Param("id") UUID id,
            @Param("subscriptionId") UUID subscriptionId,
            @Param("userId") UUID userId,
            @Param("orderId") UUID orderId,
            @Param("refundAmount") java.math.BigDecimal refundAmount,
            @Param("refundableCredits") java.math.BigDecimal refundableCredits,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("metadataJson") String metadataJson
    );

    @Update("""
            UPDATE subscriptions
               SET status = 'refunded', refund_completed_at = now(),
                   refund_amount = #{refundAmount}, refundable_credits = #{refundableCredits},
                   version = version + 1
             WHERE id = #{subscriptionId} AND user_id = #{userId} AND status = 'refund_pending'
            """)
    int markRefunded(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("userId") UUID userId,
            @Param("refundAmount") java.math.BigDecimal refundAmount,
            @Param("refundableCredits") java.math.BigDecimal refundableCredits
    );

    @Update("""
            UPDATE orders SET status = 'refunded', closed_at = now(), updated_at = now()
             WHERE id = #{orderId} AND user_id = #{userId} AND order_type = 'subscription'
               AND status = 'paid'
            """)
    int markSubscriptionOrderRefunded(@Param("orderId") UUID orderId, @Param("userId") UUID userId);

    @Insert("""
            INSERT INTO subscription_service_groups (subscription_id, service_group_id)
            SELECT #{subscriptionId}, psg.service_group_id
              FROM plan_service_groups psg
              JOIN routing_groups g ON g.id = psg.service_group_id
             WHERE psg.plan_id = #{planId}
               AND g.status = 'active'
               AND g.audience != 'internal'
            ON CONFLICT DO NOTHING
            """)
    int snapshotPlanServiceGroups(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("planId") UUID planId
    );

    @Insert("""
            INSERT INTO subscription_models (subscription_id, model_id)
            SELECT #{subscriptionId}, pm.model_id
              FROM plan_models pm
             WHERE pm.plan_id = #{planId}
            ON CONFLICT DO NOTHING
            """)
    int snapshotPlanModels(
            @Param("subscriptionId") UUID subscriptionId,
            @Param("planId") UUID planId
    );

    /** 读取活动订阅标识和开通时并发快照，供 Gateway 构造跨 API 令牌共享的并发租约。 */
    @Select("""
            SELECT id AS subscription_id, concurrency_limit
              FROM subscriptions
             WHERE user_id = #{userId}
               AND status = 'active'
               AND starts_at <= now()
               AND expires_at > now()
             ORDER BY starts_at DESC, id
             LIMIT 1
            """)
    @Results(id = "subscriptionAccessRow", value = {
            @Result(column = "subscription_id", property = "subscriptionId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "concurrency_limit", property = "concurrencyLimit")
    })
    SubscriptionAccessRow findActiveSubscriptionAccess(@Param("userId") UUID userId);

    @Select("""
            SELECT id
              FROM subscriptions
             WHERE user_id = #{userId}
               AND status = 'active'
               AND starts_at <= now()
               AND expires_at > now()
             ORDER BY starts_at DESC, id
             LIMIT 1
            """)
    UUID findActiveSubscriptionId(@Param("userId") UUID userId);

    @Select("""
            SELECT service_group_id
              FROM subscription_service_groups
             WHERE subscription_id = #{subscriptionId}
             ORDER BY service_group_id
            """)
    List<UUID> findAllowedGroupIds(@Param("subscriptionId") UUID subscriptionId);

    @Select("""
            SELECT model_id
              FROM subscription_models
             WHERE subscription_id = #{subscriptionId}
             ORDER BY model_id
            """)
    List<UUID> findAllowedModelIds(@Param("subscriptionId") UUID subscriptionId);
}
