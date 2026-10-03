package com.nexusapi.server.modules.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.billing.enums.CreditBucket;
import com.nexusapi.server.modules.billing.enums.CreditType;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.modules.routing.service.ServiceGroupCatalogService;
import com.nexusapi.server.modules.subscription.service.SubscriptionAccessService;
import com.nexusapi.server.modules.subscription.service.SubscriptionService;
import com.nexusapi.server.modules.subscription.dto.SubscriptionCancelRequest;
import com.nexusapi.server.modules.subscription.vo.SubscriptionCancellationResponse;
import com.nexusapi.server.testing.TestServiceGroupFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** 验证套餐分组权限、订阅积分优先扣费和通用余额兜底的完整资金来源闭环。 */
@SpringBootTest(properties = "nexus.payment.mock-enabled=true")
@AutoConfigureMockMvc
@Import(SubscriptionBillingIntegrationTest.FixedCaptchaConfiguration.class)
class SubscriptionBillingIntegrationTest {
    private static final AtomicInteger REGISTRATION_SEQUENCE = new AtomicInteger();
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired BillingService billingService;
    @Autowired SubscriptionService subscriptionService;
    @Autowired com.nexusapi.server.modules.subscription.mapper.SubscriptionMapper subscriptionMapper;
    @Autowired SubscriptionAccessService subscriptionAccessService;
    @Autowired ServiceGroupCatalogService serviceGroupCatalogService;

    private final List<UUID> createdPlanIds = new ArrayList<>();

