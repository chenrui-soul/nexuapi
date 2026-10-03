package com.nexusapi.server.modules.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.testing.TestServiceGroupFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ModelMarketIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID groupId;
    private UUID modelId;

    @BeforeEach
    void resetState() {
        // Maven 全量回归会复用同一测试数据库，需清理模型与服务分组，
        // 避免其他集成测试残留数据影响模型市场总数断言。
        // 该测试自建固定编码的接口文档，先清理自身关系和文档，避免唯一键污染后续轮次。
        jdbcTemplate.update("DELETE FROM model_interfaces WHERE interface_id IN "
                + "(SELECT id FROM api_interfaces WHERE interface_code = 'openai_chat_test')");
        jdbcTemplate.update("DELETE FROM api_interfaces WHERE interface_code = 'openai_chat_test'");
        jdbcTemplate.execute("TRUNCATE TABLE users, routing_groups, ai_models CASCADE");
        groupId = TestServiceGroupFixture.createPublicGroup(jdbcTemplate);
        modelId = jdbcTemplate.queryForObject(
                "SELECT model_id FROM routing_group_models WHERE group_id = ?",
                UUID.class,
                groupId
        );
        jdbcTemplate.update("""
                UPDATE ai_models
                   SET input_price = 2.5, output_price = 10, cached_input_price = 1,
                       max_output_tokens = 8192, supports_tools = true,
                       supports_structured_output = true, price_unit = 'million_tokens'
                 WHERE id = ?
                """, modelId);
        jdbcTemplate.update("UPDATE routing_groups SET price_multiplier = 0.2 WHERE id = ?", groupId);
        UUID pricingVersionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO model_pricing_versions (
                    id, model_id, version_no, billing_type, unit_price, display_original_price,
                    input_token_ratio, output_token_ratio, audio_input_token_ratio,
                    audio_output_token_ratio, cached_input_token_ratio,
                    cache_write_5m_token_ratio, cache_write_1h_token_ratio, charge_desc
                ) VALUES (?, ?, 1, 4, 3, 5, 10000, 40000, 30000, 70000, 2500, 0, 0, '按 Token 倍率结算积分')
                """, pricingVersionId, modelId);
        jdbcTemplate.update("""
                UPDATE ai_models
                   SET active_pricing_version_id = ?, billing_type = 4, unit_price = 3,
                       display_original_price = 5, input_token_ratio = 10000,
                       output_token_ratio = 40000, audio_input_token_ratio = 30000,
                       audio_output_token_ratio = 70000, cached_input_token_ratio = 2500,
                       cache_write_5m_token_ratio = 0, cache_write_1h_token_ratio = 0,
                       charge_desc = '按 Token 倍率结算积分'
                 WHERE id = ?
                """, pricingVersionId, modelId);
    }

    @Test
    void publicCatalogIncludesUnroutedModelsAndGroupFilterCalculatesOneMillionTokenPrices() throws Exception {
        UUID noRouteModel = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type, public_visible, status
                ) VALUES (?, ?, 'No Route', 'hidden-provider', 'text', true, 'active')
                """, noRouteModel, "no-route-" + noRouteModel);
        jdbcTemplate.update("UPDATE ai_models SET input_price = 4, output_price = 6 WHERE id = ?", noRouteModel);
        jdbcTemplate.update("""
                INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                VALUES (?, ?, 'manual', 'active')
                """, groupId, noRouteModel);

        UUID ungroupedModel = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    input_price, output_price, public_visible, status
                ) VALUES (?, ?, 'Ungrouped Model', 'catalog-provider', 'text', 8, 9, true, 'active')
                """, ungroupedModel, "ungrouped-" + ungroupedModel);

        String catalogResponse = mockMvc.perform(get("/api/v1/model-market")
                        .param("page", "1")
                        .param("page_size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.service_group_id").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode catalogItems = objectMapper.readTree(catalogResponse).at("/data/items");
        JsonNode ungroupedItem = findById(catalogItems, ungroupedModel);
        assertThat(ungroupedItem.path("availability").asText()).isEqualTo("catalog");
        assertThat(ungroupedItem.path("effective_input_price").decimalValue()).isEqualByComparingTo("8");
        assertThat(ungroupedItem.has("service_group_id")).isFalse();
        assertThat(ungroupedItem.path("recommended_service_group_id").isMissingNode()
                || ungroupedItem.path("recommended_service_group_id").isNull()).isTrue();

        JsonNode catalogRoutedItem = findById(catalogItems, modelId);
        assertThat(catalogRoutedItem.path("recommended_service_group_id").asText()).isEqualTo(groupId.toString());
        assertThat(catalogRoutedItem.path("recommended_service_group_name").asText()).isEqualTo("Test Public Group");
        assertThat(catalogRoutedItem.path("recommended_price_multiplier").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(catalogRoutedItem.path("recommended_effective_unit_price").decimalValue()).isEqualByComparingTo("0.6");

        String response = mockMvc.perform(get("/api/v1/model-market")
                        .param("service_group_id", groupId.toString())
                        .param("page", "1")
                        .param("page_size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.service_group_id").value(groupId.toString()))
                .andReturn().getResponse().getContentAsString();

        JsonNode items = objectMapper.readTree(response).at("/data/items");
        JsonNode routedItem = findById(items, modelId);
        JsonNode noRouteItem = findById(items, noRouteModel);
        assertThat(routedItem.path("effective_input_price").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(routedItem.path("effective_output_price").decimalValue()).isEqualByComparingTo("2");
        assertThat(routedItem.path("billing_type").asInt()).isEqualTo(4);
        assertThat(routedItem.path("billing_unit").asText()).isEqualTo("million_tokens");
        assertThat(routedItem.path("base_unit_price").decimalValue()).isEqualByComparingTo("3");
        assertThat(routedItem.path("effective_unit_price").decimalValue()).isEqualByComparingTo("0.6");
        assertThat(routedItem.path("display_original_price").decimalValue()).isEqualByComparingTo("5");
        assertThat(routedItem.path("input_token_ratio").asLong()).isEqualTo(10000);
        assertThat(routedItem.path("output_token_ratio").asLong()).isEqualTo(40000);
        assertThat(routedItem.path("audio_input_token_ratio").asLong()).isEqualTo(30000);
        assertThat(routedItem.path("audio_output_token_ratio").asLong()).isEqualTo(70000);
        assertThat(routedItem.path("cached_input_token_ratio").asLong()).isEqualTo(2500);
        assertThat(routedItem.path("cache_write_5m_token_ratio").asLong()).isZero();
        assertThat(routedItem.path("cache_write_1h_token_ratio").asLong()).isZero();
        assertThat(routedItem.path("charge_desc").asText()).isEqualTo("按 Token 倍率结算积分");
        assertThat(routedItem.path("pricing_version_id").asText()).isNotBlank();
        assertThat(noRouteItem.path("effective_input_price").decimalValue()).isEqualByComparingTo("0.8");
        assertThat(noRouteItem.path("effective_output_price").decimalValue()).isEqualByComparingTo("1.2");
        assertThat(noRouteItem.path("base_unit_price").decimalValue()).isEqualByComparingTo("4");
        assertThat(noRouteItem.path("effective_unit_price").decimalValue()).isEqualByComparingTo("0.8");
        // Jackson 会按当前序列化策略将 null 字段省略或显式输出为 null，
        // 两种表达都代表该模型尚未发布 V2 价格版本。
        assertThat(noRouteItem.path("pricing_version_id").isNull()
                || noRouteItem.path("pricing_version_id").isMissingNode()).isTrue();
        assertThat(noRouteItem.path("availability").asText()).isEqualTo("in_group");
        assertThat(routedItem.path("price_unit").asText()).isEqualTo("million_tokens");
        for (JsonNode item : items) {
            assertThat(item.has("encrypted_credential")).isFalse();
            assertThat(item.has("base_url")).isFalse();
            assertThat(item.has("supplier_input_price")).isFalse();
            assertThat(item.has("channel_id")).isFalse();
        }
    }

    @Test
    void publicGroupWithoutRuntimeRouteStillAppearsInServiceGroupCatalog() throws Exception {
        UUID emptyGroupId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, 'Catalog Only Group', 1.5, 'all', 'active')
                """, emptyGroupId, "catalog-only-" + emptyGroupId);

        String response = mockMvc.perform(get("/api/v1/service-groups"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode group = findById(objectMapper.readTree(response).at("/data"), emptyGroupId);
        assertThat(group.path("model_count").asLong()).isZero();
    }

    @Test
    void detailReturnsInterfaceDocumentationAndVisibleGroupPricesWithoutInternalRoutingData() throws Exception {
        UUID interfaceId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO api_interfaces (
                    id, interface_code, interface_name, interface_version, capability_type,
                    transport_mode, http_method, public_path, request_content_type,
                    request_schema, response_schema, description, status
                ) VALUES (?, 'openai_chat_test', 'Chat Completions', 'v1', 'text', 'stream', 'POST',
                          '/v1/chat/completions', 'application/json',
                          '{"fields":[{"name":"model","path":"model","type":"string","required":true,"description":"模型名称","deprecated":false,"sensitive":false,"children":[]}]}'::jsonb,
                          '{"fields":[{"name":"id","path":"id","type":"string","required":true,"description":"响应编号","deprecated":false,"sensitive":false,"children":[]}]}'::jsonb,
                          '公开文本对话接口', 'active')
                """, interfaceId);
        jdbcTemplate.update("INSERT INTO model_interfaces (model_id, interface_id) VALUES (?, ?)", modelId, interfaceId);

        String response = mockMvc.perform(get("/api/v1/model-market/{modelId}", modelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model.id").value(modelId.toString()))
                .andExpect(jsonPath("$.data.interfaces[0].interface_code").value("openai_chat_test"))
                .andExpect(jsonPath("$.data.interfaces[0].request_fields[0].name").value("model"))
                .andExpect(jsonPath("$.data.service_groups[0].id").value(groupId.toString()))
                .andReturn().getResponse().getContentAsString();

        JsonNode detail = objectMapper.readTree(response).path("data");
        assertThat(detail.at("/service_groups/0/effective_unit_price").decimalValue())
                .isEqualByComparingTo("0.6");
        assertThat(detail.has("channel_id")).isFalse();
        assertThat(detail.has("base_url")).isFalse();
        assertThat(detail.has("encrypted_credential")).isFalse();
    }

    @Test
    void rejectsUnknownOrUnavailableServiceGroup() throws Exception {
        mockMvc.perform(get("/api/v1/model-market")
                        .param("service_group_id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GROUP_NOT_AVAILABLE"));
    }

    @Test
    void rejectsInvalidPaginationAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/model-market")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.page").exists());
    }

    private JsonNode findById(JsonNode items, UUID id) {
        for (JsonNode item : items) {
            if (id.toString().equals(item.path("id").asText())) return item;
        }
        throw new AssertionError("未找到 ID=" + id + " 的响应项");
    }
}
