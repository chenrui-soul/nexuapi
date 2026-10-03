package com.nexusapi.server.modules.billing.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.billing.entity.RechargeOrderRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.UUID;

@Mapper
public interface RechargeOrderMapper {

    @Select("""
            SELECT id, user_id, order_no, amount, currency, status, payment_provider,
                   provider_order_id, idempotency_key, paid_at, closed_at,
                   metadata::text AS metadata_json, created_at, updated_at
              FROM orders
             WHERE user_id = #{userId}
               AND idempotency_key = #{idempotencyKey}
               AND order_type = 'recharge'
             LIMIT 1
            """)
    @Results(id = "rechargeOrderRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "order_no", property = "orderNo"),
            @Result(column = "amount", property = "amount"),
            @Result(column = "currency", property = "currency"),
            @Result(column = "status", property = "status"),
            @Result(column = "payment_provider", property = "paymentProvider"),
            @Result(column = "provider_order_id", property = "providerOrderId"),
            @Result(column = "idempotency_key", property = "idempotencyKey"),
            @Result(column = "paid_at", property = "paidAt"),
            @Result(column = "closed_at", property = "closedAt"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    RechargeOrderRow findByIdempotency(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Select("""
            SELECT id, user_id, order_no, amount, currency, status, payment_provider,
                   provider_order_id, idempotency_key, paid_at, closed_at,
                   metadata::text AS metadata_json, created_at, updated_at
              FROM orders
             WHERE id = #{id}
               AND user_id = #{userId}
               AND order_type = 'recharge'
             LIMIT 1
            """)
    @ResultMap("rechargeOrderRow")
    RechargeOrderRow findById(@Param("id") UUID id, @Param("userId") UUID userId);

    @Select("""
            SELECT id, user_id, order_no, amount, currency, status, payment_provider,
                   provider_order_id, idempotency_key, paid_at, closed_at,
                   metadata::text AS metadata_json, created_at, updated_at
              FROM orders
             WHERE id = #{id}
               AND user_id = #{userId}
               AND order_type = 'recharge'
             FOR UPDATE
            """)
    @ResultMap("rechargeOrderRow")
    RechargeOrderRow lockById(@Param("id") UUID id, @Param("userId") UUID userId);

    @Insert("""
            INSERT INTO orders (
                id, user_id, order_no, order_type, amount, currency, status,
                payment_provider, idempotency_key, metadata
            ) VALUES (
                #{id}, #{userId}, #{orderNo}, 'recharge', #{amount}, #{currency}, 'pending',
                #{paymentProvider}, #{idempotencyKey}, CAST(#{metadataJson} AS jsonb)
            )
            """)
    int insert(RechargeOrderRow row);

    @Update("""
            UPDATE orders
               SET status = 'paid', provider_order_id = #{providerOrderId},
                   paid_at = now(), closed_at = now(), updated_at = now()
             WHERE id = #{id}
               AND user_id = #{userId}
               AND order_type = 'recharge'
               AND status = 'pending'
            """)
    int markPaid(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("providerOrderId") String providerOrderId
    );

}
