package com.nexusapi.server.testing;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** 为 API 令牌集成测试建立最小、真实可路由的公开服务分组。 */
public final class TestServiceGroupFixture {
    private TestServiceGroupFixture() {
    }

    public static UUID createPublicGroup(JdbcTemplate jdbcTemplate) {
        UUID supplierId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO suppliers (
                    id, code, name, supplier_type, status, health_status,
                    billing_mode, settlement_currency, metadata
                ) VALUES (?, ?, ?, 'direct', 'active', 'healthy', 'postpaid', 'USD', '{}'::jsonb)
                """, supplierId, "test-supplier-" + supplierId, "Test Supplier " + supplierId);
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    supports_streaming, public_visible, status
                ) VALUES (?, ?, 'Test Model', 'test', 'text', true, true, 'active')
                """, modelId, "test-model-" + modelId);
        jdbcTemplate.update("""
                INSERT INTO channels (
                    id, supplier_id, name, provider_type, operation_code, endpoint_type, base_url,
                    encrypted_credential, credential_key_version, credential_fingerprint,
                    status, timeout_ms, priority, weight
                ) VALUES (?, ?, ?, 'openai', 'chat_completions', 'text', 'https://example.invalid/v1',
                          decode('01', 'hex'), 1, 'test-fixture', 'active', 5000, 100, 100)
                """, channelId, supplierId, "Test Endpoint " + channelId);
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, modelId, "test-upstream-model",
                "0", "0", "0");
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, 'Test Public Group', 1, 'all', 'active')
                """, groupId, "test-group-" + groupId);
        jdbcTemplate.update("""
                INSERT INTO routing_group_suppliers (group_id, supplier_id, priority, weight, status)
                VALUES (?, ?, 100, 100, 'active')
                """, groupId, supplierId);
        jdbcTemplate.update("""
                INSERT INTO routing_group_supplier_credentials (
                    group_id, supplier_id, encrypted_credential, credential_key_version,
                    credential_fingerprint, status
                ) VALUES (?, ?, decode('01', 'hex'), 1, 'test-fixture', 'active')
                """, groupId, supplierId);
        jdbcTemplate.update("""
                INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                VALUES (?, ?, 'manual', 'active')
                """, groupId, modelId);
        return groupId;
    }
}
