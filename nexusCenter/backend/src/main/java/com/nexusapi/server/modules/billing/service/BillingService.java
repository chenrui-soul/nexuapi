package com.nexusapi.server.modules.billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.billing.entity.BillingLedgerRow;
import com.nexusapi.server.modules.billing.entity.BillingRefundRow;
import com.nexusapi.server.modules.billing.entity.ExpiringCreditBatchRow;
import com.nexusapi.server.modules.billing.entity.WalletAccountRow;
import com.nexusapi.server.modules.billing.entity.WalletReservationBatchAllocationRow;
import com.nexusapi.server.modules.billing.entity.WalletReservationRow;
import com.nexusapi.server.modules.billing.enums.CreditBucket;
import com.nexusapi.server.modules.billing.enums.CreditType;
import com.nexusapi.server.modules.billing.mapper.BillingMapper;
import com.nexusapi.server.modules.billing.vo.BillingLedgerItemResponse;
import com.nexusapi.server.modules.billing.vo.WalletBalanceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 钱包和账本的业务编排层，是整个项目唯一允许修改用户余额的组件。
 *
 * <p>每次操作都在同一事务内执行“锁行→幂等检查→余额更新→状态更新→账本追加”，
 * 任何一步失败都会整体回滚，避免出现余额变了但账本没写成的半成功状态。</p>
 */
@Service
public class BillingService {
    private static final BigDecimal ZERO = new BigDecimal("0.000000000000");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999999.999999999999");
    private static final int MAX_METADATA_JSON_LENGTH = 8_192;
    private static final Set<String> SENSITIVE_METADATA_KEYS = Set.of(
            "authorization", "secret", "token", "access_token", "refresh_token", "api_key", "apikey", "password"
    );

    private final BillingMapper mapper;
    private final ObjectMapper objectMapper;

    public BillingService(BillingMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public WalletBalanceResponse getBalance(UUID userId) {
        return toBalance(requireWallet(mapper.findWallet(userId)));
    }

    @Transactional(readOnly = true)
    public PageResponse<BillingLedgerItemResponse> listLedger(UUID userId, int page, int pageSize) {
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findLedgerPage(userId, offset, pageSize).stream().map(this::toLedgerItem).toList(),
                mapper.countLedger(userId),
                page,
                pageSize
        );
    }

    /** 充值、赠送或人工调账的统一入账入口。 */
    @Transactional
    public MutationResult credit(
            UUID userId,
            BigDecimal rawAmount,
            CreditBucket bucket,
            CreditType creditType,
            String idempotencyKey,
            String sourceType,
            String sourceId,
            Instant expiresAt,
            Map<String, Object> metadata
    ) {
        BigDecimal amount = normalizePositive(rawAmount);
        String key = normalizeText(idempotencyKey, 160, "幂等键");
        String normalizedSourceType = normalizeOptionalText(sourceType, 64, "来源类型");
        String normalizedSourceId = normalizeOptionalText(sourceId, 160, "来源编号");
        if (bucket == CreditBucket.EXPIRING && (expiresAt == null || !expiresAt.isAfter(Instant.now()))) {
            throw validation("有效期额度必须设置未来的过期时间");
        }
        if (bucket == CreditBucket.PERMANENT && expiresAt != null) {
            throw validation("永久额度不能设置过期时间");
        }

        WalletAccountRow wallet = requireWallet(mapper.lockWallet(userId));
        BillingLedgerRow existing = mapper.findLedgerByIdempotency(userId, key);
        if (existing != null) {
            BigDecimal expectedPermanent = bucket == CreditBucket.PERMANENT ? amount : ZERO;
            BigDecimal expectedExpiring = bucket == CreditBucket.EXPIRING ? amount : ZERO;
            assertLedgerReplay(existing, creditType.value(), amount, expectedPermanent, expectedExpiring, ZERO,
                    null, null, normalizedSourceType, normalizedSourceId);
            return mutationFromLedger(existing, true);
        }

        BigDecimal permanent = wallet.getPermanentCredits();
        BigDecimal expiring = wallet.getExpiringCredits();
        if (bucket == CreditBucket.PERMANENT) {
            permanent = permanent.add(amount);
        } else {
            expiring = expiring.add(amount);
        }
        applyWallet(wallet, permanent, expiring, wallet.getFrozenCredits());
        BillingLedgerRow ledger = newLedger(
                wallet, null, null, null, creditType.value(), key,
                normalizedSourceType, normalizedSourceId, expiresAt, metadata,
                bucket == CreditBucket.PERMANENT ? amount : ZERO,
                bucket == CreditBucket.EXPIRING ? amount : ZERO,
                ZERO
        );
        mapper.insertLedger(ledger);
        if (bucket == CreditBucket.EXPIRING) {
            ExpiringCreditBatchRow batch = new ExpiringCreditBatchRow();
            batch.setId(UUID.randomUUID());
            batch.setUserId(userId);
            batch.setSubscriptionId(subscriptionId(normalizedSourceType, normalizedSourceId));
            batch.setCreditLedgerId(ledger.getId());
            batch.setSourceType(normalizedSourceType == null ? creditType.value() : normalizedSourceType);
            batch.setSourceId(normalizedSourceId == null ? ledger.getId().toString() : normalizedSourceId);
            batch.setGrantedAmount(amount);
            batch.setAvailableAmount(amount);
            batch.setFrozenAmount(ZERO);
            batch.setConsumedAmount(ZERO);
            batch.setExpiredAmount(ZERO);
            batch.setExpiresAt(expiresAt);
            batch.setStatus("active");
            mapper.insertExpiringBatch(batch);
        }
        return mutationFromLedger(ledger, false);
    }

