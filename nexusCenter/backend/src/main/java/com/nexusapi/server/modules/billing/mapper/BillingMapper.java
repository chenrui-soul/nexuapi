package com.nexusapi.server.modules.billing.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.billing.entity.BillingLedgerRow;
import com.nexusapi.server.modules.billing.entity.BillingRefundRow;
import com.nexusapi.server.modules.billing.entity.ExpiringCreditBatchRow;
import com.nexusapi.server.modules.billing.entity.WalletAccountRow;
import com.nexusapi.server.modules.billing.entity.WalletReservationBatchAllocationRow;
import com.nexusapi.server.modules.billing.entity.WalletReservationRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 资金模块唯一数据访问边界。
 *
 * <p>写操作由 Service 先锁定钱包/冻结单，再同时更新余额、状态并追加账本。</p>
 */
@Mapper
public interface BillingMapper {

    @Select("""
            SELECT w.user_id, w.permanent_credits, w.expiring_credits, w.frozen_credits,
                   w.version, w.updated_at
              FROM wallet_accounts w
             WHERE w.user_id = #{userId}
            """)
    @Results(id = "walletAccountRow", value = {
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "permanent_credits", property = "permanentCredits"),
            @Result(column = "expiring_credits", property = "expiringCredits"),
            @Result(column = "frozen_credits", property = "frozenCredits"),
            @Result(column = "version", property = "version"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    WalletAccountRow findWallet(@Param("userId") UUID userId);

    /** 冻结新请求时同时确认用户还是 active。 */
    @Select("""
            SELECT w.user_id, w.permanent_credits, w.expiring_credits, w.frozen_credits,
                   w.version, w.updated_at
              FROM wallet_accounts w
              JOIN users u ON u.id = w.user_id
             WHERE w.user_id = #{userId}
               AND u.status = 'active'
               AND u.deleted_at IS NULL
             FOR UPDATE OF w
            """)
    @ResultMap("walletAccountRow")
    WalletAccountRow lockActiveWallet(@Param("userId") UUID userId);

    /** 结算、释放和退款必须能完成资金收尾，即使用户之后被停用。 */
    @Select("""
            SELECT user_id, permanent_credits, expiring_credits, frozen_credits,
                   version, updated_at
              FROM wallet_accounts
             WHERE user_id = #{userId}
             FOR UPDATE
            """)
    @ResultMap("walletAccountRow")
    WalletAccountRow lockWallet(@Param("userId") UUID userId);

    /**
     * 永久积分热路径的原子预冻结：仅在没有限时积分时使用，避免“锁行→读取→对象更新”产生额外往返。
     * 返回更新后的钱包快照；用户停用、余额不足或并发条件不满足时返回 null，由 Service 回退到完整批次路径。
     */
    @Select("""
            UPDATE wallet_accounts w
               SET permanent_credits = w.permanent_credits - #{amount},
                   frozen_credits = w.frozen_credits + #{amount},
                   version = w.version + 1
              FROM users u
             WHERE w.user_id = #{userId}
               AND u.id = w.user_id
               AND u.status = 'active'
               AND u.deleted_at IS NULL
               AND w.expiring_credits = 0
               AND w.permanent_credits >= #{amount}
            RETURNING w.user_id, w.permanent_credits, w.expiring_credits, w.frozen_credits,
                      w.version, w.updated_at
            """)
    @ResultMap("walletAccountRow")
    WalletAccountRow reservePermanentCredits(
            @Param("userId") UUID userId,
            @Param("amount") BigDecimal amount
    );

    @Update("""
            UPDATE wallet_accounts
               SET permanent_credits = #{permanentCredits},
                   expiring_credits = #{expiringCredits},
                   frozen_credits = #{frozenCredits},
                   version = version + 1
             WHERE user_id = #{userId}
               AND version = #{version}
            """)
    int updateWallet(WalletAccountRow wallet);

    @Select("""
            SELECT id, user_id, api_key_id, reservation_id, request_id, entry_type,
                   amount, balance_after, idempotency_key, source_type, source_id,
                   expires_at, metadata::text AS metadata_json,
                   permanent_delta, expiring_delta, frozen_delta,
                   permanent_after, expiring_after, frozen_after, available_after,
                   wallet_version_after, created_at
              FROM billing_ledger
             WHERE user_id = #{userId}
               AND idempotency_key = #{idempotencyKey}
             LIMIT 1
            """)
    @Results(id = "billingLedgerRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "api_key_id", property = "apiKeyId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "reservation_id", property = "reservationId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "entry_type", property = "entryType"),
            @Result(column = "amount", property = "amount"),
            @Result(column = "balance_after", property = "balanceAfter"),
            @Result(column = "idempotency_key", property = "idempotencyKey"),
            @Result(column = "source_type", property = "sourceType"),
            @Result(column = "source_id", property = "sourceId"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "permanent_delta", property = "permanentDelta"),
            @Result(column = "expiring_delta", property = "expiringDelta"),
            @Result(column = "frozen_delta", property = "frozenDelta"),
            @Result(column = "permanent_after", property = "permanentAfter"),
            @Result(column = "expiring_after", property = "expiringAfter"),
            @Result(column = "frozen_after", property = "frozenAfter"),
            @Result(column = "available_after", property = "availableAfter"),
            @Result(column = "wallet_version_after", property = "walletVersionAfter"),
            @Result(column = "created_at", property = "createdAt")
    })
    BillingLedgerRow findLedgerByIdempotency(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Insert("""
            INSERT INTO billing_ledger (
                id, user_id, api_key_id, reservation_id, request_id, entry_type,
                amount, balance_after, idempotency_key, source_type, source_id,
                expires_at, metadata, permanent_delta, expiring_delta, frozen_delta,
                permanent_after, expiring_after, frozen_after, available_after,
                wallet_version_after
            ) VALUES (
                #{id}, #{userId}, #{apiKeyId}, #{reservationId}, #{requestId}, #{entryType},
                #{amount}, #{balanceAfter}, #{idempotencyKey}, #{sourceType}, #{sourceId},
                #{expiresAt}, CAST(#{metadataJson} AS jsonb), #{permanentDelta},
                #{expiringDelta}, #{frozenDelta}, #{permanentAfter}, #{expiringAfter},
                #{frozenAfter}, #{availableAfter}, #{walletVersionAfter}
            )
            """)
    int insertLedger(BillingLedgerRow row);

    @Select("""
            SELECT id, user_id, api_key_id, reservation_id, request_id, entry_type,
                   amount, balance_after, idempotency_key, source_type, source_id,
                   expires_at, metadata::text AS metadata_json,
                   permanent_delta, expiring_delta, frozen_delta,
                   permanent_after, expiring_after, frozen_after, available_after,
                   wallet_version_after, created_at
              FROM billing_ledger
             WHERE user_id = #{userId}
             ORDER BY created_at DESC, id DESC
             OFFSET #{offset} LIMIT #{limit}
            """)
    @ResultMap("billingLedgerRow")
    List<BillingLedgerRow> findLedgerPage(
            @Param("userId") UUID userId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("SELECT count(*) FROM billing_ledger WHERE user_id = #{userId}")
    long countLedger(@Param("userId") UUID userId);

    @Select("""
            SELECT id, user_id, api_key_id, service_group_id, request_id, idempotency_key, status,
                   reserved_amount, reserved_permanent, reserved_expiring,
                   settled_amount, settled_permanent, settled_expiring,
                   refunded_amount, refunded_permanent, refunded_expiring,
                   settlement_idempotency_key, release_idempotency_key,
                   created_at, settled_at, released_at, updated_at, version
              FROM wallet_reservations
             WHERE user_id = #{userId}
               AND idempotency_key = #{idempotencyKey}
             LIMIT 1
            """)
    @Results(id = "walletReservationRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "api_key_id", property = "apiKeyId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "service_group_id", property = "serviceGroupId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "idempotency_key", property = "idempotencyKey"),
            @Result(column = "status", property = "status"),
            @Result(column = "reserved_amount", property = "reservedAmount"),
            @Result(column = "reserved_permanent", property = "reservedPermanent"),
            @Result(column = "reserved_expiring", property = "reservedExpiring"),
            @Result(column = "settled_amount", property = "settledAmount"),
            @Result(column = "settled_permanent", property = "settledPermanent"),
            @Result(column = "settled_expiring", property = "settledExpiring"),
            @Result(column = "refunded_amount", property = "refundedAmount"),
            @Result(column = "refunded_permanent", property = "refundedPermanent"),
            @Result(column = "refunded_expiring", property = "refundedExpiring"),
            @Result(column = "settlement_idempotency_key", property = "settlementIdempotencyKey"),
            @Result(column = "release_idempotency_key", property = "releaseIdempotencyKey"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "settled_at", property = "settledAt"),
            @Result(column = "released_at", property = "releasedAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "version", property = "version")
    })
    WalletReservationRow findReservationByIdempotency(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Select("""
            SELECT id, user_id, api_key_id, service_group_id, request_id, idempotency_key, status,
                   reserved_amount, reserved_permanent, reserved_expiring,
                   settled_amount, settled_permanent, settled_expiring,
                   refunded_amount, refunded_permanent, refunded_expiring,
                   settlement_idempotency_key, release_idempotency_key,
                   created_at, settled_at, released_at, updated_at, version
              FROM wallet_reservations
             WHERE user_id = #{userId}
               AND request_id = #{requestId}
             LIMIT 1
            """)
    @ResultMap("walletReservationRow")
    WalletReservationRow findReservationByRequest(
            @Param("userId") UUID userId,
            @Param("requestId") String requestId
    );

    @Select("""
            SELECT id, user_id, api_key_id, service_group_id, request_id, idempotency_key, status,
                   reserved_amount, reserved_permanent, reserved_expiring,
                   settled_amount, settled_permanent, settled_expiring,
                   refunded_amount, refunded_permanent, refunded_expiring,
                   settlement_idempotency_key, release_idempotency_key,
                   created_at, settled_at, released_at, updated_at, version
              FROM wallet_reservations
             WHERE id = #{id}
               AND user_id = #{userId}
             FOR UPDATE
            """)
    @ResultMap("walletReservationRow")
    WalletReservationRow lockReservation(@Param("id") UUID id, @Param("userId") UUID userId);

    @Insert("""
            INSERT INTO wallet_reservations (
                id, user_id, api_key_id, service_group_id, request_id, idempotency_key, status,
                reserved_amount, reserved_permanent, reserved_expiring
            ) VALUES (
                #{id}, #{userId}, #{apiKeyId}, #{serviceGroupId}, #{requestId}, #{idempotencyKey}, 'reserved',
                #{reservedAmount}, #{reservedPermanent}, #{reservedExpiring}
            )
            """)
    int insertReservation(WalletReservationRow row);

    @Update("""
            UPDATE wallet_reservations
               SET status = 'settled',
                   settled_amount = #{settledAmount},
                   settled_permanent = #{settledPermanent},
                   settled_expiring = #{settledExpiring},
                   settlement_idempotency_key = #{settlementIdempotencyKey},
                   settled_at = now(),
                   version = version + 1
             WHERE id = #{id}
               AND user_id = #{userId}
               AND status = 'reserved'
               AND version = #{version}
            """)
    int markReservationSettled(WalletReservationRow row);

    @Update("""
            UPDATE wallet_reservations
               SET status = 'released',
                   release_idempotency_key = #{releaseIdempotencyKey},
                   released_at = now(),
                   version = version + 1
             WHERE id = #{id}
               AND user_id = #{userId}
               AND status = 'reserved'
               AND version = #{version}
            """)
    int markReservationReleased(WalletReservationRow row);

    @Update("""
            UPDATE wallet_reservations
               SET refunded_amount = refunded_amount + #{amount},
                   refunded_permanent = refunded_permanent + #{permanentAmount},
                   refunded_expiring = refunded_expiring + #{expiringAmount},
                   version = version + 1
             WHERE id = #{reservationId}
               AND user_id = #{userId}
               AND status = 'settled'
               AND version = #{reservationVersion}
               AND refunded_amount + #{amount} <= settled_amount
            """)
    int addReservationRefund(
            @Param("reservationId") UUID reservationId,
            @Param("userId") UUID userId,
            @Param("amount") BigDecimal amount,
            @Param("permanentAmount") BigDecimal permanentAmount,
            @Param("expiringAmount") BigDecimal expiringAmount,
            @Param("reservationVersion") long reservationVersion
    );

    @Select("""
            SELECT COALESCE(SUM(
                CASE
                    WHEN status = 'reserved' THEN reserved_amount
                    WHEN status = 'settled' THEN settled_amount - refunded_amount
                    ELSE 0
                END
            ), 0)
              FROM wallet_reservations
             WHERE user_id = #{userId}
               AND api_key_id = #{apiKeyId}
            """)
    BigDecimal sumApiKeyExposure(@Param("userId") UUID userId, @Param("apiKeyId") UUID apiKeyId);

    @Select("""
            SELECT id, user_id, reservation_id, idempotency_key, amount,
                   permanent_amount, expiring_amount, cumulative_amount,
                   refundable_after, reason, created_at
              FROM billing_refunds
             WHERE user_id = #{userId}
               AND idempotency_key = #{idempotencyKey}
             LIMIT 1
            """)
    @Results(id = "billingRefundRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "reservation_id", property = "reservationId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "idempotency_key", property = "idempotencyKey"),
            @Result(column = "amount", property = "amount"),
            @Result(column = "permanent_amount", property = "permanentAmount"),
            @Result(column = "expiring_amount", property = "expiringAmount"),
            @Result(column = "cumulative_amount", property = "cumulativeAmount"),
            @Result(column = "refundable_after", property = "refundableAfter"),
            @Result(column = "reason", property = "reason"),
            @Result(column = "created_at", property = "createdAt")
    })
    BillingRefundRow findRefundByIdempotency(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Insert("""
            INSERT INTO billing_refunds (
                id, user_id, reservation_id, idempotency_key, amount,
                permanent_amount, expiring_amount, cumulative_amount,
                refundable_after, reason
            ) VALUES (
                #{id}, #{userId}, #{reservationId}, #{idempotencyKey}, #{amount},
                #{permanentAmount}, #{expiringAmount}, #{cumulativeAmount},
                #{refundableAfter}, #{reason}
            )
            """)
    int insertRefund(BillingRefundRow row);

    @Insert("""
            INSERT INTO expiring_credit_batches (
                id, user_id, subscription_id, credit_ledger_id, source_type, source_id,
                granted_amount, available_amount, frozen_amount, consumed_amount, expired_amount,
                expires_at, status
            ) VALUES (
                #{id}, #{userId}, #{subscriptionId}, #{creditLedgerId}, #{sourceType}, #{sourceId},
                #{grantedAmount}, #{availableAmount}, #{frozenAmount}, #{consumedAmount}, #{expiredAmount},
                #{expiresAt}, #{status}
            )
            """)
    int insertExpiringBatch(ExpiringCreditBatchRow row);

    @Select("""
            SELECT b.id, b.user_id, b.subscription_id, b.credit_ledger_id,
                   b.source_type, b.source_id, b.granted_amount, b.available_amount,
                   b.frozen_amount, b.consumed_amount, b.expired_amount,
                   b.expires_at, b.status, b.created_at, b.updated_at, b.version
              FROM expiring_credit_batches b
              JOIN subscriptions s ON s.id = b.subscription_id
              JOIN subscription_service_groups ssg
                ON ssg.subscription_id = s.id AND ssg.service_group_id = #{serviceGroupId}
             WHERE b.user_id = #{userId}
               AND b.available_amount > 0
               AND b.expires_at > now()
               AND s.user_id = #{userId}
               AND s.status = 'active'
               AND s.starts_at <= now()
               AND s.expires_at > now()
             ORDER BY b.expires_at, b.created_at, b.id
             FOR UPDATE OF b
            """)
    @Results(id = "expiringCreditBatchRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "subscription_id", property = "subscriptionId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "credit_ledger_id", property = "creditLedgerId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "source_type", property = "sourceType"),
            @Result(column = "source_id", property = "sourceId"),
            @Result(column = "granted_amount", property = "grantedAmount"),
            @Result(column = "available_amount", property = "availableAmount"),
            @Result(column = "frozen_amount", property = "frozenAmount"),
            @Result(column = "consumed_amount", property = "consumedAmount"),
            @Result(column = "expired_amount", property = "expiredAmount"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "version", property = "version")
    })
    List<ExpiringCreditBatchRow> lockSpendableSubscriptionBatches(
            @Param("userId") UUID userId,
            @Param("serviceGroupId") UUID serviceGroupId
    );

    @Select("""
            SELECT id, user_id, subscription_id, credit_ledger_id,
                   source_type, source_id, granted_amount, available_amount,
                   frozen_amount, consumed_amount, expired_amount,
                   expires_at, status, created_at, updated_at, version
              FROM expiring_credit_batches
             WHERE user_id = #{userId}
               AND subscription_id IS NULL
               AND available_amount > 0
               AND expires_at > now()
             ORDER BY expires_at, created_at, id
             FOR UPDATE
            """)
    @ResultMap("expiringCreditBatchRow")
    List<ExpiringCreditBatchRow> lockSpendableGeneralBatches(@Param("userId") UUID userId);

    @Select("""
            SELECT id, user_id, subscription_id, credit_ledger_id,
                   source_type, source_id, granted_amount, available_amount,
                   frozen_amount, consumed_amount, expired_amount,
                   expires_at, status, created_at, updated_at, version
              FROM expiring_credit_batches
             WHERE user_id = #{userId}
               AND subscription_id = #{subscriptionId}
               AND (available_amount > 0 OR frozen_amount > 0)
             ORDER BY expires_at, created_at, id
             FOR UPDATE
            """)
    @ResultMap("expiringCreditBatchRow")
    List<ExpiringCreditBatchRow> lockSubscriptionBatches(
            @Param("userId") UUID userId,
            @Param("subscriptionId") UUID subscriptionId
    );

    @Select("""
            SELECT id, user_id, subscription_id, credit_ledger_id,
                   source_type, source_id, granted_amount, available_amount,
                   frozen_amount, consumed_amount, expired_amount,
                   expires_at, status, created_at, updated_at, version
              FROM expiring_credit_batches
             WHERE user_id = #{userId}
               AND available_amount > 0
               AND expires_at <= now()
             ORDER BY expires_at, created_at, id
             FOR UPDATE
            """)
    @ResultMap("expiringCreditBatchRow")
    List<ExpiringCreditBatchRow> lockDueBatches(@Param("userId") UUID userId);

    @Select("""
            SELECT id, user_id, subscription_id, credit_ledger_id,
                   source_type, source_id, granted_amount, available_amount,
                   frozen_amount, consumed_amount, expired_amount,
                   expires_at, status, created_at, updated_at, version
              FROM expiring_credit_batches
             WHERE id = #{id}
               AND user_id = #{userId}
             FOR UPDATE
            """)
    @ResultMap("expiringCreditBatchRow")
    ExpiringCreditBatchRow lockExpiringBatch(@Param("id") UUID id, @Param("userId") UUID userId);

    @Select("""
            SELECT DISTINCT user_id
              FROM expiring_credit_batches
             WHERE available_amount > 0
               AND expires_at <= now()
             ORDER BY user_id
             LIMIT #{limit}
            """)
    List<UUID> findDueBatchUserIds(@Param("limit") int limit);

    @Update("""
            UPDATE expiring_credit_batches
               SET available_amount = #{availableAmount},
                   frozen_amount = #{frozenAmount},
                   consumed_amount = #{consumedAmount},
                   expired_amount = #{expiredAmount},
                   expires_at = #{expiresAt},
                   status = #{status},
                   version = version + 1
             WHERE id = #{id}
               AND user_id = #{userId}
               AND version = #{version}
            """)
    int updateExpiringBatch(ExpiringCreditBatchRow row);

    @Insert("""
            INSERT INTO wallet_reservation_batch_allocations (
                id, reservation_id, batch_id, reserved_amount, additional_amount,
                settled_amount, released_amount, expired_amount, refunded_amount
            ) VALUES (
                #{id}, #{reservationId}, #{batchId}, #{reservedAmount}, #{additionalAmount},
                #{settledAmount}, #{releasedAmount}, #{expiredAmount}, #{refundedAmount}
            )
            """)
    int insertBatchAllocation(WalletReservationBatchAllocationRow row);

    @Select("""
            SELECT a.id, a.reservation_id, a.batch_id, a.reserved_amount,
                   a.additional_amount, a.settled_amount, a.released_amount,
                   a.expired_amount, a.refunded_amount, a.created_at, a.updated_at,
                   a.version, b.expires_at AS batch_expires_at
              FROM wallet_reservation_batch_allocations a
              JOIN expiring_credit_batches b ON b.id = a.batch_id
             WHERE a.reservation_id = #{reservationId}
             ORDER BY b.expires_at, a.created_at, a.id
             FOR UPDATE OF a, b
            """)
    @Results(id = "walletReservationBatchAllocationRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "reservation_id", property = "reservationId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "batch_id", property = "batchId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "reserved_amount", property = "reservedAmount"),
            @Result(column = "additional_amount", property = "additionalAmount"),
            @Result(column = "settled_amount", property = "settledAmount"),
            @Result(column = "released_amount", property = "releasedAmount"),
            @Result(column = "expired_amount", property = "expiredAmount"),
            @Result(column = "refunded_amount", property = "refundedAmount"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "version", property = "version"),
            @Result(column = "batch_expires_at", property = "batchExpiresAt")
    })
    List<WalletReservationBatchAllocationRow> lockBatchAllocations(@Param("reservationId") UUID reservationId);

    @Update("""
            UPDATE wallet_reservation_batch_allocations
               SET additional_amount = #{additionalAmount},
                   settled_amount = #{settledAmount},
                   released_amount = #{releasedAmount},
                   expired_amount = #{expiredAmount},
                   refunded_amount = #{refundedAmount},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
            """)
    int updateBatchAllocation(WalletReservationBatchAllocationRow row);
}
