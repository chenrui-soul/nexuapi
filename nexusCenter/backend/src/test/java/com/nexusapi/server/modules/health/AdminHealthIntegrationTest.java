package com.nexusapi.server.modules.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.health.service.ChannelHealthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wave 7A 管理端健康接口的权限、CSRF、手动探测和敏感信息集成测试。 */
@SpringBootTest(properties = {
        "nexus.health.enabled=false",
        "nexus.health.alert-cooldown=30m"
})
@AutoConfigureMockMvc
@Import(AdminHealthIntegrationTest.FixedCaptchaConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminHealthIntegrationTest {
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
    private ChannelHealthService healthService;

    private WireMockServer upstream;

    @BeforeAll
    void startUpstream() {
        upstream = new WireMockServer(options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE notifications, health_alerts, health_checks, audit_logs,
                    routing_groups, channels, suppliers, ai_models, users CASCADE
                """);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        upstream.resetAll();
    }

    @Test
    void ordinaryUserCannotReadHealthOperationsAndPostRequiresCsrf() throws Exception {
        RegisteredUser ordinary = register("health-user@example.com", "Health User");
        RegisteredUser admin = registerAdmin("health-admin@example.com");
        RouteFixture fixture = createRouteFixture("permission");

        mockMvc.perform(get("/api/v1/admin/health/channels").cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        mockMvc.perform(post("/api/v1/admin/health/channels/{id}/probe", fixture.channelId())
                        .cookie(admin.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM health_checks WHERE target_id = ?", Integer.class, fixture.channelId()
        )).isZero();
    }

    @Test
    void adminCanQueryHealthAndRunManualProbeWithoutCredentialDisclosure() throws Exception {
        RegisteredUser admin = registerAdmin("health-query-admin@example.com");
        RouteFixture fixture = createRouteFixture("query");
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/query/v1/health/ready"))
                .willReturn(aResponse().withStatus(200).withBody("Bearer sk-response-body-secret")));

        MvcResult probe = mockMvc.perform(post("/api/v1/admin/health/channels/{id}/probe", fixture.channelId())
                        .cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("healthy"))
                .andExpect(jsonPath("$.data.channel_status").value("active"))
                .andReturn();
        upstream.verify(1, getRequestedFor(urlEqualTo("/query/v1/health/ready"))
                .withHeader("Authorization", equalTo("Bearer health-query-credential")));

        MvcResult channels = mockMvc.perform(get("/api/v1/admin/health/channels")
                        .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].health_probe_path").value("/health/ready"))
                .andReturn();
        mockMvc.perform(get("/api/v1/admin/health/groups").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].health_status").value("healthy"));
        mockMvc.perform(get("/api/v1/admin/health/checks").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/api/v1/admin/health/alerts").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        String combined = probe.getResponse().getContentAsString() + channels.getResponse().getContentAsString();
        assertThat(combined).doesNotContain("health-query-credential");
        assertThat(combined).doesNotContain("sk-response-body-secret");
        assertThat(combined.toLowerCase()).doesNotContain("encrypted_credential");
        assertThat(combined.toLowerCase()).doesNotContain("authorization");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'admin.channel.health_probe'",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void disabledChannelCannotBeManuallyProbed() throws Exception {
        RegisteredUser admin = registerAdmin("health-disabled-admin@example.com");
        RouteFixture fixture = createRouteFixture("disabled");
        jdbcTemplate.update("UPDATE channels SET status = 'disabled' WHERE id = ?", fixture.channelId());

        mockMvc.perform(post("/api/v1/admin/health/channels/{id}/probe", fixture.channelId())
                        .cookie(admin.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM health_checks WHERE target_id = ?", Integer.class, fixture.channelId()
        )).isZero();
    }

    @Test
    void repeatedFailuresDoNotOpenGroupAlertOrExposeSecrets() throws Exception {
        RegisteredUser admin = registerAdmin("health-alert-admin@example.com");
        RouteFixture fixture = createRouteFixture("alert-list");

        healthService.recordGatewayFailure(fixture.channelId(), "upstream_5xx", "upstream_http_500");
        healthService.recordGatewayFailure(fixture.channelId(), "upstream_5xx", "upstream_http_500");
        healthService.recordGatewayFailure(fixture.channelId(), "upstream_5xx", "upstream_http_500");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/health/alerts")
                        .cookie(admin.session()).param("status", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("health-alert-credential");
        assertThat(body.toLowerCase()).doesNotContain("authorization");
    }

    private RouteFixture createRouteFixture(String suffix) {
        UUID supplierId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        ChannelCredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(
                "health-" + suffix + "-credential"
        );
        jdbcTemplate.update("""
                INSERT INTO suppliers (
                    id, code, name, supplier_type, status, health_status,
                    billing_mode, settlement_currency, metadata
                ) VALUES (?, ?, ?, 'direct', 'active', 'unconfigured', 'postpaid', 'USD', '{}'::jsonb)
                """, supplierId, "supplier-" + suffix, "Supplier " + suffix);
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    supports_streaming, supports_tools, price_unit, public_visible, status
                ) VALUES (?, ?, ?, 'openai', 'text', true, true, 'million_tokens', true, 'active')
                """, modelId, "model-" + suffix, "Model " + suffix);
        jdbcTemplate.update("""
                INSERT INTO model_interfaces (model_id, interface_id)
                SELECT ?, id FROM api_interfaces WHERE interface_code = 'openai_chat'
                """, modelId);
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, ?, 1, 'all', 'active')
                """, groupId, "group-" + suffix, "Group " + suffix);
        jdbcTemplate.update("""
                INSERT INTO channels (
                    id, supplier_id, name, provider_type, operation_code, endpoint_type,
                    base_url, health_probe_path,
                    encrypted_credential, credential_key_version, credential_fingerprint,
                    credential_updated_at, status, timeout_ms, priority, weight
                ) VALUES (?, ?, ?, 'openai', 'chat_completions', 'text',
                          ?, '/health/ready', ?, ?, ?, now(), 'active', 5000, 100, 100)
                """, channelId, supplierId, "Channel " + suffix, upstream.baseUrl() + "/" + suffix + "/v1",
                encrypted.ciphertext(), encrypted.keyVersion(), encrypted.fingerprint());
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, modelId, "upstream-" + suffix,
                "0", "0", "0");
        jdbcTemplate.update("""
                INSERT INTO routing_group_suppliers (group_id, supplier_id, priority, weight, status)
                VALUES (?, ?, 100, 100, 'active')
                """, groupId, supplierId);
        jdbcTemplate.update("""
                INSERT INTO routing_group_supplier_credentials (
                    group_id, supplier_id, encrypted_credential, credential_key_version,
                    credential_fingerprint, updated_at, status
                ) VALUES (?, ?, ?, ?, ?, now(), 'active')
                """, groupId, supplierId, encrypted.ciphertext(), encrypted.keyVersion(), encrypted.fingerprint());
        jdbcTemplate.update("""
                INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                VALUES (?, ?, 'manual', 'active')
                """, groupId, modelId);
        return new RouteFixture(channelId, groupId);
    }

    private RegisteredUser registerAdmin(String email) throws Exception {
        RegisteredUser user = register(email, "Wave 7A Admin");
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

    private record RouteFixture(UUID channelId, UUID groupId) {
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
