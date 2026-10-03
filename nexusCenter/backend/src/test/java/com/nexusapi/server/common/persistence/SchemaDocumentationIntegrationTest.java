package com.nexusapi.server.common.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Flyway 已把业务表和全部业务字段说明真实写入 PostgreSQL 元数据。 */
@SpringBootTest
class SchemaDocumentationIntegrationTest {
    private static final String BUSINESS_TABLES = """
            'users', 'user_roles', 'api_keys', 'system_access_tokens', 'ai_models', 'suppliers', 'channels', 'routing_groups', 'routing_group_models',
            'routing_group_suppliers', 'routing_group_supplier_credentials', 'routing_group_user_grants',
            'wallet_accounts', 'billing_ledger',
            'wallet_reservations', 'billing_refunds', 'plans', 'subscriptions', 'orders',
            'plan_service_groups', 'subscription_service_groups', 'plan_models', 'subscription_models',
            'expiring_credit_batches',
            'wallet_reservation_batch_allocations',
            'request_logs', 'request_logs_default', 'upstream_attempt_logs', 'usage_aggregates', 'notifications',
            'health_checks', 'health_alerts', 'audit_logs', 'outbox_events',
            'model_sync_settings', 'model_sync_runs',
            'model_pricing_versions', 'model_pricing_rules', 'model_context_tiers',
            'supplier_model_prices', 'request_billing_details',
            'billing_time_rules', 'billing_time_rule_models',
            'api_interfaces', 'model_interfaces'
            , 'payment_events', 'payment_reconciliation_records',
            'currency_exchange_rates', 'supplier_billing_imports', 'supplier_billing_items',
            'financial_reconciliation_records'
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void latestFlywayMigrationDocumentsEveryBusinessTableAndColumn() {
        String currentVersion = jdbcTemplate.queryForObject("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success = true AND version IS NOT NULL
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """, String.class);
        assertThat(currentVersion).isEqualTo("63");

        Integer retiredGroupChannelCredentialTables = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name = 'routing_group_channel_credentials'
                """, Integer.class);
        assertThat(retiredGroupChannelCredentialTables)
                .as("停用的分组渠道凭证兼容表必须由 V58 清理")
                .isZero();

        Integer deprecatedRuntimeColumns = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND ((table_name = 'model_interfaces' AND column_name = 'interface_code')
                     OR (table_name = 'channels' AND column_name IN ('interface_code', 'interface_codes')))
                """, Integer.class);
        assertThat(deprecatedRuntimeColumns)
                .as("接口文档关系和渠道表不得再保存 Gateway 运行时接口编码")
                .isZero();

        List<String> missingTableComments = jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public'
                   AND c.relname IN (%s)
                   AND obj_description(c.oid, 'pg_class') IS NULL
                 ORDER BY c.relname
                """.formatted(BUSINESS_TABLES), String.class);
        assertThat(missingTableComments).as("所有业务表都必须有中文用途说明").isEmpty();

        List<String> missingColumnComments = jdbcTemplate.queryForList("""
                SELECT c.relname || '.' || a.attname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE n.nspname = 'public'
                   AND c.relname IN (%s)
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                   AND col_description(c.oid, a.attnum) IS NULL
                 ORDER BY c.relname, a.attnum
                """.formatted(BUSINESS_TABLES), String.class);
        assertThat(missingColumnComments).as("所有业务字段都必须有中文字段说明").isEmpty();
    }
}
