package com.nexusapi.server.modules.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.routing.mapper.GatewayRoutingMapper;
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

/** Wave 5 管理配置的权限、资金口径、凭证安全和关系一致性集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminUpstreamConfigurationIntegrationTest.FixedCaptchaConfiguration.class)
class AdminUpstreamConfigurationIntegrationTest {
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
    private GatewayRoutingMapper gatewayRoutingMapper;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE users, routing_groups, channels, suppliers, ai_models CASCADE
                """);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void ordinaryUserAndMissingCsrfCannotChangeAdminConfiguration() throws Exception {
        RegisteredUser ordinary = register("ordinary@example.com", "Ordinary User");
        mockMvc.perform(post("/api/v1/admin/models")
                        .cookie(ordinary.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload("gpt-admin-denied", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        RegisteredUser admin = registerAdmin("admin-csrf@example.com");
        mockMvc.perform(post("/api/v1/admin/models")
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload("gpt-no-csrf", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_models", Integer.class)).isZero();
    }

    @Test
    void adminCanConfigureCompleteUpstreamPathWithoutCredentialDisclosure() throws Exception {
        RegisteredUser admin = registerAdmin("admin-upstream@example.com");
        JsonNode model = createModel(admin.session(), "gpt-5.6-sol", "0.1200000000", "0.4800000000");
        UUID modelId = UUID.fromString(model.path("id").asText());

        String credential = "sk-upstream-super-sensitive-2026";
        JsonNode channel = createChannel(admin.session(), "OpenAI Official", credential);
        UUID channelId = UUID.fromString(channel.path("id").asText());
        assertThat(channel.toString()).doesNotContain(credential);
        assertThat(channel.has("credential")).isFalse();
        assertThat(channel.path("credential_configured").asBoolean()).isFalse();
        assertThat(channel.path("supplier_name").asText()).isEqualTo("OpenAI Official Supplier");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT encrypted_credential IS NULL FROM channels WHERE id = ?", Boolean.class, channelId
        )).isTrue();

        JsonNode mapping = createChannelModel(admin.session(), channelId, modelId, "gpt-5.6-sol-2026");
        UUID channelModelId = UUID.fromString(mapping.path("id").asText());
        assertThat(mapping.path("cost_input_price").decimalValue()).isEqualByComparingTo("0.0800000000");

        JsonNode group = createGroup(admin.session(), "premium", "1.250000");
        UUID groupId = UUID.fromString(group.path("id").asText());
        UUID supplierId = jdbcTemplate.queryForObject(
                "SELECT supplier_id FROM channels WHERE id = ?", UUID.class, channelId
        );
        JsonNode configured = data(saveGroupConfiguration(
                admin.session(), groupId, 0L, modelId, channelModelId, supplierId, credential
        ));
        assertThat(configured.path("default_model_ids").toString()).contains(modelId.toString());
        Map<String, Object> storedCredential = jdbcTemplate.queryForMap("""
                SELECT encrypted_credential, credential_key_version
                  FROM routing_group_supplier_credentials
                 WHERE group_id = ? AND supplier_id = ?
                """, groupId, supplierId);
        assertThat(credentialCipher.decrypt(
                (byte[]) storedCredential.get("encrypted_credential"),
                (Integer) storedCredential.get("credential_key_version")
        )).isEqualTo(credential);

        // 新模型立即进入既有公开目录，证明管理配置和用户侧读取链路已经接通。
        mockMvc.perform(get("/api/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].public_name").value("gpt-5.6-sol"))
                .andExpect(jsonPath("$.data[0].input_price").value(0.1200000000));

        String auditJson = jdbcTemplate.queryForObject(
                "SELECT string_agg(coalesce(before_data::text, '') || coalesce(after_data::text, ''), '') FROM audit_logs",
                String.class
        );
        assertThat(auditJson).doesNotContain(credential);
        // 注册管理员本身会产生认证审计；这里只统计供应商和五类上游配置审计。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action LIKE 'admin.%'",
                Integer.class
        )).isEqualTo(5);
    }

    @Test
    void staleVersionCannotOverwriteNewerModelPrice() throws Exception {
        RegisteredUser admin = registerAdmin("admin-version@example.com");
        JsonNode created = createModel(admin.session(), "versioned-model", "0.1000000000", "0.2000000000");
        UUID id = UUID.fromString(created.path("id").asText());

        mockMvc.perform(put("/api/v1/admin/models/{id}", id)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload("versioned-model", 0L, "0.3000000000", "0.6000000000"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.input_price").value(0.3000000000));

        mockMvc.perform(put("/api/v1/admin/models/{id}", id)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload("versioned-model", 0L, "9.0000000000", "9.0000000000"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_VERSION_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT input_price FROM ai_models WHERE id = ?", java.math.BigDecimal.class, id
        )).isEqualByComparingTo("0.3000000000");
    }

    @Test
    void adminCanPublishImmutablePricingVersionsAndActivateHistory() throws Exception {
        RegisteredUser admin = registerAdmin("admin-pricing-version@example.com");
        JsonNode created = createModel(admin.session(), "pricing-version-model", "0.1", "0.2");
        UUID modelId = UUID.fromString(created.path("id").asText());

        mockMvc.perform(get("/api/v1/admin/models/{id}/pricing", modelId).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").doesNotExist())
                .andExpect(jsonPath("$.data.model_version").value(0))
                .andExpect(jsonPath("$.data.versions").isEmpty());

        MvcResult firstPublish = mockMvc.perform(put("/api/v1/admin/models/{id}/pricing", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pricingPayload(0L, 4, "2.500000000001", Map.of("quality", "high")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active.version_no").value(1))
                .andExpect(jsonPath("$.data.active.billing_type").value(4))
                .andExpect(jsonPath("$.data.active.unit_price").value(2.500000000001))
                .andExpect(jsonPath("$.data.active.audio_input_token_ratio").value(10000))
                .andExpect(jsonPath("$.data.active.audio_output_token_ratio").value(10000))
                .andExpect(jsonPath("$.data.active.rules[0].match_conditions.quality").value("high"))
                .andExpect(jsonPath("$.data.active.context_tiers[0].min_input_tokens").value(0))
                .andExpect(jsonPath("$.data.model_version").value(1))
                .andReturn();
        UUID firstVersionId = UUID.fromString(data(firstPublish).path("active").path("id").asText());

        mockMvc.perform(put("/api/v1/admin/models/{id}/pricing", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pricingPayload(0L, 4, "9", Map.of("quality", "stale")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_VERSION_CONFLICT"));

        MvcResult secondPublish = mockMvc.perform(put("/api/v1/admin/models/{id}/pricing", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pricingPayload(1L, 4, "3.750000000000", Map.of("quality", "standard")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active.version_no").value(2))
                .andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.model_version").value(2))
                .andReturn();
        UUID secondVersionId = UUID.fromString(data(secondPublish).path("active").path("id").asText());

        mockMvc.perform(post("/api/v1/admin/models/{modelId}/pricing/versions/{versionId}/activate",
                            modelId, firstVersionId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("model_version", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active.version_no").value(1))
                .andExpect(jsonPath("$.data.active.unit_price").value(2.500000000001))
                .andExpect(jsonPath("$.data.model_version").value(3));

        mockMvc.perform(delete("/api/v1/admin/models/{modelId}/pricing/versions/{versionId}",
                            modelId, secondVersionId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("model_version", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active.version_no").value(1))
                .andExpect(jsonPath("$.data.versions.length()").value(1))
                .andExpect(jsonPath("$.data.model_version").value(4));

        mockMvc.perform(delete("/api/v1/admin/models/{modelId}/pricing/versions/{versionId}",
                            modelId, firstVersionId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("model_version", 4))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_versions WHERE model_id = ?", Integer.class, modelId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_rules WHERE pricing_version_id = ?",
                Integer.class, secondVersionId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_context_tiers WHERE pricing_version_id = ?",
                Integer.class, secondVersionId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM ai_models WHERE id = ?", Long.class, modelId
        )).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_id = ? AND action LIKE 'admin.model.pricing.%'",
                Integer.class, modelId.toString()
        )).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_id = ? AND action = 'admin.model.pricing.version.delete'",
                Integer.class, modelId.toString()
        )).isEqualTo(1);
    }

    @Test
    void audioModelRejectsTokenPricingAndAcceptsIndependentAudioSecond() throws Exception {
        RegisteredUser admin = registerAdmin("admin-audio-pricing@example.com");
        UUID modelId = UUID.fromString(createModelWithCapability(
                admin.session(), "audio-pricing-model", "audio"
        ).path("id").asText());

        mockMvc.perform(put("/api/v1/admin/models/{id}/pricing", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pricingPayload(0L, 4, "1", Map.of("voice", "alloy")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_versions WHERE model_id = ?", Integer.class, modelId
        )).isZero();

        mockMvc.perform(put("/api/v1/admin/models/{id}/pricing", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pricingPayload(0L, 6, "2", Map.of("voice", "alloy")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active.billing_type").value(6))
                .andExpect(jsonPath("$.data.active.billing_unit").value("audio_second"));
    }

    @Test
    void pricingRulesRejectCredentialConditionsWithoutEchoingValue() throws Exception {
        RegisteredUser admin = registerAdmin("admin-pricing-secret@example.com");
        UUID modelId = UUID.fromString(createModel(
                admin.session(), "pricing-secret-model", "0.1", "0.2"
        ).path("id").asText());

        MvcResult result = mockMvc.perform(put("/api/v1/admin/models/{id}/pricing", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(pricingPayload(
                                0L, 4, "1", Map.of("authorization", "must-not-leak")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("must-not-leak");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_versions WHERE model_id = ?", Integer.class, modelId
        )).isZero();
    }

    @Test
    void modelListCanFilterByCapabilityTypeAndProvider() throws Exception {
        RegisteredUser admin = registerAdmin("admin-model-filter@example.com");
        UUID textModelId = UUID.fromString(createModel(
                admin.session(), "text-model", "0.1", "0.2"
        ).path("id").asText());
        Map<String, Object> imagePayload = modelPayload("image-model", null, "0.3", "0.4");
        imagePayload.put("provider", "anthropic");
        imagePayload.put("capability_type", "image");
        MvcResult imageResult = mockMvc.perform(post("/api/v1/admin/models")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(imagePayload)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID imageModelId = UUID.fromString(data(imageResult).path("id").asText());
        UUID premiumGroupId = UUID.fromString(createGroup(admin.session(), "premium", "1.2").path("id").asText());
        UUID sourceSupplierId = UUID.fromString(createSupplier(
                admin.session(), "Synchronized Group Supplier"
        ).path("id").asText());
        jdbcTemplate.update("""
                UPDATE routing_groups
                   SET source_supplier_id = ?, source_group_id = 'upstream-premium',
                       source_group_name = 'Upstream Premium', sync_source = 'caicai_market',
                       source_status = 'active', source_managed = true
                 WHERE id = ?
                """, sourceSupplierId, premiumGroupId);
        jdbcTemplate.update(
                "INSERT INTO routing_group_models (id, group_id, model_id, source_type, source_status) VALUES (?, ?, ?, 'caicai_market', 'active')",
                UUID.randomUUID(), premiumGroupId, imageModelId
        );
        jdbcTemplate.update(
                "INSERT INTO routing_group_models (id, group_id, model_id, source_type, source_status) VALUES (?, ?, ?, 'caicai_market', 'stale')",
                UUID.randomUUID(), premiumGroupId, textModelId
        );

        mockMvc.perform(get("/api/v1/admin/models")
                        .cookie(admin.session())
                        .param("capability_type", "image")
                        .param("provider", "anthrop")
                        .param("service_group", "premium"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].public_name").value("image-model"))
                .andExpect(jsonPath("$.data.items[0].provider").value("anthropic"));

        // 同步分组默认勾选严格跟随上游 active 关系，stale 模型不能继续默认开放。
        mockMvc.perform(get("/api/v1/admin/groups/{id}/configuration", premiumGroupId)
                        .cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.default_model_ids.length()").value(1))
                .andExpect(jsonPath("$.data.default_model_ids[0]").value(imageModelId.toString()));
    }

    @Test
    void channelAndChannelModelListsCanFilterBySupplierAndExposeEndpointType() throws Exception {
        RegisteredUser admin = registerAdmin("admin-endpoint-filter@example.com");
        UUID textSupplierId = UUID.fromString(createSupplier(admin.session(), "Text Supplier").path("id").asText());
        UUID videoSupplierId = UUID.fromString(createSupplier(admin.session(), "Video Supplier").path("id").asText());

        Map<String, Object> textChannelPayload = channelPayload(
                textSupplierId, "Text Endpoint", "text-endpoint-secret", null
        );
        textChannelPayload.put("endpoint_type", "text");
        UUID textChannelId = UUID.fromString(data(mockMvc.perform(post("/api/v1/admin/channels")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(textChannelPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.endpoint_type").value("text"))
                .andReturn()).path("id").asText());

        Map<String, Object> videoChannelPayload = channelPayload(
                videoSupplierId, "Video Endpoint", "video-endpoint-secret", null
        );
        videoChannelPayload.put("operation_code", "video_create");
        videoChannelPayload.put("endpoint_type", "video");
        UUID videoChannelId = UUID.fromString(data(mockMvc.perform(post("/api/v1/admin/channels")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(videoChannelPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.endpoint_type").value("video"))
                .andReturn()).path("id").asText());

        UUID textModelId = UUID.fromString(createModel(
                admin.session(), "supplier-filter-text", "0.1", "0.2"
        ).path("id").asText());
        UUID videoModelId = UUID.fromString(createModel(
                admin.session(), "supplier-filter-video", "0.1", "0.2"
        ).path("id").asText());
        createChannelModel(admin.session(), textChannelId, textModelId, "upstream-filter-text");
        createChannelModel(admin.session(), videoChannelId, videoModelId, "upstream-filter-video");

        mockMvc.perform(get("/api/v1/admin/channels")
                        .cookie(admin.session())
                        .param("supplier_id", textSupplierId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("Text Endpoint"))
                .andExpect(jsonPath("$.data.items[0].endpoint_type").value("text"));

        mockMvc.perform(get("/api/v1/admin/channel-models")
                        .cookie(admin.session())
                        .param("supplier_id", videoSupplierId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchModelStatusRequiresAdminCsrfAndIsIdempotent() throws Exception {
        RegisteredUser ordinary = register("ordinary-model-batch@example.com", "Ordinary User");
        RegisteredUser admin = registerAdmin("admin-model-batch@example.com");
        UUID firstId = UUID.fromString(createModel(admin.session(), "batch-model-a", "0.1", "0.2").path("id").asText());
        UUID secondId = UUID.fromString(createModel(admin.session(), "batch-model-b", "0.1", "0.2").path("id").asText());
        Map<String, Object> payload = Map.of("model_ids", List.of(firstId, secondId), "status", "disabled");

        mockMvc.perform(post("/api/v1/admin/models/batch-status")
                        .cookie(ordinary.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/models/batch-status")
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/models/batch-status")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("disabled"))
                .andExpect(jsonPath("$.data.requested_count").value(2))
                .andExpect(jsonPath("$.data.updated_count").value(2))
                .andExpect(jsonPath("$.data.unchanged_count").value(0));

        mockMvc.perform(post("/api/v1/admin/models/batch-status")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated_count").value(0))
                .andExpect(jsonPath("$.data.unchanged_count").value(2));

        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM ai_models WHERE id IN (?, ?)", String.class, firstId, secondId
        )).containsOnly("disabled");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'admin.model.batch_status.disabled'", Integer.class
        )).isEqualTo(2);
    }

    @Test
    void removedManualRouteEndpointReturnsNotFound() throws Exception {
        RegisteredUser admin = registerAdmin("admin-mismatch@example.com");
        UUID firstModelId = UUID.fromString(createModel(admin.session(), "model-a", "0.1", "0.2").path("id").asText());
        UUID secondModelId = UUID.fromString(createModel(admin.session(), "model-b", "0.1", "0.2").path("id").asText());
        UUID channelId = UUID.fromString(createChannel(admin.session(), "Mismatch Channel", "safe-secret-value").path("id").asText());
        UUID mappingId = UUID.fromString(createChannelModel(
                admin.session(), channelId, firstModelId, "upstream-model-a"
        ).path("id").asText());
        UUID groupId = UUID.fromString(createGroup(admin.session(), "mismatch", "1.000000").path("id").asText());

        mockMvc.perform(post("/api/v1/admin/group-routes")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(routePayload(groupId, secondModelId, mappingId, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void channelModelConfigRejectsEmbeddedSecrets() throws Exception {
        RegisteredUser admin = registerAdmin("admin-config-secret@example.com");
        UUID modelId = UUID.fromString(createModel(admin.session(), "secret-model", "0.1", "0.2").path("id").asText());
        UUID channelId = UUID.fromString(createChannel(admin.session(), "Secret Config Channel", "channel-secret").path("id").asText());
        Map<String, Object> payload = channelModelPayload(channelId, modelId, "secret-upstream", null);
        payload.put("config", Map.of("headers", Map.of("access.token.value", "should-not-be-stored")));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/channel-models")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("should-not-be-stored");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'channel_models'", Integer.class)).isZero();
    }

    @Test
    void channelHealthProbePathAcceptsSafeRelativePathAndRejectsSsrfVariants() throws Exception {
        RegisteredUser admin = registerAdmin("admin-probe-path@example.com");
        UUID supplierId = UUID.fromString(createSupplier(admin.session(), "Probe Supplier").path("id").asText());
        Map<String, Object> safe = channelPayload(supplierId, "Safe Probe", "safe-probe-secret", null);
        safe.put("health_probe_path", "/health/ready");

        mockMvc.perform(post("/api/v1/admin/channels")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(safe)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.health_probe_path").value("/health/ready"));

        for (String unsafePath : List.of(
                "https://evil.example/health", "/../admin", "/models?token=x", "/models#fragment"
        )) {
            Map<String, Object> unsafe = channelPayload(
                    supplierId, "Unsafe " + UUID.randomUUID(), "unsafe-probe-secret", null
            );
            unsafe.put("health_probe_path", unsafePath);
            mockMvc.perform(post("/api/v1/admin/channels")
                            .cookie(admin.session()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(unsafe)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM channels", Integer.class)).isEqualTo(1);
    }

    @Test
    void operationCodeDeterminesRequestMethodAndRejectsUnknownCapabilities() throws Exception {
        RegisteredUser admin = registerAdmin("admin-request-method@example.com");
        UUID supplierId = UUID.fromString(createSupplier(admin.session(), "Request Method Supplier").path("id").asText());

        Map<String, Object> legacyPayload = channelPayload(
                supplierId, "Default Post Interface", "", null
        );
        mockMvc.perform(post("/api/v1/admin/channels")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(legacyPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.request_method").value("POST"));

        Map<String, Object> getPayload = channelPayload(
                supplierId, "Explicit Get Interface", "", null
        );
        getPayload.put("operation_code", "video_list");
        MvcResult created = mockMvc.perform(post("/api/v1/admin/channels")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(getPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.request_method").value("GET"))
                .andReturn();
        UUID getChannelId = UUID.fromString(data(created).path("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT request_method FROM channels WHERE id = ?", String.class, getChannelId
        )).isEqualTo("GET");

        Map<String, Object> invalidPayload = channelPayload(
                supplierId, "Invalid Delete Interface", "", null
        );
        invalidPayload.put("operation_code", "unknown_operation");
        mockMvc.perform(post("/api/v1/admin/channels")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM channels", Integer.class)).isEqualTo(2);
    }

    @Test
    void channelOperationCatalogExposesAllRegisteredGatewayCapabilities() throws Exception {
        RegisteredUser admin = registerAdmin("admin-operation-catalog@example.com");

        mockMvc.perform(get("/api/v1/admin/channel-operations").cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[0].operation_code").value("chat_completions"))
                .andExpect(jsonPath("$.data[6].operation_code").value("video_detail"))
                .andExpect(jsonPath("$.data[6].request_method").value("GET"))
                .andExpect(jsonPath("$.data[6].public_path").value("/v1/videos/{taskId}"));
    }

    @Test
    void channelConfigurationDoesNotExposeDocumentBindings() throws Exception {
        RegisteredUser admin = registerAdmin("admin-clear-channel-interfaces@example.com");
        UUID supplierId = UUID.fromString(createSupplier(
                admin.session(), "Clear Interface Supplier"
        ).path("id").asText());
        JsonNode created = createChannelForSupplier(
                admin.session(), supplierId, "Clear Interface Endpoint", "text"
        );
        UUID channelId = UUID.fromString(created.path("id").asText());

        assertThat(created.path("interface_codes").isMissingNode()).isTrue();
        assertThat(created.path("interface_code").isMissingNode()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'channels' "
                        + "AND column_name IN ('interface_code','interface_codes')",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM channels WHERE id = ?", Integer.class, channelId))
                .isOne();
    }

    @Test
    void groupConfigurationSelectsModelsAndIsolatesUpstreamCredentialsPerGroup() throws Exception {
        RegisteredUser admin = registerAdmin("admin-group-credential@example.com");
        UUID modelId = UUID.fromString(createModel(admin.session(), "group-key-model", "0.1", "0.2").path("id").asText());
        String channelDefault = "sk-channel-default-safe";
        UUID channelId = UUID.fromString(createChannel(admin.session(), "Shared Performance Channel", channelDefault).path("id").asText());
        UUID supplierId = jdbcTemplate.queryForObject(
                "SELECT supplier_id FROM channels WHERE id = ?", UUID.class, channelId
        );
        UUID mappingId = UUID.fromString(createChannelModel(
                admin.session(), channelId, modelId, "group-key-upstream"
        ).path("id").asText());
        JsonNode premiumGroup = createGroup(admin.session(), "premium-key", "1.500000");
        JsonNode standardGroup = createGroup(admin.session(), "standard-key", "1.000000");
        assertThat(premiumGroup.path("credential_configured").asBoolean()).isFalse();
        assertThat(standardGroup.path("credential_configured").asBoolean()).isFalse();
        UUID premiumGroupId = UUID.fromString(premiumGroup.path("id").asText());
        UUID standardGroupId = UUID.fromString(standardGroup.path("id").asText());

        String premiumKey = "sk-premium-independent-secret";
        String standardKey = "sk-standard-independent-secret";
        MvcResult premiumResult = saveGroupConfiguration(
                admin.session(), premiumGroupId, 0L, modelId, mappingId, supplierId, premiumKey
        );
        MvcResult standardResult = saveGroupConfiguration(
                admin.session(), standardGroupId, 0L, modelId, mappingId, supplierId, standardKey
        );
        String premiumBody = premiumResult.getResponse().getContentAsString();
        assertThat(premiumBody).doesNotContain(premiumKey).doesNotContain("encrypted_credential");
        assertThat(standardResult.getResponse().getContentAsString()).doesNotContain(standardKey);
        assertThat(data(premiumResult).path("routes").isMissingNode()).isTrue();
        assertThat(data(premiumResult).path("supplier_credentials").get(0).path("credential_active").asBoolean()).isTrue();
        assertThat(data(premiumResult).path("supplier_credentials").get(0).path("credential_configured").asBoolean()).isTrue();
        assertThat(data(premiumResult).path("supplier_credentials").get(0).has("credential")).isFalse();
        assertThat(data(premiumResult).has("channel_credentials")).isFalse();

        MvcResult groupList = mockMvc.perform(get("/api/v1/admin/groups")
                        .cookie(admin.session()).param("query", "premium-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].credential_configured").value(true))
                .andReturn();
        assertThat(groupList.getResponse().getContentAsString())
                .doesNotContain(premiumKey)
                .doesNotContain("encrypted_credential");

        Map<String, Object> storedPremiumCredential = jdbcTemplate.queryForMap("""
                SELECT encrypted_credential, credential_key_version
                  FROM routing_group_supplier_credentials
                 WHERE group_id = ? AND supplier_id = ?
                """, premiumGroupId, supplierId);
        assertThat(credentialCipher.decrypt(
                (byte[]) storedPremiumCredential.get("encrypted_credential"),
                (Integer) storedPremiumCredential.get("credential_key_version")
        )).isEqualTo(premiumKey);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT priority, weight, status
                  FROM routing_group_suppliers
                 WHERE group_id = ? AND supplier_id = ?
                """, premiumGroupId, supplierId))
                .containsEntry("priority", 10)
                .containsEntry("weight", 100)
                .containsEntry("status", "active");

        var premiumRoute = gatewayRoutingMapper.findCandidates(premiumGroupId, modelId).getFirst();
        var standardRoute = gatewayRoutingMapper.findCandidates(standardGroupId, modelId).getFirst();
        assertThat(premiumRoute.isGroupCredentialOverride()).isTrue();
        assertThat(standardRoute.isGroupCredentialOverride()).isTrue();
        assertThat(credentialCipher.decrypt(
                premiumRoute.getEncryptedCredential(), premiumRoute.getCredentialKeyVersion()
        )).isEqualTo(premiumKey);
        assertThat(credentialCipher.decrypt(
                standardRoute.getEncryptedCredential(), standardRoute.getCredentialKeyVersion()
        )).isEqualTo(standardKey);

        // 空 credential 表示保留原密钥，同时分组版本继续执行乐观锁递增。
        saveGroupConfiguration(
                admin.session(), premiumGroupId, 1L, modelId, mappingId, supplierId, null
        );
        var preservedRoute = gatewayRoutingMapper.findCandidates(premiumGroupId, modelId).getFirst();
        assertThat(credentialCipher.decrypt(
                preservedRoute.getEncryptedCredential(), preservedRoute.getCredentialKeyVersion()
        )).isEqualTo(premiumKey);

        String auditJson = jdbcTemplate.queryForObject(
                "SELECT coalesce(string_agg(coalesce(before_data::text, '') || coalesce(after_data::text, ''), ''), '') FROM audit_logs",
                String.class
        );
        assertThat(auditJson).doesNotContain(premiumKey).doesNotContain(standardKey).doesNotContain(channelDefault);

        // 供应商停用后，网关必须立即停止为新请求选择该供应商的任何路由。
        jdbcTemplate.update("""
                UPDATE suppliers
                   SET status = 'disabled', disabled_reason = 'integration test', disabled_at = now()
                 WHERE id = ?
                """, supplierId);
        assertThat(gatewayRoutingMapper.findCandidates(premiumGroupId, modelId)).isEmpty();
    }

    @Test
    void serviceGroupSavesResourcesWithoutCreatingChannelModelAndGatewayRoutesDynamically() throws Exception {
        RegisteredUser admin = registerAdmin("admin-auto-route@example.com");
        RegisteredUser user = register("user-auto-route@example.com", "Automatic Route User");
        UUID modelId = UUID.fromString(createModel(
                admin.session(), "auto-route-text-model", "0.1", "0.2"
        ).path("id").asText());
        bindModelInterface(modelId, "openai_chat");
        UUID supplierId = UUID.fromString(createSupplier(admin.session(), "Automatic Route Supplier").path("id").asText());
        UUID channelId = UUID.fromString(createChannelForSupplier(
                admin.session(), supplierId, "Automatic Text Endpoint", "text"
        ).path("id").asText());
        UUID groupId = UUID.fromString(createGroup(
                admin.session(), "auto-route", "1.000000"
        ).path("id").asText());

        MvcResult result = saveAutomaticGroupConfiguration(
                admin.session(), groupId, 0L, List.of(modelId), supplierId, "sk-auto-route-group"
        );

        assertThat(data(result).path("routes").isMissingNode()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'channel_models'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM routing_group_models
                 WHERE group_id = ? AND model_id = ?
                   AND source_type = 'manual' AND source_status = 'active'
                """, Integer.class, groupId, modelId)).isEqualTo(1);
        assertThat(gatewayRoutingMapper.findCandidates(groupId, modelId))
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.getUpstreamModel()).isEqualTo("auto-route-text-model");
                    assertThat(route.getSupplierInputPrice()).isEqualByComparingTo("0");
                });

        mockMvc.perform(get("/api/v1/service-groups").cookie(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(groupId.toString()))
                .andExpect(jsonPath("$.data[0].model_count").value(1))
                .andExpect(jsonPath("$.data[0].supplier_count").doesNotExist());
        mockMvc.perform(get("/api/v1/model-market")
                        .cookie(user.session())
                        .param("service_group_id", groupId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(modelId.toString()))
                .andExpect(jsonPath("$.data.items[0].channel_id").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].supplier_id").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].credential").doesNotExist());

        Map<String, Object> apiKey = new LinkedHashMap<>();
        apiKey.put("name", "Automatic Route Key");
        apiKey.put("service_group_id", groupId);
        apiKey.put("default_group_id", groupId);
        apiKey.put("allowed_model_ids", List.of(modelId));
        apiKey.put("allowed_group_ids", List.of(groupId));
        apiKey.put("ip_allowlist", List.of());
        apiKey.put("rpm_limit", null);
        apiKey.put("tpm_limit", null);
        apiKey.put("concurrency_limit", null);
        apiKey.put("credit_limit", null);
        apiKey.put("expires_at", null);
        mockMvc.perform(post("/api/v1/api-keys")
                        .cookie(user.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(apiKey)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.service_group_id").doesNotExist())
                .andExpect(jsonPath("$.data.key_hash").doesNotExist());

        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, modelId, "supplier-specific-model",
                "0.01", "0.005", "0.02");
        assertThat(gatewayRoutingMapper.findCandidates(groupId, modelId))
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.getUpstreamModel()).isEqualTo("supplier-specific-model");
                    assertThat(route.getSupplierInputPrice()).isEqualByComparingTo("0.01");
                    assertThat(route.getSupplierCachedInputPrice()).isEqualByComparingTo("0.005");
                    assertThat(route.getSupplierOutputPrice()).isEqualByComparingTo("0.02");
                });

        jdbcTemplate.update("UPDATE channels SET metadata = metadata #- ARRAY['upstream_models', ?] WHERE id = ?", modelId.toString(), channelId);
        assertThat(gatewayRoutingMapper.findCandidates(groupId, modelId))
                .singleElement()
                .satisfies(route -> assertThat(route.getUpstreamModel()).isEqualTo("auto-route-text-model"));

        saveAutomaticGroupConfiguration(admin.session(), groupId, 1L, List.of(modelId), supplierId, null);
        assertThat(jdbcTemplate.queryForObject("SELECT metadata->'upstream_models'->? FROM channels WHERE id = ?",
                String.class, modelId.toString(), channelId)).isNull();
        assertThat(gatewayRoutingMapper.findCandidates(groupId, modelId)).hasSize(1);
    }

    @Test
    void serviceGroupAllowsTemporarilyUnroutableModelAndBecomesAvailableAfterEndpointIsAdded() throws Exception {
        RegisteredUser admin = registerAdmin("admin-auto-route-capability@example.com");
        UUID modelId = UUID.fromString(createModelWithCapability(
                admin.session(), "auto-route-image-model", "image"
        ).path("id").asText());
        bindModelInterface(modelId, "openai_images");
        UUID supplierId = UUID.fromString(createSupplier(admin.session(), "Image Capability Supplier").path("id").asText());
        createChannelForSupplier(admin.session(), supplierId, "Text Only Endpoint", "text");
        UUID groupId = UUID.fromString(createGroup(
                admin.session(), "auto-image", "1.000000"
        ).path("id").asText());

        saveAutomaticGroupConfiguration(
                admin.session(), groupId, 0L, List.of(modelId), supplierId, "sk-image-group"
        );
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'channel_models'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM routing_group_suppliers", Integer.class)).isOne();
        assertThat(gatewayRoutingMapper.findCandidates(groupId, modelId)).isEmpty();

        createChannelForSupplier(admin.session(), supplierId, "Image Endpoint", "image");
        assertThat(gatewayRoutingMapper.findCandidates(groupId, modelId)).hasSize(1);
    }

    @Test
    void manualGroupModelDirectoryFollowsAdministratorSelection() throws Exception {
        RegisteredUser admin = registerAdmin("admin-manual-group-models@example.com");
        UUID retainedModelId = UUID.fromString(createModel(
                admin.session(), "manual-retained-model", "0.1", "0.2"
        ).path("id").asText());
        UUID removedModelId = UUID.fromString(createModel(
                admin.session(), "manual-removed-model", "0.1", "0.2"
        ).path("id").asText());
        UUID supplierId = UUID.fromString(createSupplier(
                admin.session(), "Manual Group Supplier"
        ).path("id").asText());
        createChannelForSupplier(admin.session(), supplierId, "Manual Text Endpoint", "text");
        UUID groupId = UUID.fromString(createGroup(
                admin.session(), "manual-directory", "1.000000"
        ).path("id").asText());

        saveAutomaticGroupConfiguration(
                admin.session(), groupId, 0L, List.of(retainedModelId, removedModelId),
                supplierId, "sk-manual-directory"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM routing_group_models
                 WHERE group_id = ? AND source_type = 'manual' AND source_status = 'active'
                """, Integer.class, groupId)).isEqualTo(2);

        saveAutomaticGroupConfiguration(
                admin.session(), groupId, 1L, List.of(retainedModelId), supplierId, null
        );

        assertThat(jdbcTemplate.queryForObject("""
                SELECT source_status FROM routing_group_models
                 WHERE group_id = ? AND model_id = ?
                """, String.class, groupId, retainedModelId)).isEqualTo("active");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT source_status FROM routing_group_models
                 WHERE group_id = ? AND model_id = ?
                """, String.class, groupId, removedModelId)).isEqualTo("stale");
        assertThat(gatewayRoutingMapper.findCandidates(groupId, retainedModelId)).hasSize(1);
        assertThat(gatewayRoutingMapper.findCandidates(groupId, removedModelId)).isEmpty();
        mockMvc.perform(get("/api/v1/service-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].model_count").value(1));

        saveAutomaticGroupConfiguration(
                admin.session(), groupId, 2L, List.of(), supplierId, null
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM routing_group_models
                 WHERE group_id = ? AND source_type = 'manual' AND source_status = 'active'
                """, Integer.class, groupId)).isZero();
        assertThat(gatewayRoutingMapper.findCandidates(groupId, retainedModelId)).isEmpty();
        mockMvc.perform(get("/api/v1/service-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].model_count").value(0));
    }

    @Test
    void replacingOpenModelRemovesOldCandidateAndStaleConfigurationCannotOverwrite() throws Exception {
        RegisteredUser admin = registerAdmin("admin-group-uncheck@example.com");
        UUID modelId = UUID.fromString(createModel(admin.session(), "uncheck-model", "0.1", "0.2").path("id").asText());
        UUID replacementModelId = UUID.fromString(createModel(
                admin.session(), "replacement-model", "0.1", "0.2"
        ).path("id").asText());
        UUID channelId = UUID.fromString(createChannel(admin.session(), "Uncheck Channel", "sk-uncheck-default").path("id").asText());
        UUID mappingId = UUID.fromString(createChannelModel(admin.session(), channelId, modelId, "uncheck-upstream").path("id").asText());
        UUID groupId = UUID.fromString(createGroup(admin.session(), "uncheck-group", "1.000000").path("id").asText());

        UUID supplierId = jdbcTemplate.queryForObject(
                "SELECT supplier_id FROM channels WHERE id = ?", UUID.class, channelId
        );
        saveGroupConfiguration(admin.session(), groupId, 0L, modelId, mappingId, supplierId, "sk-uncheck-group");
        assertThat(gatewayRoutingMapper.findCandidates(groupId, modelId)).hasSize(1);

        Map<String, Object> replacementSnapshot = automaticGroupConfigurationPayload(
                1L, List.of(replacementModelId), supplierId, null
        );
        mockMvc.perform(put("/api/v1/admin/groups/{id}/configuration", groupId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(replacementSnapshot)))
                .andExpect(status().isOk());
        assertThat(gatewayRoutingMapper.findCandidates(groupId, modelId)).isEmpty();
        assertThat(gatewayRoutingMapper.findCandidates(groupId, replacementModelId)).hasSize(1);

        mockMvc.perform(put("/api/v1/admin/groups/{id}/configuration", groupId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(replacementSnapshot)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_VERSION_CONFLICT"));
    }

    private MvcResult saveGroupConfiguration(
            Cookie session,
            UUID groupId,
            long groupVersion,
            UUID modelId,
            UUID mappingId,
            UUID supplierId,
            String credential
    ) throws Exception {
        Map<String, Object> supplierCredential = new LinkedHashMap<>();
        supplierCredential.put("supplier_id", supplierId);
        supplierCredential.put("priority", 10);
        supplierCredential.put("weight", 100);
        supplierCredential.put("credential", credential);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("group_version", groupVersion);
        payload.put("model_ids", List.of(modelId));
        payload.put("supplier_credentials", List.of(supplierCredential));
        return mockMvc.perform(put("/api/v1/admin/groups/{id}/configuration", groupId)
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group.version").value(groupVersion + 1))
                .andExpect(jsonPath("$.data.supplier_credentials[0].credential").doesNotExist())
                .andExpect(jsonPath("$.data.supplier_credentials[0].credential_configured").value(true))
                .andReturn();
    }

    private MvcResult saveAutomaticGroupConfiguration(
            Cookie session,
            UUID groupId,
            long groupVersion,
            List<UUID> modelIds,
            UUID supplierId,
            String credential
    ) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/groups/{id}/configuration", groupId)
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(automaticGroupConfigurationPayload(
                                groupVersion, modelIds, supplierId, credential
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group.version").value(groupVersion + 1))
                .andExpect(jsonPath("$.data.supplier_credentials[0].credential").doesNotExist())
                .andExpect(jsonPath("$.data.supplier_credentials[0].credential_configured").value(true))
                .andReturn();
    }

    private Map<String, Object> automaticGroupConfigurationPayload(
            long groupVersion,
            List<UUID> modelIds,
            UUID supplierId,
            String credential
    ) {
        Map<String, Object> supplierCredential = new LinkedHashMap<>();
        supplierCredential.put("supplier_id", supplierId);
        supplierCredential.put("priority", 10);
        supplierCredential.put("weight", 100);
        supplierCredential.put("credential", credential);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("group_version", groupVersion);
        payload.put("model_ids", modelIds);
        payload.put("supplier_credentials", List.of(supplierCredential));
        return payload;
    }

    private JsonNode createModel(Cookie session, String publicName, String inputPrice, String outputPrice) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/models")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload(publicName, null, inputPrice, outputPrice))))
                .andExpect(status().isCreated())
                .andReturn();
        return data(result);
    }

    private JsonNode createModelWithCapability(Cookie session, String publicName, String capabilityType) throws Exception {
        Map<String, Object> payload = modelPayload(publicName, null, "0.1000000000", "0.2000000000");
        payload.put("capability_type", capabilityType);
        payload.put("input_modalities", List.of(capabilityType));
        payload.put("output_modalities", List.of(capabilityType));
        MvcResult result = mockMvc.perform(post("/api/v1/admin/models")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return data(result);
    }

    private void bindModelInterface(UUID modelId, String interfaceCode) {
        jdbcTemplate.update("""
                INSERT INTO model_interfaces (model_id, interface_id)
                SELECT ?, id
                  FROM api_interfaces
                 WHERE interface_code = ?
                ON CONFLICT DO NOTHING
                """, modelId, interfaceCode);
    }

    private JsonNode createChannel(Cookie session, String name, String credential) throws Exception {
        UUID supplierId = UUID.fromString(createSupplier(session, name + " Supplier").path("id").asText());
        MvcResult result = mockMvc.perform(post("/api/v1/admin/channels")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(channelPayload(supplierId, name, credential, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.credential").doesNotExist())
                .andExpect(jsonPath("$.data.credential_configured").value(false))
                .andReturn();
        return data(result);
    }

    private JsonNode createChannelForSupplier(
            Cookie session,
            UUID supplierId,
            String name,
            String endpointType
    ) throws Exception {
        Map<String, Object> payload = channelPayload(supplierId, name, "", null);
        payload.put("operation_code", switch (endpointType) {
            case "image" -> "image_generations";
            case "video" -> "video_create";
            case "audio" -> "audio_speech";
            case "embedding" -> "embeddings";
            default -> "chat_completions";
        });
        payload.put("endpoint_type", endpointType);
        MvcResult result = mockMvc.perform(post("/api/v1/admin/channels")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return data(result);
    }

    private JsonNode createSupplier(Cookie session, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/suppliers")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "code", "supplier-" + UUID.randomUUID(),
                                "name", name,
                                "supplier_type", "direct",
                                "status", "active",
                                "billing_mode", "postpaid",
                                "settlement_currency", "USD",
                                "metadata", Map.of()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return data(result);
    }

    private JsonNode createChannelModel(Cookie session, UUID channelId, UUID modelId, String upstreamModel) throws Exception {
        UUID id = UUID.randomUUID();
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, modelId, upstreamModel,
                "0.0800000000", "0.0400000000", "0.3200000000");
        return objectMapper.readTree("{\"id\":\"" + id + "\",\"cost_input_price\":0.08}");
    }

    private JsonNode createGroup(Cookie session, String code, String multiplier) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/groups")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(groupPayload(code, multiplier, null))))
                .andExpect(status().isCreated())
                .andReturn();
        return data(result);
    }

    private Map<String, Object> modelPayload(String publicName, Long version) {
        return modelPayload(publicName, version, "0.1200000000", "0.4800000000");
    }

    private Map<String, Object> modelPayload(String publicName, Long version, String inputPrice, String outputPrice) {
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
        payload.put("input_price", inputPrice);
        payload.put("output_price", outputPrice);
        payload.put("cached_input_price", "0.0300000000");
        payload.put("price_unit", "million_tokens");
        payload.put("public_visible", true);
        payload.put("status", "active");
        if (version != null) {
            payload.put("version", version);
        }
        return payload;
    }

    /** 价格发布请求是完整快照；测试同时覆盖条件规则和长上下文分档。 */
    private Map<String, Object> pricingPayload(
            long modelVersion,
            int billingType,
            String unitPrice,
            Map<String, String> conditions
    ) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("priority", 10);
        rule.put("name", "测试计价规则");
        rule.put("match_conditions", conditions);
        rule.put("billing_type", null);
        rule.put("unit_price", null);
        rule.put("price_multiplier", "1.2500000000");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("billing_type", billingType);
        payload.put("unit_price", unitPrice);
        payload.put("display_original_price", "5.000000000000");
        payload.put("input_token_ratio", 10000);
        payload.put("output_token_ratio", 40000);
        payload.put("audio_input_token_ratio", 10000);
        payload.put("audio_output_token_ratio", 10000);
        payload.put("cached_input_token_ratio", 2500);
        payload.put("cache_write_5m_token_ratio", 0);
        payload.put("cache_write_1h_token_ratio", 0);
        payload.put("charge_desc", "平台积分按计费规则结算");
        payload.put("context_tier_mode", billingType == 4 ? 0 : 0);
        payload.put("unmatched_behavior", "base");
        payload.put("rules", List.of(rule));
        payload.put("context_tiers", billingType == 4 ? List.of(Map.of(
                "priority", 10,
                "min_input_tokens", 0,
                "max_input_tokens", 128000,
                "input_ratio", 10000,
                "output_ratio", 40000,
                "cached_input_ratio", 2500
        )) : List.of());
        payload.put("change_note", "集成测试发布");
        payload.put("model_version", modelVersion);
        return payload;
    }

    private Map<String, Object> channelPayload(UUID supplierId, String name, String credential, Long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supplier_id", supplierId);
        payload.put("name", name);
        payload.put("provider_type", "openai");
        payload.put("operation_code", "chat_completions");
        payload.put("endpoint_type", "text");
        payload.put("request_method", "POST");
        payload.put("base_url", "https://api.openai.com/v1");
        payload.put("proxy_url", null);
        payload.put("status", "active");
        payload.put("timeout_ms", 120_000);
        payload.put("concurrency_limit", 100);
        payload.put("priority", 10);
        payload.put("weight", 100);
        if (version != null) {
            payload.put("version", version);
        }
        return payload;
    }

    private Map<String, Object> channelModelPayload(UUID channelId, UUID modelId, String upstreamModel, Long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel_id", channelId);
        payload.put("model_id", modelId);
        payload.put("upstream_model", upstreamModel);
        payload.put("priority", 10);
        payload.put("weight", 100);
        payload.put("cost_input_price", "0.0800000000");
        payload.put("cost_cached_input_price", "0.0400000000");
        payload.put("cost_output_price", "0.3200000000");
        payload.put("status", "active");
        payload.put("config", Map.of("organization", "default"));
        if (version != null) {
            payload.put("version", version);
        }
        return payload;
    }

    private Map<String, Object> groupPayload(String code, String multiplier, Long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", code + " Group");
        payload.put("description", "Wave 5 integration group");
        payload.put("price_multiplier", multiplier);
        payload.put("audience", "all");
        payload.put("status", "active");
        if (version != null) {
            payload.put("version", version);
        }
        return payload;
    }

    private Map<String, Object> routePayload(UUID groupId, UUID modelId, UUID channelModelId, Long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("group_id", groupId);
        payload.put("model_id", modelId);
        payload.put("channel_model_id", channelModelId);
        payload.put("priority", 10);
        payload.put("weight", 100);
        payload.put("retryable", true);
        payload.put("status", "active");
        if (version != null) {
            payload.put("version", version);
        }
        return payload;
    }

    private RegisteredUser registerAdmin(String email) throws Exception {
        RegisteredUser user = register(email, "Wave 5 Admin");
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
