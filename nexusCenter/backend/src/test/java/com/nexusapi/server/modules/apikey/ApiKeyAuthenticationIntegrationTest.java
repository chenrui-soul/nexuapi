package com.nexusapi.server.modules.apikey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.apikey.security.NexusApiKeyPrincipal;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
        ApiKeyAuthenticationIntegrationTest.FixedCaptchaConfiguration.class,
        ApiKeyAuthenticationIntegrationTest.GatewayAuthenticationProbeController.class
})
class ApiKeyAuthenticationIntegrationTest {
    private static final String PROBE_PATH = "/v1/test-authentication";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private AuthenticationCases cases;
    private UUID serviceGroupId;

    @BeforeEach
    void resetState() throws Exception {
        cases = objectMapper.readValue(
                Files.readString(Path.of("references/api-key-authentication-cases.json")),
                AuthenticationCases.class
        );
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        serviceGroupId = TestServiceGroupFixture.createPublicGroup(jdbcTemplate);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void validKeyBuildsApiKeyPrincipalWithoutExposingCredentials() throws Exception {
        CreatedKey key = createKey();
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM api_keys WHERE id = ?",
                UUID.class,
                key.id()
        );

        mockMvc.perform(get(PROBE_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "bearer " + key.secret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.api_key_id").value(key.id().toString()))
                .andExpect(jsonPath("$.user_id").value(userId.toString()))
                .andExpect(jsonPath("$.masked_key").value(org.hamcrest.Matchers.containsString("...")))
                .andExpect(jsonPath("$.credentials_present").value(false))
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    void missingMalformedDuplicateUnknownAndUnsupportedKeysReturnSameUnauthorizedError() throws Exception {
        expectRejected(get(PROBE_PATH), null);
        for (String value : cases.malformedAuthorizationValues()) {
            expectRejected(get(PROBE_PATH).header(HttpHeaders.AUTHORIZATION, value), value);
        }
        expectRejected(
                get(PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + cases.unknownSecret()),
                cases.unknownSecret()
        );
        expectRejected(
                get(PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + cases.unsupportedVersionSecret()),
                cases.unsupportedVersionSecret()
        );
        expectRejected(
                get(PROBE_PATH).header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + cases.unknownSecret(),
                        "Bearer " + cases.unsupportedVersionSecret()
                ),
                cases.unknownSecret()
        );
    }

    @Test
    void disabledKeyIsRejectedImmediately() throws Exception {
        CreatedKey key = createKey();
        jdbcTemplate.update(
                "UPDATE api_keys SET status = 'disabled', status_changed_at = now() WHERE id = ?",
                key.id()
        );
        expectRejected(authenticatedRequest(key.secret()), key.secret());
    }

    @Test
    void expiredKeyIsRejectedEvenWhenStoredStatusIsStillActive() throws Exception {
        CreatedKey key = createKey();
        jdbcTemplate.update(
                """
                UPDATE api_keys
                   SET created_at = now() - interval '2 hours',
                       expires_at = now() - interval '1 hour'
                 WHERE id = ?
                """,
                key.id()
        );
        expectRejected(authenticatedRequest(key.secret()), key.secret());
    }

    @Test
    void revokedKeyIsRejectedImmediately() throws Exception {
        CreatedKey key = createKey();
        jdbcTemplate.update(
                "UPDATE api_keys SET status = 'revoked', revoked_at = now(), status_changed_at = now() WHERE id = ?",
                key.id()
        );
        expectRejected(authenticatedRequest(key.secret()), key.secret());
    }

    @Test
    void keyBelongingToSuspendedUserIsRejected() throws Exception {
        CreatedKey key = createKey();
        jdbcTemplate.update(
                "UPDATE users SET status = 'suspended' WHERE id = (SELECT user_id FROM api_keys WHERE id = ?)",
                key.id()
        );
        expectRejected(authenticatedRequest(key.secret()), key.secret());
    }

    private RequestBuilder authenticatedRequest(String secret) {
        return get(PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
    }

    private void expectRejected(RequestBuilder request, String sensitiveValue) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error.message").value("Invalid API key"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"))
                .andReturn();
        if (sensitiveValue != null) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(sensitiveValue);
        }
    }

    private CreatedKey createKey() throws Exception {
        Cookie session = register(cases.user());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Gateway Test Key");
        payload.put("service_group_id", serviceGroupId);
        payload.put("default_group_id", serviceGroupId);
        payload.put("allowed_model_ids", List.of());
        payload.put("allowed_group_ids", List.of(serviceGroupId));
        payload.put("ip_allowlist", List.of());
        payload.put("rpm_limit", 60);
        payload.put("tpm_limit", 200_000);
        payload.put("concurrency_limit", 5);
        payload.put("credit_limit", 100);
        payload.put("expires_at", Instant.now().plusSeconds(3600).toString());

        MvcResult result = mockMvc.perform(post("/api/v1/api-keys")
                        .cookie(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new CreatedKey(
                UUID.fromString(body.at("/data/id").asText()),
                body.at("/data/secret").asText()
        );
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
                        .content(objectMapper.writeValueAsString(Map.of(
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

    record CreatedKey(UUID id, String secret) {
    }

    record UserCase(String name, String email, String password) {
    }

    record AuthenticationCases(
            String captchaCode,
            UserCase user,
            String unknownSecret,
            String unsupportedVersionSecret,
            List<String> malformedAuthorizationValues
    ) {
    }

    @RestController
    static class GatewayAuthenticationProbeController {
        @GetMapping(PROBE_PATH)
        Map<String, Object> current(Authentication authentication) {
            NexusApiKeyPrincipal principal = (NexusApiKeyPrincipal) authentication.getPrincipal();
            return Map.of(
                    "api_key_id", principal.apiKeyId(),
                    "user_id", principal.userId(),
                    "masked_key", principal.maskedKey(),
                    "credentials_present", authentication.getCredentials() != null
            );
        }
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
