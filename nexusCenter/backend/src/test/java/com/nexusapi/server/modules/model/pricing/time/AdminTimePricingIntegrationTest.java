package com.nexusapi.server.modules.model.pricing.time;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 时段倍率管理接口的 PostgreSQL、权限、CSRF、乐观锁和跨天冲突集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminTimePricingIntegrationTest.FixedCaptchaConfiguration.class)
class AdminTimePricingIntegrationTest {
    private static final String CAPTCHA_CODE = "ACEF";
    private static final String PASSWORD = "StrongPassword!2026";
    private static final String RULES_PATH = "/api/v1/admin/billing/time-rules";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE users, ai_models CASCADE");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void adminCanManageRulesWithCsrfOptimisticLockingAndRedactedResponses() throws Exception {
        RegisteredUser ordinary = register("time-pricing-user@example.com", "Time Pricing User");
        RegisteredUser admin = registerAdmin("time-pricing-admin@example.com");
        UUID modelId = createModel(admin.session(), "time-priced-model");
        Map<String, Object> createPayload = rulePayload(
                "工作日晚高峰", "1.250", List.of(1, 2, 3, 4, 5), "18:00", "22:00",
                true, List.of(modelId), 0
        );

        mockMvc.perform(get(RULES_PATH).cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        mockMvc.perform(post(RULES_PATH)
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createPayload)))
                .andExpect(status().isForbidden());

        MvcResult createdResult = mockMvc.perform(post(RULES_PATH)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.multiplier").value(1.25))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.models[0].id").value(modelId.toString()))
                .andExpect(jsonPath("$.data.supplier_cost").doesNotExist())
                .andExpect(jsonPath("$.data.upstream_api_key").doesNotExist())
                .andExpect(jsonPath("$.data.route_id").doesNotExist())
                .andReturn();
        UUID ruleId = UUID.fromString(data(createdResult).path("id").asText());

        mockMvc.perform(get(RULES_PATH).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("工作日晚高峰"));

        Map<String, Object> updatePayload = rulePayload(
                "工作日晚高峰 V2", "1.500", List.of(1, 2, 3, 4, 5), "19:00", "23:00",
                false, List.of(modelId), 0
        );
        mockMvc.perform(put(RULES_PATH + "/{ruleId}", ruleId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updatePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("工作日晚高峰 V2"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put(RULES_PATH + "/{ruleId}", ruleId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updatePayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_VERSION_CONFLICT"));

        mockMvc.perform(put(RULES_PATH + "/{ruleId}/status", ruleId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", true, "version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(delete(RULES_PATH + "/{ruleId}", ruleId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 2))))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_time_rules WHERE id = ?", Integer.class, ruleId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_id = ? AND action LIKE 'admin.billing.time-rule.%'",
                Integer.class, ruleId.toString()
        )).isEqualTo(4);
    }

    @Test
    void enabledRulesRejectOverlapsAndAllowAdjacentBoundariesIncludingCrossMidnight() throws Exception {
        RegisteredUser admin = registerAdmin("time-pricing-overlap@example.com");
        UUID daytimeModelId = createModel(admin.session(), "daytime-priced-model");
        UUID overnightModelId = createModel(admin.session(), "overnight-priced-model");

        createRule(admin.session(), rulePayload(
                "周一上午", "1.100", List.of(1), "09:00", "12:00",
                true, List.of(daytimeModelId), 0
        ));
        createRule(admin.session(), rulePayload(
                "周一午间", "1.200", List.of(1), "12:00", "13:00",
                true, List.of(daytimeModelId), 0
        ));

        mockMvc.perform(post(RULES_PATH)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(rulePayload(
                                "周一重叠", "1.300", List.of(1), "11:00", "12:30",
                                true, List.of(daytimeModelId), 0
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_CONFLICT"));

        createRule(admin.session(), rulePayload(
                "周一深夜", "1.400", List.of(1), "22:00", "02:00",
                true, List.of(overnightModelId), 0
        ));

        mockMvc.perform(post(RULES_PATH)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(rulePayload(
                                "周二凌晨重叠", "1.500", List.of(2), "01:00", "03:00",
                                true, List.of(overnightModelId), 0
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_CONFLICT"));

        createRule(admin.session(), rulePayload(
                "周二凌晨边界", "1.600", List.of(2), "02:00", "03:00",
                true, List.of(overnightModelId), 0
        ));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_time_rules WHERE enabled = true", Integer.class
        )).isEqualTo(4);
    }

    private JsonNode createRule(Cookie session, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post(RULES_PATH)
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return data(result);
    }

    private UUID createModel(Cookie session, String publicName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/models")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload(publicName))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(data(result).path("id").asText());
    }

    private Map<String, Object> modelPayload(String publicName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("public_name", publicName);
        payload.put("display_name", publicName + " Display");
        payload.put("provider", "openai");
        payload.put("capability_type", "text");
        payload.put("input_modalities", List.of("text"));
        payload.put("output_modalities", List.of("text"));
        payload.put("context_window", 200_000);
        payload.put("max_output_tokens", 32_000);
        payload.put("supports_streaming", true);
        payload.put("supports_tools", true);
        payload.put("supports_structured_output", true);
        payload.put("input_price", "0.1200000000");
        payload.put("output_price", "0.4800000000");
        payload.put("cached_input_price", "0.0300000000");
        payload.put("price_unit", "million_tokens");
        payload.put("public_visible", true);
        payload.put("status", "active");
        return payload;
    }

    private Map<String, Object> rulePayload(
            String name,
            String multiplier,
            List<Integer> days,
            String startTime,
            String endTime,
            boolean enabled,
            List<UUID> modelIds,
            long version
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("multiplier", multiplier);
        payload.put("days_of_week", days);
        payload.put("start_time", startTime);
        payload.put("end_time", endTime);
        payload.put("enabled", enabled);
        payload.put("model_ids", modelIds);
        payload.put("version", version);
        return payload;
    }

    private RegisteredUser registerAdmin(String email) throws Exception {
        RegisteredUser user = register(email, "Time Pricing Admin");
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'admin')", user.id());
        return user;
    }

    private RegisteredUser register(String email, String name) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk())
                .andReturn();
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
                .andExpect(status().isCreated())
                .andReturn();
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
