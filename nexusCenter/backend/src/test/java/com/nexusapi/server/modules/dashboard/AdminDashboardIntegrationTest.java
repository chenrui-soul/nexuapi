package com.nexusapi.server.modules.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.dashboard.service.DashboardAggregationService;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wave 8 经营聚合的金额精度、幂等、权限、时间边界和敏感信息集成测试。 */
@SpringBootTest(properties = {
        "nexus.dashboard.enabled=false",
        "nexus.health.enabled=false"
})
@AutoConfigureMockMvc
@Import(AdminDashboardIntegrationTest.FixedCaptchaConfiguration.class)
class AdminDashboardIntegrationTest {
    private static final String CAPTCHA_CODE = "ACEF";
    private static final String PASSWORD = "StrongPassword!2026";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
    @Autowired
    private ChannelCredentialCipher credentialCipher;
    @Autowired
    private DashboardAggregationService aggregationService;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE usage_aggregates, upstream_attempt_logs, request_logs, audit_logs,
                    routing_groups, channels, suppliers, ai_models, users CASCADE
                """);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void adminCanReadAccurateIdempotentFinancialAndStabilityMetrics() throws Exception {
        RegisteredUser admin = registerAdmin("dashboard-admin@example.com");
        AnalyticsFixture fixture = createFixture();
        Instant from = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant to = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        insertFacts(fixture, from.plus(10, ChronoUnit.MINUTES));

        assertThat(aggregationService.refreshWindow(from, to)).isTrue();
        assertThat(aggregationService.refreshWindow(from, to)).isTrue();

        MvcResult result = mockMvc.perform(get("/api/v1/admin/dashboard/overview")
                        .cookie(admin.session())
                        .param("preset", "7d")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preset").value("custom"))
                .andExpect(jsonPath("$.data.summary.request_count").value(2))
                .andExpect(jsonPath("$.data.summary.success_count").value(1))
                .andExpect(jsonPath("$.data.summary.failure_count").value(1))
                .andExpect(jsonPath("$.data.summary.success_rate").value(50.0))
                .andExpect(jsonPath("$.data.summary.input_tokens").value(100))
                .andExpect(jsonPath("$.data.summary.output_tokens").value(50))
                .andExpect(jsonPath("$.data.summary.cached_tokens").value(20))
                .andExpect(jsonPath("$.data.summary.attempt_count").value(2))
                .andExpect(jsonPath("$.data.summary.attempt_success_count").value(1))
                .andExpect(jsonPath("$.data.summary.attempt_supplier_failure_count").value(1))
                .andExpect(jsonPath("$.data.summary.latency_p95_ms").value(290))
                .andExpect(jsonPath("$.data.summary.attempt_latency_p95_ms").value(290))
                .andExpect(jsonPath("$.data.settlement_currency").value("USD"))
                .andExpect(jsonPath("$.data.rankings.suppliers[0].dimension_name").value("Wave 8 Supplier"))
                .andExpect(jsonPath("$.data.rankings.models[0].dimension_name").value("Wave 8 Model"))
                .andExpect(jsonPath("$.data.error_distribution[0].category").value("upstream_5xx"))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.at("/summary/billed_amount").isTextual()).isTrue();
        assertThat(data.at("/summary/input_tokens").asLong()).isEqualTo(100L);
        assertThat(data.at("/summary/output_tokens").asLong()).isEqualTo(50L);
        assertThat(data.at("/summary/cached_tokens").asLong()).isEqualTo(20L);
        assertThat(data.at("/summary/billed_amount").textValue()).isEqualTo("12.000000000000");
        assertThat(data.at("/summary/supplier_cost_amount").textValue()).isEqualTo("7.000000000000");
        assertThat(data.at("/summary/gross_margin_amount").textValue()).isEqualTo("5.000000000000");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sum(request_count) FROM usage_aggregates
                 WHERE bucket_size = 'hour' AND dimension_type = 'platform' AND dimension_id = 'all'
                """, Long.class)).isEqualTo(2L);

        String lowerBody = result.getResponse().getContentAsString().toLowerCase();
        assertThat(lowerBody).doesNotContain("dashboard-upstream-credential");
        assertThat(lowerBody).doesNotContain("authorization");
        assertThat(lowerBody).doesNotContain("api_key");
        assertThat(lowerBody).doesNotContain("email_ciphertext");
        assertThat(lowerBody).doesNotContain("error_summary");
    }

    @Test
    void ordinaryUserCannotReadDashboard() throws Exception {
        RegisteredUser ordinary = register("dashboard-user@example.com", "Dashboard User");

        mockMvc.perform(get("/api/v1/admin/dashboard/overview").cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get("/api/v1/admin/dashboard/resources").cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));
    }

    @Test
    void resourceSummaryCountsAllActiveModelsBeyondTheListPageLimit() throws Exception {
        RegisteredUser admin = registerAdmin("dashboard-resources-admin@example.com");
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    public_name, display_name, provider, capability_type, status
                )
                SELECT 'dashboard-model-' || sequence_no,
                       'Dashboard Model ' || sequence_no,
                       'openai', 'text', 'active'
                  FROM generate_series(1, 105) AS sequence_no
                """);

        mockMvc.perform(get("/api/v1/admin/dashboard/resources").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.models.total").value(105))
                .andExpect(jsonPath("$.data.models.active").value(105))
                .andExpect(jsonPath("$.data.suppliers.total").value(0))
                .andExpect(jsonPath("$.data.channels.total").value(0))
                .andExpect(jsonPath("$.data.open_alert_count").value(0));
    }

    @Test
    void invalidPresetAndUnsafeTimeRangesAreRejected() throws Exception {
        RegisteredUser admin = registerAdmin("dashboard-range-admin@example.com");
        Instant now = Instant.now();

        mockMvc.perform(get("/api/v1/admin/dashboard/overview")
                        .cookie(admin.session()).param("preset", "all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/admin/dashboard/overview")
                        .cookie(admin.session()).param("from", now.minus(1, ChronoUnit.DAYS).toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/dashboard/overview")
                        .cookie(admin.session())
                        .param("from", now.minus(91, ChronoUnit.DAYS).toString())
                        .param("to", now.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/dashboard/overview")
                        .cookie(admin.session())
                        .param("from", now.minus(1, ChronoUnit.HOURS).toString())
                        .param("to", now.plus(1, ChronoUnit.DAYS).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mixedSettlementCurrenciesAreRejectedInsteadOfProducingInvalidMargin() throws Exception {
        RegisteredUser admin = registerAdmin("dashboard-currency-admin@example.com");
        AnalyticsFixture fixture = createFixture();
        Instant from = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant to = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        insertFacts(fixture, from.plus(10, ChronoUnit.MINUTES));
        jdbcTemplate.update("""
                UPDATE request_logs
                   SET supplier_cost_currency = 'CNY', supplier_cost_amount = 1.00000000,
                       gross_margin_amount = -1.00000000
                 WHERE request_id = 'wave8-failure'
                """);
        aggregationService.refreshWindow(from, to);

        mockMvc.perform(get("/api/v1/admin/dashboard/overview")
                        .cookie(admin.session())
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value(
                        "统计区间包含多个供应商结算币种，当前版本不支持跨币种金额汇总"
                ));
    }

    private AnalyticsFixture createFixture() {
        UUID supplierId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID channelModelId = UUID.randomUUID();
        ChannelCredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(
                "dashboard-upstream-credential"
        );
        jdbcTemplate.update("""
                INSERT INTO suppliers (
                    id, code, name, supplier_type, status, health_status,
                    billing_mode, settlement_currency, metadata
                ) VALUES (?, 'wave-8-supplier', 'Wave 8 Supplier', 'direct', 'active', 'healthy',
                          'postpaid', 'USD', '{}'::jsonb)
                """, supplierId);
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    supports_streaming, supports_tools, price_unit, public_visible, status
                ) VALUES (?, 'wave-8-model', 'Wave 8 Model', 'openai', 'text',
                          true, true, 'million_tokens', true, 'active')
                """, modelId);
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, 'wave-8-group', 'Wave 8 Group', 1.2, 'all', 'active')
                """, groupId);
        jdbcTemplate.update("""
                INSERT INTO channels (
                    id, supplier_id, name, provider_type, operation_code, base_url, health_probe_path,
                    encrypted_credential, credential_key_version, credential_fingerprint,
                    credential_updated_at, status, timeout_ms, priority, weight
                ) VALUES (?, ?, 'Wave 8 Channel', 'openai', 'chat_completions',
                          'https://upstream.invalid/v1', '/models',
                          ?, ?, ?, now(), 'active', 5000, 10, 100)
                """, channelId, supplierId, encrypted.ciphertext(), encrypted.keyVersion(), encrypted.fingerprint());
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, modelId, "wave-8-upstream",
                "1", "0.5", "2");
        jdbcTemplate.update("""
                INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                VALUES (?, ?, 'manual', 'active')
                """, groupId, modelId);
        return new AnalyticsFixture(supplierId, modelId, groupId, channelId, channelModelId);
    }

    private void insertFacts(AnalyticsFixture fixture, Instant createdAt) {
        insertRequest(fixture, "wave8-success", createdAt, 200, null, null,
                100, 50, 20, "12.00000000", "7.00000000", "5.00000000", 100);
        insertRequest(fixture, "wave8-failure", createdAt.plusSeconds(5), 502, "UPSTREAM_ERROR", "upstream_5xx",
                0, 0, 0, "0.00000000", "0.00000000", "0.00000000", 300);
        insertAttempt(fixture, "wave8-success", createdAt, "success", null, 100, 200);
        insertAttempt(fixture, "wave8-failure", createdAt.plusSeconds(5),
                "supplier_failure", "upstream_5xx", 300, 500);
    }

    private void insertRequest(
            AnalyticsFixture fixture,
            String requestId,
            Instant createdAt,
            int statusCode,
            String platformErrorCode,
            String supplierErrorCategory,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            String billedAmount,
            String costAmount,
            String marginAmount,
            long durationMs
    ) {
        jdbcTemplate.update("""
                INSERT INTO request_logs (
                    id, request_id, model_id, supplier_id, channel_id, channel_model_id, group_id,
                    public_model, upstream_model, started_at, completed_at, duration_ms,
                    status_code, platform_error_code, input_tokens, output_tokens, cached_tokens,
                    billed_amount, price_multiplier, supplier_cost_amount, supplier_cost_currency,
                    gross_margin_amount, supplier_error_category, streaming, retry_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'wave-8-model', 'wave-8-upstream', ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, 1.2, ?, 'USD', ?, ?, false, 0, ?)
                """, UUID.randomUUID(), requestId, fixture.modelId(), fixture.supplierId(), fixture.channelId(),
                fixture.channelModelId(), fixture.groupId(), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusMillis(durationMs)), durationMs,
                statusCode, platformErrorCode, inputTokens, outputTokens, cachedTokens,
                new BigDecimal(billedAmount), new BigDecimal(costAmount), new BigDecimal(marginAmount),
                supplierErrorCategory, Timestamp.from(createdAt));
    }

    private void insertAttempt(
            AnalyticsFixture fixture,
            String requestId,
            Instant createdAt,
            String outcome,
            String errorCategory,
            long durationMs,
            int upstreamStatus
    ) {
        jdbcTemplate.update("""
                INSERT INTO upstream_attempt_logs (
                    id, request_id, supplier_id, channel_id, channel_model_id, model_id,
                    attempt_no, started_at, completed_at, duration_ms, outcome,
                    upstream_status, error_category, error_summary, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, 'sanitized', ?)
                """, UUID.randomUUID(), requestId, fixture.supplierId(), fixture.channelId(),
                fixture.channelModelId(), fixture.modelId(), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusMillis(durationMs)), durationMs, outcome, upstreamStatus,
                errorCategory, Timestamp.from(createdAt));
    }

    private RegisteredUser registerAdmin(String email) throws Exception {
        RegisteredUser user = register(email, "Wave 8 Admin");
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'admin')", user.id());
        return user;
    }

    private RegisteredUser register(String email, String name) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk()).andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id").asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "email", email,
                                "password", PASSWORD,
                                "challenge_id", challengeId,
                                "captcha_code", CAPTCHA_CODE
                        ))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(registration.getResponse().getContentAsString());
        return new RegisteredUser(
                UUID.fromString(body.at("/data/user/id").asText()),
                registration.getResponse().getCookie("NEXUS_SESSION")
        );
    }

    private record AnalyticsFixture(
            UUID supplierId,
            UUID modelId,
            UUID groupId,
            UUID channelId,
            UUID channelModelId
    ) {
    }

    private record RegisteredUser(UUID id, Cookie session) {
    }

    @TestConfiguration
    static class FixedCaptchaConfiguration {
        @Bean
        @Primary
        CaptchaCodeGenerator fixedCaptchaCodeGenerator() {
            return length -> CAPTCHA_CODE;
        }
    }
}
