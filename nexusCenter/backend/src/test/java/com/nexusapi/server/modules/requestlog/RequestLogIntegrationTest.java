package com.nexusapi.server.modules.requestlog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.requestlog.model.GatewayRequestLog;
import com.nexusapi.server.modules.requestlog.service.RequestLogService;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证用户调用日志的用户隔离、筛选和响应字段白名单。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RequestLogIntegrationTest.FixedCaptchaConfiguration.class)
class RequestLogIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RedisConnectionFactory redisConnectionFactory;
    @Autowired private RequestLogService requestLogService;

    private UUID groupId;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        groupId = TestServiceGroupFixture.createPublicGroup(jdbcTemplate);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void currentUserCanOnlyReadOwnWhitelistedLogs() throws Exception {
        RegisteredUser alice = register("Alice", "alice-logs@example.com");
        RegisteredUser bob = register("Bob", "bob-logs@example.com");
        UUID aliceKey = createApiKey(alice.id(), "Production Web", "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
        UUID bobKey = createApiKey(bob.id(), "Secret Bob Key", "0202020202020202020202020202020202020202020202020202020202020202");

        recordLog(alice.id(), aliceKey, "req-alice-success", 200, null, "safe-model", "raw-upstream-a", "provider secret a");
        recordLog(alice.id(), aliceKey, "req-alice-failed", 504, "UPSTREAM_TIMEOUT", "safe-model", "raw-upstream-b", "provider secret b");
        recordLog(bob.id(), bobKey, "req-bob-private", 200, null, "private-model", "raw-upstream-c", "provider secret c");

        MvcResult list = mockMvc.perform(get("/api/v1/request-logs")
                        .cookie(alice.session())
                        .param("period", "24h")
                        .param("page_size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page_size").value(1))
                .andExpect(jsonPath("$.data.items[0].request_id").value("req-alice-failed"))
                .andExpect(jsonPath("$.data.items[0].api_key_name").value("Production Web"))
                .andExpect(jsonPath("$.data.items[0].service_group_name").value("Test Public Group"))
                .andExpect(jsonPath("$.data.items[0].failure_reason").value("上游请求超时"))
                .andExpect(jsonPath("$.data.items[0].supplier_id").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].channel_id").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].upstream_model").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].supplier_cost_amount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].client_ip").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].upstream_error_summary").doesNotExist())
                .andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain("req-bob-private", "provider secret", "raw-upstream");

        mockMvc.perform(get("/api/v1/request-logs")
                        .cookie(alice.session())
                        .param("status", "success")
                        .param("query", "Production Web")
                        .param("model", "safe-model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].request_id").value("req-alice-success"));

        mockMvc.perform(get("/api/v1/request-logs")
                        .cookie(alice.session())
                        .param("period", "today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        Instant customFrom = Instant.now().minusSeconds(10_800);
        Instant customTo = Instant.now().minusSeconds(7_200);
        jdbcTemplate.update("UPDATE request_logs SET created_at = ? WHERE request_id = ?",
                java.sql.Timestamp.from(customFrom.plusSeconds(600)), "req-alice-success");
        jdbcTemplate.update("UPDATE request_logs SET created_at = ? WHERE request_id = ?",
                java.sql.Timestamp.from(customTo.plusSeconds(600)), "req-alice-failed");
        mockMvc.perform(get("/api/v1/request-logs")
                        .cookie(alice.session())
                        .param("period", "custom")
                        .param("from", customFrom.toString())
                        .param("to", customTo.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].request_id").value("req-alice-success"));
        mockMvc.perform(get("/api/v1/request-logs")
                        .cookie(alice.session())
                        .param("period", "custom")
                        .param("from", customFrom.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/request-logs/req-alice-failed").cookie(alice.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failure_reason").value("上游请求超时"));
        mockMvc.perform(get("/api/v1/request-logs/req-bob-private").cookie(alice.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REQUEST_LOG_NOT_FOUND"));
    }

    private UUID createApiKey(UUID userId, String name, String hashHex) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO api_keys (
                    id, user_id, name, key_prefix, key_suffix, key_hash, status,
                    default_group_id, service_group_id, allowed_group_ids
                ) VALUES (?, ?, ?, 'sk-test', 'last', decode(?, 'hex'), 'active', ?, ?, jsonb_build_array(?::text))
                """, id, userId, name, hashHex, groupId, groupId, groupId);
        return id;
    }

    private void recordLog(UUID userId, UUID apiKeyId, String requestId, int statusCode,
                           String errorCode, String publicModel, String upstreamModel, String upstreamError) {
        Instant completedAt = Instant.now();
        requestLogService.record(new GatewayRequestLog(
                UUID.randomUUID(), requestId, userId, apiKeyId, null,
                null, null, null, groupId, publicModel, upstreamModel,
                completedAt.minusMillis(812), completedAt, 812, statusCode, errorCode,
                100, 20, 10, new BigDecimal("0.25000000"), BigDecimal.ONE,
                new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("0.02"),
                new BigDecimal("0.003"), "USD", new BigDecimal("0.247"),
                statusCode >= 400 ? "timeout" : null, true, statusCode >= 400 ? 1 : 0,
                "203.0.113.10", "private-user-agent-hash", upstreamError, "private-route-reason",
                "{}", "{}", "{}", "{}", 0L, 0L
        ));
    }

    private RegisteredUser register(String name, String email) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk()).andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id").asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "email", email, "password", "StrongPass123!",
                                "challenge_id", challengeId, "captcha_code", "ACEF"
                        ))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(registration.getResponse().getContentAsString());
        return new RegisteredUser(UUID.fromString(body.at("/data/user/id").asText()),
                registration.getResponse().getCookie("NEXUS_SESSION"));
    }

    record RegisteredUser(UUID id, Cookie session) {}

    @TestConfiguration
    static class FixedCaptchaConfiguration {
        @Bean
        @Primary
        CaptchaCodeGenerator fixedCaptchaCodeGenerator() {
            return length -> "ACEF";
        }
    }
}