    /**
     * 在请求发往上游前预冻结最大可能消费，先冻结有效期额度再冻结永久额度。
     */
    @Transactional
    public ReservationResult reserve(
            UUID userId,
            UUID apiKeyId,
            String requestId,
            BigDecimal rawAmount,
            BigDecimal rawCreditLimit,
            String idempotencyKey,
            Map<String, Object> metadata
    ) {
        return reserve(userId, apiKeyId, null, requestId, rawAmount, rawCreditLimit, idempotencyKey, metadata);
    }

    /** Gateway 冻结入口，服务分组用于优先选择当前订阅的积分批次。 */
    @Transactional
    public ReservationResult reserve(
            UUID userId,
            UUID apiKeyId,
            UUID serviceGroupId,
            String requestId,
            BigDecimal rawAmount,
            BigDecimal rawCreditLimit,
            String idempotencyKey,
            Map<String, Object> metadata
    ) {
        BigDecimal amount = normalizePositive(rawAmount);
        BigDecimal creditLimit = normalizeOptionalPositive(rawCreditLimit);
        String normalizedRequestId = normalizeText(requestId, 80, "请求编号");
        String key = normalizeText(idempotencyKey, 160, "幂等键");

        // 先处理已完成的幂等重放：即使用户之后被停用，也能读取原操作结果。
        WalletReservationRow existing = mapper.findReservationByIdempotency(userId, key);
        if (existing != null) {
            assertReservationReplay(existing, apiKeyId, serviceGroupId, normalizedRequestId, amount);
            BillingLedgerRow ledger = requireReplayLedger(userId, key, "reserve");
            return reservationFrom(existing, ledger, true);
        }

        // 永久积分请求直接尝试单条原子 UPDATE，避免先读钱包再更新产生额外数据库往返。
        // 限时积分、余额不足、用户停用或并发竞态时 UPDATE 返回 null，再回退到完整路径，
        // 确保订阅积分优先、用户状态校验和幂等语义不变。
        if (creditLimit == null) {
            WalletAccountRow reservedWallet = mapper.reservePermanentCredits(userId, amount);
            if (reservedWallet != null) {
                return persistPermanentReservation(
                        reservedWallet, apiKeyId, serviceGroupId, normalizedRequestId, key, amount, metadata
                );
            }
        }

        WalletAccountRow wallet = mapper.lockActiveWallet(userId);
        if (wallet == null) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        // 锁定钱包后再查一次，收敛并发首次请求和重放请求之间的竞态。
        existing = mapper.findReservationByIdempotency(userId, key);
        if (existing != null) {
            assertReservationReplay(existing, apiKeyId, serviceGroupId, normalizedRequestId, amount);
            BillingLedgerRow ledger = requireReplayLedger(userId, key, "reserve");
            return reservationFrom(existing, ledger, true);
        }
        expireDueBatchesIfNeeded(userId, wallet);
        if (mapper.findLedgerByIdempotency(userId, key) != null
                || mapper.findReservationByRequest(userId, normalizedRequestId) != null) {
            throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        }
        assertCreditLimit(userId, apiKeyId, creditLimit, amount);

        List<ExpiringCreditBatchRow> spendableBatches = spendableBatches(userId, serviceGroupId);
        BigDecimal spendableExpiring = spendableBatches.stream()
                .map(ExpiringCreditBatchRow::getAvailableAmount)
                .reduce(ZERO, BigDecimal::add);
        if (spendableExpiring.add(wallet.getPermanentCredits()).compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        BigDecimal fromExpiring = min(spendableExpiring, amount);
        BigDecimal fromPermanent = amount.subtract(fromExpiring);

        applyWallet(
                wallet,
                wallet.getPermanentCredits().subtract(fromPermanent),
                wallet.getExpiringCredits().subtract(fromExpiring),
                wallet.getFrozenCredits().add(amount)
        );

        WalletReservationRow reservation = new WalletReservationRow();
        reservation.setId(UUID.randomUUID());
        reservation.setUserId(userId);
        reservation.setApiKeyId(apiKeyId);
        reservation.setServiceGroupId(serviceGroupId);
        reservation.setRequestId(normalizedRequestId);
        reservation.setIdempotencyKey(key);
        reservation.setStatus("reserved");
        reservation.setReservedAmount(amount);
        reservation.setReservedPermanent(fromPermanent);
        reservation.setReservedExpiring(fromExpiring);
        reservation.setSettledAmount(ZERO);
        reservation.setRefundedAmount(ZERO);
        mapper.insertReservation(reservation);
        allocateReservationBatches(reservation.getId(), spendableBatches, fromExpiring, false, Map.of());

        BillingLedgerRow ledger = newLedger(
                wallet, apiKeyId, reservation.getId(), normalizedRequestId, "reserve", key,
                "api_request", normalizedRequestId, null, metadata,
                fromPermanent.negate(), fromExpiring.negate(), amount
        );
        mapper.insertLedger(ledger);
        return reservationFrom(reservation, ledger, false);
    }

    /** 将永久积分原子预冻结的结果补齐冻结单和账本，避免再次更新 wallet_accounts。 */
    private ReservationResult persistPermanentReservation(
            WalletAccountRow wallet,
            UUID apiKeyId,
            UUID serviceGroupId,
            String requestId,
            String idempotencyKey,
            BigDecimal amount,
            Map<String, Object> metadata
    ) {
        WalletReservationRow reservation = new WalletReservationRow();
        reservation.setId(UUID.randomUUID());
        reservation.setUserId(wallet.getUserId());
        reservation.setApiKeyId(apiKeyId);
        reservation.setServiceGroupId(serviceGroupId);
        reservation.setRequestId(requestId);
        reservation.setIdempotencyKey(idempotencyKey);
        reservation.setStatus("reserved");
        reservation.setReservedAmount(amount);
        reservation.setReservedPermanent(amount);
        reservation.setReservedExpiring(ZERO);
        reservation.setSettledAmount(ZERO);
        reservation.setRefundedAmount(ZERO);
        mapper.insertReservation(reservation);

        BillingLedgerRow ledger = newLedger(
                wallet, apiKeyId, reservation.getId(), requestId, "reserve", idempotencyKey,
                "api_request", requestId, null, metadata, amount.negate(), ZERO, amount
        );
        mapper.insertLedger(ledger);
        return reservationFrom(reservation, ledger, false);
    }

    /** 按实际费用结算，自动释放多冻结部分，或原子补扣差额。 */
    @Transactional
    public SettlementResult settle(
            UUID userId,
            UUID reservationId,
            BigDecimal rawActualAmount,
            BigDecimal rawCreditLimit,
            String idempotencyKey,
            Map<String, Object> metadata
    ) {
        BigDecimal actualAmount = normalizePositive(rawActualAmount);
        BigDecimal creditLimit = normalizeOptionalPositive(rawCreditLimit);
        String key = normalizeText(idempotencyKey, 160, "幂等键");
        WalletReservationRow reservation = requireReservation(mapper.lockReservation(reservationId, userId));

        if ("settled".equals(reservation.getStatus())) {
            if (!Objects.equals(reservation.getSettlementIdempotencyKey(), key)
                    || reservation.getSettledAmount().compareTo(actualAmount) != 0) {
                throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
            }
            return settlementFrom(reservation, requireReplayLedger(userId, key, "settle"), true);
        }
        if (!"reserved".equals(reservation.getStatus())) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_STATE_CONFLICT);
        }
        if (mapper.findLedgerByIdempotency(userId, key) != null) {
            throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        }

