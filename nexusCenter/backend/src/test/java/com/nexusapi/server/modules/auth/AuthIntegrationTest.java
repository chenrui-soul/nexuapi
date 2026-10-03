package com.nexusapi.server.modules.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.AuthRateLimitService;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.auth.service.PasswordResetDelivery;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthIntegrationTest.FixedCaptchaConfiguration.class)
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private AuthRateLimitService rateLimitService;

    @Autowired
    private RecordingPasswordResetDelivery passwordResetDelivery;

    private AuthCases cases;

    @BeforeEach
    void resetState() throws Exception {
        cases = objectMapper.readValue(Files.readString(Path.of("references/auth-cases.json")), AuthCases.class);
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        passwordResetDelivery.clear();
    }

    @Test
    void registrationSessionCurrentUserAndLogoutFormACompleteSecureFlow() throws Exception {
        String challengeId = issueCaptcha("register");

        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", cases.user().name(),
                                "email", cases.user().email(),
                                "password", cases.user().password(),
                                "challenge_id", challengeId,
                                "captcha_code", cases.captchaCode()
                        ))))
                .andExpect(status().isCreated())
                .andExpect(cookie().exists("NEXUS_SESSION"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.email").value(cases.user().email()))
                .andExpect(jsonPath("$.data.user.roles[0]").value("user"))
                .andExpect(jsonPath("$.data.expires_in").value(1800))
                .andReturn();

        JsonNode registrationBody = objectMapper.readTree(registration.getResponse().getContentAsString());
        UUID userId = UUID.fromString(registrationBody.at("/data/user/id").asText());
        Cookie sessionCookie = registration.getResponse().getCookie("NEXUS_SESSION");
        assertThat(sessionCookie).isNotNull();

        byte[] emailCiphertext = jdbcTemplate.queryForObject(
                "SELECT email_ciphertext FROM users WHERE id = ?",
                byte[].class,
                userId
        );
        byte[] emailLookupHash = jdbcTemplate.queryForObject(
                "SELECT email_lookup_hash FROM users WHERE id = ?",
                byte[].class,
                userId
        );
        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String.class,
                userId
        );
        assertThat(new String(emailCiphertext, StandardCharsets.UTF_8)).doesNotContain(cases.user().email());
        assertThat(emailLookupHash).hasSize(32);
        assertThat(passwordHash).startsWith("$argon2");

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.email").value(cases.user().email()))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.email_ciphertext").doesNotExist());

        mockMvc.perform(post("/api/v1/auth/logout").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logged_out").value(true));

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_SESSION_EXPIRED"));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE actor_user_id = ?",
                Integer.class,
                userId
        );
        assertThat(auditCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void captchaIsOneTimeAndDuplicateEmailIsRejected() throws Exception {
        String challengeId = issueCaptcha("register");
        Map<String, Object> payload = Map.of(
                "name", cases.user().name(),
                "email", cases.user().email(),
                "password", cases.user().password(),
                "challenge_id", challengeId,
                "captcha_code", cases.captchaCode()
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_CAPTCHA_INVALID"));

        String secondChallenge = issueCaptcha("register");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", cases.user().name(),
                                "email", cases.user().email().toUpperCase(),
                                "password", cases.user().password(),
                                "challenge_id", secondChallenge,
                                "captcha_code", cases.captchaCode()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_EMAIL_EXISTS"));
    }

    @Test
    void loginUsesGenericCredentialErrorsAndRememberedRedisSession() throws Exception {
        registerUser(cases.user());

        String wrongPasswordChallenge = issueCaptcha("login");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", cases.user().email(),
                                "password", cases.invalidPassword(),
                                "challenge_id", wrongPasswordChallenge,
                                "captcha_code", cases.captchaCode(),
                                "remember", false
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("邮箱或密码不正确"));

        String missingUserChallenge = issueCaptcha("login");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "missing@example.com",
                                "password", cases.invalidPassword(),
                                "challenge_id", missingUserChallenge,
                                "captcha_code", cases.captchaCode(),
                                "remember", false
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("邮箱或密码不正确"));

        String validChallenge = issueCaptcha("login");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "  " + cases.user().email().toUpperCase() + "  ",
                                "password", cases.user().password(),
                                "challenge_id", validChallenge,
                                "captcha_code", cases.captchaCode().toLowerCase(),
                                "remember", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("NEXUS_SESSION"))
                .andExpect(jsonPath("$.data.expires_in").value(2_592_000))
                .andExpect(jsonPath("$.data.user.email").value(cases.user().email()));
    }

    @Test
    void logoutRequiresCsrfProtection() throws Exception {
        Cookie session = registerUser(cases.user());

        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("NEXUS_XSRF_TOKEN", true))
                .andExpect(jsonPath("$.data.header").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/logout").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk());
    }

    @Test
    void successfulLoginCannotResetTheSharedIpFailureLimit() {
        String ipAddress = "203.0.113.10";
        String firstAccountHash = "account-a";
        String secondAccountHash = "account-b";

        for (int attempt = 0; attempt < 7; attempt++) {
            rateLimitService.recordLoginFailure(ipAddress, firstAccountHash);
        }
        rateLimitService.clearAccountLoginFailures(firstAccountHash);
        rateLimitService.recordLoginFailure(ipAddress, secondAccountHash);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> rateLimitService.assertLoginAllowed(ipAddress, "account-c"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.AUTH_TOO_MANY_ATTEMPTS));
    }

    @Test
    void passwordRecoveryUsesOneTimeMailCodeAndRevokesOldSessions() throws Exception {
        Cookie oldSession = registerUser(cases.user());
        String challengeId = issueCaptcha("password_reset");

        MvcResult forgot = mockMvc.perform(post("/api/v1/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", cases.user().email(),
                                "challenge_id", challengeId,
                                "captcha_code", cases.captchaCode()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reset_id").isNotEmpty())
                .andExpect(jsonPath("$.data.expires_in").value(600))
                .andExpect(jsonPath("$.data.verification_code").doesNotExist())
                .andReturn();

        assertThat(passwordResetDelivery.email()).isEqualTo(cases.user().email());
        assertThat(passwordResetDelivery.code()).matches("\\d{6}");
        String resetId = objectMapper.readTree(forgot.getResponse().getContentAsString()).at("/data/reset_id").asText();
        String newPassword = "Recovered2026";

        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", cases.user().email(),
                                "reset_id", resetId,
                                "verification_code", passwordResetDelivery.code(),
                                "new_password", newPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password_reset").value(true));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT email_verified_at IS NOT NULL FROM users WHERE email_lookup_hash IS NOT NULL LIMIT 1",
                Boolean.class
        )).isTrue();

        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", cases.user().email(),
                                "reset_id", resetId,
                                "verification_code", passwordResetDelivery.code(),
                                "new_password", "Another2026"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_RESET_INVALID"));

        mockMvc.perform(get("/api/v1/auth/me").cookie(oldSession))
                .andExpect(status().isUnauthorized());
        loginExpecting(cases.user().email(), cases.user().password(), false, 401);
        loginExpecting(cases.user().email(), newPassword, false, 200);
    }

    @Test
    void passwordRecoveryDoesNotRevealWhetherEmailExists() throws Exception {
        registerUser(cases.user());
        String challengeId = issueCaptcha("password_reset");
        MvcResult response = mockMvc.perform(post("/api/v1/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "missing@example.com",
                                "challenge_id", challengeId,
                                "captcha_code", cases.captchaCode()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reset_id").isNotEmpty())
                .andExpect(jsonPath("$.data.expires_in").value(600))
                .andReturn();

        assertThat(passwordResetDelivery.email()).isNull();
        String resetId = objectMapper.readTree(response.getResponse().getContentAsString()).at("/data/reset_id").asText();
        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "missing@example.com",
                                "reset_id", resetId,
                                "verification_code", "000000",
                                "new_password", "Missing2026"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_RESET_INVALID"));
    }

    @Test
    void accountSecurityPasswordChangeAndSessionRevocationRequireCsrf() throws Exception {
        Cookie firstSession = registerUser(cases.user());
        Cookie secondSession = loginExpecting(cases.user().email(), cases.user().password(), true, 200);

        mockMvc.perform(get("/api/v1/auth/security").cookie(secondSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(cases.user().email()))
                .andExpect(jsonPath("$.data.password_changed_at").isNotEmpty())
                .andExpect(jsonPath("$.data.active_session_count").value(2))
                .andExpect(jsonPath("$.data.email_verified").value(false))
                .andExpect(jsonPath("$.data.session_ids").doesNotExist());

        Map<String, String> changeBody = Map.of(
                "current_password", cases.user().password(),
                "new_password", "Changed2026"
        );
        mockMvc.perform(post("/api/v1/auth/password/change").cookie(secondSession)
                        .contentType(MediaType.APPLICATION_JSON).content(json(changeBody)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/password/change").cookie(secondSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "current_password", "Wrong2026",
                                "new_password", "Changed2026"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_CURRENT_PASSWORD_INVALID"));

        mockMvc.perform(post("/api/v1/auth/password/change").cookie(secondSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(changeBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password_changed").value(true))
                .andExpect(jsonPath("$.data.revoked_sessions").value(1));

        mockMvc.perform(get("/api/v1/auth/me").cookie(firstSession)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(secondSession)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/security").cookie(secondSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email_verified").value(false));

        mockMvc.perform(post("/api/v1/auth/sessions/revoke-others").cookie(secondSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/sessions/revoke-others").cookie(secondSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked_count").value(0));
    }

    private Cookie registerUser(UserCase user) throws Exception {
        String challenge = issueCaptcha("register");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", user.name(),
                                "email", user.email(),
                                "password", user.password(),
                                "challenge_id", challenge,
                                "captcha_code", cases.captchaCode()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getCookie("NEXUS_SESSION");
    }

    private String issueCaptcha(String scene) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", scene))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image").value(org.hamcrest.Matchers.startsWith("data:image/svg+xml;base64,")))
                .andExpect(jsonPath("$.data.expires_in").value(300))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/challenge_id")
                .asText();
    }

    private Cookie loginExpecting(String email, String password, boolean remember, int expectedStatus) throws Exception {
        String challenge = issueCaptcha("login");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", password,
                                "challenge_id", challenge,
                                "captcha_code", cases.captchaCode(),
                                "remember", remember
                        ))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return result.getResponse().getCookie("NEXUS_SESSION");
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

        @Bean
        @Primary
        RecordingPasswordResetDelivery recordingPasswordResetDelivery() {
            return new RecordingPasswordResetDelivery();
        }
    }

    static class RecordingPasswordResetDelivery implements PasswordResetDelivery {
        private String email;
        private String code;

        @Override public void assertAvailable() { }
        @Override public void sendCode(String normalizedEmail, String verificationCode, Duration ttl) {
            this.email = normalizedEmail;
            this.code = verificationCode;
        }
        String email() { return email; }
        String code() { return code; }
        void clear() { email = null; code = null; }
    }
}
