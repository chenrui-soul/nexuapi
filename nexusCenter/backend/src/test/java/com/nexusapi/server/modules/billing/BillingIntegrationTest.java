package com.nexusapi.server.modules.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.billing.enums.CreditBucket;
import com.nexusapi.server.modules.billing.enums.CreditType;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.testing.TestServiceGroupFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实 PostgreSQL/Redis 验证钱包、不可变账本和完整资金状态机。
 */
@SpringBootTest(properties = {"nexus.payment.mock-enabled=true", "nexus.payment.points-per-currency=100"})
@AutoConfigureMockMvc
@Import(BillingIntegrationTest.FixedCaptchaConfiguration.class)
class BillingIntegrationTest {
    private static final BigDecimal ZERO = new BigDecimal("0.00000000");

    @Autowired
    private BillingService billingService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private BillingCases cases;
    private UUID serviceGroupId;

    @BeforeEach
    void resetState() throws Exception {
        cases = objectMapper.readValue(
                Files.readString(Path.of("references/billing-cases.json")),
                BillingCases.class
        );
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        serviceGroupId = TestServiceGroupFixture.createPublicGroup(jdbcTemplate);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void creditIsIdempotentAndLedgerIsDatabaseImmutable() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        BigDecimal amount = money(cases.permanentCredit());

        BillingService.MutationResult created = credit(user.id(), amount, "credit-idem-1");
        BillingService.MutationResult replayed = credit(user.id(), amount, "credit-idem-1");

        assertThat(created.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.ledgerId()).isEqualTo(created.ledgerId());
        assertWallet(user.id(), amount, ZERO, ZERO);
        assertThat(count("billing_ledger")).isEqualTo(1);

        assertBusinessError(
                () -> credit(user.id(), amount.add(BigDecimal.ONE), "credit-idem-1"),
                ErrorCode.BILLING_IDEMPOTENCY_CONFLICT
        );
        assertBusinessError(
                () -> billingService.credit(
                        user.id(), BigDecimal.ONE, CreditBucket.PERMANENT, CreditType.GRANT,
                        "credit-sensitive-metadata", "test", "test-source", null,
                        Map.of("authorization", "Bearer must-not-persist")
                ),
                ErrorCode.VALIDATION_ERROR
        );
        assertThat(count("billing_ledger")).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE billing_ledger SET amount = amount + 1 WHERE id = ?",
                created.ledgerId()
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM billing_ledger WHERE id = ?",
                created.ledgerId()
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void creditReserveSettleAndPartialRefundFormCompleteClosedLoop() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        BigDecimal permanent = money(cases.permanentCredit());
        BigDecimal expiring = money(cases.expiringCredit());
        BigDecimal reserved = money(cases.reservationAmount());
        BigDecimal settled = money(cases.settlementAmount());
        BigDecimal refund = money(cases.partialRefund());

        credit(user.id(), permanent, "credit-permanent");
        billingService.credit(
                user.id(), expiring, CreditBucket.EXPIRING, CreditType.GRANT,
                "credit-expiring", "plan", "starter", Instant.now().plusSeconds(86_400),
                Map.of("case", "expiring")
        );

        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, "req-billing-1", reserved, null, "reserve-1", Map.of()
        );
        BillingService.ReservationResult reserveReplay = billingService.reserve(
                user.id(), null, "req-billing-1", reserved, null, "reserve-1", Map.of()
        );
        assertThat(reserveReplay.replayed()).isTrue();
        assertThat(reserveReplay.reservationId()).isEqualTo(reservation.reservationId());
        assertWallet(user.id(), new BigDecimal("80.00000000"), ZERO, reserved);

        BillingService.SettlementResult settlement = billingService.settle(
                user.id(), reservation.reservationId(), settled, null, "settle-1", Map.of()
        );
        BillingService.SettlementResult settlementReplay = billingService.settle(
                user.id(), reservation.reservationId(), settled, null, "settle-1", Map.of()
        );
        assertThat(settlement.releasedAmount()).isEqualByComparingTo("10.00000000");
        assertThat(settlementReplay.replayed()).isTrue();
        assertWallet(user.id(), new BigDecimal("90.00000000"), ZERO, ZERO);

        BillingService.RefundResult firstRefund = billingService.refund(
                user.id(), reservation.reservationId(), refund, "refund-1", "partial", Map.of()
        );
        BillingService.RefundResult refundReplay = billingService.refund(
                user.id(), reservation.reservationId(), refund, "refund-1", "partial", Map.of()
        );
        assertThat(firstRefund.cumulativeRefundedAmount()).isEqualByComparingTo(refund);
        assertThat(refundReplay.replayed()).isTrue();
        assertWallet(user.id(), new BigDecimal("90.00000000"), refund, ZERO);

