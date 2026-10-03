package com.nexusapi.server.modules.apikey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiKeyIntegrationTest.FixedCaptchaConfiguration.class)
class ApiKeyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private AuthCases cases;
    private UUID serviceGroupId;

    @BeforeEach
    void resetState() throws Exception {
        cases = objectMapper.readValue(Files.readString(Path.of("references/auth-cases.json")), AuthCases.class);
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        serviceGroupId = TestServiceGroupFixture.createPublicGroup(jdbcTemplate);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void createAndListExposeSecretOnlyOnceAndStoreOnlyProtectedMaterial() throws Exception {
        Cookie session = register(cases.user());

        MvcResult created = createKey(session, createPayload("Production Web"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.secret").isNotEmpty())
                .andExpect(jsonPath("$.data.masked_key").value(org.hamcrest.Matchers.containsString("...")))
                .andExpect(jsonPath("$.data.key_hash").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID keyId = UUID.fromString(body.at("/data/id").asText());
        String secret = body.at("/data/secret").asText();
        assertThat(secret).matches("sk-nx-v1_[A-Za-z0-9_-]{43}");

        byte[] storedHash = jdbcTemplate.queryForObject(
                "SELECT key_hash FROM api_keys WHERE id = ?",
                byte[].class,
                keyId
        );
        Integer hashVersion = jdbcTemplate.queryForObject(
                "SELECT key_hash_version FROM api_keys WHERE id = ?",
                Integer.class,
                keyId
        );
        assertThat(storedHash).hasSize(32);
        assertThat(hashVersion).isEqualTo(1);

        mockMvc.perform(get("/api/v1/api-keys").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(keyId.toString()))
                .andExpect(jsonPath("$.data.items[0].name").value("Production Web"))
                .andExpect(jsonPath("$.data.items[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].key_hash").doesNotExist());

        String auditJson = jdbcTemplate.queryForObject(
                "SELECT coalesce(before_data::text, '') || coalesce(after_data::text, '') FROM audit_logs WHERE resource_id = ?",
                String.class,
                keyId.toString()
        );
        assertThat(auditJson).doesNotContain(secret).doesNotContain("key_hash");
    }

    @Test
    void listReturnsSettledReservedAndRemainingCreditsForEachApiKey() throws Exception {
        Cookie session = register(cases.user());
        MvcResult created = createKey(session, createPayload("Usage Key"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID keyId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/id")
                .asText());
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM api_keys WHERE id = ?",
                UUID.class,
                keyId
        );

        // 已结算用量扣除已退款积分，与 BillingService 的 Key 级额度拦截口径保持一致。
        jdbcTemplate.update("""
                INSERT INTO wallet_reservations (
                    user_id, api_key_id, request_id, idempotency_key, status,
                    reserved_amount, reserved_permanent, reserved_expiring,
                    settled_amount, settled_permanent, settled_expiring,
                    refunded_amount, refunded_permanent, refunded_expiring
                ) VALUES (?, ?, ?, ?, 'settled', 12.500000000000, 12.500000000000, 0,
                          10.250000000000, 10.250000000000, 0,
                          1.250000000000, 1.250000000000, 0)
                """, userId, keyId, "req-api-key-settled", "reserve:api-key-settled");
        jdbcTemplate.update("""
                INSERT INTO wallet_reservations (
                    user_id, api_key_id, request_id, idempotency_key, status,
                    reserved_amount, reserved_permanent, reserved_expiring
                ) VALUES (?, ?, ?, ?, 'reserved', 2.500000000000, 2.500000000000, 0)
                """, userId, keyId, "req-api-key-reserved", "reserve:api-key-reserved");

        mockMvc.perform(get("/api/v1/api-keys").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].used_credits").value("9.000000000000"))
                .andExpect(jsonPath("$.data.items[0].reserved_credits").value("2.500000000000"))
                .andExpect(jsonPath("$.data.items[0].remaining_credits").value("88.500000000000"));
    }

    @Test
    void createRequiresCsrfAndDoesNotPersistOnRejectedRequest() throws Exception {
        Cookie session = register(cases.user());

        mockMvc.perform(post("/api/v1/api-keys")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createPayload("No CSRF"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM api_keys", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void usersCannotReadOrModifyAnotherUsersApiKey() throws Exception {
        Cookie firstSession = register(cases.user());
        Cookie secondSession = register(cases.alternateUser());
        MvcResult created = createKey(secondSession, createPayload("Private Key"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID keyId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).at("/data/id").asText());

        Map<String, Object> update = updatePayload("Stolen Name", 0L);
        mockMvc.perform(patch("/api/v1/api-keys/{id}", keyId)
                        .cookie(firstSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("API_KEY_NOT_FOUND"));

        String storedName = jdbcTemplate.queryForObject(
                "SELECT name FROM api_keys WHERE id = ?",
                String.class,
                keyId
        );
        assertThat(storedName).isEqualTo("Private Key");
    }

    @Test
    void optimisticUpdatesStatusChangesAndRevocationAreSafeAndIdempotent() throws Exception {
        Cookie session = register(cases.user());
        MvcResult created = createKey(session, createPayload("Deploy Key"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID keyId = UUID.fromString(createdBody.at("/data/id").asText());

        mockMvc.perform(patch("/api/v1/api-keys/{id}", keyId)
                        .cookie(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updatePayload("Deploy Key v2", 0L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Deploy Key v2"))
                .andExpect(jsonPath("$.data.version").value(1));

        Boolean configurationTimestampAdvanced = jdbcTemplate.queryForObject(
                "SELECT updated_at > created_at FROM api_keys WHERE id = ?",
                Boolean.class,
                keyId
        );
        assertThat(configurationTimestampAdvanced).isTrue();

        mockMvc.perform(patch("/api/v1/api-keys/{id}", keyId)
                        .cookie(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updatePayload("Stale Update", 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("API_KEY_VERSION_CONFLICT"));

        Map<String, Object> disabled = Map.of("status", "disabled", "version", 1);
        mockMvc.perform(put("/api/v1/api-keys/{id}/status", keyId)
                        .cookie(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("disabled"))
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(put("/api/v1/api-keys/{id}/status", keyId)
                        .cookie(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(delete("/api/v1/api-keys/{id}", keyId).cookie(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));

        Boolean revocationTimestampRecorded = jdbcTemplate.queryForObject(
                "SELECT updated_at = status_changed_at AND updated_at = revoked_at FROM api_keys WHERE id = ?",
                Boolean.class,
                keyId
        );
        assertThat(revocationTimestampRecorded).isTrue();
        mockMvc.perform(delete("/api/v1/api-keys/{id}", keyId).cookie(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));

        mockMvc.perform(put("/api/v1/api-keys/{id}/status", keyId)
                        .cookie(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "active", "version", 3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("API_KEY_STATUS_CONFLICT"));
    }

    @Test
    void invalidCidrAndTooSoonExpiryFailClosed() throws Exception {
        Cookie session = register(cases.user());
        Map<String, Object> invalidCidr = createPayload("Invalid CIDR");
        invalidCidr.put("ip_allowlist", List.of("example.com/24"));

        createKey(session, invalidCidr)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        Map<String, Object> shortExpiry = createPayload("Short Expiry");
        shortExpiry.put("expires_at", Instant.now().plusSeconds(60).toString());
        createKey(session, shortExpiry)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM api_keys", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void modelWhitelistMustBelongToSelectedServiceGroup() throws Exception {
        Cookie session = register(cases.user());
        UUID foreignModelId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    public_visible, status
                ) VALUES (?, ?, 'Foreign Model', 'test', 'text', true, 'active')
                """, foreignModelId, "foreign-model-" + foreignModelId);
        Map<String, Object> payload = createPayload("Invalid Group Scope");
        payload.put("allowed_model_ids", List.of(foreignModelId));

        createKey(session, payload)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("所选服务分组")));

        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM api_keys", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void catalogOnlyGroupAndModelCanBeSelectedBeforeRuntimeRouteIsConfigured() throws Exception {
        Cookie session = register(cases.user());
        UUID catalogGroupId = UUID.randomUUID();
        UUID catalogModelId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, 'Catalog Only Group', 1, 'all', 'active')
                """, catalogGroupId, "catalog-only-" + catalogGroupId);
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    public_visible, status
                ) VALUES (?, ?, 'Catalog Only Model', 'test', 'text', true, 'active')
                """, catalogModelId, "catalog-only-model-" + catalogModelId);
        jdbcTemplate.update("""
                INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                VALUES (?, ?, 'manual', 'active')
                """, catalogGroupId, catalogModelId);

        Map<String, Object> payload = createPayload("Catalog Only Key");
        payload.put("service_group_id", catalogGroupId);
        payload.put("default_group_id", catalogGroupId);
        payload.put("allowed_group_ids", List.of(catalogGroupId));
        payload.put("allowed_model_ids", List.of(catalogModelId));

        createKey(session, payload)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.secret").isNotEmpty());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_keys WHERE service_group_id = ?",
                Integer.class,
                catalogGroupId
        );
        assertThat(count).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions createKey(Cookie session, Map<String, Object> payload) throws Exception {
        return mockMvc.perform(post("/api/v1/api-keys")
                .cookie(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)));
    }

    private Map<String, Object> createPayload(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("service_group_id", serviceGroupId);
        payload.put("default_group_id", serviceGroupId);
        payload.put("allowed_model_ids", List.of());
        payload.put("allowed_group_ids", List.of(serviceGroupId));
        payload.put("ip_allowlist", List.of("203.0.113.10/32"));
        payload.put("rpm_limit", 60);
        payload.put("tpm_limit", 200_000);
        payload.put("concurrency_limit", 5);
        payload.put("credit_limit", 100);
        payload.put("expires_at", Instant.now().plusSeconds(3600).toString());
        return payload;
    }

    private Map<String, Object> updatePayload(String name, long version) {
        Map<String, Object> payload = createPayload(name);
        payload.put("version", version);
        return payload;
    }

    private Cookie register(UserCase user) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id")
                .asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", user.name(),
                                "email", user.email(),
                                "password", user.password(),
                                "challenge_id", challengeId,
                                "captcha_code", cases.captchaCode()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return registration.getResponse().getCookie("NEXUS_SESSION");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    record UserCase(String name, String email, String password) {
    }

    record AuthCases(String captchaCode, UserCase user, UserCase alternateUser, String invalidPassword) {
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
