package com.nexusapi.server.modules.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
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

/** 验证用户仪表盘隔离、分组权限、最近 60 次真实请求口径和响应字段白名单。 */
@SpringBootTest(properties = {
        "nexus.dashboard.enabled=false",
        "nexus.health.enabled=false"
})
@AutoConfigureMockMvc
@Import(UserAnalyticsIntegrationTest.FixedCaptchaConfiguration.class)
class UserAnalyticsIntegrationTest {
    private static final String CAPTCHA_CODE = "ACEF";
    private static final String PASSWORD = "StrongPassword!2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE usage_aggregates, upstream_attempt_logs, request_logs,
                    routing_groups, ai_models, users CASCADE
                """);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void dashboardOnlyAggregatesCurrentUserAndReturnsSafeRealData() throws Exception {
        RegisteredUser alice = register("Analytics Alice", "analytics-alice@example.com");
        RegisteredUser bob = register("Analytics Bob", "analytics-bob@example.com");
        ModelGroup fixture = createGroup("analytics-public", "用户真实分组", "all", true);
        UUID aliceKey = createApiKey(alice.id(), fixture.groupId(), "Production Web");
        UUID bobKey = createApiKey(bob.id(), fixture.groupId(), "Bob Private Key");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        insertRequest(alice.id(), aliceKey, fixture, "req-alice-success", 200, null,
                new BigDecimal("1.25000000"), 100, 20, 10, 100, now.minusSeconds(600));
        insertRequest(alice.id(), aliceKey, fixture, "req-alice-failed", 504, "UPSTREAM_TIMEOUT",
                BigDecimal.ZERO, 0, 0, 0, 500, now.minusSeconds(300));
        insertRequest(bob.id(), bobKey, fixture, "req-bob-private", 200, null,
                new BigDecimal("9.00000000"), 900, 90, 0, 50, now.minusSeconds(120));

        MvcResult result = mockMvc.perform(get("/api/v1/dashboard/overview")
                        .cookie(alice.session())
                        .param("from", now.minus(1, ChronoUnit.DAYS).toString())
                        .param("to", now.plusSeconds(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preset").value("custom"))
                .andExpect(jsonPath("$.data.summary.request_count").value(2))
                .andExpect(jsonPath("$.data.summary.success_count").value(1))
                .andExpect(jsonPath("$.data.summary.failure_count").value(1))
                .andExpect(jsonPath("$.data.summary.success_rate").value(50.0))
                .andExpect(jsonPath("$.data.summary.input_tokens").value(100))
                .andExpect(jsonPath("$.data.summary.billed_amount").value("1.250000000000"))
                .andExpect(jsonPath("$.data.summary.latency_p50_ms").value(300))
                .andExpect(jsonPath("$.data.summary.latency_p95_ms").value(480))
                .andExpect(jsonPath("$.data.rankings.api_keys[0].dimension_name").value("Production Web"))
                .andExpect(jsonPath("$.data.recent_requests[0].request_id").value("req-alice-failed"))
                .andExpect(jsonPath("$.data.recent_requests[0].failure_reason").value("上游请求超时"))
                .andExpect(jsonPath("$.data.live_metrics.request_count").value(0))
                .andReturn();

        String body = result.getResponse().getContentAsString().toLowerCase();
        assertThat(body).doesNotContain(
                "req-bob-private", "bob private key", "supplier", "channel", "upstream",
                "credential", "cost", "route", "authorization"
        );
    }

    @Test
    void groupStatusUsesLatestSixtyRealRequestsAndRespectsVisibility() throws Exception {
        RegisteredUser alice = register("Status Alice", "status-alice@example.com");
        RegisteredUser bob = register("Status Bob", "status-bob@example.com");
        ModelGroup quality = createGroup("quality-live", "高质量", "all", true);
        ModelGroup assigned = createGroup("assigned-live", "专属分组", "assigned", true);
        ModelGroup internal = createGroup("internal-live", "内部分组", "internal", true);
        ModelGroup empty = createGroup("empty-live", "空分组", "all", false);
        jdbcTemplate.update("""
                INSERT INTO routing_group_user_grants (group_id, user_id, status)
                VALUES (?, ?, 'active')
                """, assigned.groupId(), alice.id());
        UUID bobKey = createApiKey(bob.id(), quality.groupId(), "Status Writer");
        Instant start = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        // 最旧 5 次失败必须被最近 60 次窗口排除。
        for (int index = 0; index < 65; index++) {
            boolean latestWindowFailure = index >= 5 && index < 7;
            int statusCode = index < 5 || latestWindowFailure ? 502 : 200;
            insertRequest(bob.id(), bobKey, quality, "quality-" + index, statusCode,
                    statusCode == 200 ? null : "UPSTREAM_ERROR", BigDecimal.ZERO,
                    0, 0, 0, 100 + index, start.plusSeconds(index));
        }

        MvcResult result = mockMvc.perform(get("/api/v1/status/groups").cookie(alice.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sample_limit").value(60))
                .andExpect(jsonPath("$.data.groups[?(@.name == '高质量')].sample_count").value(60))
                .andExpect(jsonPath("$.data.groups[?(@.name == '高质量')].availability").value(96.67))
                .andExpect(jsonPath("$.data.groups[?(@.name == '高质量')].status").value("partial"))
                .andExpect(jsonPath("$.data.groups[?(@.name == '高质量')].history.length()").value(60))
                .andExpect(jsonPath("$.data.groups[?(@.name == '专属分组')].name").value("专属分组"))
                .andExpect(jsonPath("$.data.groups[?(@.name == '空分组')].status").value("unconfigured"))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("groups").toString()).doesNotContain("内部分组", internal.groupId().toString());
        assertThat(data.path("groups").toString()).contains(empty.groupId().toString());
        String body = result.getResponse().getContentAsString().toLowerCase();
        assertThat(body).doesNotContain(
                "user_id", "supplier", "channel", "upstream", "credential", "cost", "route", "error_summary"
        );

        mockMvc.perform(get("/api/v1/dashboard/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/status/groups")).andExpect(status().isUnauthorized());
    }

    private ModelGroup createGroup(String code, String name, String audience, boolean withModel) {
        UUID groupId = UUID.randomUUID();
        UUID modelId = withModel ? UUID.randomUUID() : null;
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, description, price_multiplier, audience, status)
                VALUES (?, ?, ?, '用户可见说明', 2.8, ?, 'active')
                """, groupId, code, name, audience);
        if (modelId != null) {
            jdbcTemplate.update("""
                    INSERT INTO ai_models (
                        id, public_name, display_name, provider, capability_type,
                        supports_streaming, public_visible, status
                    ) VALUES (?, ?, ?, 'test', 'text', true, true, 'active')
                    """, modelId, code + "-model", name + "模型");
            jdbcTemplate.update("""
                    INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                    VALUES (?, ?, 'manual', 'active')
                    """, groupId, modelId);
        }
        return new ModelGroup(groupId, modelId, code + "-model");
    }

    private UUID createApiKey(UUID userId, UUID groupId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO api_keys (
                    id, user_id, name, key_prefix, key_suffix, key_hash, status,
                    default_group_id, service_group_id, allowed_group_ids
                ) VALUES (?, ?, ?, 'sk-test', 'last', decode(?, 'hex'), 'active', ?, ?, jsonb_build_array(?::text))
                """, id, userId, name, id.toString().replace("-", "").repeat(2), groupId, groupId, groupId);
        return id;
    }

    private void insertRequest(
            UUID userId,
            UUID apiKeyId,
            ModelGroup fixture,
            String requestId,
            int statusCode,
            String platformErrorCode,
            BigDecimal billedAmount,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long durationMs,
            Instant createdAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO request_logs (
                    id, request_id, user_id, api_key_id, model_id, group_id,
                    public_model, upstream_model, started_at, completed_at, duration_ms,
                    status_code, platform_error_code, input_tokens, output_tokens, cached_tokens,
                    billed_amount, price_multiplier, streaming, retry_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'private-upstream-model', ?, ?, ?, ?, ?, ?, ?, ?, ?, 2.8, false, 0, ?)
                """, UUID.randomUUID(), requestId, userId, apiKeyId, fixture.modelId(), fixture.groupId(),
                fixture.publicModel(), Timestamp.from(createdAt), Timestamp.from(createdAt.plusMillis(durationMs)),
                durationMs, statusCode, platformErrorCode, inputTokens, outputTokens, cachedTokens,
                billedAmount, Timestamp.from(createdAt));
    }

    private RegisteredUser register(String name, String email) throws Exception {
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

    private record ModelGroup(UUID groupId, UUID modelId, String publicModel) {
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