        WalletAccountRow wallet = requireWallet(mapper.lockWallet(userId));
        expireDueBatchesIfNeeded(userId, wallet);
        List<WalletReservationBatchAllocationRow> allocations = reservation.getReservedExpiring().signum() == 0
                ? List.of() : mapper.lockBatchAllocations(reservationId);
        Map<UUID, WalletReservationBatchAllocationRow> allocationsByBatch = new HashMap<>();
        allocations.forEach(allocation -> allocationsByBatch.put(allocation.getBatchId(), allocation));
        BigDecimal remaining = actualAmount;
        BigDecimal consumeReservedExpiring = ZERO;
        BigDecimal releasedExpiring = ZERO;

        for (WalletReservationBatchAllocationRow allocation : allocations) {
            ExpiringCreditBatchRow batch = requireBatch(
                    mapper.lockExpiringBatch(allocation.getBatchId(), userId)
            );
            BigDecimal consume = min(remaining, allocation.getReservedAmount());
            BigDecimal unused = allocation.getReservedAmount().subtract(consume);
            boolean expired = !batch.getExpiresAt().isAfter(Instant.now());

            batch.setFrozenAmount(batch.getFrozenAmount().subtract(allocation.getReservedAmount()));
            batch.setConsumedAmount(batch.getConsumedAmount().add(consume));
            if (expired) {
                batch.setExpiredAmount(batch.getExpiredAmount().add(unused));
                allocation.setExpiredAmount(allocation.getExpiredAmount().add(unused));
            } else {
                batch.setAvailableAmount(batch.getAvailableAmount().add(unused));
                allocation.setReleasedAmount(allocation.getReleasedAmount().add(unused));
                releasedExpiring = releasedExpiring.add(unused);
            }
            allocation.setSettledAmount(allocation.getSettledAmount().add(consume));
            updateBatch(batch);
            updateAllocation(allocation);
            consumeReservedExpiring = consumeReservedExpiring.add(consume);
            remaining = remaining.subtract(consume);
        }

        BigDecimal consumeReservedPermanent = min(remaining, reservation.getReservedPermanent());
        remaining = remaining.subtract(consumeReservedPermanent);

