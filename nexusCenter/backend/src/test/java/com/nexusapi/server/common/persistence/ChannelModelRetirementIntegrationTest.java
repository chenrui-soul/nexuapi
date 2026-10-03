package com.nexusapi.server.common.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelModelRetirementIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private String database;
    private UUID supplierId;
    private UUID channelId;
    private UUID modelId;
    private UUID mappingId;

    @BeforeEach
    void legacyDatabase() {
        String url = System.getenv("DB_URL");
        if (url == null || !url.endsWith("/nexus_api_test")) {
            throw new IllegalStateException("Migration tests require the isolated nexus_api_test database");
        }
        String user = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        database = "nexus_retirement_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        dataSource = new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(dataSource).target("62").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        supplierId = UUID.randomUUID();
        channelId = UUID.randomUUID();
        modelId = UUID.randomUUID();
        mappingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO suppliers (id, code, name, supplier_type, status, billing_mode, settlement_currency)
                VALUES (?, 'migration', 'Migration', 'direct', 'active', 'prepaid', 'USD')
                """, supplierId);
        jdbc.update("""
                INSERT INTO ai_models (id, public_name, display_name, provider, capability_type, status)
                VALUES (?, 'seedance-2.5', 'Seedance', 'bytedance', 'video', 'active')
                """, modelId);
        jdbc.update("""
                INSERT INTO channels (id, supplier_id, name, provider_type, operation_code, endpoint_type,
                    base_url, status, metadata)
                VALUES (?, ?, 'Migration video', 'openai', 'video_create', 'video',
                    'https://example.test/v1/videos', 'active', '{"owner":"preserved"}'::jsonb)
                """, channelId, supplierId);
        jdbc.update("""
                INSERT INTO channel_models (id, channel_id, model_id, upstream_model,
                    cost_input_price, cost_cached_input_price, cost_output_price, status, config)
                VALUES (?, ?, ?, 'bytedance/seedance-2.5', 0.1234567890, 0.05, 0.3, 'active', '{"timeout":17}'::jsonb)
                """, mappingId, channelId, modelId);
    }

    @AfterEach
    void dropIsolatedDatabase() {
        if (database != null) admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void upgradePreservesTransportCostsAndHistoricalLogsAndRemovesTable() {
        jdbc.update("""
                INSERT INTO request_logs (request_id, public_model, started_at, channel_model_id, supplier_cost_amount)
                VALUES ('migration-request', 'seedance-2.5', now(), ?, 0.12345678)
                """, mappingId);
        jdbc.update("""
                INSERT INTO upstream_attempt_logs (request_id, supplier_id, channel_id, channel_model_id,
                    model_id, attempt_no, started_at, completed_at, duration_ms, outcome)
                VALUES ('migration-request', ?, ?, ?, ?, 0, now(), now(), 1, 'success')
                """, supplierId, channelId, mappingId, modelId);
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.channel_models')::text", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT metadata->>'owner' FROM channels WHERE id = ?", String.class, channelId))
                .isEqualTo("preserved");
        assertThat(jdbc.queryForObject("SELECT metadata->'upstream_models'->?->>'upstream_model' FROM channels WHERE id = ?",
                String.class, modelId.toString(), channelId)).isEqualTo("bytedance/seedance-2.5");
        assertThat(jdbc.queryForObject("SELECT metadata->'upstream_models'->?->>'cost_input_price' FROM channels WHERE id = ?",
                String.class, modelId.toString(), channelId)).isEqualTo("0.1234567890");
        assertThat(jdbc.queryForObject("SELECT metadata->'upstream_models'->?->'config'->>'timeout' FROM channels WHERE id = ?",
                String.class, modelId.toString(), channelId)).isEqualTo("17");
        assertThat(jdbc.queryForObject("SELECT channel_model_id FROM request_logs WHERE request_id = 'migration-request'", UUID.class))
                .isEqualTo(mappingId);
        assertThat(jdbc.queryForObject("SELECT supplier_cost_amount FROM request_logs WHERE request_id = 'migration-request'", java.math.BigDecimal.class))
                .isEqualByComparingTo("0.12345678");
        assertThat(jdbc.queryForObject("SELECT channel_model_id FROM upstream_attempt_logs WHERE request_id = 'migration-request'", UUID.class))
                .isEqualTo(mappingId);
        assertThat(Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted).isZero();
    }

    @Test
    void disabledOptionsDoNotBecomeActiveAfterUpgrade() {
        jdbc.update("UPDATE channel_models SET status = 'disabled' WHERE id = ?", mappingId);
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(jdbc.queryForObject("SELECT metadata->'upstream_models' FROM channels WHERE id = ?", String.class, channelId)).isNull();
    }

    @Test
    void conflictingAliasesAbortWithoutLosingLegacyData() {
        jdbc.update("""
                INSERT INTO channel_models (channel_id, model_id, upstream_model, status)
                VALUES (?, ?, 'another-model', 'active')
                """, channelId, modelId);
        assertThatThrownBy(() -> Flyway.configure().dataSource(dataSource).load().migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("duplicate active");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_models WHERE channel_id = ?", Integer.class, channelId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT metadata->'upstream_models' FROM channels WHERE id = ?", String.class, channelId)).isNull();
    }
}
