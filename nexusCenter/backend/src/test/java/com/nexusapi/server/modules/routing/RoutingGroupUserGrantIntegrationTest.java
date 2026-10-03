package com.nexusapi.server.modules.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.apikey.service.ApiKeyAuthenticationService;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 特殊服务分组从管理员授权到用户查询、创建 Key 和运行时撤权的完整安全回归。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoutingGroupUserGrantIntegrationTest.FixedCaptchaConfiguration.class)
class RoutingGroupUserGrantIntegrationTest {
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
    private ApiKeyAuthenticationService authenticationService;

    private UUID groupId;

    @BeforeEach
    void resetState() {
        // 同一 Maven 进程可能先运行其他服务分组测试，必须同时清理分组数据，
        // 否则残留的公开分组会让“授权前不可见”断言产生顺序相关的偶发失败。
        jdbcTemplate.execute("TRUNCATE TABLE users, routing_groups CASCADE");
        groupId = TestServiceGroupFixture.createPublicGroup(jdbcTemplate);
        jdbcTemplate.update("UPDATE routing_groups SET audience = 'assigned' WHERE id = ?", groupId);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void onlyAdminWithCsrfCanAssignActiveUsersToAssignedGroup() throws Exception {
        RegisteredUser admin = registerAdmin("grant-admin@example.com", "Grant Admin");
        RegisteredUser ordinary = register("ordinary@example.com", "Ordinary User");
        RegisteredUser target = register("sensitive.target@example.com", "Sensitive Target");
        RegisteredUser suspended = register("suspended@example.com", "Suspended User");
        jdbcTemplate.update("UPDATE users SET status = 'suspended' WHERE id = ?", suspended.id());
        String payload = json(Map.of("user_ids", List.of(target.id())));

        mockMvc.perform(put("/api/v1/admin/groups/{id}/user-grants", groupId)
                        .cookie(ordinary.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        mockMvc.perform(put("/api/v1/admin/groups/{id}/user-grants", groupId)
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/groups/{id}/user-grants", groupId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("user_ids", List.of(suspended.id())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        MvcResult result = mockMvc.perform(put("/api/v1/admin/groups/{id}/user-grants", groupId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].user_id").value(target.id().toString()))
                .andExpect(jsonPath("$.data[0].masked_email").value("se***et@e***.com"))
                .andExpect(jsonPath("$.data[0].email_ciphertext").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("sensitive.target@example.com");
        mockMvc.perform(get("/api/v1/admin/groups").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].authorized_user_count").value(1));

        String audit = jdbcTemplate.queryForObject("""
                SELECT after_data::text FROM audit_logs
                 WHERE action = 'admin.routing_group.user_grants.update'
                 ORDER BY id DESC LIMIT 1
                """, String.class);
        assertThat(audit).contains(target.id().toString())
                .doesNotContain("sensitive.target@example.com", "masked_email", "password", "token");
    }

    @Test
    void ordinaryAndInternalGroupsRejectUserGrantMaintenance() throws Exception {
        RegisteredUser admin = registerAdmin("grant-boundary@example.com", "Boundary Admin");
        RegisteredUser target = register("boundary-target@example.com", "Boundary Target");
        jdbcTemplate.update("UPDATE routing_groups SET audience = 'all' WHERE id = ?", groupId);

        mockMvc.perform(put("/api/v1/admin/groups/{id}/user-grants", groupId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("user_ids", List.of(target.id())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        jdbcTemplate.update("UPDATE routing_groups SET audience = 'internal' WHERE id = ?", groupId);
        mockMvc.perform(get("/api/v1/admin/groups/{id}/user-grants", groupId).cookie(admin.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void assignedGroupIsInvisibleUntilGrantedAndRevocationInvalidatesExistingKey() throws Exception {
        RegisteredUser admin = registerAdmin("visibility-admin@example.com", "Visibility Admin");
        RegisteredUser target = register("visible.target@example.com", "Visible Target");
        RegisteredUser other = register("other.target@example.com", "Other Target");

        mockMvc.perform(get("/api/v1/service-groups"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/service-groups").cookie(target.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/model-market")
                        .cookie(target.session()).param("service_group_id", groupId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GROUP_NOT_AVAILABLE"));
        createKey(other.session())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GROUP_NOT_AVAILABLE"));

        replaceGrants(admin.session(), List.of(target.id())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/service-groups").cookie(target.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(groupId.toString()));
        mockMvc.perform(get("/api/v1/service-groups").cookie(other.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/model-market")
                        .cookie(target.session()).param("service_group_id", groupId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));

        MvcResult created = createKey(target.session())
                .andExpect(status().isCreated())
                .andReturn();
        String secret = objectMapper.readTree(created.getResponse().getContentAsString()).at("/data/secret").asText();
        assertThat(authenticationService.authenticate(secret)).isNotNull();

        replaceGrants(admin.session(), List.of()).andExpect(status().isOk());
        assertThat(authenticationService.authenticate(secret)).isNull();
        mockMvc.perform(get("/api/v1/model-market")
                        .cookie(target.session()).param("service_group_id", groupId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GROUP_NOT_AVAILABLE"));
    }

    private org.springframework.test.web.servlet.ResultActions replaceGrants(Cookie adminSession, List<UUID> userIds) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/groups/{id}/user-grants", groupId)
                .cookie(adminSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("user_ids", userIds))));
    }

    private org.springframework.test.web.servlet.ResultActions createKey(Cookie session) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Assigned Group Key");
        payload.put("service_group_id", groupId);
        payload.put("default_group_id", groupId);
        payload.put("allowed_model_ids", List.of());
        payload.put("allowed_group_ids", List.of(groupId));
        payload.put("ip_allowlist", List.of());
        payload.put("rpm_limit", 60);
        payload.put("tpm_limit", 200_000);
        payload.put("concurrency_limit", 5);
        payload.put("credit_limit", 100);
        payload.put("expires_at", Instant.now().plusSeconds(3600).toString());
        return mockMvc.perform(post("/api/v1/api-keys").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(payload)));
    }

    private RegisteredUser registerAdmin(String email, String name) throws Exception {
        RegisteredUser user = register(email, name);
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
                                "name", name, "email", email, "password", PASSWORD,
                                "challenge_id", challengeId, "captcha_code", CAPTCHA_CODE
                        ))))
                .andExpect(status().isCreated()).andReturn();
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
