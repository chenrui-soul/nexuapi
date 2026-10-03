package com.nexusapi.server.modules.billing.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.billing.entity.PaymentOrderRow;
import com.nexusapi.server.modules.billing.entity.PendingPaymentRefundRow;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.JdbcType;

import java.util.UUID;

/** 正式支付回调、幂等事件和订单状态收口的数据边界。 */
@Mapper
public interface PaymentMapper {
    @Select("""
            SELECT id, user_id, order_no, order_type, amount, currency, status,
                   payment_provider, provider_order_id, metadata::text AS metadata_json
              FROM orders WHERE status = 'pending' AND payment_provider <> 'mock'
             ORDER BY created_at LIMIT #{limit}
            """)
    @ResultMap("paymentOrder")
    java.util.List<PaymentOrderRow> findPendingOrders(@Param("limit") int limit);
    @Select("""
            SELECT id, user_id, order_no, order_type, amount, currency, status,
                   payment_provider, provider_order_id, metadata::text AS metadata_json
              FROM orders WHERE order_no = #{orderNo} FOR UPDATE
            """)
    @Results(id = "paymentOrder", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "metadata_json", property = "metadataJson")
    })
    PaymentOrderRow lockOrderByNo(@Param("orderNo") String orderNo);

    @Select("""
            SELECT id, user_id, order_no, order_type, amount, currency, status,
                   payment_provider, provider_order_id, metadata::text AS metadata_json
              FROM orders WHERE user_id = #{userId} AND idempotency_key = #{key} LIMIT 1
            """)
    @ResultMap("paymentOrder")
    PaymentOrderRow findOrderByIdempotency(@Param("userId") UUID userId, @Param("key") String key);

    @Insert("""
            INSERT INTO payment_events (id, provider, event_id, event_type, payload_hash, status, metadata)
            VALUES (#{id}, #{provider}, #{eventId}, #{eventType}, #{payloadHash}, 'received', CAST(#{metadataJson} AS jsonb))
            ON CONFLICT (provider, event_id) DO NOTHING
            """)
    int insertEvent(@Param("id") UUID id, @Param("provider") String provider, @Param("eventId") String eventId,
                    @Param("eventType") String eventType, @Param("payloadHash") String payloadHash,
                    @Param("metadataJson") String metadataJson);

    @Update("UPDATE payment_events SET status = #{status}, processed_at = now(), error_message = #{error} WHERE provider = #{provider} AND event_id = #{eventId}")
    int finishEvent(@Param("provider") String provider, @Param("eventId") String eventId,
                    @Param("status") String status, @Param("error") String error);

    @Update("""
            UPDATE orders SET status = 'paid', provider_order_id = #{providerOrderId}, paid_at = now(), closed_at = now(), updated_at = now()
             WHERE id = #{orderId} AND status = 'pending'
            """)
    int markPaid(@Param("orderId") UUID orderId, @Param("providerOrderId") String providerOrderId);

    @Select("""
            SELECT r.id AS refund_id, r.subscription_id, r.user_id, r.order_id,
                   o.payment_provider AS provider, o.provider_order_id, o.order_no,
                   r.refund_amount AS amount, r.idempotency_key AS out_request_no
              FROM subscription_refunds r JOIN orders o ON o.id = r.order_id
             WHERE r.status = 'pending' AND o.payment_provider <> 'mock'
             ORDER BY r.requested_at, r.id LIMIT #{limit}
            """)
    @Results(id = "pendingPaymentRefund", value = {
            @Result(column = "refund_id", property = "refundId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "subscription_id", property = "subscriptionId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "order_id", property = "orderId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class)
    })
    java.util.List<PendingPaymentRefundRow> findPendingRefunds(@Param("limit") int limit);

    @Update("UPDATE subscription_refunds SET status = 'succeeded', provider_refund_id = #{providerRefundId}, completed_at = now() WHERE id = #{refundId} AND status = 'pending'")
    int markRefundSucceeded(@Param("refundId") UUID refundId, @Param("providerRefundId") String providerRefundId);

    @Select("""
            SELECT r.id AS refund_id, r.subscription_id, r.user_id, r.order_id,
                   o.payment_provider AS provider, o.provider_order_id, o.order_no,
                   r.refund_amount AS amount, r.idempotency_key AS out_request_no
              FROM subscription_refunds r JOIN orders o ON o.id = r.order_id
             WHERE r.idempotency_key = #{outRequestNo} FOR UPDATE
            """)
    @ResultMap("pendingPaymentRefund")
    PendingPaymentRefundRow lockRefundByRequestNo(@Param("outRequestNo") String outRequestNo);

    @Update("UPDATE subscriptions SET status = 'refunded', refund_completed_at = now(), version = version + 1 WHERE id = #{subscriptionId} AND status = 'refund_pending'")
    int markSubscriptionRefunded(@Param("subscriptionId") UUID subscriptionId);

    @Update("UPDATE orders SET status = 'refunded', closed_at = now(), updated_at = now() WHERE id = #{orderId} AND status = 'paid'")
    int markOrderRefunded(@Param("orderId") UUID orderId);

    @Insert("""
            INSERT INTO payment_reconciliation_records
                (provider, reconciliation_date, provider_trade_no, order_id, provider_amount, local_amount, status, details)
            VALUES (#{provider}, CURRENT_DATE, #{providerTradeNo}, #{orderId}, #{providerAmount}, #{localAmount}, #{status}, CAST(#{detailsJson} AS jsonb))
            ON CONFLICT (provider, reconciliation_date, provider_trade_no)
            DO UPDATE SET provider_amount = EXCLUDED.provider_amount, local_amount = EXCLUDED.local_amount,
                          status = EXCLUDED.status, details = EXCLUDED.details
            """)
    int upsertReconciliation(@Param("provider") String provider, @Param("providerTradeNo") String providerTradeNo,
                             @Param("orderId") UUID orderId, @Param("providerAmount") java.math.BigDecimal providerAmount,
                             @Param("localAmount") java.math.BigDecimal localAmount, @Param("status") String status,
                             @Param("detailsJson") String detailsJson);
}