        List<ExpiringCreditBatchRow> extraCandidates = remaining.signum() == 0 || wallet.getExpiringCredits().signum() == 0
                ? List.of() : spendableBatches(userId, reservation.getServiceGroupId());
        BigDecimal extraExpiring = min(
                remaining,
                extraCandidates.stream().map(ExpiringCreditBatchRow::getAvailableAmount)
                        .reduce(ZERO, BigDecimal::add)
        );
        if (extraExpiring.signum() > 0) {
            allocateReservationBatches(
                    reservationId, extraCandidates, extraExpiring, true, allocationsByBatch
            );
        }
        remaining = remaining.subtract(extraExpiring);
        BigDecimal extraPermanent = remaining;
        if (wallet.getPermanentCredits().compareTo(extraPermanent) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        BigDecimal exposure = reservation.getApiKeyId() == null
                ? ZERO
                : scale(mapper.sumApiKeyExposure(userId, reservation.getApiKeyId()));
        BigDecimal exposureAfter = exposure.subtract(reservation.getReservedAmount()).add(actualAmount);
        if (creditLimit != null && exposureAfter.compareTo(creditLimit) > 0) {
            throw new BusinessException(ErrorCode.API_KEY_CREDIT_LIMIT_EXCEEDED);
        }

        BigDecimal releasePermanent = reservation.getReservedPermanent().subtract(consumeReservedPermanent);
        BigDecimal settledExpiring = consumeReservedExpiring.add(extraExpiring);
        BigDecimal settledPermanent = consumeReservedPermanent.add(extraPermanent);

        BigDecimal newPermanent = wallet.getPermanentCredits().subtract(extraPermanent).add(releasePermanent);
        BigDecimal newExpiring = wallet.getExpiringCredits().subtract(extraExpiring).add(releasedExpiring);
        BigDecimal newFrozen = wallet.getFrozenCredits().subtract(reservation.getReservedAmount());
        BigDecimal permanentDelta = newPermanent.subtract(wallet.getPermanentCredits());
        BigDecimal expiringDelta = newExpiring.subtract(wallet.getExpiringCredits());
        BigDecimal frozenDelta = newFrozen.subtract(wallet.getFrozenCredits());
        applyWallet(wallet, newPermanent, newExpiring, newFrozen);

        reservation.setSettledAmount(actualAmount);
        reservation.setSettledPermanent(settledPermanent);
        reservation.setSettledExpiring(settledExpiring);
        reservation.setSettlementIdempotencyKey(key);
        if (mapper.markReservationSettled(reservation) != 1) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_STATE_CONFLICT);
        }

        BillingLedgerRow ledger = newLedger(
                wallet, reservation.getApiKeyId(), reservation.getId(), reservation.getRequestId(),
                "settle", key, "api_request", reservation.getRequestId(), null, metadata,
                permanentDelta, expiringDelta, frozenDelta
        );
        mapper.insertLedger(ledger);
        reservation.setStatus("settled");
        return settlementFrom(reservation, ledger, false);
    }

    /** 上游未产生可计费结果时，完整释放原冻结额度。 */
    @Transactional
    public ReservationResult release(
            UUID userId,
            UUID reservationId,
            String idempotencyKey,
            Map<String, Object> metadata
    ) {
        String key = normalizeText(idempotencyKey, 160, "幂等键");
        WalletReservationRow reservation = requireReservation(mapper.lockReservation(reservationId, userId));
        if ("released".equals(reservation.getStatus())) {
            if (!Objects.equals(reservation.getReleaseIdempotencyKey(), key)) {
                throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
            }
            return reservationFrom(reservation, requireReplayLedger(userId, key, "release"), true);
        }
        if (!"reserved".equals(reservation.getStatus())) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_STATE_CONFLICT);
        }
        if (mapper.findLedgerByIdempotency(userId, key) != null) {
            throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        }

        WalletAccountRow wallet = requireWallet(mapper.lockWallet(userId));
        expireDueBatchesIfNeeded(userId, wallet);
        BigDecimal releasedExpiring = ZERO;
        List<WalletReservationBatchAllocationRow> allocations = reservation.getReservedExpiring().signum() == 0
                ? List.of() : mapper.lockBatchAllocations(reservationId);
        for (WalletReservationBatchAllocationRow allocation : allocations) {
            ExpiringCreditBatchRow batch = requireBatch(
                    mapper.lockExpiringBatch(allocation.getBatchId(), userId)
            );
            BigDecimal amount = allocation.getReservedAmount();
            batch.setFrozenAmount(batch.getFrozenAmount().subtract(amount));
            if (batch.getExpiresAt().isAfter(Instant.now())) {
                batch.setAvailableAmount(batch.getAvailableAmount().add(amount));
                allocation.setReleasedAmount(allocation.getReleasedAmount().add(amount));
                releasedExpiring = releasedExpiring.add(amount);
            } else {
                batch.setExpiredAmount(batch.getExpiredAmount().add(amount));
                allocation.setExpiredAmount(allocation.getExpiredAmount().add(amount));
            }
            updateBatch(batch);
            updateAllocation(allocation);
        }
        applyWallet(
                wallet,
                wallet.getPermanentCredits().add(reservation.getReservedPermanent()),
                wallet.getExpiringCredits().add(releasedExpiring),
                wallet.getFrozenCredits().subtract(reservation.getReservedAmount())
        );
        reservation.setReleaseIdempotencyKey(key);
        if (mapper.markReservationReleased(reservation) != 1) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_STATE_CONFLICT);
        }
        BillingLedgerRow ledger = newLedger(
                wallet, reservation.getApiKeyId(), reservation.getId(), reservation.getRequestId(),
                "release", key, "api_request", reservation.getRequestId(), null, metadata,
                reservation.getReservedPermanent(), releasedExpiring,
                reservation.getReservedAmount().negate()
        );
        mapper.insertLedger(ledger);
        reservation.setStatus("released");
        return reservationFrom(reservation, ledger, false);
    }

    /** 支持多次部分退款，但累计金额不能超过已结算金额。 */
    @Transactional
    public RefundResult refund(
            UUID userId,
            UUID reservationId,
            BigDecimal rawAmount,
            String idempotencyKey,
            String reason,
            Map<String, Object> metadata
    ) {
        BigDecimal amount = normalizePositive(rawAmount);
        String key = normalizeText(idempotencyKey, 160, "幂等键");
        String normalizedReason = normalizeOptionalText(reason, 500, "退款原因");
        WalletReservationRow reservation = requireReservation(mapper.lockReservation(reservationId, userId));
        BillingRefundRow existing = mapper.findRefundByIdempotency(userId, key);
        if (existing != null) {
            if (!existing.getReservationId().equals(reservationId)
                    || existing.getAmount().compareTo(amount) != 0
                    || !Objects.equals(existing.getReason(), normalizedReason)) {
                throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
            }
            BillingLedgerRow ledger = requireReplayLedger(userId, key, "refund");
            return refundFrom(existing, reservation, ledger, true);
        }
        if (!"settled".equals(reservation.getStatus())) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_STATE_CONFLICT);
        }
        if (mapper.findLedgerByIdempotency(userId, key) != null) {
            throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        }
        BigDecimal refundable = reservation.getSettledAmount().subtract(reservation.getRefundedAmount());
        if (amount.compareTo(refundable) > 0) {
            throw new BusinessException(ErrorCode.BILLING_REFUND_EXCEEDS_SETTLED);
        }

        WalletAccountRow wallet = requireWallet(mapper.lockWallet(userId));
        expireDueBatchesIfNeeded(userId, wallet);
        BigDecimal remaining = amount;
        BigDecimal refundExpiring = ZERO;
        BigDecimal creditedExpiring = ZERO;
        List<WalletReservationBatchAllocationRow> allocations = reservation.getReservedExpiring().signum() == 0
                ? List.of() : mapper.lockBatchAllocations(reservationId);
        for (WalletReservationBatchAllocationRow allocation : allocations) {
            BigDecimal refundableFromBatch = allocation.getSettledAmount().subtract(allocation.getRefundedAmount());
            BigDecimal batchRefund = min(remaining, refundableFromBatch);
            if (batchRefund.signum() == 0) {
                continue;
            }
            ExpiringCreditBatchRow batch = requireBatch(
                    mapper.lockExpiringBatch(allocation.getBatchId(), userId)
            );
            batch.setConsumedAmount(batch.getConsumedAmount().subtract(batchRefund));
            if (batch.getExpiresAt().isAfter(Instant.now())) {
                batch.setAvailableAmount(batch.getAvailableAmount().add(batchRefund));
                creditedExpiring = creditedExpiring.add(batchRefund);
            } else {
                batch.setExpiredAmount(batch.getExpiredAmount().add(batchRefund));
            }
            allocation.setRefundedAmount(allocation.getRefundedAmount().add(batchRefund));
            updateBatch(batch);
            updateAllocation(allocation);
            refundExpiring = refundExpiring.add(batchRefund);
            remaining = remaining.subtract(batchRefund);
        }
        BigDecimal refundPermanent = remaining;
        BigDecimal refundablePermanent = reservation.getSettledPermanent()
                .subtract(reservation.getRefundedPermanent());
        if (refundPermanent.compareTo(refundablePermanent) > 0) {
            throw new BusinessException(ErrorCode.BILLING_REFUND_EXCEEDS_SETTLED);
        }
        applyWallet(
                wallet,
                wallet.getPermanentCredits().add(refundPermanent),
                wallet.getExpiringCredits().add(creditedExpiring),
                wallet.getFrozenCredits()
        );
        if (mapper.addReservationRefund(
                reservationId, userId, amount, refundPermanent, refundExpiring, reservation.getVersion()
        ) != 1) {
            throw new BusinessException(ErrorCode.BILLING_REFUND_EXCEEDS_SETTLED);
        }

        BillingRefundRow refund = new BillingRefundRow();
        refund.setId(UUID.randomUUID());
        refund.setUserId(userId);
        refund.setReservationId(reservationId);
        refund.setIdempotencyKey(key);
        refund.setAmount(amount);
        refund.setPermanentAmount(refundPermanent);
        refund.setExpiringAmount(refundExpiring);
        refund.setCumulativeAmount(reservation.getRefundedAmount().add(amount));
        refund.setRefundableAfter(reservation.getSettledAmount().subtract(refund.getCumulativeAmount()));
        refund.setReason(normalizedReason);
        mapper.insertRefund(refund);

        BillingLedgerRow ledger = newLedger(
                wallet, reservation.getApiKeyId(), reservationId, reservation.getRequestId(),
                "refund", key, "reservation", reservationId.toString(), null, metadata,
                refundPermanent, creditedExpiring, ZERO
        );
        mapper.insertLedger(ledger);
        reservation.setRefundedAmount(reservation.getRefundedAmount().add(amount));
        reservation.setRefundedPermanent(reservation.getRefundedPermanent().add(refundPermanent));
        reservation.setRefundedExpiring(reservation.getRefundedExpiring().add(refundExpiring));
        return refundFrom(refund, reservation, ledger, false);
    }

    /** 返回需要执行到期回收的用户，供定时任务分用户进入短事务。 */
    @Transactional(readOnly = true)
    public List<UUID> findDueBatchUserIds(int limit) {
        if (limit < 1 || limit > 500) {
            throw validation("到期回收批量大小必须在 1 到 500 之间");
        }
        return List.copyOf(mapper.findDueBatchUserIds(limit));
    }

    /** 定时任务或请求入口均可幂等回收当前用户已经到期的可用限时积分。 */
    @Transactional
    public void expireDueBatches(UUID userId) {
        WalletAccountRow wallet = requireWallet(mapper.lockWallet(userId));
        expireDueBatchesIfNeeded(userId, wallet);
    }

    /** 套餐切换或取消时立即关闭旧订阅可用积分；在途冻结仍按原批次完成收尾。 */
    @Transactional
    public void expireSubscriptionCredits(UUID userId, UUID subscriptionId, String reason) {
        WalletAccountRow wallet = requireWallet(mapper.lockWallet(userId));
        Instant closedAt = Instant.now();
        BigDecimal expired = ZERO;
        for (ExpiringCreditBatchRow batch : mapper.lockSubscriptionBatches(userId, subscriptionId)) {
            BigDecimal amount = batch.getAvailableAmount();
            batch.setExpiresAt(closedAt.isAfter(batch.getCreatedAt())
                    ? closedAt : batch.getCreatedAt().plusNanos(1));
            if (amount.signum() > 0) {
                batch.setAvailableAmount(ZERO);
                batch.setExpiredAmount(batch.getExpiredAmount().add(amount));
                expired = expired.add(amount);
            }
            batch.setStatus("expired");
            updateBatch(batch);
        }
        if (expired.signum() == 0) return;

        applyWallet(
                wallet,
                wallet.getPermanentCredits(),
                wallet.getExpiringCredits().subtract(expired),
                wallet.getFrozenCredits()
        );
        String key = "subscription:expire:" + subscriptionId;
        if (mapper.findLedgerByIdempotency(userId, key) == null) {
            mapper.insertLedger(newLedger(
                    wallet, null, null, null, "expire", key,
                    "subscription", subscriptionId.toString(), closedAt,
                    Map.of("reason", normalizeText(reason, 120, "关闭原因")),
                    ZERO, expired.negate(), ZERO
            ));
        }
    }

    private void assertCreditLimit(UUID userId, UUID apiKeyId, BigDecimal creditLimit, BigDecimal addition) {
        if (creditLimit == null) {
            return;
        }
        if (apiKeyId == null) {
            throw validation("设置 API 令牌消费上限时必须提供 apiKeyId");
        }
        BigDecimal exposure = scale(mapper.sumApiKeyExposure(userId, apiKeyId));
        if (exposure.add(addition).compareTo(creditLimit) > 0) {
            throw new BusinessException(ErrorCode.API_KEY_CREDIT_LIMIT_EXCEEDED);
        }
    }

    /** 订阅积分来源使用独立批次；普通充值或赠送的限时积分作为通用批次。 */
    private UUID subscriptionId(String sourceType, String sourceId) {
        if (!"subscription".equals(sourceType) || sourceId == null) {
            return null;
        }
        try {
            return UUID.fromString(sourceId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 订阅批次优先，其次是其他通用限时批次；永久余额由调用方最后兜底。 */
    private List<ExpiringCreditBatchRow> spendableBatches(UUID userId, UUID serviceGroupId) {
        List<ExpiringCreditBatchRow> result = new ArrayList<>();
        if (serviceGroupId != null) {
            result.addAll(mapper.lockSpendableSubscriptionBatches(userId, serviceGroupId));
        }
        result.addAll(mapper.lockSpendableGeneralBatches(userId));
        return result;
    }

    /**
     * 只有账户存在限时积分时才查询到期批次；绝大多数永久积分请求可跳过一次 FOR UPDATE 查询，
     * 同时保留原有到期回收语义。若数据出现聚合余额与批次不一致，应由对账任务修复，而不是在热路径放大锁竞争。
     */
    private void expireDueBatchesIfNeeded(UUID userId, WalletAccountRow wallet) {
        if (wallet.getExpiringCredits() == null || wallet.getExpiringCredits().signum() <= 0) {
            return;
        }
        expireDueBatchesLocked(userId, wallet);
    }

    /** 批次到期回收只减少对应用户的聚合限时余额，不触碰永久余额和在途冻结。 */
    private void expireDueBatchesLocked(UUID userId, WalletAccountRow wallet) {
        for (ExpiringCreditBatchRow batch : mapper.lockDueBatches(userId)) {
            BigDecimal amount = batch.getAvailableAmount();
            if (amount.signum() <= 0) continue;
            batch.setAvailableAmount(ZERO);
            batch.setExpiredAmount(batch.getExpiredAmount().add(amount));
            batch.setStatus("expired");
            updateBatch(batch);
            applyWallet(
                    wallet,
                    wallet.getPermanentCredits(),
                    wallet.getExpiringCredits().subtract(amount),
                    wallet.getFrozenCredits()
            );
            String key = "expiring-batch:expire:" + batch.getId();
            if (mapper.findLedgerByIdempotency(userId, key) == null) {
                mapper.insertLedger(newLedger(
                        wallet, null, null, null, "expire", key,
                        "credit_batch", batch.getId().toString(), batch.getExpiresAt(),
                        Map.of("source_type", batch.getSourceType()),
                        ZERO, amount.negate(), ZERO
                ));
            }
        }
    }

    /** 将一次冻结金额按最早到期批次分摊；额外结算批次立即从 available 转为 consumed。 */
    private void allocateReservationBatches(
            UUID reservationId,
            List<ExpiringCreditBatchRow> candidates,
            BigDecimal amount,
            boolean additional,
            Map<UUID, WalletReservationBatchAllocationRow> existingAllocations
    ) {
        BigDecimal remaining = amount;
        for (ExpiringCreditBatchRow batch : candidates) {
            if (remaining.signum() == 0) break;
            BigDecimal allocationAmount = min(remaining, batch.getAvailableAmount());
            if (allocationAmount.signum() == 0) continue;

            batch.setAvailableAmount(batch.getAvailableAmount().subtract(allocationAmount));
            if (additional) {
                batch.setConsumedAmount(batch.getConsumedAmount().add(allocationAmount));
            } else {
                batch.setFrozenAmount(batch.getFrozenAmount().add(allocationAmount));
            }
            batch.setStatus(batch.getAvailableAmount().signum() == 0 && batch.getFrozenAmount().signum() == 0
                    ? "depleted" : "active");
            updateBatch(batch);

            WalletReservationBatchAllocationRow row = existingAllocations.get(batch.getId());
            if (additional && row != null) {
                row.setAdditionalAmount(row.getAdditionalAmount().add(allocationAmount));
                row.setSettledAmount(row.getSettledAmount().add(allocationAmount));
                updateAllocation(row);
            } else {
                row = new WalletReservationBatchAllocationRow();
                row.setId(UUID.randomUUID());
                row.setReservationId(reservationId);
                row.setBatchId(batch.getId());
                row.setReservedAmount(additional ? ZERO : allocationAmount);
                row.setAdditionalAmount(additional ? allocationAmount : ZERO);
                row.setSettledAmount(additional ? allocationAmount : ZERO);
                row.setReleasedAmount(ZERO);
                row.setExpiredAmount(ZERO);
                row.setRefundedAmount(ZERO);
                mapper.insertBatchAllocation(row);
                if (additional) {
                    existingAllocations.put(batch.getId(), row);
                }
            }
            remaining = remaining.subtract(allocationAmount);
        }
        if (remaining.signum() != 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
    }

    private ExpiringCreditBatchRow requireBatch(ExpiringCreditBatchRow row) {
        if (row == null) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_STATE_CONFLICT, "积分批次不存在", null);
        }
        return row;
    }

    private void updateBatch(ExpiringCreditBatchRow batch) {
        if (!batch.getExpiresAt().isAfter(Instant.now())) {
            batch.setStatus("expired");
        } else if (batch.getAvailableAmount().signum() == 0 && batch.getFrozenAmount().signum() == 0) {
            batch.setStatus("depleted");
        } else {
            batch.setStatus("active");
        }
        if (mapper.updateExpiringBatch(batch) != 1) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_STATE_CONFLICT);
        }
        batch.setVersion(batch.getVersion() + 1);
    }

    private void updateAllocation(WalletReservationBatchAllocationRow allocation) {
        if (mapper.updateBatchAllocation(allocation) != 1) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_STATE_CONFLICT);
        }
        allocation.setVersion(allocation.getVersion() + 1);
    }

    private void applyWallet(
            WalletAccountRow wallet,
            BigDecimal permanent,
            BigDecimal expiring,
            BigDecimal frozen
    ) {
        permanent = scale(permanent);
        expiring = scale(expiring);
        frozen = scale(frozen);
        if (permanent.signum() < 0 || expiring.signum() < 0 || frozen.signum() < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        if (permanent.compareTo(MAX_AMOUNT) > 0
                || expiring.compareTo(MAX_AMOUNT) > 0
                || frozen.compareTo(MAX_AMOUNT) > 0
                || permanent.add(expiring).add(frozen).compareTo(MAX_AMOUNT) > 0) {
            throw validation("钱包余额超过系统上限");
        }
        wallet.setPermanentCredits(permanent);
        wallet.setExpiringCredits(expiring);
        wallet.setFrozenCredits(frozen);
        if (mapper.updateWallet(wallet) != 1) {
            throw new IllegalStateException("Wallet optimistic update failed while row was locked");
        }
        wallet.setVersion(wallet.getVersion() + 1);
    }

    private BillingLedgerRow newLedger(
            WalletAccountRow wallet,
            UUID apiKeyId,
            UUID reservationId,
            String requestId,
            String entryType,
            String idempotencyKey,
            String sourceType,
            String sourceId,
            Instant expiresAt,
            Map<String, Object> metadata,
            BigDecimal permanentDelta,
            BigDecimal expiringDelta,
            BigDecimal frozenDelta
    ) {
        BillingLedgerRow row = new BillingLedgerRow();
        row.setId(UUID.randomUUID());
        row.setUserId(wallet.getUserId());
        row.setApiKeyId(apiKeyId);
        row.setReservationId(reservationId);
        row.setRequestId(requestId);
        row.setEntryType(entryType);
        row.setPermanentDelta(scale(permanentDelta));
        row.setExpiringDelta(scale(expiringDelta));
        row.setFrozenDelta(scale(frozenDelta));
        row.setAmount(scale(permanentDelta.add(expiringDelta).add(frozenDelta)));
        row.setPermanentAfter(wallet.getPermanentCredits());
        row.setExpiringAfter(wallet.getExpiringCredits());
        row.setFrozenAfter(wallet.getFrozenCredits());
        row.setAvailableAfter(available(wallet));
        row.setBalanceAfter(total(wallet));
        row.setWalletVersionAfter(wallet.getVersion());
        row.setIdempotencyKey(idempotencyKey);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setExpiresAt(expiresAt);
        row.setMetadataJson(toMetadataJson(metadata == null ? Map.of() : metadata));
        return row;
    }

    private BillingLedgerRow requireReplayLedger(UUID userId, String key, String expectedType) {
        BillingLedgerRow row = mapper.findLedgerByIdempotency(userId, key);
        if (row == null || !expectedType.equals(row.getEntryType())) {
            throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        }
        return row;
    }

    private void assertLedgerReplay(
            BillingLedgerRow row,
            String type,
            BigDecimal amount,
            BigDecimal permanentDelta,
            BigDecimal expiringDelta,
            BigDecimal frozenDelta,
            UUID reservationId,
            String requestId,
            String sourceType,
            String sourceId
    ) {
        if (!type.equals(row.getEntryType())
                || row.getAmount().compareTo(amount) != 0
                || row.getPermanentDelta().compareTo(permanentDelta) != 0
                || row.getExpiringDelta().compareTo(expiringDelta) != 0
                || row.getFrozenDelta().compareTo(frozenDelta) != 0
                || !Objects.equals(row.getReservationId(), reservationId)
                || !Objects.equals(row.getRequestId(), requestId)
                || !Objects.equals(row.getSourceType(), sourceType)
                || !Objects.equals(row.getSourceId(), sourceId)) {
            throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        }
    }

    private void assertReservationReplay(
            WalletReservationRow row,
            UUID apiKeyId,
            UUID serviceGroupId,
            String requestId,
            BigDecimal amount
    ) {
        if (!Objects.equals(row.getApiKeyId(), apiKeyId)
                || !Objects.equals(row.getServiceGroupId(), serviceGroupId)
                || !Objects.equals(row.getRequestId(), requestId)
                || row.getReservedAmount().compareTo(amount) != 0) {
            throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        }
    }

    private WalletAccountRow requireWallet(WalletAccountRow row) {
        if (row == null) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        return row;
    }

    private WalletReservationRow requireReservation(WalletReservationRow row) {
        if (row == null) {
            throw new BusinessException(ErrorCode.BILLING_RESERVATION_NOT_FOUND);
        }
        return row;
    }

    private WalletBalanceResponse toBalance(WalletAccountRow row) {
        return new WalletBalanceResponse(
                row.getPermanentCredits(),
                row.getExpiringCredits(),
                row.getFrozenCredits(),
                available(row),
                total(row),
                row.getVersion(),
                row.getUpdatedAt()
        );
    }

    private BillingLedgerItemResponse toLedgerItem(BillingLedgerRow row) {
        return new BillingLedgerItemResponse(
                row.getId(), row.getApiKeyId(), row.getReservationId(), row.getRequestId(),
                row.getEntryType(), row.getAmount(), row.getPermanentDelta(), row.getExpiringDelta(),
                row.getFrozenDelta(), row.getAvailableAfter(), row.getFrozenAfter(),
                row.getBalanceAfter(), row.getSourceType(), row.getSourceId(), row.getCreatedAt()
        );
    }

    private MutationResult mutationFromLedger(BillingLedgerRow row, boolean replayed) {
        return new MutationResult(
                row.getId(), row.getEntryType(), row.getAmount(), snapshotFrom(row), replayed
        );
    }

    private ReservationResult reservationFrom(
            WalletReservationRow reservation,
            BillingLedgerRow ledger,
            boolean replayed
    ) {
        return new ReservationResult(
                reservation.getId(), reservation.getStatus(), reservation.getReservedAmount(),
                reservation.getSettledAmount(), reservation.getRefundedAmount(), snapshotFrom(ledger), replayed
        );
    }

    private SettlementResult settlementFrom(
            WalletReservationRow reservation,
            BillingLedgerRow ledger,
            boolean replayed
    ) {
        return new SettlementResult(
                reservation.getId(), reservation.getSettledAmount(),
                reservation.getReservedAmount().subtract(reservation.getSettledAmount()).max(ZERO),
                snapshotFrom(ledger), replayed
        );
    }

    private RefundResult refundFrom(
            BillingRefundRow refund,
            WalletReservationRow reservation,
            BillingLedgerRow ledger,
            boolean replayed
    ) {
        return new RefundResult(
                refund.getId(), reservation.getId(), refund.getAmount(), refund.getCumulativeAmount(),
                refund.getRefundableAfter(), snapshotFrom(ledger), replayed
        );
    }

    private WalletSnapshot snapshotFrom(BillingLedgerRow row) {
        return new WalletSnapshot(
                row.getPermanentAfter(), row.getExpiringAfter(), row.getFrozenAfter(),
                row.getAvailableAfter(), row.getBalanceAfter(), row.getWalletVersionAfter()
        );
    }

    private BigDecimal available(WalletAccountRow wallet) {
        return scale(wallet.getPermanentCredits().add(wallet.getExpiringCredits()));
    }

    private BigDecimal total(WalletAccountRow wallet) {
        return scale(available(wallet).add(wallet.getFrozenCredits()));
    }

    private BigDecimal normalizePositive(BigDecimal value) {
        if (value == null) {
            throw validation("金额不能为空");
        }
        BigDecimal normalized;
        try {
            normalized = value.setScale(12, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw validation("金额最多保留 12 位小数");
        }
        if (normalized.signum() <= 0 || normalized.compareTo(MAX_AMOUNT) > 0) {
            throw validation("金额必须大于 0 且不超过系统上限");
        }
        return normalized;
    }

    private BigDecimal normalizeOptionalPositive(BigDecimal value) {
        return value == null ? null : normalizePositive(value);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(12, RoundingMode.UNNECESSARY);
    }

    private BigDecimal min(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private String normalizeText(String value, int maxLength, String field) {
        String normalized = normalizeOptionalText(value, maxLength, field);
        if (normalized == null) {
            throw validation(field + "不能为空");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw validation(field + "格式无效");
        }
        return normalized;
    }

    private String toMetadataJson(Map<String, Object> value) {
        assertSafeMetadata(value);
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > MAX_METADATA_JSON_LENGTH) {
                throw validation("账单元数据过大");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize billing metadata", exception);
        }
    }

    /** 防止 Authorization、Secret、Token 或密码被误写入长期保留的资金账本。 */
    private void assertSafeMetadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
                if (SENSITIVE_METADATA_KEYS.contains(key)) {
                    throw validation("账单元数据不能包含敏感凭证字段");
                }
                assertSafeMetadata(entry.getValue());
            }
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(this::assertSafeMetadata);
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                assertSafeMetadata(java.lang.reflect.Array.get(value, index));
            }
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }

    public record WalletSnapshot(
            BigDecimal permanentCredits,
            BigDecimal expiringCredits,
            BigDecimal frozenCredits,
            BigDecimal availableCredits,
            BigDecimal totalCredits,
            long version
    ) {
    }

    public record MutationResult(
            UUID ledgerId,
            String operation,
            BigDecimal amount,
            WalletSnapshot wallet,
            boolean replayed
    ) {
    }

    public record ReservationResult(
            UUID reservationId,
            String status,
            BigDecimal reservedAmount,
            BigDecimal settledAmount,
            BigDecimal refundedAmount,
            WalletSnapshot wallet,
            boolean replayed
    ) {
    }

    public record SettlementResult(
            UUID reservationId,
            BigDecimal settledAmount,
            BigDecimal releasedAmount,
            WalletSnapshot wallet,
            boolean replayed
    ) {
    }

    public record RefundResult(
            UUID refundId,
            UUID reservationId,
            BigDecimal amount,
            BigDecimal cumulativeRefundedAmount,
            BigDecimal refundableAmount,
            WalletSnapshot wallet,
            boolean replayed
    ) {
    }
}