    @BeforeEach
    void resetUsers() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    @AfterEach
    void removeTestPlans() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        for (UUID planId : createdPlanIds) {
            jdbcTemplate.update("DELETE FROM plans WHERE id = ?", planId);
        }
        createdPlanIds.clear();
    }

    @Test
    void subscriptionCreditsAreUsedBeforePermanentBalanceAndRefundReturnsToOriginalBatch() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("allowed");
        activate(user.id(), groupId, new BigDecimal("3"));
        creditPermanent(user.id(), new BigDecimal("10"), "permanent-fallback");

        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, groupId, "req-subscription-split",
                new BigDecimal("5"), null, "reserve-subscription-split", Map.of()
        );
        assertWallet(user.id(), "8", "0", "5");
        assertThat(decimal("""
                SELECT COALESCE(sum(reserved_amount), 0)
                  FROM wallet_reservation_batch_allocations
                 WHERE reservation_id = ?
                """, reservation.reservationId())).isEqualByComparingTo("3");

        billingService.settle(
                user.id(), reservation.reservationId(), new BigDecimal("4"), null,
                "settle-subscription-split", Map.of()
        );
        assertWallet(user.id(), "9", "0", "0");
        assertThat(decimal("""
                SELECT consumed_amount FROM expiring_credit_batches WHERE subscription_id IS NOT NULL
                """)).isEqualByComparingTo("3");

        billingService.refund(
                user.id(), reservation.reservationId(), new BigDecimal("2"),
                "refund-subscription-split", "test", Map.of()
        );
        assertWallet(user.id(), "9", "2", "0");
        Map<String, Object> batch = jdbcTemplate.queryForMap("""
                SELECT available_amount, consumed_amount
                  FROM expiring_credit_batches
                 WHERE subscription_id IS NOT NULL
                """);
        assertThat((BigDecimal) batch.get("available_amount")).isEqualByComparingTo("2");
        assertThat((BigDecimal) batch.get("consumed_amount")).isEqualByComparingTo("1");
    }

    @Test
    void exhaustedSubscriptionCreditsFallBackCompletelyToPermanentBalance() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("zero");
        activate(user.id(), groupId, BigDecimal.ZERO);
        creditPermanent(user.id(), new BigDecimal("6"), "zero-package-fallback");

        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, groupId, "req-zero-package", new BigDecimal("2"),
                null, "reserve-zero-package", Map.of()
        );
        billingService.settle(
                user.id(), reservation.reservationId(), new BigDecimal("2"), null,
                "settle-zero-package", Map.of()
        );

        assertWallet(user.id(), "4", "0", "0");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM wallet_reservation_batch_allocations WHERE reservation_id = ?
                """, Integer.class, reservation.reservationId())).isZero();
    }

    @Test
    void settlementAboveReservationReusesTheOriginalSubscriptionBatchAllocation() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("extra");
        activate(user.id(), groupId, new BigDecimal("10"));

        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, groupId, "req-additional-subscription",
                new BigDecimal("2"), null, "reserve-additional-subscription", Map.of()
        );
        billingService.settle(
                user.id(), reservation.reservationId(), new BigDecimal("5"), null,
                "settle-additional-subscription", Map.of()
        );

        assertWallet(user.id(), "0", "5", "0");
        Map<String, Object> allocation = jdbcTemplate.queryForMap("""
                SELECT reserved_amount, additional_amount, settled_amount
                  FROM wallet_reservation_batch_allocations
                 WHERE reservation_id = ?
                """, reservation.reservationId());
        assertThat((BigDecimal) allocation.get("reserved_amount")).isEqualByComparingTo("2");
        assertThat((BigDecimal) allocation.get("additional_amount")).isEqualByComparingTo("3");
        assertThat((BigDecimal) allocation.get("settled_amount")).isEqualByComparingTo("5");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM wallet_reservation_batch_allocations WHERE reservation_id = ?
                """, Integer.class, reservation.reservationId())).isOne();
    }

    @Test
    void chargeOrderIsSubscriptionThenGeneralExpiringThenPermanent() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("three-buckets");
        activate(user.id(), groupId, new BigDecimal("2"));
        creditExpiring(user.id(), new BigDecimal("3"), "general-expiring");
        creditPermanent(user.id(), new BigDecimal("5"), "permanent-after-expiring");

        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, groupId, "req-three-buckets", new BigDecimal("7"),
                null, "reserve-three-buckets", Map.of()
        );
        billingService.settle(
                user.id(), reservation.reservationId(), new BigDecimal("7"), null,
                "settle-three-buckets", Map.of()
        );

        assertWallet(user.id(), "3", "0", "0");
        Map<String, Object> allocations = jdbcTemplate.queryForMap("""
                SELECT COALESCE(sum(a.settled_amount) FILTER (WHERE b.subscription_id IS NOT NULL), 0)
                           AS subscription_amount,
                       COALESCE(sum(a.settled_amount) FILTER (WHERE b.subscription_id IS NULL), 0)
                           AS general_amount
                  FROM wallet_reservation_batch_allocations a
                  JOIN expiring_credit_batches b ON b.id = a.batch_id
                 WHERE a.reservation_id = ?
                """, reservation.reservationId());
        assertThat((BigDecimal) allocations.get("subscription_amount")).isEqualByComparingTo("2");
        assertThat((BigDecimal) allocations.get("general_amount")).isEqualByComparingTo("3");
    }

    @Test
    void switchingPlanExpiresUnusedOldCreditsButKeepsFrozenAllocationForRelease() throws Exception {
        RegisteredUser user = register();
        UUID oldGroupId = createGroup("old-plan");
        ActivatedPlan oldPlan = activate(user.id(), oldGroupId, new BigDecimal("3"));
        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, oldGroupId, "req-old-plan-inflight", new BigDecimal("2"),
                null, "reserve-old-plan-inflight", Map.of()
        );

        UUID newGroupId = createGroup("new-plan");
        ActivatedPlan newPlan = activate(user.id(), newGroupId, new BigDecimal("4"));
        assertWallet(user.id(), "0", "4", "2");
        assertThatThrownBy(() -> subscriptionAccessService.assertGroupAllowed(user.id(), oldGroupId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SUBSCRIPTION_GROUP_NOT_ALLOWED));
        subscriptionAccessService.assertGroupAllowed(user.id(), newGroupId);
        assertThatThrownBy(() -> subscriptionAccessService.assertModelAllowed(user.id(), oldPlan.modelId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SUBSCRIPTION_MODEL_NOT_ALLOWED));
        subscriptionAccessService.assertModelAllowed(user.id(), newPlan.modelId());

        billingService.release(
                user.id(), reservation.reservationId(), "release-old-plan-inflight", Map.of()
        );

        assertWallet(user.id(), "0", "4", "0");
        List<Map<String, Object>> batches = jdbcTemplate.queryForList("""
                SELECT b.available_amount, b.frozen_amount, b.expired_amount, b.status,
                       s.status AS subscription_status
                  FROM expiring_credit_batches b
                  JOIN subscriptions s ON s.id = b.subscription_id
                 ORDER BY b.created_at
                """);
        assertThat(batches).hasSize(2);
        assertThat((BigDecimal) batches.get(0).get("available_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) batches.get(0).get("frozen_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) batches.get(0).get("expired_amount")).isEqualByComparingTo("3");
        assertThat(batches.get(0).get("status")).isEqualTo("expired");
        assertThat(batches.get(0).get("subscription_status")).isEqualTo("cancelled");
        assertThat((BigDecimal) batches.get(1).get("available_amount")).isEqualByComparingTo("4");
        assertThat(batches.get(1).get("subscription_status")).isEqualTo("active");
    }

    @Test
    void activeSubscriptionRejectsGroupsOutsideItsSnapshotEvenWhenWalletHasBalance() throws Exception {
        RegisteredUser user = register();
        UUID allowedGroupId = createGroup("allowed-scope");
        UUID deniedGroupId = createGroup("denied-scope");
        activate(user.id(), allowedGroupId, BigDecimal.ONE);
        creditPermanent(user.id(), new BigDecimal("100"), "denied-group-balance");

        assertThatThrownBy(() -> subscriptionAccessService.assertGroupAllowed(user.id(), deniedGroupId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SUBSCRIPTION_GROUP_NOT_ALLOWED));
        assertThat(serviceGroupCatalogService.listSelectableGroups(user.id()))
                .extracting(group -> group.id())
                .containsExactly(allowedGroupId);
        assertWallet(user.id(), "100", "1", "0");
    }

    @Test
    void activeSubscriptionUsesIndependentGroupAndModelSnapshots() throws Exception {
        RegisteredUser user = register();
        UUID allowedGroupId = createGroup("model-snapshot");
        ActivatedPlan activation = activate(user.id(), allowedGroupId, BigDecimal.ONE);
        UUID deniedModelId = createModel("denied-model");

        subscriptionAccessService.assertGroupAllowed(user.id(), allowedGroupId);
        subscriptionAccessService.assertModelAllowed(user.id(), activation.modelId());
        assertThatThrownBy(() -> subscriptionAccessService.assertModelAllowed(user.id(), deniedModelId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SUBSCRIPTION_MODEL_NOT_ALLOWED));

        jdbcTemplate.update("DELETE FROM plan_models WHERE plan_id = ?", activation.planId());
        jdbcTemplate.update("INSERT INTO plan_models (plan_id, model_id) VALUES (?, ?)",
                activation.planId(), deniedModelId);

        subscriptionAccessService.assertModelAllowed(user.id(), activation.modelId());
        assertThatThrownBy(() -> subscriptionAccessService.assertModelAllowed(user.id(), deniedModelId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SUBSCRIPTION_MODEL_NOT_ALLOWED));
    }

    @Test
    void subscriptionModelScopeFiltersCatalogGatewayAndApiKeyWrites() throws Exception {
        RegisteredUser user = register();
        UUID groupId = TestServiceGroupFixture.createPublicGroup(jdbcTemplate);
        UUID allowedModelId = jdbcTemplate.queryForObject(
                "SELECT model_id FROM routing_group_models WHERE group_id = ?", UUID.class, groupId
        );
        UUID deniedModelId = createRoutedSiblingModel(groupId, "subscription-denied");
        activate(user.id(), groupId, allowedModelId, BigDecimal.ONE);

        Map<String, Object> invalidPayload = apiKeyPayload(groupId, List.of(deniedModelId), "Denied Model Key");
        mockMvc.perform(post("/api/v1/api-keys")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidPayload)))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/api-keys")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                apiKeyPayload(groupId, List.of(allowedModelId), "Allowed Model Key")
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID keyId = UUID.fromString(createdBody.at("/data/id").asText());

        Map<String, Object> deniedUpdate = apiKeyPayload(groupId, List.of(deniedModelId), "Denied Update");
        deniedUpdate.put("version", 0);
        mockMvc.perform(patch("/api/v1/api-keys/{id}", keyId)
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deniedUpdate)))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM api_keys WHERE id = ?", Long.class, keyId
        )).isZero();

        MvcResult runtimeKey = mockMvc.perform(post("/api/v1/api-keys")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                apiKeyPayload(groupId, List.of(), "Package Runtime Key")
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        String secret = objectMapper.readTree(runtimeKey.getResponse().getContentAsString())
                .at("/data/secret").asText();
        int attemptsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM upstream_attempt_logs", Integer.class
        );

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", jdbcTemplate.queryForObject(
                                        "SELECT public_name FROM ai_models WHERE id = ?",
                                        String.class,
                                        deniedModelId
                                ),
                                "messages", List.of(Map.of("role", "user", "content", "denied"))
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                        .at("/error/code").asText()).isEqualTo("subscription_model_not_allowed"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_reservations WHERE user_id = ?", Integer.class, user.id()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM upstream_attempt_logs", Integer.class
        )).isEqualTo(attemptsBefore);

        String gatewayModels = mockMvc.perform(get("/v1/models")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode gatewayData = objectMapper.readTree(gatewayModels).path("data");
        assertThat(gatewayData).hasSize(1);
        assertThat(gatewayData.get(0).path("id").asText())
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT public_name FROM ai_models WHERE id = ?", String.class, allowedModelId
                ));

        mockMvc.perform(get("/api/v1/models").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                        .path("data")).hasSize(1));
        mockMvc.perform(get("/api/v1/model-market")
                        .cookie(user.session()).param("page_size", "100"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                        .at("/data/total").asLong()).isEqualTo(1));
        mockMvc.perform(get("/api/v1/model-market/{modelId}", deniedModelId)
                        .cookie(user.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void usersWithoutActiveSubscriptionKeepOriginalModelAccessRules() throws Exception {
        RegisteredUser user = register();
        subscriptionAccessService.assertGroupAllowed(user.id(), UUID.randomUUID());
        subscriptionAccessService.assertModelAllowed(user.id(), UUID.randomUUID());
        assertThat(subscriptionAccessService.snapshot(user.id()).restricted()).isFalse();
    }

    @Test
    void subscriptionConcurrencyLimitIsSnapshottedWhenActivated() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("conc");
        ActivatedPlan activation = activate(
                user.id(), groupId, createModel("concurrency-model"), new BigDecimal("10"), 2
        );

        SubscriptionAccessService.AccessSnapshot beforeChange = subscriptionAccessService.snapshot(user.id());
        assertThat(beforeChange.subscriptionConcurrencyLimit()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT concurrency_limit FROM subscriptions WHERE id = ?",
                Integer.class,
                beforeChange.subscriptionId()
        )).isEqualTo(2);

        jdbcTemplate.update("UPDATE plans SET concurrency_limit = 9 WHERE id = ?", activation.planId());

        SubscriptionAccessService.AccessSnapshot afterChange = subscriptionAccessService.snapshot(user.id());
        assertThat(afterChange.subscriptionConcurrencyLimit()).isEqualTo(2);
    }

    @Test
    void frozenSubscriptionCreditsReleasedAfterExpiryBecomeExpiredInsteadOfSpendable() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("expiry");
        activate(user.id(), groupId, new BigDecimal("3"));
        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, groupId, "req-expired-release", new BigDecimal("2"),
                null, "reserve-expired-release", Map.of()
        );
        jdbcTemplate.update("""
                UPDATE expiring_credit_batches
                   SET created_at = now() - interval '2 days',
                       expires_at = now() - interval '1 day'
                 WHERE subscription_id IS NOT NULL
                """);

        billingService.release(
                user.id(), reservation.reservationId(), "release-expired-subscription", Map.of()
        );

        assertWallet(user.id(), "0", "0", "0");
        Map<String, Object> batch = jdbcTemplate.queryForMap("""
                SELECT available_amount, frozen_amount, expired_amount, status
                  FROM expiring_credit_batches
                 WHERE subscription_id IS NOT NULL
                """);
        assertThat((BigDecimal) batch.get("available_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) batch.get("frozen_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) batch.get("expired_amount")).isEqualByComparingTo("3");
        assertThat(batch.get("status")).isEqualTo("expired");
    }

    @Test
    void cancellingSubscriptionWithoutInflightRequestsRefundsUnusedCreditsAndStopsAccess() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("cancel-now");
        activate(user.id(), groupId, new BigDecimal("10"));
        creditPermanent(user.id(), new BigDecimal("7"), "cancel-keeps-permanent");
        UUID subscriptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM subscriptions WHERE user_id = ? AND status = 'active'", UUID.class, user.id()
        );
        jdbcTemplate.update("UPDATE orders SET amount = 100 WHERE id = (SELECT order_id FROM subscriptions WHERE id = ?)", subscriptionId);
        long version = jdbcTemplate.queryForObject("SELECT version FROM subscriptions WHERE id = ?", Long.class, subscriptionId);

        SubscriptionCancellationResponse result = subscriptionService.cancel(
                user.id(), new SubscriptionCancelRequest(version)
        );

        assertThat(result.status()).isEqualTo("refunded");
        assertThat(result.refundAmount()).isEqualByComparingTo("100.00");
        assertThat(result.refundableCredits()).isEqualByComparingTo("10");
        assertThat(subscriptionMapper.findActiveSubscriptionId(user.id())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = (SELECT order_id FROM subscriptions WHERE id = ?)", String.class, subscriptionId
        )).isEqualTo("refunded");
        assertWallet(user.id(), "7", "0", "0");
    }

    @Test
    void cancellingSubscriptionWithInflightRequestWaitsThenRefundsReleasedCredits() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("cancel-wait");
        activate(user.id(), groupId, new BigDecimal("10"));
        UUID subscriptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM subscriptions WHERE user_id = ? AND status = 'active'", UUID.class, user.id()
        );
        jdbcTemplate.update("UPDATE orders SET amount = 100 WHERE id = (SELECT order_id FROM subscriptions WHERE id = ?)", subscriptionId);
        long version = jdbcTemplate.queryForObject("SELECT version FROM subscriptions WHERE id = ?", Long.class, subscriptionId);
        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, groupId, "req-cancel-flight", new BigDecimal("4"), null,
                "reserve-cancel-flight", Map.of()
        );

        SubscriptionCancellationResponse pending = subscriptionService.cancel(
                user.id(), new SubscriptionCancelRequest(version)
        );
        assertThat(pending.status()).isEqualTo("refund_pending");
        assertThat(pending.refundPending()).isTrue();
        assertThat(pending.frozenCredits()).isEqualByComparingTo("4");

        billingService.release(user.id(), reservation.reservationId(), "release-cancel-flight", Map.of());
        assertThat(subscriptionService.finalizePendingRefunds(10)).isEqualTo(1);
        SubscriptionCancellationResponse completed = subscriptionService.cancel(
                user.id(), new SubscriptionCancelRequest(version + 1)
        );
        assertThat(completed.status()).isEqualTo("refunded");
        assertThat(completed.refundAmount()).isEqualByComparingTo("100.00");
        assertThat(completed.refundableCredits()).isEqualByComparingTo("10");
    }

    @Test
    void formalPaymentProviderNeverGetsMarkedRefundedByMockFinalizer() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("formal");
        activate(user.id(), groupId, new BigDecimal("10"));
        UUID subscriptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM subscriptions WHERE user_id = ? AND status = 'active'", UUID.class, user.id()
        );
        UUID orderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM subscriptions WHERE id = ?", UUID.class, subscriptionId
        );
        jdbcTemplate.update("UPDATE orders SET amount = 100, payment_provider = 'stripe' WHERE id = ?", orderId);
        long version = jdbcTemplate.queryForObject("SELECT version FROM subscriptions WHERE id = ?", Long.class, subscriptionId);

        SubscriptionCancellationResponse result = subscriptionService.cancel(
                user.id(), new SubscriptionCancelRequest(version)
        );

        assertThat(result.status()).isEqualTo("refund_pending");
        assertThat(result.refundPending()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("paid");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM subscription_refunds WHERE subscription_id = ?", String.class, subscriptionId
        )).isEqualTo("pending");
        assertThat(subscriptionService.finalizePendingRefunds(10)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, subscriptionId))
                .isEqualTo("refund_pending");
    }

    @Test
    void cancelledSubscriptionCanBeReopenedWithoutTreatingRefundedHistoryAsCurrent() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("reopen");
        activate(user.id(), groupId, new BigDecimal("10"));
        UUID firstSubscriptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM subscriptions WHERE user_id = ? AND status = 'active'", UUID.class, user.id()
        );
        long version = jdbcTemplate.queryForObject("SELECT version FROM subscriptions WHERE id = ?", Long.class, firstSubscriptionId);
        subscriptionService.cancel(user.id(), new SubscriptionCancelRequest(version));

        activate(user.id(), groupId, new BigDecimal("5"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE user_id = ? AND status = 'active'", Integer.class, user.id()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM subscriptions WHERE id = ?", String.class, firstSubscriptionId
        )).isEqualTo("refunded");
    }

    @Test
    void cancellationRefundsOnlyUnusedSubscriptionCreditsWithTwoDecimalRounding() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("partial-refund");
        activate(user.id(), groupId, new BigDecimal("3"));
        UUID subscriptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM subscriptions WHERE user_id = ? AND status = 'active'", UUID.class, user.id()
        );
        UUID orderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM subscriptions WHERE id = ?", UUID.class, subscriptionId
        );
        jdbcTemplate.update("UPDATE orders SET amount = 10.00 WHERE id = ?", orderId);
        BillingService.ReservationResult reservation = billingService.reserve(
                user.id(), null, groupId, "req-partial-cancel", new BigDecimal("1"), null,
                "reserve-partial-cancel", Map.of()
        );
        billingService.settle(user.id(), reservation.reservationId(), new BigDecimal("1"), null,
                "settle-partial-cancel", Map.of());
        long version = jdbcTemplate.queryForObject("SELECT version FROM subscriptions WHERE id = ?", Long.class, subscriptionId);

        SubscriptionCancellationResponse result = subscriptionService.cancel(
                user.id(), new SubscriptionCancelRequest(version)
        );

        assertThat(result.status()).isEqualTo("refunded");
        assertThat(result.refundableCredits()).isEqualByComparingTo("2");
        assertThat(result.refundAmount()).isEqualByComparingTo("6.67");
    }

    @Test
    void cancelEndpointRequiresCsrfAndRepeatedRequestDoesNotDuplicateRefund() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("cancel-api");
        activate(user.id(), groupId, new BigDecimal("4"));
        UUID subscriptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM subscriptions WHERE user_id = ? AND status = 'active'", UUID.class, user.id()
        );
        long version = jdbcTemplate.queryForObject("SELECT version FROM subscriptions WHERE id = ?", Long.class, subscriptionId);
        String payload = objectMapper.writeValueAsString(Map.of("version", version));

        mockMvc.perform(post("/api/v1/subscriptions/current/cancel")
                        .cookie(user.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM subscriptions WHERE id = ?", String.class, subscriptionId
        )).isEqualTo("active");

        mockMvc.perform(post("/api/v1/subscriptions/current/cancel")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/subscriptions/current/cancel")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_refunds WHERE subscription_id = ?", Integer.class, subscriptionId
        )).isOne();
    }

    @Test
    void cancellationRejectsStaleSubscriptionVersionWithoutChangingBalance() throws Exception {
        RegisteredUser user = register();
        UUID groupId = createGroup("stale-version");
        activate(user.id(), groupId, new BigDecimal("4"));
        UUID subscriptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM subscriptions WHERE user_id = ? AND status = 'active'", UUID.class, user.id()
        );
        long version = jdbcTemplate.queryForObject("SELECT version FROM subscriptions WHERE id = ?", Long.class, subscriptionId);

        assertThatThrownBy(() -> subscriptionService.cancel(
                user.id(), new SubscriptionCancelRequest(version + 1)
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SUBSCRIPTION_VERSION_CONFLICT));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM subscriptions WHERE id = ?", String.class, subscriptionId
        )).isEqualTo("active");
        assertWallet(user.id(), "0", "4", "0");
    }

    private ActivatedPlan activate(UUID userId, UUID groupId, BigDecimal credits) {
        return activate(userId, groupId, createModel("plan-model"), credits);
    }

    private ActivatedPlan activate(UUID userId, UUID groupId, UUID modelId, BigDecimal credits) {
        return activate(userId, groupId, modelId, credits, 5);
    }

    private ActivatedPlan activate(
            UUID userId,
            UUID groupId,
            UUID modelId,
            BigDecimal credits,
            int concurrencyLimit
    ) {
        UUID planId = UUID.randomUUID();
        createdPlanIds.add(planId);
        jdbcTemplate.update("""
                INSERT INTO plans (
                    id, code, name, description, billing_cycle, price, included_credits,
                    concurrency_limit, entitlements, status, display_order, featured
                ) VALUES (?, ?, 'Test Subscription Plan', 'Test', 'monthly', 1, ?, ?,
                          '{"features":["test"]}'::jsonb, 'active', 999, false)
                """, planId, "test-plan-" + planId, credits, concurrencyLimit);
        jdbcTemplate.update("""
                INSERT INTO plan_service_groups (plan_id, service_group_id) VALUES (?, ?)
                """, planId, groupId);
        jdbcTemplate.update("INSERT INTO plan_models (plan_id, model_id) VALUES (?, ?)", planId, modelId);
        subscriptionService.activateMock(userId, planId);
        return new ActivatedPlan(planId, modelId);
    }

    private UUID createModel(String prefix) {
        UUID modelId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    public_visible, status
                ) VALUES (?, ?, ?, 'subscription-test', 'text', true, 'active')
                """, modelId, prefix + "-" + modelId, prefix + " " + modelId);
        return modelId;
    }

    private UUID createRoutedSiblingModel(UUID groupId, String prefix) {
        UUID modelId = createModel(prefix);
        UUID channelId = jdbcTemplate.queryForObject("""
                SELECT c.id
                  FROM routing_group_suppliers rgs
                  JOIN channels c ON c.supplier_id = rgs.supplier_id
                 WHERE rgs.group_id = ?
                 LIMIT 1
                """, UUID.class, groupId);
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, modelId, prefix + "-upstream",
                "0", "0", "0");
        jdbcTemplate.update("""
                INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                VALUES (?, ?, 'manual', 'active')
                """, groupId, modelId);
        return modelId;
    }

    private Map<String, Object> apiKeyPayload(UUID groupId, List<UUID> modelIds, String name) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", name);
        payload.put("service_group_id", groupId);
        payload.put("default_group_id", groupId);
        payload.put("allowed_model_ids", modelIds);
        payload.put("allowed_group_ids", List.of(groupId));
        payload.put("ip_allowlist", List.of());
        payload.put("rpm_limit", 60);
        payload.put("tpm_limit", 200_000);
        payload.put("concurrency_limit", 5);
        payload.put("credit_limit", 100);
        payload.put("expires_at", Instant.now().plusSeconds(3600).toString());
        return payload;
    }

    private UUID createGroup(String suffix) {
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, ?, 1, 'all', 'active')
                """, groupId, "subscription-" + suffix + "-" + groupId, "Subscription " + suffix);
        return groupId;
    }

    private void creditPermanent(UUID userId, BigDecimal amount, String key) {
        billingService.credit(
                userId, amount, CreditBucket.PERMANENT, CreditType.RECHARGE,
                key, "test", key, null, Map.of()
        );
    }

    private void creditExpiring(UUID userId, BigDecimal amount, String key) {
        billingService.credit(
                userId, amount, CreditBucket.EXPIRING, CreditType.GRANT,
                key, "test", key, Instant.now().plusSeconds(86_400), Map.of()
        );
    }

    private RegisteredUser register() throws Exception {
        String email = "subscription.billing." + UUID.randomUUID() + "@example.com";
        // 每次注册使用独立测试地址，避免与其他集成测试共享 Redis 时触发注册频控。
        int sequence = REGISTRATION_SEQUENCE.incrementAndGet();
        String remoteAddress = "198.18." + (1 + (sequence / 250) % 250)
                + "." + (1 + sequence % 250);
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha")
                        .param("scene", "register")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        }))
                .andExpect(status().isOk()).andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id").asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Subscription Billing User",
                                "email", email,
                                "password", "NexusSecure2026",
                                "challenge_id", challengeId,
                                "captcha_code", "ACEF"
                        ))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(registration.getResponse().getContentAsString());
        return new RegisteredUser(
                UUID.fromString(body.at("/data/user/id").asText()),
                registration.getResponse().getCookie("NEXUS_SESSION")
        );
    }

    private void assertWallet(UUID userId, String permanent, String expiring, String frozen) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT permanent_credits, expiring_credits, frozen_credits
                  FROM wallet_accounts
                 WHERE user_id = ?
                """, userId);
        assertThat((BigDecimal) row.get("permanent_credits")).isEqualByComparingTo(permanent);
        assertThat((BigDecimal) row.get("expiring_credits")).isEqualByComparingTo(expiring);
        assertThat((BigDecimal) row.get("frozen_credits")).isEqualByComparingTo(frozen);
    }

    private BigDecimal decimal(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
    }

    record RegisteredUser(UUID id, Cookie session) { }

    record ActivatedPlan(UUID planId, UUID modelId) { }

    @TestConfiguration
    static class FixedCaptchaConfiguration {
        @Bean
        @Primary
        CaptchaCodeGenerator fixedCaptchaCodeGenerator() {
            return length -> "ACEF";
        }
    }
}
