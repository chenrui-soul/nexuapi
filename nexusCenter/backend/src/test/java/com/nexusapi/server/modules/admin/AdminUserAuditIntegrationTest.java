package com.nexusapi.server.modules.admin;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 用户权限管理和只读审计查询的权限、脱敏、并发与自保护集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminUserAuditIntegrationTest.FixedCaptchaConfiguration.class)
class AdminUserAuditIntegrationTest {
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
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void ordinaryUserCannotReadUsersOrAuditLogs() throws Exception {
        RegisteredUser ordinary = register("ordinary-user@example.com", "Ordinary User");

        mockMvc.perform(get("/api/v1/admin/users").cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get("/api/v1/admin/audit-logs").cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));
    }

    @Test
    void adminUserListReturnsOnlyMaskedEmailAndSupportsExactEmailLookup() throws Exception {
        RegisteredUser admin = registerAdmin("admin-list@example.com", "List Admin");
        String targetEmail = "sensitive.person@example.com";
        RegisteredUser target = register(targetEmail, "Sensitive Person");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/users")
                        .cookie(admin.session())
                        .param("query", targetEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(target.id().toString()))
                .andExpect(jsonPath("$.data.items[0].masked_email").value("se***on@e***.com"))
                .andExpect(jsonPath("$.data.items[0].password_hash").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].email_ciphertext").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].email_lookup_hash").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(targetEmail);
    }

    @Test
    void updateRequiresCsrfUsesOptimisticLockAndWritesQueryableAudit() throws Exception {
        RegisteredUser admin = registerAdmin("admin-update@example.com", "Update Admin");
        RegisteredUser target = register("target-user@example.com", "Target User");
        Map<String, Object> payload = Map.of(
                "display_name", "Target Operator",
                "status", "active",
                "roles", List.of("user", "operator"),
                "version", 0
        );

        mockMvc.perform(put("/api/v1/admin/users/{id}", target.id())
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        mockMvc.perform(put("/api/v1/admin/users/{id}", target.id())
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("Target Operator"))
                .andExpect(jsonPath("$.data.roles[0]").value("operator"))
                .andExpect(jsonPath("$.data.roles[1]").value("user"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put("/api/v1/admin/users/{id}", target.id())
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_VERSION_CONFLICT"));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .cookie(admin.session())
                        .param("query", "admin.user.update")
                        .param("resource_type", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("admin.user.update"))
                .andExpect(jsonPath("$.data.items[0].resource_id").value(target.id().toString()))
                .andExpect(jsonPath("$.data.items[0].after_data.display_name").value("Target Operator"));
    }

    @Test
    void currentAdminCannotDisableSelfOrRemoveAdminRole() throws Exception {
        RegisteredUser admin = registerAdmin("admin-self@example.com", "Self Admin");

        mockMvc.perform(put("/api/v1/admin/users/{id}", admin.id())
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "display_name", "Self Admin",
                                "status", "suspended",
                                "roles", List.of("user", "admin"),
                                "version", 0
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_SELF_PROTECTION"));

        mockMvc.perform(put("/api/v1/admin/users/{id}", admin.id())
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "display_name", "Self Admin",
                                "status", "active",
                                "roles", List.of("user"),
                                "version", 0
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_SELF_PROTECTION"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_roles WHERE user_id = ? AND role_code = 'admin'",
                Integer.class,
                admin.id()
        )).isEqualTo(1);
    }

    private RegisteredUser registerAdmin(String email, String name) throws Exception {
        RegisteredUser user = register(email, name);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'admin')", user.id());
        return user;
    }

    private RegisteredUser register(String email, String name) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk())
                .andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id").asText();
        MvcResult registration = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/register")
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
