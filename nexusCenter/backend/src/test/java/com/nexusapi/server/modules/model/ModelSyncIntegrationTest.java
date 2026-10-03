package com.nexusapi.server.modules.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.model.service.ModelSyncService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 模型市场同步的权限、配置、完整分页、安全默认值和失败原子性集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ModelSyncIntegrationTest.FixedCaptchaConfiguration.class)
class ModelSyncIntegrationTest {
    private static final String CAPTCHA_CODE = "ACEF";
    private static final String PASSWORD = "StrongPassword!2026";
    private static final String LOCK_KEY = "nexus:model-sync:lock";
    private static final String PRICE_GROUP_TOKEN = "test-price-group-token";
    private static final UUID SUPPLIER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String UPSTREAM_GROUP_ID = "f67fa30f-ce43-432c-9dbd-165da64880a5";
    private static final WireMockServer UPSTREAM = new WireMockServer(options().dynamicPort());

    static {
        UPSTREAM.start();
    }

    @DynamicPropertySource
    static void modelSyncProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.model-sync.market-url", () -> UPSTREAM.baseUrl() + "/api/models/market");
        registry.add("nexus.model-sync.price-groups-url", () -> UPSTREAM.baseUrl() + "/api/price-groups/visible");
        registry.add("nexus.model-sync.price-group-token", () -> PRICE_GROUP_TOKEN);
        registry.add("nexus.model-sync.source-supplier-code", () -> "caicai");
        registry.add("nexus.model-sync.page-size", () -> 2);
        registry.add("nexus.model-sync.max-retries", () -> 0);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ModelSyncService syncService;

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE model_sync_runs, routing_group_models, routing_groups,
                               users, ai_models, channels, suppliers CASCADE
                """);
        jdbcTemplate.update("""
                INSERT INTO suppliers (
                    id, code, name, supplier_type, status, health_status,
                    billing_mode, settlement_currency, metadata
                ) VALUES (?, 'caicai', '菜菜 API', 'aggregator', 'active', 'healthy', 'prepaid', 'USD', '{}'::jsonb)
                """, SUPPLIER_ID);
        jdbcTemplate.update("""
                INSERT INTO model_sync_settings (
                    id, enabled, interval_minutes, next_run_at, updated_by, version, created_at, updated_at
                ) VALUES (1, false, 360, NULL, NULL, 0, now(), now())
                ON CONFLICT (id) DO UPDATE
                   SET enabled = false, interval_minutes = 360, next_run_at = NULL,
                       updated_by = NULL, version = 0, updated_at = now()
                """);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        UPSTREAM.resetAll();
    }

    @Test
    void onlyAdminWithCsrfCanChangeSettingsAndImmediateRunUsesGlobalLock() throws Exception {
        RegisteredUser ordinary = register("sync-user@example.com");
        RegisteredUser admin = registerAdmin("sync-admin@example.com");

        mockMvc.perform(get("/api/v1/admin/models/sync").cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));
        mockMvc.perform(put("/api/v1/admin/models/sync")
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", true, "interval_minutes", 60, "version", 0))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/models/sync")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", true, "interval_minutes", 60, "version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.interval_minutes").value(60))
                .andExpect(jsonPath("$.data.next_run_at").isNotEmpty())
                .andExpect(jsonPath("$.data.version").value(1));

        redis.opsForValue().set(LOCK_KEY, "another-worker");
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MODEL_SYNC_IN_PROGRESS"));
    }

    @Test
    void manualSyncImportsSafeDefaultsAndNeverOverwritesManualBusinessConfiguration() throws Exception {
        RegisteredUser admin = registerAdmin("sync-import@example.com");
        createManualModel(admin.session(), "Alpha");
        UUID existingGroupId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO routing_groups (
                    id, code, name, description, price_multiplier, audience, status
                ) VALUES (?, 'gpt_tj', 'gpt特价', '本地人工售价策略', 0.140000, 'assigned', 'active')
                """, existingGroupId);
        stubPriceGroups(List.of(priceGroup(UPSTREAM_GROUP_ID, "gpt特价", 2_000)));
        stubPage(1, 3, List.of(
                marketModelWithGroup("Alpha", "Alpha From Market", List.of("streaming", "tools"), 1, 10_000),
                marketModelWithGroup("CaseModel", "Upper Case", List.of("streaming"), 1, 10_000)
        ));
        stubPage(2, 3, List.of(
                marketModelWithGroup(
                        "casemodel", "Lower Case", List.of("streaming"), 1, 10_000,
                        Map.of("headers", Map.of("access.token.value", "must-never-be-stored"))
                )
        ));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/models/sync/run")
                        .cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.fetched_count").value(3))
                .andExpect(jsonPath("$.data.inserted_count").value(1))
                .andExpect(jsonPath("$.data.updated_count").value(1))
                .andExpect(jsonPath("$.data.skipped_count").value(1))
                .andExpect(jsonPath("$.data.group_total").value(1))
                .andExpect(jsonPath("$.data.group_inserted_count").value(0))
                .andExpect(jsonPath("$.data.group_updated_count").value(1))
                .andExpect(jsonPath("$.data.group_unchanged_count").value(0))
                .andExpect(jsonPath("$.data.group_stale_count").value(0))
                .andReturn();

        Map<String, Object> manual = jdbcTemplate.queryForMap("""
                SELECT provider, status, public_visible, input_price, output_price,
                       source_managed, sync_source
                  FROM ai_models
                 WHERE public_name = 'Alpha'
                """);
        assertThat(manual.get("provider")).isEqualTo("manual-provider");
        assertThat(manual.get("status")).isEqualTo("active");
        assertThat(manual.get("public_visible")).isEqualTo(true);
        assertThat(manual.get("input_price").toString()).isEqualTo("0.1200000000");
        assertThat(manual.get("output_price").toString()).isEqualTo("0.4800000000");
        assertThat(manual.get("source_managed")).isEqualTo(false);
        assertThat(manual.get("sync_source")).isEqualTo("caicai_market");

        Map<String, Object> imported = jdbcTemplate.queryForMap("""
                SELECT public_name, provider, status, public_visible, supports_streaming, supports_tools,
                       supports_structured_output, input_price, output_price, cached_input_price,
                       source_managed, source_metadata::text AS source_metadata
                  FROM ai_models
                 WHERE lower(public_name) = 'casemodel'
                """);
        assertThat(imported.get("public_name")).isEqualTo("casemodel");
        assertThat(imported.get("provider")).isEqualTo("unknown");
        assertThat(imported.get("status")).isEqualTo("active");
        assertThat(imported.get("public_visible")).isEqualTo(true);
        assertThat(imported.get("supports_streaming")).isEqualTo(true);
        assertThat(imported.get("supports_tools")).isEqualTo(true);
        assertThat(imported.get("supports_structured_output")).isEqualTo(true);
        assertThat(imported.get("input_price").toString()).isEqualTo("0E-10");
        assertThat(imported.get("output_price").toString()).isEqualTo("0E-10");
        assertThat(imported.get("cached_input_price").toString()).isEqualTo("0E-10");
        assertThat(imported.get("source_managed")).isEqualTo(true);
        assertThat(imported.get("source_metadata").toString())
                .doesNotContain("must-never-be-stored", "access.token.value", "authorization", "secret");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("must-never-be-stored", PRICE_GROUP_TOKEN);

        Map<String, Object> group = jdbcTemplate.queryForMap("""
                SELECT price_multiplier, audience, status, source_group_id, source_rate,
                       source_status, source_supplier_id, source_metadata::text AS source_metadata
                  FROM routing_groups WHERE id = ?
                """, existingGroupId);
        assertThat(group.get("price_multiplier").toString()).isEqualTo("0.140000");
        assertThat(group.get("audience")).isEqualTo("assigned");
        assertThat(group.get("status")).isEqualTo("active");
        assertThat(group.get("source_group_id")).isEqualTo(UPSTREAM_GROUP_ID);
        assertThat(group.get("source_rate")).isEqualTo(2_000L);
        assertThat(group.get("source_status")).isEqualTo("active");
        assertThat(group.get("source_supplier_id")).isEqualTo(SUPPLIER_ID);
        assertThat(group.get("source_metadata").toString()).doesNotContain(PRICE_GROUP_TOKEN, "authorization");

        Map<String, Object> membership = jdbcTemplate.queryForMap("""
                SELECT rgm.source_status, rgm.upstream_last_status, rgm.upstream_success_rate,
                       rgm.upstream_consecutive_failures
                  FROM routing_group_models rgm
                  JOIN ai_models m ON m.id = rgm.model_id
                 WHERE rgm.group_id = ? AND m.public_name = 'Alpha'
                """, existingGroupId);
        assertThat(membership.get("source_status")).isEqualTo("active");
        assertThat(((Number) membership.get("upstream_last_status")).intValue()).isEqualTo(1);
        assertThat(membership.get("upstream_success_rate")).isEqualTo(10_000);
        assertThat(membership.get("upstream_consecutive_failures")).isEqualTo(0);

        mockMvc.perform(get("/api/v1/admin/models")
                        .cookie(admin.session()).param("query", "Alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].service_groups[0].id").value(existingGroupId.toString()))
                .andExpect(jsonPath("$.data.items[0].service_groups[0].name").value("gpt特价"));

        // 后续快照移除分组和关联时只标记 stale，不删除历史也不停用本地售价策略。
        UPSTREAM.resetAll();
        stubPriceGroups(List.of());
        stubPage(1, 1, List.of(marketModel("Alpha", "Alpha From Market", 3, 4, List.of("streaming"), null)));
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group_total").value(0))
                .andExpect(jsonPath("$.data.group_stale_count").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_status FROM routing_groups WHERE id = ?", String.class, existingGroupId
        )).isEqualTo("stale");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM routing_groups WHERE id = ?", String.class, existingGroupId
        )).isEqualTo("active");
        assertThat(jdbcTemplate.queryForList(
                "SELECT source_status FROM routing_group_models WHERE group_id = ?", String.class, existingGroupId
        )).hasSize(2).containsOnly("stale");
    }

    @Test
    void serviceGroupRunCountsDistinguishInsertUnchangedUpdateAndStale() throws Exception {
        RegisteredUser admin = registerAdmin("sync-group-counts@example.com");
        stubPriceGroups(List.of(priceGroup(UPSTREAM_GROUP_ID, "gpt特价", 2_000)));
        stubPage(1, 1, List.of(marketModelWithGroup(
                "group-count-model", "Group Count Model", List.of("streaming"), 1, 10_000
        )));

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group_total").value(1))
                .andExpect(jsonPath("$.data.group_inserted_count").value(1))
                .andExpect(jsonPath("$.data.group_updated_count").value(0))
                .andExpect(jsonPath("$.data.group_unchanged_count").value(0))
                .andExpect(jsonPath("$.data.group_stale_count").value(0));

        Map<String, Object> insertedGroup = jdbcTemplate.queryForMap("""
                SELECT audience, status
                  FROM routing_groups
                 WHERE source_group_id = ?
                """, UPSTREAM_GROUP_ID);
        assertThat(insertedGroup.get("audience")).isEqualTo("all");
        assertThat(insertedGroup.get("status")).isEqualTo("disabled");

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group_inserted_count").value(0))
                .andExpect(jsonPath("$.data.group_updated_count").value(0))
                .andExpect(jsonPath("$.data.group_unchanged_count").value(1))
                .andExpect(jsonPath("$.data.group_stale_count").value(0));

        UPSTREAM.resetAll();
        stubPriceGroups(List.of(priceGroup(UPSTREAM_GROUP_ID, "gpt特价", 2_500)));
        stubPage(1, 1, List.of(marketModelWithGroup(
                "group-count-model", "Group Count Model", List.of("streaming"), 1, 10_000
        )));
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group_updated_count").value(1))
                .andExpect(jsonPath("$.data.group_unchanged_count").value(0));

        UPSTREAM.resetAll();
        stubPriceGroups(List.of());
        stubPage(1, 1, List.of(marketModel(
                "group-count-model", "Group Count Model", 3, 4, List.of("streaming"), null
        )));
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group_total").value(0))
                .andExpect(jsonPath("$.data.group_stale_count").value(1));
    }

    @Test
    void nullAndEmptyGroupArraysAreBothTreatedAsNoMembership() throws Exception {
        RegisteredUser admin = registerAdmin("sync-empty-groups@example.com");
        stubPriceGroups(List.of());
        Map<String, Object> legacyModel = marketModel(
                "legacy-ungrouped", "Legacy Ungrouped", 3, 4, List.of("streaming"), null
        );
        legacyModel.put("available_group_ids", null);
        legacyModel.put("available_groups", List.of());
        legacyModel.put("group_statuses", List.of());
        stubPage(1, 1, List.of(legacyModel));

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.fetched_count").value(1))
                .andExpect(jsonPath("$.data.inserted_count").value(1));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM routing_group_models", Integer.class
        )).isZero();
    }

    @Test
    void recentFailedGroupHealthStatusIsPersistedWithoutRejectingTheSnapshot() throws Exception {
        RegisteredUser admin = registerAdmin("sync-failed-group-health@example.com");
        stubPriceGroups(List.of(priceGroup(UPSTREAM_GROUP_ID, "gpt特价", 2_000)));
        Map<String, Object> model = marketModelWithGroup(
                "failed-group-health", "Failed Group Health", List.of("streaming"), 2, 0
        );
        Map<String, Object> status = new LinkedHashMap<>((Map<String, Object>) ((List<?>) model.get("group_statuses")).getFirst());
        status.put("history", List.of(0));
        status.put("consecutive_failures", 99);
        model.put("group_statuses", List.of(status));
        stubPage(1, 1, List.of(model));

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT rgm.upstream_last_status
                  FROM routing_group_models rgm
                  JOIN ai_models m ON m.id = rgm.model_id
                 WHERE m.public_name = 'failed-group-health'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void syncCreatesImmutablePricingWithRulesAndInclusiveContextTiers() throws Exception {
        RegisteredUser admin = registerAdmin("sync-pricing@example.com");
        stubPriceGroups(List.of());
        Map<String, Object> model = marketModel(
                "gpt-5.6-sol", "GPT 5.6 SOL", 3, 4, List.of("text", "reasoning"), null
        );
        model.put("unit_price", 2_500_000L);
        model.put("display_original_price", 8_750_000L);
        model.put("output_token_ratio", 60_000L);
        model.put("audio_input_token_ratio", 30_000L);
        model.put("audio_output_token_ratio", 70_000L);
        model.put("cached_input_token_ratio", 1_000L);
        model.put("context_tier_mode", 1);
        model.put("pricing_rules", List.of(Map.of(
                "rule_name", "priority-tier",
                "billing_type", 4,
                "unit_price", 5_000_000L,
                "conditions", Map.of("service_tier", "priority", "duration", 10, "mode", true)
        )));
        model.put("context_tiers", List.of(
                Map.of(
                        "up_to", 272_000L,
                        "input_ratio", 0L,
                        "output_ratio", 0L,
                        "cached_input_ratio", 0L,
                        "cache_write_5m_ratio", 0L,
                        "cache_write_1h_ratio", 0L
                ),
                Map.of(
                        "up_to", 0L,
                        "input_ratio", 20_000L,
                        "output_ratio", 90_000L,
                        "cached_input_ratio", 2_000L,
                        "cache_write_5m_ratio", 25_000L,
                        "cache_write_1h_ratio", 0L
                )
        ));
        model.put("context_tiers_active", true);
        stubPage(1, 1, List.of(model));

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());

        UUID modelId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_models WHERE public_name = 'gpt-5.6-sol'", UUID.class
        );
        Map<String, Object> active = jdbcTemplate.queryForMap("""
                SELECT v.unit_price, v.display_original_price, v.audio_input_token_ratio,
                       v.audio_output_token_ratio, v.source_type, v.source_hash,
                       m.pricing_source_managed, m.active_pricing_version_id
                  FROM ai_models m
                  JOIN model_pricing_versions v ON v.id = m.active_pricing_version_id
                 WHERE m.id = ?
                """, modelId);
        assertThat((java.math.BigDecimal) active.get("unit_price")).isEqualByComparingTo("5");
        assertThat((java.math.BigDecimal) active.get("display_original_price")).isEqualByComparingTo("17.5");
        assertThat(active.get("audio_input_token_ratio")).isEqualTo(30_000L);
        assertThat(active.get("audio_output_token_ratio")).isEqualTo(70_000L);
        assertThat(active.get("source_type")).isEqualTo("caicai_market");
        assertThat(active.get("source_hash").toString()).hasSize(64);
        assertThat(active.get("pricing_source_managed")).isEqualTo(true);

        Map<String, Object> rule = jdbcTemplate.queryForMap("""
                SELECT priority, unit_price, match_conditions->>'duration' AS duration,
                       match_conditions->>'mode' AS mode
                  FROM model_pricing_rules
                 WHERE pricing_version_id = ?
                """, active.get("active_pricing_version_id"));
        assertThat(rule.get("priority")).isEqualTo(10);
        assertThat((java.math.BigDecimal) rule.get("unit_price")).isEqualByComparingTo("10");
        assertThat(rule.get("duration")).isEqualTo("10");
        assertThat(rule.get("mode")).isEqualTo("true");

        List<Map<String, Object>> tiers = jdbcTemplate.queryForList("""
                SELECT min_input_tokens, max_input_tokens, input_ratio, output_ratio,
                       cached_input_ratio, cache_write_5m_ratio
                  FROM model_context_tiers
                 WHERE pricing_version_id = ?
                 ORDER BY priority
                """, active.get("active_pricing_version_id"));
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).get("min_input_tokens")).isEqualTo(0L);
        assertThat(tiers.get(0).get("max_input_tokens")).isEqualTo(272_001L);
        assertThat(tiers.get(0).get("output_ratio")).isEqualTo(60_000L);
        assertThat(tiers.get(1).get("min_input_tokens")).isEqualTo(272_001L);
        assertThat(tiers.get(1).get("max_input_tokens")).isNull();
        assertThat(tiers.get(1).get("output_ratio")).isEqualTo(90_000L);
        assertThat(tiers.get(1).get("cache_write_5m_ratio")).isEqualTo(25_000L);

        // 相同有效价格哈希重复同步只刷新模型观测，不重复创建不可变版本。
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_versions WHERE model_id = ?", Integer.class, modelId
        )).isEqualTo(1);

        model.put("unit_price", 3_000_000L);
        stubPage(1, 1, List.of(model));
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_versions WHERE model_id = ?", Integer.class, modelId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_price FROM ai_models WHERE id = ?", java.math.BigDecimal.class, modelId
        )).isEqualByComparingTo("6");
    }

    @Test
    void upstreamAudioSecondTypeThreeIsStoredAsLocalTypeSix() throws Exception {
        RegisteredUser admin = registerAdmin("sync-audio-second@example.com");
        stubPriceGroups(List.of());
        Map<String, Object> model = marketModel(
                "audio-second-model", "Audio Second", 4, 3, List.of("audio"), null
        );
        stubPage(1, 1, List.of(model));

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());

        Map<String, Object> saved = jdbcTemplate.queryForMap("""
                SELECT capability_type, price_unit
                  FROM ai_models
                 WHERE public_name = 'audio-second-model'
                """);
        assertThat(saved.get("capability_type")).isEqualTo("audio");
        assertThat(saved.get("price_unit")).isEqualTo("second");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT billing_type FROM ai_models WHERE public_name = 'audio-second-model'",
                Integer.class
        )).isEqualTo(6);
    }

    @Test
    void nullContextTierArrayUsesLegacySingleTierFieldsOrRepresentsNoTier() throws Exception {
        RegisteredUser admin = registerAdmin("sync-legacy-context-tier@example.com");
        stubPriceGroups(List.of());
        JsonNode fixture = objectMapper.readTree(
                Path.of("references", "v21-context-tier-compat-fixtures.json").toFile()
        );
        JsonNode legacyFixture = fixture.path("legacy_single_tier");
        JsonNode emptyFixture = fixture.path("legacy_empty");

        Map<String, Object> legacyModel = marketModel(
                "legacy-context-tier", "Legacy Context Tier", 3, 4, List.of("text"), null
        );
        applyLegacyContextTierFixture(legacyModel, legacyFixture);
        Map<String, Object> emptyModel = marketModel(
                "legacy-empty-tier", "Legacy Empty Tier", 3, 4, List.of("text"), null
        );
        applyLegacyContextTierFixture(emptyModel, emptyFixture);
        stubPage(1, 2, List.of(legacyModel, emptyModel));

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.fetched_count").value(2));

        List<Map<String, Object>> tiers = jdbcTemplate.queryForList("""
                SELECT t.min_input_tokens, t.max_input_tokens, t.input_ratio, t.output_ratio
                  FROM model_context_tiers t
                  JOIN model_pricing_versions v ON v.id = t.pricing_version_id
                  JOIN ai_models m ON m.id = v.model_id
                 WHERE m.public_name = 'legacy-context-tier'
                 ORDER BY t.priority
                """);
        JsonNode expectedTiers = legacyFixture.path("expected_tiers");
        assertThat(tiers).hasSize(expectedTiers.size());
        for (int index = 0; index < expectedTiers.size(); index++) {
            JsonNode expected = expectedTiers.get(index);
            assertThat(tiers.get(index).get("min_input_tokens")).isEqualTo(expected.path("min_input_tokens").asLong());
            if (expected.get("max_input_tokens").isNull()) {
                assertThat(tiers.get(index).get("max_input_tokens")).isNull();
            } else {
                assertThat(tiers.get(index).get("max_input_tokens")).isEqualTo(expected.path("max_input_tokens").asLong());
            }
            assertThat(tiers.get(index).get("input_ratio")).isEqualTo(expected.path("input_ratio").asLong());
            assertThat(tiers.get(index).get("output_ratio")).isEqualTo(expected.path("output_ratio").asLong());
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM model_context_tiers t
                  JOIN model_pricing_versions v ON v.id = t.pricing_version_id
                  JOIN ai_models m ON m.id = v.model_id
                 WHERE m.public_name = 'legacy-empty-tier'
                """, Integer.class)).isEqualTo(emptyFixture.path("expected_tier_count").asInt());
    }

    @Test
    void manualPricingIsProtectedUntilAdministratorSwitchesBackToFollowing() throws Exception {
        RegisteredUser admin = registerAdmin("sync-pricing-mode@example.com");
        stubPriceGroups(List.of());
        Map<String, Object> model = marketModel("pricing-mode-model", "Pricing Mode", 3, 4, List.of("text"), null);
        stubPage(1, 1, List.of(model));
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());

        UUID modelId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_models WHERE public_name = 'pricing-mode-model'", UUID.class
        );
        long version = jdbcTemplate.queryForObject("SELECT version FROM ai_models WHERE id = ?", Long.class, modelId);
        mockMvc.perform(put("/api/v1/admin/models/{id}/pricing", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualPricingPayload(version, "99"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pricing_mode").value("manual"));

        model.put("unit_price", 4_000_000L);
        stubPage(1, 1, List.of(model));
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_price FROM ai_models WHERE id = ?", java.math.BigDecimal.class, modelId
        )).isEqualByComparingTo("99");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_versions WHERE model_id = ?", Integer.class, modelId
        )).isEqualTo(2);

        long manualVersion = jdbcTemplate.queryForObject("SELECT version FROM ai_models WHERE id = ?", Long.class, modelId);
        mockMvc.perform(put("/api/v1/admin/models/{id}/pricing/source-mode", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("follow_upstream", true, "model_version", manualVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pricing_mode").value("follow_upstream"));

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_price FROM ai_models WHERE id = ?", java.math.BigDecimal.class, modelId
        )).isEqualByComparingTo("8");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_versions WHERE model_id = ?", Integer.class, modelId
        )).isEqualTo(3);
    }

    @Test
    void unchangedUpstreamHashRestoresSourceVersionWhenActiveVersionIsInconsistent() throws Exception {
        RegisteredUser admin = registerAdmin("sync-pricing-self-heal@example.com");
        stubPriceGroups(List.of());
        Map<String, Object> model = marketModel(
                "pricing-self-heal", "Pricing Self Heal", 3, 4, List.of("text"), null
        );
        stubPage(1, 1, List.of(model));
        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());

        UUID modelId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_models WHERE public_name = 'pricing-self-heal'", UUID.class
        );
        UUID sourceVersionId = jdbcTemplate.queryForObject(
                "SELECT active_pricing_version_id FROM ai_models WHERE id = ?", UUID.class, modelId
        );
        long modelVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM ai_models WHERE id = ?", Long.class, modelId
        );
        mockMvc.perform(put("/api/v1/admin/models/{id}/pricing", modelId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(manualPricingPayload(modelVersion, "99"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pricing_mode").value("manual"));

        // 模拟旧 Shadow 数据或异常恢复留下的不一致状态：跟随开关为 true，当前却仍指向人工版本。
        jdbcTemplate.update("UPDATE ai_models SET pricing_source_managed = true WHERE id = ?", modelId);

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk());

        Map<String, Object> restored = jdbcTemplate.queryForMap("""
                SELECT m.active_pricing_version_id, m.unit_price, v.source_type
                  FROM ai_models m
                  JOIN model_pricing_versions v ON v.id = m.active_pricing_version_id
                 WHERE m.id = ?
                """, modelId);
        assertThat(restored.get("active_pricing_version_id")).isEqualTo(sourceVersionId);
        assertThat((java.math.BigDecimal) restored.get("unit_price")).isEqualByComparingTo("5");
        assertThat(restored.get("source_type")).isEqualTo("caicai_market");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_pricing_versions WHERE model_id = ?", Integer.class, modelId
        )).isEqualTo(2);
    }

    @Test
    void invalidPricingSnapshotRollsBackWholeModelBatchWithoutEchoingSensitiveValues() throws Exception {
        RegisteredUser admin = registerAdmin("sync-invalid-pricing@example.com");
        stubPriceGroups(List.of());
        Map<String, Object> model = marketModel("invalid-pricing", "Invalid Pricing", 1, 2, List.of("image"), null);
        model.put("pricing_rules", List.of(Map.of(
                "rule_name", "unsafe-rule",
                "billing_type", 2,
                "unit_price", 500_000L,
                "conditions", Map.of("authorization", "Bearer must-never-leak")
        )));
        stubPage(1, 1, List.of(model));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/models/sync/run")
                        .cookie(admin.session()).with(csrf()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("MODEL_SYNC_FAILED"))
                .andReturn();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_models", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM model_pricing_versions", Integer.class)).isZero();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("must-never-leak", "Bearer");
    }

    @Test
    void incompleteOrBusinessFailedPageDoesNotCommitPartialSnapshot() throws Exception {
        RegisteredUser admin = registerAdmin("sync-failure@example.com");
        stubPriceGroups(List.of(priceGroup(UPSTREAM_GROUP_ID, "gpt特价", 2_000)));
        stubPage(1, 3, List.of(
                marketModel("first-page-a", "First A", 3, 4, List.of(), null),
                marketModel("first-page-b", "First B", 3, 4, List.of(), null)
        ));
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/models/market"))
                .withQueryParam("page", equalTo("2"))
                .withQueryParam("page_size", equalTo("2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":400,\"msg\":\"invalid secret-token detail\"}")));

        MvcResult result = mockMvc.perform(post("/api/v1/admin/models/sync/run")
                        .cookie(admin.session()).with(csrf()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("MODEL_SYNC_FAILED"))
                .andReturn();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_models", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM routing_groups", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM model_sync_runs ORDER BY started_at DESC LIMIT 1", String.class
        )).isEqualTo("failed");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("invalid secret-token detail", "secret-token");
    }

    @Test
    void missingListPricingFieldIsFilledFromExactModelDetail() throws Exception {
        RegisteredUser admin = registerAdmin("sync-detail-fallback@example.com");
        stubPriceGroups(List.of());
        JsonNode fixtures = detailFallbackFixtures();
        Map<String, Object> incomplete = objectMapper.convertValue(
                fixtures.path("list_incomplete"), new com.fasterxml.jackson.core.type.TypeReference<>() {}
        );
        Map<String, Object> detail = objectMapper.convertValue(
                fixtures.path("detail_complete"), new com.fasterxml.jackson.core.type.TypeReference<>() {}
        );
        stubPage(1, 1, List.of(incomplete));
        stubDetail("detail-fallback-model", detail);

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"));

        Map<String, Object> saved = jdbcTemplate.queryForMap("""
                SELECT v.unit_price, v.display_original_price, v.output_token_ratio,
                       v.audio_input_token_ratio, v.audio_output_token_ratio,
                       v.cache_write_5m_token_ratio, v.cache_write_1h_token_ratio, v.charge_desc
                  FROM ai_models m
                  JOIN model_pricing_versions v ON v.id = m.active_pricing_version_id
                 WHERE m.public_name = 'detail-fallback-model'
                """);
        assertThat((java.math.BigDecimal) saved.get("unit_price")).isEqualByComparingTo("6");
        assertThat((java.math.BigDecimal) saved.get("display_original_price")).isEqualByComparingTo("18");
        assertThat(saved.get("output_token_ratio")).isEqualTo(50_000L);
        assertThat(saved.get("audio_input_token_ratio")).isEqualTo(30_000L);
        assertThat(saved.get("audio_output_token_ratio")).isEqualTo(70_000L);
        assertThat(saved.get("cache_write_5m_token_ratio")).isEqualTo(12_500L);
        assertThat(saved.get("cache_write_1h_token_ratio")).isEqualTo(20_000L);
        assertThat(saved.get("charge_desc")).isEqualTo("详情接口完整计费信息");
        UPSTREAM.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                        urlPathEqualTo("/api/models/market"))
                .withQueryParam("model_name", equalTo("detail-fallback-model")));
    }

    @Test
    void mismatchedExactModelDetailFailsBeforeAnySnapshotIsWritten() throws Exception {
        RegisteredUser admin = registerAdmin("sync-detail-mismatch@example.com");
        stubPriceGroups(List.of());
        JsonNode fixtures = detailFallbackFixtures();
        Map<String, Object> incomplete = objectMapper.convertValue(
                fixtures.path("list_incomplete"), new com.fasterxml.jackson.core.type.TypeReference<>() {}
        );
        Map<String, Object> mismatch = objectMapper.convertValue(
                fixtures.path("detail_mismatch"), new com.fasterxml.jackson.core.type.TypeReference<>() {}
        );
        stubPage(1, 1, List.of(incomplete));
        stubDetail("detail-fallback-model", mismatch);

        mockMvc.perform(post("/api/v1/admin/models/sync/run").cookie(admin.session()).with(csrf()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("MODEL_SYNC_FAILED"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_models", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM model_pricing_versions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM model_sync_runs ORDER BY started_at DESC LIMIT 1", String.class
        )).isEqualTo("failed");
    }

    @Test
    void enabledDueScheduleRunsOnceAndAdvancesNextExecutionTime() throws Exception {
        stubPriceGroups(List.of());
        Map<String, Object> scheduled = marketModel("scheduled-model", "Scheduled", 3, 4, List.of(), null);
        scheduled.remove("audio_input_token_ratio");
        Map<String, Object> scheduledDetail = new LinkedHashMap<>(scheduled);
        scheduledDetail.put("audio_input_token_ratio", 25_000L);
        stubPage(1, 1, List.of(scheduled));
        stubDetail("scheduled-model", scheduledDetail);
        jdbcTemplate.update("""
                UPDATE model_sync_settings
                   SET enabled = true, interval_minutes = 15, next_run_at = now() - interval '1 minute'
                 WHERE id = 1
                """);

        assertThat(syncService.runScheduledIfDue()).isTrue();
        assertThat(syncService.runScheduledIfDue()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_sync_runs WHERE trigger_type = 'scheduled' AND status = 'succeeded'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_run_at > now() FROM model_sync_settings WHERE id = 1", Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT v.audio_input_token_ratio
                  FROM ai_models m
                  JOIN model_pricing_versions v ON v.id = m.active_pricing_version_id
                 WHERE m.public_name = 'scheduled-model'
                """, Long.class)).isEqualTo(25_000L);
    }

    private void stubPage(int page, int total, List<Map<String, Object>> models) throws Exception {
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/models/market"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .withQueryParam("page_size", equalTo("2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(json(Map.of(
                                "code", 0,
                                "msg", "ok",
                                "data", Map.of(
                                        "list", models,
                                        "groups", List.of(),
                                        "total", total,
                                        "page", page,
                                        "page_size", 2
                                )
                        )))));
    }

    private void stubDetail(String modelName, Map<String, Object> model) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", List.of(model));
        data.put("groups", List.of());
        data.put("total", 1);
        data.put("page", 1);
        data.put("page_size", 20);
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/models/market"))
                .withQueryParam("model_name", equalTo(modelName))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(json(Map.of("code", 0, "msg", "ok", "data", data)))));
    }

    private JsonNode detailFallbackFixtures() throws Exception {
        return objectMapper.readTree(Path.of(
                "references", "model-market-detail-fallback-fixtures.json"
        ).toFile());
    }

    private void stubPriceGroups(List<Map<String, Object>> groups) throws Exception {
        UPSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/price-groups/visible"))
                .withHeader("Authorization", equalTo("Bearer " + PRICE_GROUP_TOKEN))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(json(Map.of("code", 0, "msg", "ok", "data", groups)))));
    }

    private Map<String, Object> priceGroup(String id, String name, long rate) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", id);
        group.put("name", name);
        group.put("description", "Upstream group description");
        group.put("rate", rate);
        group.put("billing_type", 0);
        group.put("rpm_limit", 120);
        group.put("daily_limit", 0);
        group.put("tpm_limit", 80_000);
        group.put("is_default", false);
        group.put("is_visible", true);
        group.put("sort_order", 10);
        group.put("status", 1);
        return group;
    }

    private Map<String, Object> marketModelWithGroup(
            String name,
            String showName,
            List<String> capabilities,
            int lastStatus,
            int successRate
    ) {
        return marketModelWithGroup(name, showName, capabilities, lastStatus, successRate, null);
    }

    private Map<String, Object> marketModelWithGroup(
            String name,
            String showName,
            List<String> capabilities,
            int lastStatus,
            int successRate,
            Map<String, Object> paramRules
    ) {
        Map<String, Object> model = marketModel(name, showName, 3, 4, capabilities, paramRules);
        model.put("available_groups", List.of("gpt特价"));
        model.put("available_group_ids", List.of(UPSTREAM_GROUP_ID));
        model.put("group_statuses", List.of(Map.of(
                "group_id", UPSTREAM_GROUP_ID,
                "group_name", "gpt特价",
                "last_status", lastStatus,
                "success_rate", successRate,
                "consecutive_failures", 0,
                "history", List.of(lastStatus),
                "last_checked_at", 1_786_949_817L,
                "last_success_at", lastStatus == 1 ? 1_786_949_817L : 0
        )));
        return model;
    }

    private Map<String, Object> marketModel(
            String name,
            String showName,
            int modelType,
            int billingType,
            List<String> capabilities,
            Map<String, Object> paramRules
    ) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model_name", name);
        model.put("show_name", showName);
        model.put("model_type", modelType);
        model.put("billing_type", billingType);
        model.put("unit_price", 2_500_000L);
        model.put("display_original_price", 0L);
        model.put("input_token_ratio", 10_000L);
        model.put("output_token_ratio", 10_000L);
        model.put("audio_input_token_ratio", 10_000L);
        model.put("audio_output_token_ratio", 10_000L);
        model.put("cached_input_token_ratio", 10_000L);
        model.put("cache_write_5m_token_ratio", 0L);
        model.put("cache_write_1h_token_ratio", 0L);
        model.put("context_tier_mode", 0);
        model.put("charge_desc", "按平台积分结算");
        model.put("pricing_rules", null);
        model.put("pricing_rules_active", true);
        model.put("context_tiers", List.of());
        model.put("context_tiers_active", false);
        model.put("max_context", 128_000);
        model.put("capabilities", capabilities);
        model.put("description", "Public market description");
        if (paramRules != null) {
            model.put("param_rules", paramRules);
        }
        return model;
    }

    private void applyLegacyContextTierFixture(Map<String, Object> model, JsonNode fixture) {
        model.put("context_tiers", null);
        model.put("context_tiers_active", fixture.path("context_tiers_active").asBoolean());
        model.put("context_threshold", fixture.path("context_threshold").asLong());
        model.put("high_context_input_ratio", fixture.path("high_context_input_ratio").asLong());
        model.put("high_context_output_ratio", fixture.path("high_context_output_ratio").asLong());
    }

    private void createManualModel(Cookie session, String publicName) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("public_name", publicName);
        payload.put("display_name", "Manual Alpha");
        payload.put("provider", "manual-provider");
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
        mockMvc.perform(post("/api/v1/admin/models")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> manualPricingPayload(long modelVersion, String unitPrice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("billing_type", 4);
        payload.put("unit_price", unitPrice);
        payload.put("display_original_price", "0");
        payload.put("input_token_ratio", 10_000L);
        payload.put("output_token_ratio", 10_000L);
        payload.put("audio_input_token_ratio", 10_000L);
        payload.put("audio_output_token_ratio", 10_000L);
        payload.put("cached_input_token_ratio", 10_000L);
        payload.put("cache_write_5m_token_ratio", 0L);
        payload.put("cache_write_1h_token_ratio", 0L);
        payload.put("charge_desc", "管理员人工售价");
        payload.put("context_tier_mode", 0);
        payload.put("unmatched_behavior", "base");
        payload.put("rules", List.of());
        payload.put("context_tiers", List.of());
        payload.put("change_note", "测试人工价格覆盖");
        payload.put("model_version", modelVersion);
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
                                "name", "Model Sync Admin",
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
