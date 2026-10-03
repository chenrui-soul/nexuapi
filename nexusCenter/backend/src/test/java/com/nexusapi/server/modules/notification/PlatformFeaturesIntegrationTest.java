package com.nexusapi.server.modules.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PlatformFeaturesIntegrationTest.FixedCaptcha.class)
class PlatformFeaturesIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    UUID admin;
    UUID alice;
    UUID bob;

    @TestConfiguration
    static class FixedCaptcha {
        @Bean @Primary CaptchaCodeGenerator fixedCaptchaCodeGenerator() { return length -> "2345"; }
    }
    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        jdbc.execute("TRUNCATE announcements CASCADE");
        jdbc.update("UPDATE platform_settings SET registration_enabled = true, manual_recharge_enabled = false, version = 0 WHERE id = 1");
        admin = createUser(); alice = createUser(); bob = createUser();
    }
    UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, display_name, email_ciphertext, email_lookup_hash, password_hash) VALUES (?, ?, ?, ?, ?)",
                id, "test", new byte[]{1}, id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "test");
        jdbc.update("INSERT INTO wallet_accounts(user_id) VALUES (?)", id);
        return id;
    }
    RequestPostProcessor as(UUID id, boolean isAdmin) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new NexusUserPrincipal(id, "test", List.of(isAdmin ? "admin" : "user")), null,
                List.of(new SimpleGrantedAuthority(isAdmin ? "ROLE_ADMIN" : "ROLE_USER"))));
    }
    String body(Object value) throws Exception { return json.writeValueAsString(value); }

    @Test
    void publicationReadIsolationWithdrawalAndPermissions() throws Exception {
        mvc.perform(get("/api/v1/announcements")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/announcements").with(as(alice, false)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("title", "bad", "content", "bad"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/announcements").with(as(admin, true))
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("title", "test", "content", "test"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/announcements").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("title", " ", "content", "test"))))
                .andExpect(status().isBadRequest());
        String created = mvc.perform(post("/api/v1/admin/announcements").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("title", "Maintenance", "content", "Line 1\n<script>alert(1)</script>"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).at("/data/id").asText();
        mvc.perform(get("/api/v1/announcements").with(as(alice, false))).andExpect(jsonPath("$.data.announcements.total").value(0));
        mvc.perform(post("/api/v1/announcements/" + id + "/read").with(as(alice, false)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/admin/announcements/" + id).with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("title", "Maintenance updated", "content", "Ready", "version", 0))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/announcements/" + id + "/publish").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/announcements/" + id + "/publish").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("published"));
        mvc.perform(get("/api/v1/announcements").with(as(alice, false)))
                .andExpect(jsonPath("$.data.unread_count").value(1)).andExpect(jsonPath("$.data.announcements.items[0].title").value("Maintenance updated"));
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/v1/announcements/" + id + "/read").with(as(alice, false)).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/announcements").with(as(alice, false))).andExpect(jsonPath("$.data.unread_count").value(0));
        mvc.perform(get("/api/v1/announcements").with(as(bob, false))).andExpect(jsonPath("$.data.unread_count").value(1));
        UUID newUser = createUser();
        mvc.perform(get("/api/v1/announcements").with(as(newUser, false))).andExpect(jsonPath("$.data.unread_count").value(1));
        mvc.perform(post("/api/v1/admin/announcements/" + id + "/withdraw").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/announcements").with(as(bob, false)))
                .andExpect(jsonPath("$.data.unread_count").value(0)).andExpect(jsonPath("$.data.announcements.total").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_type = 'announcement'", Long.class)).isEqualTo(4);
    }

    @Test
    void registrationToggleIsImmediateEnforcedAndVersioned() throws Exception {
        mvc.perform(get("/api/v1/system/registration")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.registration_enabled").value(true));
        mvc.perform(get("/api/v1/admin/settings").with(as(alice, false))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/settings").with(as(alice, false)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"registration_enabled\":false,\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/settings").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"registration_enabled\":null,\"version\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/settings").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"registration_enabled\":false,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get("/api/v1/system/registration")).andExpect(jsonPath("$.data.registration_enabled").value(false));
        Map<String, Object> registration = Map.of("name", "new user", "email", "new-platform@example.com",
                "password", "StrongPassword123!", "challenge_id", UUID.randomUUID().toString(), "captcha_code", "2345");
        long users = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(registration)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_REGISTRATION_DISABLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(users);
        mvc.perform(put("/api/v1/admin/settings").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"registration_enabled\":true,\"version\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/admin/settings").with(as(admin, true)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"registration_enabled\":true,\"version\":1}"))
                .andExpect(status().isOk());
        JsonNode captcha = json.readTree(mvc.perform(get("/api/v1/auth/captcha?scene=register"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var valid = new java.util.HashMap<>(registration);
        valid.put("challenge_id", captcha.get("challenge_id").asText());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(valid)))
                .andExpect(status().isCreated());
    }
    @Test
    void manualRechargeIsProtectedValidatedAuditedAndIdempotent() throws Exception {
        String path = "/api/v1/admin/users/" + alice + "/recharge";
        String request = body(Map.of("request_id", UUID.randomUUID(), "credits", "25.50", "reason", "Offline payment"));
        mvc.perform(post(path).with(as(admin, true)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ADMIN_RECHARGE_DISABLED"));
        mvc.perform(put("/api/v1/admin/settings").with(as(admin, true)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"registration_enabled\":true,\"manual_recharge_enabled\":true,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manual_recharge_enabled").value(true));
        mvc.perform(post(path).with(as(alice, false)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(as(admin, true)).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        for (String amount : List.of("0", "-1", "1.001", "1000000001")) {
            mvc.perform(post(path).with(as(admin, true)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("request_id", UUID.randomUUID(), "credits", amount, "reason", "test")))).andExpect(status().isBadRequest());
        }
        mvc.perform(post(path).with(as(admin, true)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(false));
        mvc.perform(post(path).with(as(admin, true)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        mvc.perform(post(path).with(as(admin, true)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request.replace("25.50", "30.00")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/users/" + bob + "/recharge").with(as(admin, true)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/admin/users/" + alice + "/wallet").with(as(alice, false))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT permanent_credits FROM wallet_accounts WHERE user_id = ?", java.math.BigDecimal.class, alice)).isEqualByComparingTo("25.50");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_ledger WHERE source_type = 'admin_recharge'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'user.credits.recharge'", Long.class)).isEqualTo(1);
        jdbc.update("UPDATE platform_settings SET manual_recharge_enabled = false");
        mvc.perform(post(path).with(as(admin, true)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("request_id", UUID.randomUUID(), "credits", "10", "reason", "test"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentRetriesCreditExactlyOnce() throws Exception {
        jdbc.update("UPDATE platform_settings SET manual_recharge_enabled = true");
        String request = body(Map.of("request_id", UUID.randomUUID(), "credits", "7.25", "reason", "Concurrent retry"));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/admin/users/" + alice + "/recharge").with(as(admin, true)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            for (var task : tasks) assertThat(task.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT permanent_credits FROM wallet_accounts WHERE user_id = ?", java.math.BigDecimal.class, alice)).isEqualByComparingTo("7.25");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_ledger WHERE source_type = 'admin_recharge'", Long.class)).isEqualTo(1);
    }

    @Test
    void nextUnreadIncludesAnnouncementsBeyondCurrentPage() throws Exception {
        UUID oldest = UUID.randomUUID();
        jdbc.update("INSERT INTO announcements(id,title,content,status,created_by,published_at) VALUES (?, 'Old unread','Body','published',?, now() - interval '2 days')", oldest, admin);
        for (int i = 0; i < 11; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO announcements(id,title,content,status,created_by,published_at) VALUES (?, 'Read','Body','published',?, now())", id, admin);
            jdbc.update("INSERT INTO announcement_reads(announcement_id,user_id) VALUES (?,?)", id, alice);
        }
        mvc.perform(get("/api/v1/announcements?page=1&page_size=10").with(as(alice, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unread_count").value(1))
                .andExpect(jsonPath("$.data.next_unread.id").value(oldest.toString()));
    }

}
