package com.nexusapi.server.modules.supplier;

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
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wave 5B 供应商权限、状态机、乐观锁和敏感元数据集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSupplierIntegrationTest.FixedCaptchaConfiguration.class)
class AdminSupplierIntegrationTest {
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

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE users, suppliers CASCADE");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void ordinaryUserAndMissingCsrfCannotCreateSupplier() throws Exception {
        RegisteredUser ordinary = register("supplier-user@example.com");
        mockMvc.perform(post("/api/v1/admin/suppliers")
                        .cookie(ordinary.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(supplierPayload("denied", "Denied Supplier", "active", null, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        RegisteredUser admin = registerAdmin("supplier-csrf@example.com");
        mockMvc.perform(post("/api/v1/admin/suppliers")
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(supplierPayload("no-csrf", "No CSRF Supplier", "active", null, null))))
                .andExpect(status().isForbidden());

        // V6 的系统保留供应商被 resetState 清除，拒绝请求不能产生新供应商。
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isZero();
    }

    @Test
    void adminCanManageSupplierWithOptimisticLockAndTerminalState() throws Exception {
        RegisteredUser admin = registerAdmin("supplier-admin@example.com");
        JsonNode created = createSupplier(admin.session(), "openai-direct", "OpenAI Direct");
        UUID id = UUID.fromString(created.path("id").asText());
        assertThat(created.path("health_status").asText()).isEqualTo("unconfigured");

        mockMvc.perform(get("/api/v1/admin/suppliers")
                        .cookie(admin.session())
                        .param("query", "openai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].settlement_currency").value("USD"));

        MvcResult terminatedResult = mockMvc.perform(put("/api/v1/admin/suppliers/{id}", id)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(supplierPayload(
                                "openai-direct", "OpenAI Direct", "terminated", "合作终止", 0L
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.disabled_at").isNotEmpty())
                .andReturn();
        assertThat(data(terminatedResult).path("status").asText()).isEqualTo("terminated");

        mockMvc.perform(put("/api/v1/admin/suppliers/{id}", id)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(supplierPayload(
                                "openai-direct", "OpenAI Direct", "active", null, 0L
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_CONFLICT"));

        mockMvc.perform(put("/api/v1/admin/suppliers/{id}", id)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(supplierPayload(
                                "openai-direct", "OpenAI Direct", "terminated", "旧版本覆盖", 0L
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_VERSION_CONFLICT"));
    }

    @Test
    void supplierMetadataRejectsCredentialFieldsWithoutEchoingSecret() throws Exception {
        RegisteredUser admin = registerAdmin("supplier-secret@example.com");
        Map<String, Object> payload = supplierPayload("unsafe", "Unsafe Supplier", "active", null, null);
        payload.put("metadata", Map.of("settlement", Map.of("x.api key", "must-not-be-stored")));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/suppliers")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("must-not-be-stored");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM suppliers", Integer.class)).isZero();
    }

    @Test
    void supplierDetailRequiresAdminAndReturnsNotFoundForUnknownSupplier() throws Exception {
        RegisteredUser ordinary = register("supplier-detail-user@example.com");
        mockMvc.perform(get("/api/v1/admin/suppliers/{id}/detail", UUID.randomUUID())
                        .cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        RegisteredUser admin = registerAdmin("supplier-detail-not-found@example.com");
        mockMvc.perform(get("/api/v1/admin/suppliers/{id}/detail", UUID.randomUUID())
                        .cookie(admin.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_NOT_FOUND"));
    }

    @Test
    void supplierDetailAggregatesResourcesFinanceAndStabilityWithoutLeakingSecrets() throws Exception {
        RegisteredUser admin = registerAdmin("supplier-detail-admin@example.com");
        UUID supplierId = UUID.fromString(createSupplier(
                admin.session(), "detail-vendor", "Detail Vendor"
        ).path("id").asText());
        UUID modelId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID disabledChannelId = UUID.randomUUID();
        UUID mappingId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO ai_models (id, public_name, display_name, provider, capability_type, status)
                VALUES (?, 'nexus-gpt', 'Nexus GPT', 'openai', 'text', 'active')
                """, modelId);
        jdbcTemplate.update("""
                INSERT INTO channels (
                    id, supplier_id, name, provider_type, operation_code, base_url, encrypted_credential,
                    status, consecutive_failures, last_error_summary
                ) VALUES (?, ?, 'Detail Primary', 'openai', 'chat_completions',
                    'https://api.vendor.example/v1?api_key=must-not-leak', ?,
                    'active', 1, 'credential=must-not-leak')
                """, channelId, supplierId, "encrypted-secret-value".getBytes(StandardCharsets.UTF_8));
        jdbcTemplate.update("""
                INSERT INTO channels (
                    id, supplier_id, name, provider_type, operation_code, base_url, encrypted_credential, status
                ) VALUES (?, ?, 'Detail Backup', 'openai', 'chat_completions',
                          'https://backup.vendor.example/v1', ?, 'disabled')
                """, disabledChannelId, supplierId, new byte[]{1, 2, 3});
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, modelId, "gpt-upstream-secretless",
                "0.1", "0.05", "0.3");
        for (int index = 0; index < 2; index++) {
            UUID groupId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO routing_groups (id, code, name, status) VALUES (?, ?, 'Detail Group', 'active')",
                    groupId, "detail-" + groupId);
            jdbcTemplate.update("INSERT INTO routing_group_models (group_id, model_id, source_type, source_status) VALUES (?, ?, 'manual', 'active')",
                    groupId, modelId);
            jdbcTemplate.update("INSERT INTO routing_group_suppliers (group_id, supplier_id, status) VALUES (?, ?, 'active')",
                    groupId, supplierId);
            jdbcTemplate.update("""
                    INSERT INTO routing_group_supplier_credentials
                        (group_id, supplier_id, encrypted_credential, credential_key_version, credential_fingerprint, status)
                    VALUES (?, ?, ?, 1, 'test', 'active')
                    """, groupId, supplierId, new byte[]{1, 2, 3});
        }

        insertRequestFact(supplierId, channelId, mappingId, modelId, "detail-success", 200, null,
                "1.25", "0.45", "0.80", null);
        insertRequestFact(supplierId, channelId, mappingId, modelId, "detail-failure", 502,
                "UPSTREAM_FAILURE", "0.75", "0.35", "0.40", "timeout");
        insertAttemptFact(supplierId, channelId, mappingId, modelId, "detail-success", 0,
                "success", null, null);
        insertAttemptFact(supplierId, channelId, mappingId, modelId, "detail-failure", 0,
                "supplier_failure", "timeout", "response-body=must-not-leak");
        insertAttemptFact(supplierId, channelId, mappingId, modelId, "detail-failure", 1,
                "upstream_rejected", "rate_limit", "authorization=must-not-leak");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/suppliers/{id}/detail", supplierId)
                        .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplier.name").value("Detail Vendor"))
                .andExpect(jsonPath("$.data.period.days").value(30))
                .andExpect(jsonPath("$.data.summary.channel_count").value(2))
                .andExpect(jsonPath("$.data.summary.available_channel_count").value(1))
                .andExpect(jsonPath("$.data.summary.model_count").value(1))
                .andExpect(jsonPath("$.data.summary.active_model_count").value(1))
                .andExpect(jsonPath("$.data.summary.request_count").value(2))
                .andExpect(jsonPath("$.data.summary.success_rate").value(50.0))
                .andExpect(jsonPath("$.data.summary.billed_amount").value("2"))
                .andExpect(jsonPath("$.data.summary.supplier_cost_amount").value("0.8"))
                .andExpect(jsonPath("$.data.summary.gross_margin_amount").value("1.2"))
                .andExpect(jsonPath("$.data.summary.attempt_count").value(3))
                .andExpect(jsonPath("$.data.summary.attempt_supplier_failure_count").value(1))
                .andExpect(jsonPath("$.data.models[0].public_name").value("nexus-gpt"))
                .andExpect(jsonPath("$.data.models[0].cost_cached_input_price").value("0.05"))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        JsonNode primaryChannel = data(result).path("channels").valueStream()
                .filter(channel -> "Detail Primary".equals(channel.path("name").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(primaryChannel.path("base_url_origin").asText()).isEqualTo("https://api.vendor.example");
        assertThat(primaryChannel.path("last_error_category").asText()).isEqualTo("rate_limit");
        assertThat(response).doesNotContain(
                "must-not-leak", "encrypted-secret-value", "last_error_summary",
                "error_summary", "encrypted_credential", "credential_fingerprint"
        );
    }

    private void insertRequestFact(
            UUID supplierId, UUID channelId, UUID mappingId, UUID modelId, String requestId,
            int statusCode, String platformErrorCode, String billedAmount, String supplierCost,
            String grossMargin, String supplierErrorCategory
    ) {
        jdbcTemplate.update("""
                INSERT INTO request_logs (
                    id, request_id, model_id, channel_id, public_model, upstream_model,
                    started_at, completed_at, duration_ms, status_code, platform_error_code,
                    billed_amount, supplier_id, channel_model_id, supplier_cost_amount,
                    supplier_cost_currency, gross_margin_amount, supplier_error_category, created_at
                ) VALUES (?, ?, ?, ?, 'nexus-gpt', 'gpt-upstream-secretless',
                    now() - interval '2 minutes', now() - interval '1 minute', 100, ?, ?,
                    CAST(? AS numeric), ?, ?, CAST(? AS numeric), 'USD', CAST(? AS numeric), ?, now() - interval '1 minute')
                """, UUID.randomUUID(), requestId, modelId, channelId, statusCode, platformErrorCode,
                billedAmount, supplierId, mappingId, supplierCost, grossMargin, supplierErrorCategory);
    }

    private void insertAttemptFact(
            UUID supplierId, UUID channelId, UUID mappingId, UUID modelId, String requestId,
            int attemptNo, String outcome, String errorCategory, String errorSummary
    ) {
        jdbcTemplate.update("""
                INSERT INTO upstream_attempt_logs (
                    id, request_id, supplier_id, channel_id, channel_model_id, model_id,
                    attempt_no, started_at, completed_at, duration_ms, outcome,
                    error_category, error_summary, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, now() - interval '30 seconds', now(), 30, ?, ?, ?, now())
                """, UUID.randomUUID(), requestId, supplierId, channelId, mappingId, modelId,
                attemptNo, outcome, errorCategory, errorSummary);
    }

    private JsonNode createSupplier(Cookie session, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/suppliers")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(supplierPayload(code, name, "active", null, null))))
                .andExpect(status().isCreated())
                .andReturn();
        return data(result);
    }

    private Map<String, Object> supplierPayload(
            String code, String name, String status, String disabledReason, Long version
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", name);
        payload.put("supplier_type", "direct");
        payload.put("status", status);
        payload.put("billing_mode", "postpaid");
        payload.put("settlement_currency", "USD");
        payload.put("disabled_reason", disabledReason);
        payload.put("metadata", Map.of("region", "global"));
        if (version != null) {
            payload.put("version", version);
        }
        return payload;
    }

    private RegisteredUser registerAdmin(String email) throws Exception {
        RegisteredUser user = register(email);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'admin')", user.id());
        return user;
    }

    private RegisteredUser register(String email) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id").asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Supplier Test User",
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
