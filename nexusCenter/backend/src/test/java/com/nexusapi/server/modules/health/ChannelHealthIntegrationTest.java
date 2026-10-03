package com.nexusapi.server.modules.health;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nexusapi.server.modules.channel.entity.AdminChannelRow;
import com.nexusapi.server.modules.channel.mapper.AdminChannelMapper;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.health.entity.ChannelHealthTarget;
import com.nexusapi.server.modules.health.mapper.ChannelHealthMapper;
import com.nexusapi.server.modules.health.model.ChannelHealthProbeResult;
import com.nexusapi.server.modules.health.scheduler.ChannelHealthProbeScheduler;
import com.nexusapi.server.modules.health.service.ChannelHealthProbeClient;
import com.nexusapi.server.modules.health.service.ChannelHealthService;
import com.nexusapi.server.modules.health.service.GroupHealthAlertService;
import com.nexusapi.server.modules.routing.mapper.GatewayRoutingMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Wave 7A 状态机集成测试：真实 PostgreSQL + WireMock，后台调度在测试配置中关闭。 */
@SpringBootTest(properties = {
        "nexus.health.enabled=true",
        "nexus.health.initial-delay=1h",
        "nexus.health.probe-timeout=2s",
        "nexus.health.probe-interval=1s",
        "nexus.health.batch-size=20"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChannelHealthIntegrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ChannelCredentialCipher credentialCipher;
    @Autowired
    private AdminChannelMapper adminChannelMapper;
    @Autowired
    private ChannelHealthService healthService;
    @Autowired
    private GroupHealthAlertService groupHealthAlertService;
    @Autowired
    private ChannelHealthProbeClient probeClient;
    @Autowired
    private ChannelHealthMapper healthMapper;
    @Autowired
    private GatewayRoutingMapper routingMapper;
    @Autowired
    private ChannelHealthProbeScheduler scheduler;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private WireMockServer upstream;

    @BeforeAll
    void startUpstream() {
        upstream = new WireMockServer(options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE notifications, health_alerts, health_checks, upstream_attempt_logs, request_logs,
                    routing_groups, channels, suppliers, ai_models, users CASCADE
                """);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        upstream.resetAll();
    }

    @Test
    void schedulerAutomaticallyProbesDueChannel() {
        RouteFixture fixture = createRouteFixture("scheduled", "/scheduled/v1");
        AdminChannelRow openEditorSnapshot = adminChannelMapper.findChannelById(fixture.channelId());
        long configurationVersion = openEditorSnapshot.getVersion();
        upstream.stubFor(get(urlEqualTo("/scheduled/v1/models"))
                .willReturn(aResponse().withStatus(500).withBody("must-not-persist")));

        scheduler.runProbeCycle();

        assertThat(channelStatus(fixture.channelId())).isEqualTo("degraded");
        assertThat(channelFailures(fixture.channelId())).isEqualTo(1);
        assertThat(latestCheckStatus(fixture.channelId())).isEqualTo("degraded");
        assertThat(channelVersion(fixture.channelId())).isEqualTo(configurationVersion);

        // 模拟管理员在探测前已打开编辑抽屉：健康失败后仍应能用原配置版本保存。
        openEditorSnapshot.setName("Channel scheduled updated");
        assertThat(adminChannelMapper.updateChannel(openEditorSnapshot)).isEqualTo(1);
        assertThat(channelVersion(fixture.channelId())).isEqualTo(configurationVersion + 1);
        assertThat(channelName(fixture.channelId())).isEqualTo("Channel scheduled updated");
    }

    @Test
    void customRelativeProbePathIsUsedWithoutPersistingResponseBody() {
        RouteFixture fixture = createRouteFixture("custom-path", "/custom/v1");
        jdbcTemplate.update(
                "UPDATE channels SET health_probe_path = '/health/ready' WHERE id = ?", fixture.channelId()
        );
        upstream.stubFor(get(urlEqualTo("/custom/v1/health/ready"))
                .willReturn(aResponse().withStatus(200).withBody("sk-body-must-be-discarded")));

        ChannelHealthTarget target = healthMapper.findProbeTargetById(fixture.channelId());
        ChannelHealthProbeResult result = probeClient.probe(target);
        healthService.recordProbeResult(target, result);

        assertThat(result.outcome()).isEqualTo(ChannelHealthProbeResult.Outcome.HEALTHY);
        upstream.verify(1, getRequestedFor(urlEqualTo("/custom/v1/health/ready")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT coalesce(string_agg(coalesce(error_summary, ''), ''), '') FROM health_checks WHERE target_id = ?",
                String.class,
                fixture.channelId()
        )).doesNotContain("sk-body-must-be-discarded");
    }

    @Test
    void operationEndpointBaseUrlUsesApiRootForImageHealthProbe() {
        RouteFixture fixture = createRouteFixture("image-endpoint", "/image/v1");
        jdbcTemplate.update(
                "UPDATE channels SET base_url = ?, health_probe_path = '/images/tasks' WHERE id = ?",
                upstream.baseUrl() + "/image/v1/images/generations", fixture.channelId()
        );
        upstream.stubFor(get(urlEqualTo("/image/v1/images/tasks"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        ChannelHealthTarget target = healthMapper.findProbeTargetById(fixture.channelId());
        ChannelHealthProbeResult result = probeClient.probe(target);

        assertThat(result.outcome()).isEqualTo(ChannelHealthProbeResult.Outcome.HEALTHY);
        upstream.verify(1, getRequestedFor(urlEqualTo("/image/v1/images/tasks")));
    }

    @Test
    void unsupportedImageProbeFallsBackToModelsAndRestoresDegradedState() {
        RouteFixture fixture = createRouteFixture("image-fallback", "/image-fallback/v1");
        jdbcTemplate.update("""
                UPDATE channels
                   SET base_url = ?, health_probe_path = '/images/tasks', status = 'degraded',
                       consecutive_failures = 3, circuit_open_until = NULL,
                       last_error_summary = 'probe_timeout'
                 WHERE id = ?
                """, upstream.baseUrl() + "/image-fallback/v1/images/generations", fixture.channelId());
        upstream.stubFor(get(urlEqualTo("/image-fallback/v1/images/tasks"))
                .willReturn(aResponse().withStatus(404)));
        upstream.stubFor(get(urlEqualTo("/image-fallback/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody("{\"object\":\"list\",\"data\":[]}")));

        ChannelHealthTarget target = dueTarget(fixture.channelId());
        ChannelHealthProbeResult result = probeClient.probe(target);
        healthService.recordProbeResult(target, result);

        assertThat(result.outcome()).isEqualTo(ChannelHealthProbeResult.Outcome.HEALTHY);
        assertThat(channelStatus(fixture.channelId())).isEqualTo("active");
        assertThat(channelFailures(fixture.channelId())).isZero();
        upstream.verify(1, getRequestedFor(urlEqualTo("/image-fallback/v1/images/tasks")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/image-fallback/v1/models")));
    }

    @Test
    void existingRedisLockPreventsDuplicateProbeCycle() {
        RouteFixture fixture = createRouteFixture("locked", "/locked/v1");
        upstream.stubFor(get(urlEqualTo("/locked/v1/models"))
                .willReturn(aResponse().withStatus(500)));
        redis.opsForValue().set("nexus:health:probe:lock", "another-instance", Duration.ofMinutes(1));

        scheduler.runProbeCycle();

        assertThat(channelStatus(fixture.channelId())).isEqualTo("active");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM health_checks WHERE target_id = ?", Integer.class, fixture.channelId()
        )).isZero();
    }

    @Test
    void repeatedFailuresKeepRouteAvailableWithoutCircuitBreaker() {
        RouteFixture fixture = createRouteFixture("open-circuit", "/open/v1");
        long configurationVersion = channelVersion(fixture.channelId());

        healthService.recordGatewayFailure(fixture.channelId(), "upstream_5xx", "upstream_http_500");
        healthService.recordGatewayFailure(fixture.channelId(), "upstream_5xx", "upstream_http_500");
        healthService.recordGatewayFailure(fixture.channelId(), "upstream_5xx", "upstream_http_500");

        assertThat(channelStatus(fixture.channelId())).isEqualTo("degraded");
        assertThat(channelFailures(fixture.channelId())).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT circuit_open_until IS NULL FROM channels WHERE id = ?",
                Boolean.class,
                fixture.channelId()
        )).isTrue();
        assertThat(routingMapper.findCandidates(fixture.groupId(), fixture.modelId())).hasSize(1);
        assertThat(supplierHealth(fixture.supplierId())).isEqualTo("degraded");
        assertThat(channelVersion(fixture.channelId())).isEqualTo(configurationVersion);
    }

    @Test
    void gatewaySuccessRestoresHealthWithoutChangingConfigurationVersion() {
        RouteFixture fixture = createRouteFixture("gateway-recover", "/gateway-recover/v1");
        jdbcTemplate.update("""
                UPDATE channels
                   SET status = 'degraded', consecutive_failures = 2,
                       circuit_open_until = NULL, last_error_summary = 'upstream_failure'
                 WHERE id = ?
                """, fixture.channelId());
        long configurationVersion = channelVersion(fixture.channelId());

        healthService.recordGatewaySuccess(fixture.channelId());

        assertThat(channelStatus(fixture.channelId())).isEqualTo("active");
        assertThat(channelFailures(fixture.channelId())).isZero();
        assertThat(channelVersion(fixture.channelId())).isEqualTo(configurationVersion);
    }

    @Test
    void successfulProbeRestoresDegradedChannelAndSupplier() {
        RouteFixture fixture = createRouteFixture("recover", "/recover/v1");
        jdbcTemplate.update("""
                UPDATE channels
                   SET status = 'degraded', consecutive_failures = 3,
                       circuit_open_until = NULL, last_error_summary = 'probe_timeout'
                 WHERE id = ?
                """, fixture.channelId());
        long configurationVersion = channelVersion(fixture.channelId());
        upstream.stubFor(get(urlEqualTo("/recover/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody("{\"object\":\"list\",\"data\":[]}")));

        ChannelHealthTarget target = dueTarget(fixture.channelId());
        ChannelHealthProbeResult result = probeClient.probe(target);
        healthService.recordProbeResult(target, result);

        assertThat(result.outcome()).isEqualTo(ChannelHealthProbeResult.Outcome.HEALTHY);
        assertThat(channelStatus(fixture.channelId())).isEqualTo("active");
        assertThat(channelFailures(fixture.channelId())).isZero();
        assertThat(routingMapper.findCandidates(fixture.groupId(), fixture.modelId())).hasSize(1);
        assertThat(supplierHealth(fixture.supplierId())).isEqualTo("healthy");
        assertThat(latestCheckStatus(fixture.channelId())).isEqualTo("healthy");
        assertThat(channelVersion(fixture.channelId())).isEqualTo(configurationVersion);
    }

    @Test
    void successfulProbeDoesNotEraseRealGatewayFailure() {
        RouteFixture fixture = createRouteFixture("gateway-failure-probe", "/gateway-failure-probe/v1");
        healthService.recordGatewayFailure(
                fixture.channelId(), "network", "upstream_connection_premature_close"
        );
        upstream.stubFor(get(urlEqualTo("/gateway-failure-probe/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody("{\"object\":\"list\",\"data\":[]}")));

        ChannelHealthTarget target = dueTarget(fixture.channelId());
        ChannelHealthProbeResult result = probeClient.probe(target);
        healthService.recordProbeResult(target, result);

        assertThat(result.outcome()).isEqualTo(ChannelHealthProbeResult.Outcome.HEALTHY);
        assertThat(channelStatus(fixture.channelId())).isEqualTo("degraded");
        assertThat(channelFailures(fixture.channelId())).isOne();
        assertThat(supplierHealth(fixture.supplierId())).isEqualTo("degraded");
        assertThat(latestCheckStatus(fixture.channelId())).isEqualTo("healthy");
    }

    @Test
    void manualSuccessfulProbeCanRestoreLegacyCircuitState() {
        RouteFixture fixture = createRouteFixture("manual-recover", "/manual-recover/v1");
        jdbcTemplate.update("""
                UPDATE channels
                   SET status = 'circuit_open', consecutive_failures = 3,
                       circuit_open_until = now() + interval '5 minutes', last_error_summary = 'probe_timeout'
                 WHERE id = ?
                """, fixture.channelId());
        long configurationVersion = channelVersion(fixture.channelId());
        upstream.stubFor(get(urlEqualTo("/manual-recover/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody("ignored")));

        ChannelHealthTarget target = healthMapper.findProbeTargetById(fixture.channelId());
        ChannelHealthProbeResult result = probeClient.probe(target);
        healthService.recordManualProbeResult(target, result);

        assertThat(result.outcome()).isEqualTo(ChannelHealthProbeResult.Outcome.HEALTHY);
        assertThat(channelStatus(fixture.channelId())).isEqualTo("active");
        assertThat(channelFailures(fixture.channelId())).isZero();
        assertThat(channelVersion(fixture.channelId())).isEqualTo(configurationVersion);
    }

    @Test
    void groupUnavailableAlertIsSuppressedDuringCooldownAndResolvedOnce() {
        RouteFixture fixture = createRouteFixture("group-alert", "/group-alert/v1");
        UUID adminId = createAdmin();

        jdbcTemplate.update("UPDATE channels SET status = 'disabled' WHERE id = ?", fixture.channelId());
        groupHealthAlertService.handleChannelChanged(fixture.channelId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM health_alerts WHERE group_id = ? AND status = 'open'",
                Integer.class, fixture.groupId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT notification_count FROM health_alerts WHERE group_id = ? AND status = 'open'",
                Integer.class, fixture.groupId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'health_alert'",
                Integer.class, adminId
        )).isEqualTo(1);
        assertThat(latestGroupCheckStatus(fixture.groupId())).isEqualTo("unavailable");

        // 同一冷却窗口内重复确认故障只累计抑制次数，不生成第二条通知。
        groupHealthAlertService.handleChannelChanged(fixture.channelId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT suppressed_count FROM health_alerts WHERE group_id = ? AND status = 'open'",
                Integer.class, fixture.groupId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'health_alert'",
                Integer.class, adminId
        )).isEqualTo(1);

        jdbcTemplate.update("UPDATE channels SET status = 'active' WHERE id = ?", fixture.channelId());
        groupHealthAlertService.handleChannelChanged(fixture.channelId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM health_alerts WHERE group_id = ? ORDER BY id DESC LIMIT 1",
                String.class, fixture.groupId()
        )).isEqualTo("resolved");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'health_alert'",
                Integer.class, adminId
        )).isEqualTo(2);
        assertThat(latestGroupCheckStatus(fixture.groupId())).isEqualTo("healthy");

        // 后续健康信号不能重复发送恢复通知。
        healthService.recordGatewaySuccess(fixture.channelId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'health_alert'",
                Integer.class, adminId
        )).isEqualTo(2);
    }

    @Test
    void disabledChannelIsNeverChangedOrSelectedForProbe() {
        RouteFixture fixture = createRouteFixture("disabled", "/disabled/v1");
        jdbcTemplate.update("UPDATE channels SET status = 'disabled' WHERE id = ?", fixture.channelId());

        healthService.recordGatewayFailure(fixture.channelId(), "network", "probe_connection_failure");
        healthService.recordGatewaySuccess(fixture.channelId());

        assertThat(channelStatus(fixture.channelId())).isEqualTo("disabled");
        assertThat(channelFailures(fixture.channelId())).isZero();
        assertThat(healthService.findDueProbeTargets())
                .extracting(ChannelHealthTarget::getChannelId)
                .doesNotContain(fixture.channelId());
    }

    @Test
    void unsupportedProbeEndpointDoesNotOpenCircuitOrPersistResponseBody() {
        RouteFixture fixture = createRouteFixture("unsupported", "/unsupported/v1");
        upstream.stubFor(get(urlEqualTo("/unsupported/v1/models"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                        .withBody("{\"error\":\"Bearer sk-probe-body-must-not-persist\"}")));

        ChannelHealthTarget target = dueTarget(fixture.channelId());
        ChannelHealthProbeResult result = probeClient.probe(target);
        healthService.recordProbeResult(target, result);

        assertThat(result.outcome()).isEqualTo(ChannelHealthProbeResult.Outcome.UNCONFIGURED);
        assertThat(channelStatus(fixture.channelId())).isEqualTo("active");
        assertThat(channelFailures(fixture.channelId())).isZero();
        assertThat(supplierHealth(fixture.supplierId())).isEqualTo("unconfigured");
        assertThat(latestCheckStatus(fixture.channelId())).isEqualTo("unconfigured");
        String summary = jdbcTemplate.queryForObject(
                "SELECT error_summary FROM health_checks WHERE target_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                fixture.channelId()
        );
        assertThat(summary).isEqualTo("probe_endpoint_unsupported");
        assertThat(summary).doesNotContain("sk-probe-body-must-not-persist");
    }

    @Test
    void partialSupplierFailureIsDegradedAndSensitiveSummaryIsDiscarded() {
        RouteFixture failed = createRouteFixture("partial-failed", "/partial-failed/v1");
        createChannel(failed.supplierId(), "partial-healthy", "/partial-healthy/v1");

        healthService.recordGatewayFailure(
                failed.channelId(), "network", "Authorization: Bearer sk-sensitive-value"
        );
        healthService.recordGatewayFailure(
                failed.channelId(), "network", "Authorization: Bearer sk-sensitive-value"
        );
        healthService.recordGatewayFailure(
                failed.channelId(), "network", "Authorization: Bearer sk-sensitive-value"
        );

        assertThat(channelStatus(failed.channelId())).isEqualTo("degraded");
        assertThat(supplierHealth(failed.supplierId())).isEqualTo("degraded");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_summary FROM channels WHERE id = ?",
                String.class,
                failed.channelId()
        )).isEqualTo("upstream_failure");
    }

    private ChannelHealthTarget dueTarget(UUID channelId) {
        List<ChannelHealthTarget> targets = healthMapper.findDueProbeTargets(1, 20);
        return targets.stream()
                .filter(target -> channelId.equals(target.getChannelId()))
                .findFirst()
                .orElseThrow();
    }

    private RouteFixture createRouteFixture(String suffix, String basePath) {
        UUID supplierId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO suppliers (
                    id, code, name, supplier_type, status, health_status,
                    billing_mode, settlement_currency, metadata
                ) VALUES (?, ?, ?, 'direct', 'active', 'unconfigured', 'postpaid', 'USD', '{}'::jsonb)
                """, supplierId, "supplier-" + suffix, "Supplier " + suffix);
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    supports_streaming, supports_tools, price_unit, public_visible, status
                ) VALUES (?, ?, ?, 'openai', 'text', true, true, 'million_tokens', true, 'active')
                """, modelId, "model-" + suffix, "Model " + suffix);
        jdbcTemplate.update("""
                INSERT INTO model_interfaces (model_id, interface_id)
                SELECT ?, id FROM api_interfaces WHERE interface_code = 'openai_chat'
                """, modelId);
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, ?, 1, 'all', 'active')
                """, groupId, "group-" + suffix, "Group " + suffix);
        UUID channelId = createChannel(supplierId, suffix, basePath);
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, modelId, "upstream-" + suffix,
                "0", "0", "0");
        jdbcTemplate.update("""
                INSERT INTO routing_group_suppliers (group_id, supplier_id, priority, weight, status)
                VALUES (?, ?, 100, 100, 'active')
                """, groupId, supplierId);
        jdbcTemplate.update("""
                INSERT INTO routing_group_supplier_credentials (
                    group_id, supplier_id, encrypted_credential, credential_key_version,
                    credential_fingerprint, updated_at, status
                )
                SELECT ?, supplier_id, encrypted_credential, credential_key_version,
                       credential_fingerprint, now(), 'active'
                  FROM channels
                 WHERE id = ?
                """, groupId, channelId);
        jdbcTemplate.update("""
                INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                VALUES (?, ?, 'manual', 'active')
                """, groupId, modelId);
        return new RouteFixture(supplierId, channelId, modelId, groupId);
    }

    private UUID createChannel(UUID supplierId, String suffix, String basePath) {
        UUID channelId = UUID.randomUUID();
        ChannelCredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt("credential-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO channels (
                    id, supplier_id, name, provider_type, operation_code, endpoint_type,
                    base_url, encrypted_credential,
                    credential_key_version, credential_fingerprint, credential_updated_at,
                    status, timeout_ms, priority, weight
                ) VALUES (?, ?, ?, 'openai', 'chat_completions', 'text',
                          ?, ?, ?, ?, now(), 'active', 5000, 100, 100)
                """, channelId, supplierId, "Channel " + suffix, upstream.baseUrl() + basePath,
                encrypted.ciphertext(), encrypted.keyVersion(), encrypted.fingerprint());
        return channelId;
    }

    /** 插入最小管理员数据，只用于验证通知接收范围，不读取或暴露邮箱密文。 */
    private UUID createAdmin() {
        UUID id = UUID.randomUUID();
        byte[] unique = id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbcTemplate.update("""
                INSERT INTO users (
                    id, display_name, email_ciphertext, email_lookup_hash,
                    password_hash, status, email_verified_at
                ) VALUES (?, 'Health Admin', ?, ?, 'unused-test-hash', 'active', now())
                """, id, unique, unique);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'admin')", id);
        return id;
    }

    private String channelStatus(UUID channelId) {
        return jdbcTemplate.queryForObject("SELECT status FROM channels WHERE id = ?", String.class, channelId);
    }

    private int channelFailures(UUID channelId) {
        return jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM channels WHERE id = ?", Integer.class, channelId
        );
    }

    private long channelVersion(UUID channelId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM channels WHERE id = ?", Long.class, channelId
        );
    }

    private String channelName(UUID channelId) {
        return jdbcTemplate.queryForObject("SELECT name FROM channels WHERE id = ?", String.class, channelId);
    }

    private String supplierHealth(UUID supplierId) {
        return jdbcTemplate.queryForObject(
                "SELECT health_status FROM suppliers WHERE id = ?", String.class, supplierId
        );
    }

    private String latestCheckStatus(UUID channelId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM health_checks WHERE target_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                channelId
        );
    }

    private String latestGroupCheckStatus(UUID groupId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM health_checks WHERE target_type = 'group' AND target_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class,
                groupId
        );
    }

    private record RouteFixture(UUID supplierId, UUID channelId, UUID modelId, UUID groupId) {
    }
}