        billingService.refund(
                user.id(), reservation.reservationId(), new BigDecimal("30.00000000"),
                "refund-2", "remaining", Map.of()
        );
        BillingService.RefundResult lateReplay = billingService.refund(
                user.id(), reservation.reservationId(), refund, "refund-1", "partial", Map.of()
        );
        assertThat(lateReplay.cumulativeRefundedAmount()).isEqualByComparingTo("20.00000000");
        assertThat(lateReplay.refundableAmount()).isEqualByComparingTo("30.00000000");
        assertThat(lateReplay.wallet().totalCredits()).isEqualByComparingTo("110.00000000");
        assertWallet(user.id(), permanent, expiring, ZERO);
        assertThat(count("billing_ledger")).isEqualTo(6);
        assertThat(count("billing_refunds")).isEqualTo(2);
    }

    @Test
    void settlementCanAtomicallyChargeMoreThanReserved() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        credit(user.id(), money(cases.permanentCredit()), "credit-overage");
        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, "req-overage", new BigDecimal("30.00000000"),
                null, "reserve-overage", Map.of()
        );

        billingService.settle(
                user.id(), reservation.reservationId(), new BigDecimal("50.00000000"),
                null, "settle-overage", Map.of()
        );
        assertWallet(user.id(), new BigDecimal("50.00000000"), ZERO, ZERO);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM billing_ledger WHERE idempotency_key = 'settle-overage'",
                BigDecimal.class
        )).isEqualByComparingTo("-50.00000000");
    }

    @Test
    void insufficientBalanceAndOverRefundFailWithoutPartialMutation() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        credit(user.id(), new BigDecimal("30.00000000"), "credit-small");

        long ledgerBefore = count("billing_ledger");
        assertBusinessError(
                () -> billingService.reserve(
                        user.id(), null, "req-too-large", new BigDecimal("40.00000000"),
                        null, "reserve-too-large", Map.of()
                ),
                ErrorCode.INSUFFICIENT_BALANCE
        );
        assertWallet(user.id(), new BigDecimal("30.00000000"), ZERO, ZERO);
        assertThat(count("billing_ledger")).isEqualTo(ledgerBefore);

        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, "req-refund-limit", new BigDecimal("20.00000000"),
                null, "reserve-refund-limit", Map.of()
        );
        billingService.settle(
                user.id(), reservation.reservationId(), new BigDecimal("20.00000000"),
                null, "settle-refund-limit", Map.of()
        );
        assertBusinessError(
                () -> billingService.refund(
                        user.id(), reservation.reservationId(), new BigDecimal("21.00000000"),
                        "refund-too-large", "invalid", Map.of()
                ),
                ErrorCode.BILLING_REFUND_EXCEEDS_SETTLED
        );
        assertWallet(user.id(), new BigDecimal("10.00000000"), ZERO, ZERO);
        assertThat(count("billing_refunds")).isZero();
    }

    @Test
    void releaseRestoresOriginalBucketsAndPreventsSettlement() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        credit(user.id(), new BigDecimal("20.00000000"), "release-permanent");
        billingService.credit(
                user.id(), new BigDecimal("10.00000000"), CreditBucket.EXPIRING, CreditType.GRANT,
                "release-expiring", "plan", "release", Instant.now().plusSeconds(86_400), Map.of()
        );
        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, "req-release", new BigDecimal("25.00000000"),
                null, "reserve-release", Map.of()
        );

        BillingService.ReservationResult released = billingService.release(
                user.id(), reservation.reservationId(), "release-1", Map.of()
        );
        BillingService.ReservationResult replay = billingService.release(
                user.id(), reservation.reservationId(), "release-1", Map.of()
        );
        assertThat(released.status()).isEqualTo("released");
        assertThat(replay.replayed()).isTrue();
        assertWallet(user.id(), new BigDecimal("20.00000000"), new BigDecimal("10.00000000"), ZERO);
        assertBusinessError(
                () -> billingService.settle(
                        user.id(), reservation.reservationId(), BigDecimal.ONE,
                        null, "settle-after-release", Map.of()
                ),
                ErrorCode.BILLING_RESERVATION_STATE_CONFLICT
        );
    }

    @Test
    void apiKeyCreditLimitIncludesReservedAndNetSettledExposure() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        UUID apiKeyId = createApiKey(user.session(), new BigDecimal("50.00000000"));
        credit(user.id(), money(cases.permanentCredit()), "credit-key-limit");

        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), apiKeyId, "req-key-limit-1", new BigDecimal("40.00000000"),
                new BigDecimal("50.00000000"), "reserve-key-limit-1", Map.of()
        );
        assertBusinessError(
                () -> billingService.reserve(
                        user.id(), apiKeyId, "req-key-limit-2", new BigDecimal("20.00000000"),
                        new BigDecimal("50.00000000"), "reserve-key-limit-2", Map.of()
                ),
                ErrorCode.API_KEY_CREDIT_LIMIT_EXCEEDED
        );
        assertBusinessError(
                () -> billingService.settle(
                        user.id(), reservation.reservationId(), new BigDecimal("60.00000000"),
                        new BigDecimal("50.00000000"), "settle-key-limit-invalid", Map.of()
                ),
                ErrorCode.API_KEY_CREDIT_LIMIT_EXCEEDED
        );
        assertWallet(user.id(), new BigDecimal("60.00000000"), ZERO, new BigDecimal("40.00000000"));

        billingService.settle(
                user.id(), reservation.reservationId(), new BigDecimal("30.00000000"),
                new BigDecimal("50.00000000"), "settle-key-limit-valid", Map.of()
        );
        billingService.reserve(
                user.id(), apiKeyId, "req-key-limit-3", new BigDecimal("20.00000000"),
                new BigDecimal("50.00000000"), "reserve-key-limit-3", Map.of()
        );
        assertWallet(user.id(), new BigDecimal("50.00000000"), ZERO, new BigDecimal("20.00000000"));
    }

    @Test
    void existingReservationCanReplayAfterAccountSuspensionButNewFreezeIsRejected() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        credit(user.id(), money(cases.permanentCredit()), "credit-suspended-replay");
        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, "req-suspended-replay", new BigDecimal("20.00000000"),
                null, "reserve-suspended-replay", Map.of()
        );
        jdbcTemplate.update("UPDATE users SET status = 'suspended' WHERE id = ?", user.id());

        BillingService.ReservationResult replay = billingService.reserve(
                user.id(), null, "req-suspended-replay", new BigDecimal("20.00000000"),
                null, "reserve-suspended-replay", Map.of()
        );
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reservationId()).isEqualTo(reservation.reservationId());
        assertBusinessError(
                () -> billingService.reserve(
                        user.id(), null, "req-suspended-new", BigDecimal.ONE,
                        null, "reserve-suspended-new", Map.of()
                ),
                ErrorCode.AUTH_ACCOUNT_DISABLED
        );
        assertWallet(user.id(), new BigDecimal("80.00000000"), ZERO, new BigDecimal("20.00000000"));
    }

    @Test
    void concurrentReservationsCannotOverdrawWallet() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        credit(user.id(), money(cases.permanentCredit()), "credit-concurrent");
        BigDecimal amount = money(cases.concurrentReservation());
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Attempt> first = executor.submit(() -> reserveAfter(start, user.id(), amount, "a"));
            Future<Attempt> second = executor.submit(() -> reserveAfter(start, user.id(), amount, "b"));
            start.countDown();
            Attempt one = first.get();
            Attempt two = second.get();

            assertThat(java.util.List.of(one, two).stream().filter(Attempt::success).count()).isEqualTo(1);
            assertThat(java.util.List.of(one, two).stream()
                    .filter(attempt -> attempt.errorCode() == ErrorCode.INSUFFICIENT_BALANCE).count()).isEqualTo(1);
        }
        assertWallet(user.id(), new BigDecimal("20.00000000"), ZERO, amount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_reservations WHERE status = 'reserved'",
                Long.class
        )).isEqualTo(1L);
    }

    @Test
    void walletAndLedgerEndpointsReturnOnlyCurrentUsersFunds() throws Exception {
        RegisteredUser primary = register(cases.primaryUser());
        RegisteredUser secondary = register(cases.secondaryUser());
        credit(primary.id(), new BigDecimal("70.00000000"), "primary-credit");
        credit(secondary.id(), new BigDecimal("15.00000000"), "secondary-credit");

        mockMvc.perform(get("/api/v1/wallet").cookie(primary.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available_credits").value(70.0))
                .andExpect(jsonPath("$.data.total_credits").value(70.0));
        mockMvc.perform(get("/api/v1/wallet/ledger").cookie(primary.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].source_id").value("test-source"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_ledger WHERE user_id = ?",
                Long.class,
                secondary.id()
        )).isEqualTo(1L);
    }

    @Test
    void mockRechargeCreatesOrderCreditsWalletAndIsIdempotent() throws Exception {
        RegisteredUser user = register(cases.primaryUser());
        String idempotencyKey = "recharge-order-" + UUID.randomUUID();
        String body = """
                {"amount": "10.00", "payment_provider": "mock"}
                """;
        MvcResult created = mockMvc.perform(post("/api/v1/wallet/recharge-orders")
                        .cookie(user.session())
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.credited_points").value(1000.0))
                .andReturn();
        UUID orderId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/id").asText());

        mockMvc.perform(post("/api/v1/wallet/recharge-orders/{id}/mock-pay", orderId)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("paid"));
        mockMvc.perform(post("/api/v1/wallet/recharge-orders/{id}/mock-pay", orderId)
                        .cookie(user.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("paid"));

        mockMvc.perform(get("/api/v1/wallet").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available_credits").value(1000.0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_ledger WHERE user_id = ? AND entry_type = 'recharge'",
                Long.class,
                user.id()
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE user_id = ? AND status = 'paid'",
                Long.class,
                user.id()
        )).isEqualTo(1L);
    }

    private Attempt reserveAfter(CountDownLatch start, UUID userId, BigDecimal amount, String suffix) {
        try {
            start.await();
            billingService.reserve(
                    userId, null, "req-concurrent-" + suffix, amount,
                    null, "reserve-concurrent-" + suffix, Map.of()
            );
            return new Attempt(true, null);
        } catch (BusinessException exception) {
            return new Attempt(false, exception.errorCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private BillingService.MutationResult credit(UUID userId, BigDecimal amount, String idempotencyKey) {
        return billingService.credit(
                userId, amount, CreditBucket.PERMANENT, CreditType.RECHARGE,
                idempotencyKey, "test", "test-source", null, Map.of("suite", "billing")
        );
    }

    private UUID createApiKey(Cookie session, BigDecimal creditLimit) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", "Billing Limit Key");
        payload.put("service_group_id", serviceGroupId);
        payload.put("default_group_id", serviceGroupId);
        payload.put("allowed_model_ids", java.util.List.of());
        payload.put("allowed_group_ids", java.util.List.of(serviceGroupId));
        payload.put("ip_allowlist", java.util.List.of());
        payload.put("rpm_limit", 60);
        payload.put("tpm_limit", 100_000);
        payload.put("concurrency_limit", 5);
        payload.put("credit_limit", creditLimit);
        payload.put("expires_at", Instant.now().plusSeconds(3600).toString());
        MvcResult result = mockMvc.perform(post("/api/v1/api-keys")
                        .cookie(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/id")
                .asText());
    }

    private void assertWallet(UUID userId, BigDecimal permanent, BigDecimal expiring, BigDecimal frozen) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT permanent_credits, expiring_credits, frozen_credits
                  FROM wallet_accounts
                 WHERE user_id = ?
                """, userId);
        assertThat((BigDecimal) row.get("permanent_credits")).isEqualByComparingTo(permanent);
        assertThat((BigDecimal) row.get("expiring_credits")).isEqualByComparingTo(expiring);
        assertThat((BigDecimal) row.get("frozen_credits")).isEqualByComparingTo(frozen);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }

    private RegisteredUser register(UserCase user) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id")
                .asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", user.name(),
                                "email", user.email(),
                                "password", user.password(),
                                "challenge_id", challengeId,
                                "captcha_code", cases.captchaCode()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(registration.getResponse().getContentAsString());
        return new RegisteredUser(
                UUID.fromString(body.at("/data/user/id").asText()),
                registration.getResponse().getCookie("NEXUS_SESSION")
        );
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    record Attempt(boolean success, ErrorCode errorCode) {
    }

    record RegisteredUser(UUID id, Cookie session) {
    }

    record UserCase(String name, String email, String password) {
    }

    record BillingCases(
            String captchaCode,
            UserCase primaryUser,
            UserCase secondaryUser,
            String permanentCredit,
            String expiringCredit,
            String reservationAmount,
            String settlementAmount,
            String partialRefund,
            String concurrentReservation
    ) {
    }

    @TestConfiguration
    static class FixedCaptchaConfiguration {
        @Bean
        @Primary
        CaptchaCodeGenerator fixedCaptchaCodeGenerator() {
            return length -> "ACEF";
        }
    }
}
