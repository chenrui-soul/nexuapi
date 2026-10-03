package com.nexusapi.server.modules.systemtoken;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证系统访问令牌独立安全域和订阅测试开通的真实资金闭环。 */
@SpringBootTest(properties = "nexus.payment.mock-enabled=true")
@AutoConfigureMockMvc
@Import(SystemAccessAndSubscriptionIntegrationTest.FixedCaptchaConfiguration.class)
class SystemAccessAndSubscriptionIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    private AuthCases cases;

    @BeforeEach
    void reset() throws Exception {
        cases = objectMapper.readValue(Files.readString(Path.of("references/auth-cases.json")), AuthCases.class);
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void systemAccessTokenIsIndependentAndOnlyExposesSelectedReadScopes() throws Exception {
        Cookie session = register(cases.user());
        MvcResult created = mockMvc.perform(post("/api/v1/system-access-tokens")
                        .with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Reporting Agent",
                                "scopes", List.of("dashboard:read"),
                                "ip_allowlist", List.of()
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.secret").value(org.hamcrest.Matchers.matchesPattern("nx-sys-v1_[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.data.token.masked_token").exists())
                .andExpect(jsonPath("$.data.token.token_hash").doesNotExist())
                .andReturn();
        JsonNode response = objectMapper.readTree(created.getResponse().getContentAsString());
        String secret = response.at("/data/secret").asText();
        UUID tokenId = UUID.fromString(response.at("/data/token/id").asText());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT octet_length(token_hash) FROM system_access_tokens WHERE id = ?",
                Integer.class, tokenId
        )).isEqualTo(32);

        mockMvc.perform(get("/api/v1/system-access/me").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopes[0]").value("dashboard:read"));
        mockMvc.perform(get("/api/v1/system-access/wallet").header("Authorization", "Bearer " + secret))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SYSTEM_ACCESS_TOKEN_SCOPE_DENIED"));
        mockMvc.perform(get("/v1/models").header("Authorization", "Bearer " + secret))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/system-access-tokens").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data[0].masked_token").exists());
    }

    @Test
    void mockSubscriptionCreatesRealPeriodAndCreditsWalletOnlyOnce() throws Exception {
        Cookie session = register(cases.user());
        MvcResult overview = mockMvc.perform(get("/api/v1/subscriptions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plans.length()").value(3))
                .andExpect(jsonPath("$.data.current_subscription").doesNotExist())
                .andReturn();
        UUID planId = UUID.fromString(objectMapper.readTree(overview.getResponse().getContentAsString())
                .at("/data/plans/0/id").asText());
        String included = objectMapper.readTree(overview.getResponse().getContentAsString())
                .at("/data/plans/0/included_credits").asText();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, 'Subscription Test Group', 1, 'all', 'active')
                """, groupId, "subscription-test-" + groupId);
        jdbcTemplate.update("""
                INSERT INTO plan_service_groups (plan_id, service_group_id) VALUES (?, ?)
                """, planId, groupId);
        UUID modelId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type, public_visible, status
                ) VALUES (?, ?, 'Subscription Test Model', 'test', 'text', true, 'active')
                """, modelId, "subscription-test-model-" + modelId);
        jdbcTemplate.update("""
                INSERT INTO plan_models (plan_id, model_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, planId, modelId);

        mockMvc.perform(post("/api/v1/subscriptions/plans/{planId}/mock-activate", planId)
                        .with(csrf()).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_subscription.planId").value(planId.toString()));
        mockMvc.perform(post("/api/v1/subscriptions/plans/{planId}/mock-activate", planId)
                        .with(csrf()).cookie(session))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE plan_id = ? AND status = 'active'",
                Integer.class, planId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT expiring_credits::text FROM wallet_accounts LIMIT 1", String.class
        )).startsWith(new java.math.BigDecimal(included).stripTrailingZeros().toPlainString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_ledger WHERE source_type = 'subscription'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM expiring_credit_batches WHERE subscription_id IS NOT NULL",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_service_groups WHERE service_group_id = ?",
                Integer.class, groupId
        )).isEqualTo(1);
    }

    private Cookie register(UserCase user) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk()).andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id").asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", user.name(), "email", user.email(), "password", user.password(),
                                "challenge_id", challengeId, "captcha_code", cases.captchaCode()
                        ))))
                .andExpect(status().isCreated()).andReturn();
        return registration.getResponse().getCookie("NEXUS_SESSION");
    }

    private String json(Object value) throws Exception { return objectMapper.writeValueAsString(value); }
    record UserCase(String name, String email, String password) { }
    record AuthCases(String captchaCode, UserCase user, UserCase alternateUser, String invalidPassword) { }

    @TestConfiguration
    static class FixedCaptchaConfiguration {
        @Bean @Primary CaptchaCodeGenerator fixedCaptchaCodeGenerator() { return length -> "ACEF"; }
    }
}
