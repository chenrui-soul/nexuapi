package com.nexusapi.server.modules.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理员套餐配置、权限边界、乐观锁和订阅快照隔离的集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSubscriptionPlanIntegrationTest.FixedCaptchaConfiguration.class)
class AdminSubscriptionPlanIntegrationTest {
    private static final String CAPTCHA_CODE = "ACEF";
    private static final String PASSWORD = "StrongPassword!2026";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void resetState() {
        cleanupTestData();
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @AfterEach
    void cleanupAfterTest() {
        cleanupTestData();
    }

    /** 只清理本测试创建的数据，保留 Flyway 预置套餐，避免污染后续订阅全量回归。 */
    private void cleanupTestData() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        jdbcTemplate.update("DELETE FROM plans WHERE code IN ('admin-pro-monthly', 'snapshot-plan')");
        jdbcTemplate.update("DELETE FROM routing_groups WHERE code LIKE 'plan-%'");
        jdbcTemplate.update("DELETE FROM ai_models WHERE provider = 'plan-test'");
    }

    @Test
    void onlyAdminWithCsrfCanCreateCompletePlanConfiguration() throws Exception {
        RegisteredUser ordinary = register("plan-user@example.com", "Plan User");
        RegisteredUser admin = registerAdmin("plan-admin@example.com");
        UUID groupId = createGroup("value");
        UUID modelId = createModel("plan-text");
        Map<String, Object> payload = planPayload("admin-pro-monthly", groupId, modelId, 0L);

        mockMvc.perform(post("/api/v1/admin/subscription-plans")
                        .cookie(ordinary.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/subscription-plans")
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/admin/subscription-plans")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("admin-pro-monthly"))
                .andExpect(jsonPath("$.data.service_groups[0].id").value(groupId.toString()))
                .andExpect(jsonPath("$.data.models[0].id").value(modelId.toString()))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();
        UUID planId = UUID.fromString(data(created).path("id").asText());

        mockMvc.perform(get("/api/v1/admin/subscription-plans/options").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.service_groups[*].id", hasItem(groupId.toString())))
                .andExpect(jsonPath("$.data.models[*].id", hasItem(modelId.toString())));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'admin.subscription-plan.create'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT entitlements->'features'->>1 FROM plans WHERE id = ?", String.class, planId
        )).isEqualTo("优先请求队列");
    }

    @Test
    void planUpdateDoesNotRewriteSoldSubscriptionSnapshotsAndRejectsStaleVersion() throws Exception {
        RegisteredUser admin = registerAdmin("plan-snapshot@example.com");
        UUID oldGroupId = createGroup("old");
        UUID newGroupId = createGroup("new");
        UUID oldModelId = createModel("old-model");
        UUID newModelId = createModel("new-model");
        UUID planId = createPlan(oldGroupId, oldModelId);
        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO subscriptions (id, user_id, plan_id, status, starts_at, expires_at, source)
                VALUES (?, ?, ?, 'active', ?, ?, 'admin')
                """, subscriptionId, admin.id(), planId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now().plusSeconds(86_400)));
        jdbcTemplate.update("INSERT INTO subscription_service_groups (subscription_id, service_group_id) VALUES (?, ?)",
                subscriptionId, oldGroupId);
        jdbcTemplate.update("INSERT INTO subscription_models (subscription_id, model_id) VALUES (?, ?)",
                subscriptionId, oldModelId);

        Map<String, Object> payload = planPayload("snapshot-plan", newGroupId, newModelId, 0L);
        mockMvc.perform(put("/api/v1/admin/subscription-plans/{planId}", planId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.service_groups[0].id").value(newGroupId.toString()))
                .andExpect(jsonPath("$.data.models[0].id").value(newModelId.toString()));

        assertThat(jdbcTemplate.queryForList(
                "SELECT service_group_id FROM subscription_service_groups WHERE subscription_id = ?",
                UUID.class, subscriptionId
        )).containsExactly(oldGroupId);
        assertThat(jdbcTemplate.queryForList(
                "SELECT model_id FROM subscription_models WHERE subscription_id = ?",
                UUID.class, subscriptionId
        )).containsExactly(oldModelId);

        mockMvc.perform(put("/api/v1/admin/subscription-plans/{planId}", planId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_VERSION_CONFLICT"));
    }

    @Test
    void archiveIsSoftDeleteAndKeepsHistoricalReferences() throws Exception {
        RegisteredUser admin = registerAdmin("plan-archive@example.com");
        UUID groupId = createGroup("archive");
        UUID modelId = createModel("archive-model");
        UUID planId = createPlan(groupId, modelId);
        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO subscriptions (id, user_id, plan_id, status, starts_at, expires_at, source)
                VALUES (?, ?, ?, 'active', ?, ?, 'admin')
                """, subscriptionId, admin.id(), planId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now().plusSeconds(86_400)));

        mockMvc.perform(delete("/api/v1/admin/subscription-plans/{planId}", planId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("archived"))
                .andExpect(jsonPath("$.data.version").value(1));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM plans WHERE id = ?", Integer.class, planId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE id = ? AND plan_id = ?", Integer.class,
                subscriptionId, planId
        )).isEqualTo(1);
    }

    private UUID createPlan(UUID groupId, UUID modelId) {
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO plans (
                    id, code, name, description, billing_cycle, price, included_credits,
                    concurrency_limit, entitlements, status, display_order, featured
                ) VALUES (?, 'snapshot-plan', 'Snapshot Plan', 'Snapshot', 'monthly', 199, 30000, 20,
                          '{"features":["snapshot"],"creation_space":true}'::jsonb, 'active', 20, true)
                """, planId);
        jdbcTemplate.update("INSERT INTO plan_service_groups (plan_id, service_group_id) VALUES (?, ?)", planId, groupId);
        jdbcTemplate.update("INSERT INTO plan_models (plan_id, model_id) VALUES (?, ?)", planId, modelId);
        return planId;
    }

    private UUID createGroup(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, ?, 1.25, 'all', 'active')
                """, id, "plan-" + suffix + "-" + id, "Plan " + suffix);
        return id;
    }

    private UUID createModel(String prefix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type, public_visible, status
                ) VALUES (?, ?, ?, 'plan-test', 'text', true, 'active')
                """, id, prefix + "-" + id, prefix);
        return id;
    }

    private Map<String, Object> planPayload(String code, UUID groupId, UUID modelId, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", "专业版");
        payload.put("description", "适合持续创作与开发调用");
        payload.put("billing_cycle", "monthly");
        payload.put("price", "199.000000000000");
        payload.put("included_credits", "30000.000000000000");
        payload.put("concurrency_limit", 20);
        payload.put("features", List.of("每月 30,000 积分", "优先请求队列"));
        payload.put("status", "active");
        payload.put("display_order", 20);
        payload.put("featured", true);
        payload.put("service_group_ids", List.of(groupId));
        payload.put("model_ids", List.of(modelId));
        payload.put("version", version);
        return payload;
    }

    private RegisteredUser registerAdmin(String email) throws Exception {
        RegisteredUser user = register(email, "Subscription Admin");
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
                        .content(json(Map.of(
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

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    record RegisteredUser(UUID id, Cookie session) {
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
