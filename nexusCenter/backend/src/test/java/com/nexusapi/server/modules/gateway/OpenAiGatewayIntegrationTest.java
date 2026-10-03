package com.nexusapi.server.modules.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import com.nexusapi.server.modules.billing.enums.CreditBucket;
import com.nexusapi.server.modules.billing.enums.CreditType;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.apikey.security.NexusApiKeyPrincipal;
import com.nexusapi.server.modules.gateway.security.ConsoleGatewayPrincipal;
import com.nexusapi.server.modules.quota.service.GatewayQuotaService;
import com.nexusapi.server.modules.routing.mapper.GatewayRoutingMapper;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/** Wave 6 网关端到端测试：真实 PostgreSQL/Redis + WireMock 上游。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenAiGatewayIntegrationTest.FixedCaptchaConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiGatewayIntegrationTest {
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
    private StringRedisTemplate redis;
    @Autowired
    private ChannelCredentialCipher credentialCipher;
    @Autowired
    private BillingService billingService;
    @Autowired
    private GatewayQuotaService quotaService;
    @Autowired
    private GatewayRoutingMapper routingMapper;

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
                TRUNCATE TABLE users, upstream_attempt_logs, request_logs, routing_groups,
                    channels, suppliers, ai_models CASCADE
                """);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        upstream.resetAll();
    }

    @Test
    void validApiKeyCanListOnlyRoutableModels() throws Exception {
        RegisteredUser user = register("models@example.com");
        Configuration config = configure("model-list", "0.1", "0.2");
        addRoute(config, "/models/v1", 10, 10, true, "list-upstream-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);

        mockMvc.perform(get("/v1/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value("model-list"))
                .andExpect(jsonPath("$.data[0].object").value("model"));
    }

    @Test
    void responsesUsesConfiguredUpstreamPathAndCompletesBillingLog() throws Exception {
        RegisteredUser user = register("responses-capability@example.com");
        fund(user.id());
        Configuration config = configure("gpt-responses-capability", "0.1", "0.2");
        addRouteAtBase(config, upstream.baseUrl() + "/v1/responses", 1, 10, true, "responses-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/responses")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"resp_1\",\"model\":\"upstream-gpt-responses-capability\",\"output\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}")));

        mockMvc.perform(post("/v1/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_responses_capability_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(), "input", "你好", "stream", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("resp_1"))
                .andExpect(jsonPath("$.model").value(config.publicModel()));
        upstream.verify(1, postRequestedFor(urlPathEqualTo("/v1/responses")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM request_logs WHERE request_id = 'req_responses_capability_001' AND status_code = 200",
                Integer.class)).isOne();
    }

    @Test
    void responsesStreamingPassesOfficialEventsAndSettlesUsage() throws Exception {
        RegisteredUser user = register("responses-stream@example.com");
        fund(user.id());
        Configuration config = configure("gpt-responses-stream", "1", "2");
        String upstreamCredential = "responses-stream-secret";
        addRouteAtBase(config, upstream.baseUrl() + "/responses-stream/v1/responses", 1, 10, true,
                upstreamCredential);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-stream/v1/responses"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                data: {"type":"response.created","response":{"id":"resp_stream_1","model":"upstream-gpt-responses-stream","status":"in_progress"}}

                                data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"你好"}

                                data: {"type":"response.completed","response":{"id":"resp_stream_1","model":"upstream-gpt-responses-stream","status":"completed","usage":{"input_tokens":90,"output_tokens":10,"total_tokens":100,"input_tokens_details":{"cached_tokens":20}}}}

                                """)));

        MvcResult pending = mockMvc.perform(post("/v1/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_responses_stream_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(), "input", "你好", "stream", true
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();

        String responseBody = completed.getResponse().getContentAsString();
        assertThat(responseBody)
                .contains("event: response.created\n")
                .contains("event: response.output_text.delta\n")
                .contains("event: response.completed\n")
                .contains("\"type\":\"response.output_text.delta\"")
                .contains("\"delta\":\"你好\"")
                .contains("\"model\":\"gpt-responses-stream\"")
                .doesNotContain("upstream-gpt-responses-stream");
        String upstreamBody = upstream.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(upstreamBody)
                .contains("\"model\":\"upstream-gpt-responses-stream\"")
                .contains("\"stream\":true")
                .doesNotContain("stream_options");
        upstream.verify(1, postRequestedFor(urlEqualTo("/responses-stream/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential)));
        Map<String, Object> log = jdbcTemplate.queryForMap("""
                SELECT input_tokens, output_tokens, cached_tokens, billed_amount, streaming, status_code
                  FROM request_logs
                 WHERE request_id = 'req_responses_stream_001'
                """);
        assertThat(((Number) log.get("input_tokens")).longValue()).isEqualTo(90L);
        assertThat(((Number) log.get("output_tokens")).longValue()).isEqualTo(10L);
        assertThat(((Number) log.get("cached_tokens")).longValue()).isEqualTo(20L);
        assertThat((Boolean) log.get("streaming")).isTrue();
        assertThat(((Number) log.get("status_code")).intValue()).isEqualTo(200);
        assertThat((BigDecimal) log.get("billed_amount")).isGreaterThan(BigDecimal.ZERO);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_responses_stream_001'",
                String.class
        )).isEqualTo("settled");
    }

    @Test
    void responsesStreamsCreatedEventBeforeUpstreamCompletion() throws Exception {
        HttpServer delayedUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch firstEventSent = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        byte[] created = """
                data: {"type":"response.created","response":{"id":"resp_realtime","model":"upstream-gpt-responses-realtime","status":"in_progress"}}

                data: {"type":"response.output_text.delta","delta":"visible before completion"}

                """.getBytes(StandardCharsets.UTF_8);
        byte[] completed = """
                data: {"type":"response.output_text.delta","delta":"realtime"}

                data: {"type":"response.completed","response":{"id":"resp_realtime","model":"upstream-gpt-responses-realtime","status":"completed","usage":{"input_tokens":4,"output_tokens":1,"total_tokens":5}}}

                """.getBytes(StandardCharsets.UTF_8);
        delayedUpstream.createContext("/responses-realtime/v1/responses", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(created);
                output.flush();
                firstEventSent.countDown();
                if (!allowCompletion.await(10, TimeUnit.SECONDS)) return;
                output.write(completed);
                output.flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        delayedUpstream.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RegisteredUser user = register("responses-realtime@example.com");
            fund(user.id());
            Configuration config = configure("gpt-responses-realtime", "1", "2");
            addRouteAtBase(
                    config,
                    "http://127.0.0.1:" + delayedUpstream.getAddress().getPort()
                            + "/responses-realtime/v1/responses",
                    1, 10, true, "responses-realtime-secret"
            );
            CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);

            Future<MvcResult> pendingCall = executor.submit(() -> mockMvc.perform(post("/v1/responses")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                            .header("X-Request-Id", "req_responses_realtime_001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "model", config.publicModel(), "input", "realtime", "stream", true
                            ))))
                    .andExpect(request().asyncStarted())
                    .andReturn());

            assertThat(firstEventSent.await(3, TimeUnit.SECONDS)).isTrue();
            MvcResult pending = pendingCall.get(3, TimeUnit.SECONDS);
            long visibleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!pending.getResponse().getContentAsString().contains("response.created")
                    && System.nanoTime() < visibleDeadline) {
                Thread.sleep(10);
            }
            assertThat(pending.getResponse().getContentAsString())
                    .contains("response.created")
                    .contains("response.output_text.delta")
                    .contains("visible before completion")
                    .doesNotContain("response.completed");

            allowCompletion.countDown();
            MvcResult finished = mockMvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(finished.getResponse().getContentAsString())
                    .contains("response.output_text.delta")
                    .contains("response.completed");
        } finally {
            allowCompletion.countDown();
            executor.shutdownNow();
            delayedUpstream.stop(0);
        }
    }

    @Test
    void responsesCompletedEventWinsOverPrematureTransportClose() throws Exception {
        HttpServer partialUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] createdEvent = """
                data: {"type":"response.created","response":{"id":"resp_terminal_1","model":"upstream-gpt-responses-terminal","status":"in_progress"}}

                """.getBytes(StandardCharsets.UTF_8);
        byte[] completedEvent = """
                data: {"type":"response.completed","response":{"id":"resp_terminal_1","model":"upstream-gpt-responses-terminal","status":"completed","usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15}}}

                """.getBytes(StandardCharsets.UTF_8);
        partialUpstream.createContext("/responses-terminal/v1/responses", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
            exchange.sendResponseHeaders(200, createdEvent.length + completedEvent.length + 128L);
            try (var output = exchange.getResponseBody()) {
                output.write(createdEvent);
                output.flush();
                Thread.sleep(25);
                output.write(completedEvent);
                output.flush();
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        partialUpstream.start();
        try {
            RegisteredUser user = register("responses-terminal@example.com");
            fund(user.id());
            Configuration config = configure("gpt-responses-terminal", "1", "2");
            addRouteAtBase(
                    config,
                    "http://127.0.0.1:" + partialUpstream.getAddress().getPort()
                            + "/responses-terminal/v1/responses",
                    1, 10, true, "responses-terminal-secret"
            );
            CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);

            MvcResult pending = mockMvc.perform(post("/v1/responses")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                            .header("X-Request-Id", "req_responses_terminal_001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "model", config.publicModel(), "input", "terminal", "stream", true
                            ))))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(completed.getResponse().getContentAsString()).contains("response.completed");
            Map<String, Object> log = jdbcTemplate.queryForMap("""
                    SELECT status_code, platform_error_code, input_tokens, output_tokens, billed_amount
                      FROM request_logs
                     WHERE request_id = 'req_responses_terminal_001'
                    """);
            assertThat(((Number) log.get("status_code")).intValue()).isEqualTo(200);
            assertThat(log.get("platform_error_code")).isNull();
            assertThat(((Number) log.get("input_tokens")).longValue()).isEqualTo(12L);
            assertThat(((Number) log.get("output_tokens")).longValue()).isEqualTo(3L);
            assertThat((BigDecimal) log.get("billed_amount")).isGreaterThan(BigDecimal.ZERO);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT outcome FROM upstream_attempt_logs WHERE request_id = 'req_responses_terminal_001'",
                    String.class
            )).isEqualTo("success");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM wallet_reservations WHERE request_id = 'req_responses_terminal_001'",
                    String.class
            )).isEqualTo("settled");
        } finally {
            partialUpstream.stop(0);
        }
    }

    @Test
    void responsesFailureBeforeGeneratedOutputReleasesReservation() throws Exception {
        RegisteredUser user = register("responses-no-output-failure@example.com");
        fund(user.id());
        Configuration config = configure("gpt-responses-no-output-failure", "1", "2");
        addRouteAtBase(config, upstream.baseUrl() + "/responses-no-output-failure/v1/responses", 1, 10, true,
                "responses-no-output-failure-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-no-output-failure/v1/responses"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                data: {"type":"response.created","response":{"id":"resp_no_output","model":"upstream-gpt-responses-no-output-failure","status":"in_progress"}}

                                """)));

        MvcResult pending = mockMvc.perform(post("/v1/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_responses_no_output_failure_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(), "input", "no output", "stream", true
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(completed.getResponse().getContentAsString())
                .contains("event: response.created\n")
                .contains("event: response.failed\n")
                .doesNotContain("response.completed");
        Map<String, Object> log = jdbcTemplate.queryForMap("""
                SELECT status_code, platform_error_code, output_tokens, billed_amount
                  FROM request_logs
                 WHERE request_id = 'req_responses_no_output_failure_001'
                """);
        assertThat(((Number) log.get("status_code")).intValue()).isEqualTo(502);
        assertThat(log.get("platform_error_code")).isEqualTo("upstream_protocol_error");
        assertThat(((Number) log.get("output_tokens")).longValue()).isZero();
        assertThat((BigDecimal) log.get("billed_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_responses_no_output_failure_001'",
                String.class
        )).isEqualTo("released");
    }

    @Test
    void responsesFirstFailureEventRetriesBeforeCommit() throws Exception {
        RegisteredUser user = register("responses-first-failure@example.com");
        fund(user.id());
        Configuration config = configure("gpt-responses-retry", "1", "2");
        String url = upstream.baseUrl() + "/responses-retry/v1/responses";
        addRouteAtBase(config, url, 1, 10, true, "responses-first-secret");
        addRouteAtBase(config, url, 20, 10, true, "responses-second-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-retry/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-first-secret"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\"}}\n\n")));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-retry/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-second-secret"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                data: {"type":"response.created","response":{"id":"resp_retry_1","model":"upstream-gpt-responses-retry","status":"in_progress"}}

                                data: {"type":"response.completed","response":{"id":"resp_retry_1","model":"upstream-gpt-responses-retry","status":"completed","usage":{"input_tokens":4,"output_tokens":1}}}

                                """)));

        MvcResult pending = mockMvc.perform(post("/v1/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_responses_retry_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(),
                                "input", "retry",
                                "prompt_cache_key", "multi-route-cache-key",
                                "client_metadata", Map.of("thread_id", "multi-route-cache-key"),
                                "stream", true
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(completed.getResponse().getContentAsString()).contains("response.completed");
        upstream.verify(1, postRequestedFor(urlEqualTo("/responses-retry/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-first-secret")));
        upstream.verify(1, postRequestedFor(urlEqualTo("/responses-retry/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-second-secret")));
        var routeSwitchBodies = upstream.getAllServeEvents().stream()
                .map(event -> event.getRequest().getBodyAsString())
                .map(body -> {
                    try {
                        return objectMapper.readTree(body);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
        assertThat(routeSwitchBodies).hasSize(2);
        assertThat(routeSwitchBodies).allMatch(body -> body.has("prompt_cache_key"));
        assertThat(routeSwitchBodies).allMatch(body -> body.has("client_metadata"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT retry_count FROM request_logs WHERE request_id = 'req_responses_retry_001'",
                Integer.class
        )).isOne();
    }

    @Test
    void responsesSingleRouteDisconnectRetriesBeforeCommit() throws Exception {
        RegisteredUser user = register("responses-single-route-retry@example.com");
        fund(user.id());
        Configuration config = configure("gpt-responses-single-route-retry", "1", "2");
        String url = upstream.baseUrl() + "/responses-single-route-retry/v1/responses";
        addRouteAtBase(config, url, 1, 10, true, "responses-single-route-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        String scenario = "single-route-precommit-disconnect";
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-single-route-retry/v1/responses"))
                .inScenario(scenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("failed-once")
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-single-route-retry/v1/responses"))
                .inScenario(scenario)
                .whenScenarioStateIs("failed-once")
                .willSetStateTo("failed-twice")
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-single-route-retry/v1/responses"))
                .inScenario(scenario)
                .whenScenarioStateIs("failed-twice")
                .willSetStateTo("recovered")
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-single-route-retry/v1/responses"))
                .inScenario(scenario)
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                data: {"type":"response.created","response":{"id":"resp_single_retry","model":"upstream-gpt-responses-single-route-retry","status":"in_progress"}}

                                data: {"type":"response.output_text.delta","delta":"recovered"}

                                data: {"type":"response.completed","response":{"id":"resp_single_retry","model":"upstream-gpt-responses-single-route-retry","status":"completed","usage":{"input_tokens":4,"output_tokens":1}}}

                                """)));

        MvcResult pending = mockMvc.perform(post("/v1/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_responses_single_route_retry_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(), "input", "retry", "stream", true
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(completed.getResponse().getContentAsString())
                .contains("response.completed")
                .doesNotContain("response.failed");
        upstream.verify(4, postRequestedFor(urlEqualTo("/responses-single-route-retry/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-single-route-secret")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT retry_count FROM request_logs WHERE request_id = 'req_responses_single_route_retry_001'",
                Integer.class
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList(
                "SELECT outcome FROM upstream_attempt_logs "
                        + "WHERE request_id = 'req_responses_single_route_retry_001' ORDER BY attempt_no",
                String.class
        )).containsExactly("supplier_failure", "supplier_failure", "supplier_failure", "success");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_summary FROM upstream_attempt_logs "
                        + "WHERE request_id = 'req_responses_single_route_retry_001' AND attempt_no = 0",
                String.class
        )).isEqualTo("upstream_connection_premature_close");
    }

    @Test
    void responsesDisconnectAfterVisibleOutputFailsWithoutReplay() throws Exception {
        HttpServer partialUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        byte[] partialCreated = """
                data: {"type":"response.created","response":{"id":"resp_partial","model":"upstream-gpt-responses-midstream","status":"in_progress"}}

                """.getBytes(StandardCharsets.UTF_8);
        byte[] partialDelta = """
                data: {"type":"response.output_text.delta","delta":"partial output is visible"}

                """.getBytes(StandardCharsets.UTF_8);
        byte[] successful = """
                data: {"type":"response.created","response":{"id":"resp_recovered","model":"upstream-gpt-responses-midstream","status":"in_progress"}}

                data: {"type":"response.output_text.delta","delta":"recovered"}

                data: {"type":"response.completed","response":{"id":"resp_recovered","model":"upstream-gpt-responses-midstream","status":"completed","usage":{"input_tokens":4,"output_tokens":1,"total_tokens":5}}}

                """.getBytes(StandardCharsets.UTF_8);
        partialUpstream.createContext("/responses-midstream/v1/responses", exchange -> {
            boolean fail = calls.incrementAndGet() < 4;
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
            long responseLength = fail
                    ? partialCreated.length + partialDelta.length + 128L
                    : successful.length;
            exchange.sendResponseHeaders(200, responseLength);
            try (var output = exchange.getResponseBody()) {
                if (fail) {
                    output.write(partialCreated);
                    output.flush();
                    Thread.sleep(25);
                    output.write(partialDelta);
                    output.flush();
                    Thread.sleep(25);
                } else {
                    output.write(successful);
                    output.flush();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        partialUpstream.start();
        try {
            RegisteredUser user = register("responses-midstream-retry@example.com");
            fund(user.id());
            Configuration config = configure("gpt-responses-midstream", "1", "2");
            addRouteAtBase(
                    config,
                    "http://127.0.0.1:" + partialUpstream.getAddress().getPort()
                            + "/responses-midstream/v1/responses",
                    1, 10, true, "responses-midstream-secret"
            );
            CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);

            MvcResult pending = mockMvc.perform(post("/v1/responses")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                            .header("X-Request-Id", "req_responses_midstream_retry_001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "model", config.publicModel(), "input", "retry", "stream", true
                            ))))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(completed.getResponse().getContentAsString())
                    .contains("event: response.created\n")
                    .contains("event: response.output_text.delta\n")
                    .contains("event: response.failed\n")
                    .contains("response.created")
                    .contains("partial output is visible")
                    .contains("response.failed")
                    .doesNotContain("response.completed")
                    .doesNotContain("recovered");
            assertThat(calls.get()).isOne();
            assertThat(jdbcTemplate.queryForList(
                    "SELECT outcome FROM upstream_attempt_logs "
                            + "WHERE request_id = 'req_responses_midstream_retry_001' ORDER BY attempt_no",
                    String.class
            )).containsExactly("supplier_failure");
            Map<String, Object> requestLog = jdbcTemplate.queryForMap("""
                    SELECT status_code, retry_count, billed_amount
                      FROM request_logs
                     WHERE request_id = 'req_responses_midstream_retry_001'
                    """);
            assertThat(((Number) requestLog.get("status_code")).intValue()).isEqualTo(502);
            assertThat(((Number) requestLog.get("retry_count")).intValue()).isZero();
            assertThat((BigDecimal) requestLog.get("billed_amount")).isGreaterThan(BigDecimal.ZERO);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM wallet_reservations WHERE request_id = 'req_responses_midstream_retry_001'",
                    String.class
            )).isEqualTo("settled");
        } finally {
            partialUpstream.stop(0);
        }
    }

    @Test
    void responsesSingleRouteRetryEscapesCacheAndSessionAffinity() throws Exception {
        RegisteredUser user = register("responses-cache-escape@example.com");
        fund(user.id());
        Configuration config = configure("gpt-responses-cache-escape", "1", "2");
        String url = upstream.baseUrl() + "/responses-cache-escape/v1/responses";
        addRouteAtBase(config, url, 1, 10, true, "responses-cache-escape-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        String scenario = "responses-cache-affinity-escape";
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-cache-escape/v1/responses"))
                .inScenario(scenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("cache-bypassed")
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-cache-escape/v1/responses"))
                .inScenario(scenario)
                .whenScenarioStateIs("cache-bypassed")
                .willSetStateTo("affinity-bypassed")
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-cache-escape/v1/responses"))
                .inScenario(scenario)
                .whenScenarioStateIs("affinity-bypassed")
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                data: {"type":"response.created","response":{"id":"resp_cache_escape","model":"upstream-gpt-responses-cache-escape","status":"in_progress"}}

                                data: {"type":"response.output_text.delta","delta":"recovered"}

                                data: {"type":"response.completed","response":{"id":"resp_cache_escape","model":"upstream-gpt-responses-cache-escape","status":"completed","usage":{"input_tokens":8,"output_tokens":1}}}

                                """)));

        MvcResult pending = mockMvc.perform(post("/v1/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_responses_cache_escape_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(),
                                "input", "retry sticky session",
                                "prompt_cache_key", "thread-cache-key",
                                "prompt_cache_retention", "24h",
                                "client_metadata", Map.of("thread_id", "thread-cache-key"),
                                "stream", true
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(completed.getResponse().getContentAsString())
                .contains("response.completed")
                .contains("recovered")
                .doesNotContain("response.failed");
        var requestBodies = upstream.getAllServeEvents().stream()
                .map(event -> event.getRequest().getBodyAsString())
                .map(body -> {
                    try {
                        return objectMapper.readTree(body);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
        assertThat(requestBodies).hasSize(3);
        assertThat(requestBodies.stream().filter(body -> body.has("prompt_cache_key")).count()).isOne();
        assertThat(requestBodies.stream().filter(body -> body.has("client_metadata")).count()).isEqualTo(2L);
        Map<String, Object> cacheEscapeLog = jdbcTemplate.queryForMap("""
                SELECT retry_count, route_switch_reason
                  FROM request_logs
                 WHERE request_id = 'req_responses_cache_escape_001'
                """);
        assertThat(((Number) cacheEscapeLog.get("retry_count")).intValue()).isEqualTo(2);
        assertThat((String) cacheEscapeLog.get("route_switch_reason"))
                .contains("responses_cache_bypass")
                .contains("responses_session_affinity_bypass");
        assertThat(jdbcTemplate.queryForList(
                "SELECT outcome FROM upstream_attempt_logs "
                        + "WHERE request_id = 'req_responses_cache_escape_001' ORDER BY attempt_no",
                String.class
        )).containsExactly("supplier_failure", "supplier_failure", "success");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_responses_cache_escape_001'",
                String.class
        )).isEqualTo("settled");
    }

    @Test
    void responsesFailureAfterCreatedEventDoesNotReplayOnAnotherRoute() throws Exception {
        RegisteredUser user = register("responses-late-failure@example.com");
        fund(user.id());
        Configuration config = configure("gpt-responses-late-failure", "1", "2");
        String url = upstream.baseUrl() + "/responses-late-failure/v1/responses";
        addRouteAtBase(config, url, 1, 10, true, "responses-late-first-secret");
        addRouteAtBase(config, url, 20, 10, true, "responses-late-second-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        StringBuilder failedStream = new StringBuilder("""
                data: {"type":"response.created","response":{"id":"resp_failed_1","model":"upstream-gpt-responses-late-failure","status":"in_progress"}}

                """);
        for (int index = 0; index < 20; index++) {
            failedStream.append("data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"internal reasoning ")
                    .append(index)
                    .append("\"}\n\n");
        }
        failedStream.append("data: {\"type\":\"response.failed\",\"response\":{\"id\":\"resp_failed_1\",\"model\":\"upstream-gpt-responses-late-failure\",\"status\":\"failed\"}}\n\n");
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-late-failure/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-late-first-secret"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody(failedStream.toString())));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/responses-late-failure/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-late-second-secret"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                data: {"type":"response.created","response":{"id":"resp_recovered_1","model":"upstream-gpt-responses-late-failure","status":"in_progress"}}

                                data: {"type":"response.output_text.delta","delta":"recovered"}

                                data: {"type":"response.completed","response":{"id":"resp_recovered_1","model":"upstream-gpt-responses-late-failure","status":"completed","usage":{"input_tokens":4,"output_tokens":1}}}

                                """)));

        MvcResult pending = mockMvc.perform(post("/v1/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_responses_late_failure_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(), "input", "fail", "stream", true
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(completed.getResponse().getContentAsString())
                .contains("event: response.created\n")
                .contains("event: response.failed\n")
                .contains("response.created")
                .contains("response.failed")
                .contains("internal reasoning")
                .doesNotContain("response.completed")
                .doesNotContain("recovered");
        upstream.verify(1, postRequestedFor(urlEqualTo("/responses-late-failure/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-late-first-secret")));
        upstream.verify(0, postRequestedFor(urlEqualTo("/responses-late-failure/v1/responses"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer responses-late-second-secret")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status_code FROM request_logs WHERE request_id = 'req_responses_late_failure_001'",
                Integer.class
        )).isEqualTo(502);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT billed_amount FROM request_logs WHERE request_id = 'req_responses_late_failure_001'",
                BigDecimal.class
        )).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_responses_late_failure_001'",
                String.class
        )).isEqualTo("released");
    }

    @Test
    void embeddingsUsesTokenBillingAndRestoresPublicModel() throws Exception {
        RegisteredUser user = register("embeddings-capability@example.com");
        fund(user.id());
        Configuration config = configureCapability(
                "text-embedding-test", "embedding", "embedding", 4,
                "1.000000000000", "million_tokens"
        );
        String upstreamCredential = "embedding-upstream-secret";
        addRoute(config, "/embedding/v1/embeddings", 1, 10, true, upstreamCredential);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/embedding/v1/embeddings"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],\"model\":\"upstream-text-embedding-test\",\"usage\":{\"prompt_tokens\":8,\"total_tokens\":8}}")));

        mockMvc.perform(post("/v1/embeddings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_embedding_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(), "input", List.of("你好", "世界")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value(config.publicModel()))
                .andExpect(jsonPath("$.data[0].embedding[1]").value(0.2));

        upstream.verify(1, postRequestedFor(urlEqualTo("/embedding/v1/embeddings"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(
                        "upstream-text-embedding-test"
                )));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_snapshot->>'input_tokens' FROM request_billing_details WHERE request_id = 'req_embedding_001'",
                Integer.class
        )).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_snapshot->>'output_tokens' FROM request_billing_details WHERE request_id = 'req_embedding_001'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status_code FROM request_logs WHERE request_id = 'req_embedding_001'", Integer.class
        )).isEqualTo(200);
    }

    @Test
    void audioSpeechReturnsBinaryAndSettlesByInputCharacters() throws Exception {
        RegisteredUser user = register("audio-speech@example.com");
        fund(user.id());
        Configuration config = configureCapability(
                "tts-test", "audio", "audio", 5,
                "100.000000000000", "character"
        );
        String upstreamCredential = "speech-upstream-secret";
        addRoute(config, "/v1/audio/speech", 1, 10, true, upstreamCredential);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        byte[] audio = new byte[]{0x49, 0x44, 0x33, 0x01, 0x02, 0x03};
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/audio/speech"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                        .withBody(audio)));

        mockMvc.perform(post("/v1/audio/speech")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_audio_speech_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(),
                                "input", "你好",
                                "voice", "alloy",
                                "response_format", "mp3"
                        ))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"))
                .andExpect(content().bytes(audio));

        upstream.verify(1, postRequestedFor(urlEqualTo("/v1/audio/speech"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("upstream-tts-test")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_snapshot->>'character_count' FROM request_billing_details WHERE request_id = 'req_audio_speech_001'",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_audio_speech_001'", String.class
        )).isEqualTo("settled");
    }

    @Test
    void audioTranscriptionUsesVerboseJsonDurationAndReturnsRequestedJson() throws Exception {
        RegisteredUser user = register("audio-transcription@example.com");
        fund(user.id());
        Configuration config = configureCapability(
                "whisper-test", "audio", "audio_transcription", 6,
                "0.100000000000", "second"
        );
        String upstreamCredential = "transcription-upstream-secret";
        addRoute(config, "/v1/audio/transcriptions", 1, 10, true, upstreamCredential);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/v1/audio/transcriptions"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"task\":\"transcribe\",\"language\":\"zh\",\"duration\":2.5,\"text\":\"测试完成\",\"segments\":[{\"id\":0,\"start\":0.0,\"end\":2.5,\"text\":\"测试完成\"}]}")));
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.mp3", "audio/mpeg", new byte[]{0x49, 0x44, 0x33, 0x01}
        );

        mockMvc.perform(multipart("/v1/audio/transcriptions")
                        .file(file)
                        .param("model", config.publicModel())
                        .param("response_format", "json")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_audio_transcription_001"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.text").value("测试完成"));

        upstream.verify(1, postRequestedFor(urlEqualTo("/v1/audio/transcriptions"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("verbose_json"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("sample.mp3")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_snapshot->>'duration_millis' FROM request_billing_details WHERE request_id = 'req_audio_transcription_001'",
                Long.class
        )).isEqualTo(2_500L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_audio_transcription_001'", String.class
        )).isEqualTo("settled");
    }

    @Test
    void embeddingAndAudioValidationRejectsBeforeBillingAndUpstream() throws Exception {
        RegisteredUser user = register("new-capability-validation@example.com");
        Configuration embedding = configureCapability(
                "embedding-validation", "embedding", "embedding", 4,
                "1.000000000000", "million_tokens"
        );
        addRoute(embedding, "/embedding-validation/v1/embeddings", 1, 10, true, "validation-secret");
        CreatedKey key = createKey(user.session(), embedding, List.of(embedding.modelId()), 60, 100_000L, 5);

        mockMvc.perform(post("/v1/embeddings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_embedding_invalid_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", embedding.publicModel(), "input", List.of("ok", 1)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        mockMvc.perform(multipart("/v1/audio/transcriptions")
                        .file(new MockMultipartFile(
                                "file", "invalid.txt", MediaType.TEXT_PLAIN_VALUE,
                                "not audio".getBytes(StandardCharsets.UTF_8)
                        ))
                        .param("model", embedding.publicModel())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_audio_invalid_001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_reservations WHERE request_id IN ('req_embedding_invalid_001','req_audio_invalid_001')",
                Integer.class
        )).isZero();
    }

    @Test
    void speechCannotUseTranscriptionUpstreamAddressEvenThoughBothAreAudioModels() throws Exception {
        RegisteredUser user = register("audio-interface-isolation@example.com");
        Configuration whisper = configureCapability(
                "whisper-interface-isolation", "audio", "audio_transcription", 6,
                "0.100000000000", "second"
        );
        UUID transcriptionChannel = addRoute(
                whisper, "/audio-isolation/v1/transcriptions", 1, 10, true, "audio-isolation-secret"
        );
        jdbcTemplate.update(
                "UPDATE channels SET operation_code = 'audio_transcriptions' WHERE id = ?",
                transcriptionChannel
        );
        CreatedKey key = createKey(user.session(), whisper, List.of(whisper.modelId()), 60, 100_000L, 5);

        mockMvc.perform(post("/v1/audio/speech")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_audio_interface_isolation_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", whisper.publicModel(),
                                "input", "不能进入语音合成",
                                "voice", "alloy"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_reservations WHERE request_id = 'req_audio_interface_isolation_001'",
                Integer.class
        )).isZero();
    }

    @Test
    void dynamicRouteWorksWithoutChannelModelOverrideAndKeepsAttemptLogs() throws Exception {
        RegisteredUser user = register("dynamic-route@example.com");
        fund(user.id());
        Configuration config = configure("gpt-dynamic-route", "0.1", "0.2");
        UUID channelId = addRoute(config, "/dynamic/v1", 1, 10, true, "dynamic-route-secret");
        jdbcTemplate.update("UPDATE channels SET metadata = metadata - 'upstream_models' WHERE id = ?", channelId);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/dynamic/v1/chat/completions")
        ).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"chatcmpl_dynamic\",\"model\":\"gpt-dynamic-route\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}")));

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_dynamic_route_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest(config.publicModel(), false)))
                .andExpect(request().asyncStarted())
                .andDo(result -> mockMvc.perform(asyncDispatch(result))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.model").value(config.publicModel())));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT channel_model_id FROM request_logs WHERE request_id = 'req_dynamic_route_001'",
                UUID.class
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT upstream_model FROM request_logs WHERE request_id = 'req_dynamic_route_001'",
                String.class
        )).isEqualTo(config.publicModel());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT channel_model_id FROM upstream_attempt_logs WHERE request_id = 'req_dynamic_route_001'",
                UUID.class
        )).isNull();
    }

    @Test
    void transportMetadataDoesNotOverrideRoutePriorityAndWeight() {
        Configuration config = configure("gpt-active-active-ranking", "0.1", "0.2");
        UUID channelId = addRoute(config, "/active-active/v1", 7, 10, true, "active-active-secret");
        jdbcTemplate.update("UPDATE channels SET metadata = metadata || '{\"priority\":9999,\"weight\":9999}'::jsonb WHERE id = ?", channelId);

        List<RuntimeRouteRow> routes = routingMapper.findCandidates(
                config.groupId(), config.modelId(), "chat_completions"
        );

        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().getEffectivePriority()).isEqualTo(14L);
        assertThat(routes.getFirst().getEffectiveWeight()).isEqualTo(10_000L);
    }

    @Test
    void videoAndImageTaskCapabilitiesUseTheirOwnChannels() throws Exception {
        RegisteredUser user = register("media-capabilities@example.com");
        fund(user.id());

        Configuration video = configureMedia("seedance-2.5", "video", "jimeng_video");
        UUID videoChannel = addRouteAtBase(video, upstream.baseUrl() + "/v1/videos", 1, 10, true, "video-secret");
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, videoChannel, video.modelId(),
                "bytedance/seedance-2.5", "0", "0", "0");
        jdbcTemplate.update("UPDATE channels SET operation_code = 'video_create', request_method = 'POST' WHERE id = ?", videoChannel);
        CreatedKey videoKey = createKey(user.session(), video, List.of(video.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/videos")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"video_1\",\"status\":\"queued\",\"model\":\"bytedance/seedance-2.5\"}")));

        mockMvc.perform(post("/v1/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + videoKey.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", video.publicModel(), "prompt", "海边日落", "duration", 5
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("video_1"))
                .andExpect(jsonPath("$.model").value(video.publicModel()));

        upstream.verify(1, postRequestedFor(urlPathEqualTo("/v1/videos"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.model", equalTo("bytedance/seedance-2.5"))));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_snapshot->>'duration_millis' FROM request_billing_details ORDER BY created_at DESC LIMIT 1",
                Long.class
        )).isEqualTo(5_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT settled_amount FROM request_billing_details ORDER BY created_at DESC LIMIT 1",
                BigDecimal.class
        )).isEqualByComparingTo("0.500000000000");

        Configuration image = configureMedia("image-task-capability", "image", "openai_image_tasks");
        UUID imageChannel = addRouteAtBase(image, upstream.baseUrl() + "/v1/images/tasks", 1, 10, true, "image-task-secret");
        jdbcTemplate.update("UPDATE channels SET operation_code = 'image_task_create', request_method = 'POST' WHERE id = ?", imageChannel);
        CreatedKey imageKey = createKey(user.session(), image, List.of(image.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/images/tasks")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"image_task_1\",\"status\":\"queued\",\"model\":\"upstream-image-task-capability\"}")));

        mockMvc.perform(post("/v1/images/tasks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + imageKey.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", image.publicModel(), "prompt", "人物肖像"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("image_task_1"));
    }

    @Test
    void videoCreateIgnoresMultipleDocumentBindings() throws Exception {
        RegisteredUser user = register("video-interface-ambiguous@example.com");
        fund(user.id());
        Configuration video = configureMedia("video-interface-ambiguous", "video", "jimeng_video");
        addRouteAtBase(video, upstream.baseUrl() + "/v1/videos", 1, 10, true, "video-ambiguous-secret");
        bindInterface(video.modelId(), "grok_video");
        CreatedKey key = createKey(user.session(), video, List.of(video.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/videos"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"video_docs_ignored\",\"status\":\"queued\",\"model\":\"upstream-video-interface-ambiguous\"}")));

        mockMvc.perform(post("/v1/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_video_default_duration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", video.publicModel(), "prompt", "海边日落"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("video_docs_ignored"));
        upstream.verify(1, postRequestedFor(urlPathEqualTo("/v1/videos")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_snapshot->>'duration_millis' FROM request_billing_details WHERE request_id = 'req_video_default_duration'",
                Long.class
        )).isEqualTo(5_000L);
    }

    @Test
    void videoCreateUsesRequestedDurationAndRejectsInvalidDuration() throws Exception {
        RegisteredUser user = register("video-duration-billing@example.com");
        fund(user.id());
        Configuration video = configureMedia("video-duration-billing", "video", "jimeng_video");
        UUID channel = addRouteAtBase(
                video, upstream.baseUrl() + "/v1/videos", 1, 10, true, "video-duration-secret"
        );
        jdbcTemplate.update("UPDATE channels SET operation_code = 'video_create', request_method = 'POST' WHERE id = ?", channel);
        CreatedKey key = createKey(user.session(), video, List.of(video.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/videos"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"video_duration_1\",\"status\":\"queued\"}")));

        mockMvc.perform(post("/v1/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_video_duration_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", video.publicModel(), "prompt", "测试", "duration", 1
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("video_duration_1"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_snapshot->>'duration_millis' FROM request_billing_details WHERE request_id = 'req_video_duration_001'",
                Long.class
        )).isEqualTo(1_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT settled_amount FROM request_billing_details WHERE request_id = 'req_video_duration_001'",
                BigDecimal.class
        )).isEqualByComparingTo("0.100000000000");

        mockMvc.perform(post("/v1/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_video_duration_invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", video.publicModel(), "prompt", "测试", "duration", 0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        upstream.verify(1, postRequestedFor(urlPathEqualTo("/v1/videos")));
    }

    @Test
    void videoListGetForwardsQueryWithoutCharging() throws Exception {
        RegisteredUser user = register("video-list-capability@example.com");
        Configuration config = configureMedia("video-list-capability", "video", "openai_video_list");
        UUID channelId = addRouteAtBase(config, upstream.baseUrl() + "/v1/videos", 1, 10, true, "video-list-secret");
        jdbcTemplate.update("UPDATE channels SET operation_code = 'video_list', request_method = 'GET' WHERE id = ?", channelId);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/v1/videos")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"list\",\"data\":[],\"has_more\":false}")));

        mockMvc.perform(get("/v1/videos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .param("model", config.publicModel())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"));
        upstream.verify(1, getRequestedFor(urlPathEqualTo("/v1/videos"))
                .withQueryParam("model", equalTo("upstream-video-list-capability"))
                .withQueryParam("limit", equalTo("10")));
    }

    @Test
    void videoTaskGetAppendsSafeTaskIdWithoutCharging() throws Exception {
        RegisteredUser user = register("video-task-capability@example.com");
        Configuration config = configureMedia("video-task-capability", "video", "openai_video_list");
        UUID channelId = addRouteAtBase(config, upstream.baseUrl() + "/v1/videos", 1, 10, true, "video-task-secret");
        jdbcTemplate.update("UPDATE channels SET operation_code = 'video_detail', request_method = 'GET' WHERE id = ?", channelId);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/v1/videos/video_task_1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"video_task_1\",\"status\":\"completed\",\"model\":\"upstream-video-task-capability\"}")));

        mockMvc.perform(get("/v1/videos/video_task_1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .param("model", config.publicModel()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("video_task_1"))
                .andExpect(jsonPath("$.model").value(config.publicModel()));
        upstream.verify(1, getRequestedFor(urlPathEqualTo("/v1/videos/video_task_1"))
                .withQueryParam("model", equalTo("upstream-video-task-capability")));
    }

    @Test
    void modelWithoutDocumentBindingsStillCallsConfiguredUpstream() throws Exception {
        RegisteredUser user = register("protocol-missing@example.com");
        fund(user.id());
        Configuration config = configure("gpt-protocol-missing", "1", "2");
        addRoute(config, "/protocol-missing/v1", 1, 10, true, "protocol-missing-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        jdbcTemplate.update("DELETE FROM model_interfaces WHERE model_id = ?", config.modelId());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlEqualTo("/protocol-missing/v1/chat/completions")
        ).willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBody(successJson("upstream-gpt-protocol-missing"))));

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest(config.publicModel(), false)))
                .andExpect(request().asyncStarted())
                .andDo(result -> mockMvc.perform(asyncDispatch(result))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.model").value(config.publicModel())));
        upstream.verify(1, postRequestedFor(urlEqualTo("/protocol-missing/v1/chat/completions")));
    }

    @Test
    void interfaceDocumentChangesDoNotAffectRuntimeCall() throws Exception {
        RegisteredUser user = register("protocol-document-only@example.com");
        fund(user.id());
        Configuration config = configure("gpt-protocol-document-only", "1", "2");
        addRoute(config, "/protocol-document-only/v1", 1, 10, true, "protocol-document-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlEqualTo("/protocol-document-only/v1/chat/completions")
        ).willReturn(aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBody(successJson("upstream-gpt-protocol-document-only"))));
        jdbcTemplate.update("""
                UPDATE api_interfaces
                   SET status = 'disabled', http_method = 'GET', public_path = '/docs-only/chat'
                 WHERE interface_code = 'openai_chat'
                """);
        try {
            mockMvc.perform(post("/v1/chat/completions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(chatRequest(config.publicModel(), false)))
                    .andExpect(request().asyncStarted())
                    .andDo(result -> mockMvc.perform(asyncDispatch(result))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.model").value(config.publicModel())));
            upstream.verify(1, postRequestedFor(urlEqualTo("/protocol-document-only/v1/chat/completions")));
        } finally {
            jdbcTemplate.update("""
                    UPDATE api_interfaces
                       SET status = 'active', http_method = 'POST', public_path = '/v1/chat/completions'
                     WHERE interface_code = 'openai_chat'
                    """);
        }
    }

    @Test
    void openAiAdapterRejectsInvalidRequestBeforeQuotaBillingAndUpstream() throws Exception {
        RegisteredUser user = register("protocol-invalid-request@example.com");
        Configuration config = configure("gpt-protocol-invalid-request", "1", "2");
        addRoute(config, "/protocol-invalid-request/v1", 1, 10, true, "protocol-invalid-request-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_protocol_invalid_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(),
                                "stream", false
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.message").value("messages 必须是非空数组"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_reservations WHERE request_id = 'req_protocol_invalid_001'",
                Integer.class
        )).isZero();
    }

    @Test
    void disabledOrUnavailableSupplierImmediatelyRemovesItsChannelsFromNewRouting() throws Exception {
        RegisteredUser user = register("supplier-disabled@example.com");
        Configuration config = configure("gpt-supplier-disabled", "1", "2");
        UUID channelId = addRoute(config, "/supplier-disabled/v1", 1, 10, true, "disabled-secret");
        UUID supplierId = jdbcTemplate.queryForObject(
                "SELECT supplier_id FROM channels WHERE id = ?", UUID.class, channelId
        );
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        jdbcTemplate.update("""
                UPDATE suppliers
                   SET status = 'disabled', disabled_reason = 'integration test', disabled_at = now()
                 WHERE id = ?
                """, supplierId);
        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-supplier-disabled", false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));

        jdbcTemplate.update("""
                UPDATE suppliers
                   SET status = 'active', disabled_reason = NULL, disabled_at = NULL,
                       health_status = 'unavailable'
                 WHERE id = ?
                """, supplierId);
        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-supplier-disabled", false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    @Test
    void requestHeaderCannotSwitchApiKeyToAnotherServiceGroup() throws Exception {
        RegisteredUser user = register("cross-group@example.com");
        Configuration config = configure("gpt-bound-group", "1", "2");
        addRoute(config, "/bound-group/v1", 1, 10, true, "bound-secret");
        Configuration other = configure("gpt-other-group", "1", "2");
        addRoute(other, "/other-group/v1", 1, 10, true, "other-secret");
        CreatedKey key = createKey(
                user.session(), config, List.of(config.modelId()), 60, 100_000L, 5
        );

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Nexus-Group", other.groupId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-bound-group", false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("permission_denied"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    @Test
    void nonStreamingCallRoutesRewritesModelAndSettlesUsageWithoutCredentialLeak() throws Exception {
        RegisteredUser user = register("json@example.com");
        Configuration config = configure("gpt-json", "1.0000000000", "2.0000000000");
        String upstreamCredential = "wiremock-json-secret-value";
        UUID channelId = addRoute(config, "/json/v1", 10, 10, true, upstreamCredential);
        jdbcTemplate.update("""
                UPDATE channels SET metadata = jsonb_set(metadata, ARRAY['upstream_models', ?, 'config'],
                    '{"organization":"org-migrated","project":"project-migrated"}'::jsonb) WHERE id = ?
                """, config.modelId().toString(), channelId);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/json/v1/chat/completions"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {"id":"chatcmpl-upstream","object":"chat.completion","model":"upstream-gpt-json",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,
                                          "prompt_tokens_details":{"cached_tokens":20}}}
                                """)));

        MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_json_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-json", false)))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult result = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-json"))
                .andExpect(jsonPath("$.choices[0].message.content").value("hello"))
                .andReturn();

        upstream.verify(1, postRequestedFor(urlEqualTo("/json/v1/chat/completions"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential))
                .withHeader("OpenAI-Organization", equalTo("org-migrated"))
                .withHeader("OpenAI-Project", equalTo("project-migrated"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.model", equalTo("upstream-gpt-json"))));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(upstreamCredential);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_gateway_json_001'",
                String.class
        )).isEqualTo("settled");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT billed_amount FROM request_logs WHERE request_id = 'req_gateway_json_001'",
                BigDecimal.class
        )).isGreaterThan(BigDecimal.ZERO);
        Map<String, Object> costSnapshot = jdbcTemplate.queryForMap("""
                SELECT supplier_id, channel_model_id, supplier_input_price,
                       supplier_cached_input_price, supplier_output_price,
                       supplier_cost_amount, supplier_cost_currency, gross_margin_amount
                  FROM request_logs
                 WHERE request_id = 'req_gateway_json_001'
                """);
        assertThat(costSnapshot.get("supplier_id")).isNotNull();
        assertThat(costSnapshot.get("channel_model_id")).isNull();
        assertThat((BigDecimal) costSnapshot.get("supplier_input_price")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) costSnapshot.get("supplier_cached_input_price")).isEqualByComparingTo("0.25");
        assertThat((BigDecimal) costSnapshot.get("supplier_output_price")).isEqualByComparingTo("1");
        assertThat((BigDecimal) costSnapshot.get("supplier_cost_amount")).isGreaterThan(BigDecimal.ZERO);
        assertThat(costSnapshot.get("supplier_cost_currency")).isEqualTo("USD");
        assertThat((BigDecimal) costSnapshot.get("gross_margin_amount")).isNotNull();
        String persisted = jdbcTemplate.queryForObject(
                "SELECT coalesce(string_agg(coalesce(upstream_error_summary, '') || metadata::text, ''), '') FROM request_logs",
                String.class
        );
        assertThat(persisted).doesNotContain(upstreamCredential);
    }

    @Test
    void imageGenerationForwardsMultipartAndSettlesByReturnedImageQuantity() throws Exception {
        RegisteredUser user = register("image-generation@example.com");
        Configuration config = configureImage("grok-image-test");
        String upstreamCredential = "wiremock-image-secret-value";
        addRoute(config, "/image/v1", 10, 10, true, upstreamCredential);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/image/v1/images/generations"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"created\":1730000000,\"data\":[{\"url\":\"https://img.test/1.png\"},{\"url\":\"https://img.test/2.png\"}]}")));

        MvcResult result = mockMvc.perform(multipart("/v1/images/generations")
                        .file(new MockMultipartFile(
                                "images", "reference.png", MediaType.IMAGE_PNG_VALUE,
                                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00}
                        ))
                        .param("model", config.publicModel())
                        .param("prompt", "a small red boat")
                        .param("n", "3")
                        .param("aspect_ratio", "16:9")
                        .param("quality", "medium")
                        .param("extra_params", "{\"resolution\":\"1k\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_image_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].url").value("https://img.test/1.png"))
                .andReturn();

        upstream.verify(1, postRequestedFor(urlEqualTo("/image/v1/images/generations"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("grok-image-test")));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(upstreamCredential);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_gateway_image_001'",
                String.class
        )).isEqualTo("settled");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_snapshot->>'quantity' FROM request_billing_details WHERE request_id = 'req_gateway_image_001'",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status_code FROM request_logs WHERE request_id = 'req_gateway_image_001'",
                Integer.class
        )).isEqualTo(200);
    }

    @Test
    void imageGenerationWithoutReferenceImageForwardsJsonAndSettles() throws Exception {
        RegisteredUser user = register("image-generation-json@example.com");
        Configuration config = configureImage("gpt-image-json-test");
        String upstreamCredential = "wiremock-image-json-secret";
        addRoute(config, "/image-json/v1", 10, 10, true, upstreamCredential);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/image-json/v1/images/generations"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"created\":1730000000,\"data\":[{\"b64_json\":\"ZmFrZQ==\"}]}")));

        mockMvc.perform(multipart("/v1/images/generations")
                        .param("model", config.publicModel())
                        .param("prompt", "a small red boat")
                        .param("quality", "medium")
                        .param("extra_params", "{\"resolution\":\"1k\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_image_json_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].b64_json").value("ZmFrZQ=="));

        upstream.verify(1, postRequestedFor(urlEqualTo("/image-json/v1/images/generations"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("gpt-image-json-test"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("resolution"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("aspect_ratio"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("1:1")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status_code FROM request_logs WHERE request_id = 'req_gateway_image_json_001'",
                Integer.class
        )).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_gateway_image_json_001'",
                String.class
        )).isEqualTo("settled");
    }

    @Test
    void imageGenerationWithUrlReferencesForwardsJsonAndSettles() throws Exception {
        RegisteredUser user = register("image-edit-json@example.com");
        Configuration config = configureImage("gpt-image-edit-json-test");
        String upstreamCredential = "wiremock-image-edit-json-secret";
        addRoute(config, "/image-edit-json/v1", 10, 10, true, upstreamCredential);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/image-edit-json/v1/images/generations"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"created\":1730000000,\"data\":[{\"url\":\"https://img.test/edited.png\"}]}")));

        String referenceUrl = "https://nexusapi.center/dashboard-ai-light-v3.png";
        String requestBody = objectMapper.createObjectNode()
                .put("model", config.publicModel())
                .put("prompt", "保留主体，改为黄昏背景")
                .put("aspect_ratio", "1:1")
                .set("images", objectMapper.createArrayNode().add(referenceUrl))
                .toString();
        mockMvc.perform(post("/v1/images/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_image_edit_json_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value(config.publicModel()))
                .andExpect(jsonPath("$.data[0].url").value("https://img.test/edited.png"));

        upstream.verify(1, postRequestedFor(urlEqualTo("/image-edit-json/v1/images/generations"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(matchingJsonPath("$.model", equalTo("upstream-gpt-image-edit-json-test")))
                .withRequestBody(matchingJsonPath("$.images[0]", equalTo(referenceUrl))));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_gateway_image_edit_json_001'",
                String.class
        )).isEqualTo("settled");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status_code FROM request_logs WHERE request_id = 'req_gateway_image_edit_json_001'",
                Integer.class
        )).isEqualTo(200);
    }

    @Test
    void imageGenerationRejectsInvalidUrlReferenceBeforeQuotaBillingAndUpstream() throws Exception {
        RegisteredUser user = register("image-edit-invalid-url@example.com");
        Configuration config = configureImage("gpt-image-edit-invalid-url");
        addRoute(config, "/image-edit-invalid-url/v1", 10, 10, true, "unused-image-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        String requestBody = objectMapper.createObjectNode()
                .put("model", config.publicModel())
                .put("prompt", "invalid reference")
                .set("images", objectMapper.createArrayNode().add("file:///etc/passwd"))
                .toString();

        mockMvc.perform(post("/v1/images/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_image_edit_invalid_url_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_reservations WHERE request_id = 'req_gateway_image_edit_invalid_url_001'",
                Integer.class
        )).isZero();
    }

    @Test
    void imageModelChatCompatibilityReturnsMarkdownForWorkBuddy() throws Exception {
        RegisteredUser user = register("image-chat-compatibility@example.com");
        Configuration config = configureImage("gpt-image-chat-compatibility");
        String upstreamCredential = "wiremock-image-chat-secret";
        addRoute(config, "/image-chat/v1", 10, 10, true, upstreamCredential);
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/image-chat/v1/images/generations"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"created\":1730000000,\"data\":[{\"url\":\"https://img.test/workbuddy.png\"}]}")));

        MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_image_chat_compatibility_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(),
                                "messages", List.of(Map.of("role", "user", "content", "一只戴草帽的小猫")),
                                "stream", false
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.model").value(config.publicModel()))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content")
                        .value("![Generated image](https://img.test/workbuddy.png)"));

        upstream.verify(1, postRequestedFor(urlEqualTo("/image-chat/v1/images/generations"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + upstreamCredential))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("一只戴草帽的小猫")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status_code FROM request_logs WHERE request_id = 'req_image_chat_compatibility_001'",
                Integer.class
        )).isEqualTo(200);
    }

    @Test
    void imageModelChatCompatibilitySupportsStreamingClients() throws Exception {
        RegisteredUser user = register("image-chat-stream@example.com");
        Configuration config = configureImage("gpt-image-chat-stream");
        addRoute(config, "/image-chat-stream/v1", 10, 10, true, "wiremock-image-chat-stream-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/image-chat-stream/v1/images/generations"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"created\":1730000000,\"data\":[{\"url\":\"https://img.test/stream.png\"}]}")));

        MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_image_chat_stream_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", config.publicModel(),
                                "messages", List.of(Map.of("role", "user", "content", "电影感城市夜景")),
                                "stream", true
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .contains("\"object\":\"chat.completion.chunk\"")
                .contains("![Generated image](https://img.test/stream.png)")
                .contains("data: [DONE]");
        upstream.verify(1, postRequestedFor(urlEqualTo("/image-chat-stream/v1/images/generations")));
    }

    @Test
    void imageGenerationRejectsInvalidUploadBeforeQuotaBillingAndUpstream() throws Exception {
        RegisteredUser user = register("image-validation@example.com");
        Configuration config = configureImage("grok-image-validation");
        addRoute(config, "/image-validation/v1", 10, 10, true, "image-validation-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);

        MockMultipartFile invalid = new MockMultipartFile(
                "images", "not-image.txt", MediaType.TEXT_PLAIN_VALUE, "not an image".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/v1/images/generations")
                        .file(invalid)
                        .param("model", config.publicModel())
                        .param("prompt", "invalid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_image_invalid_001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        assertThat(upstream.getAllServeEvents()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_reservations WHERE request_id = 'req_gateway_image_invalid_001'",
                Integer.class
        )).isZero();
    }

    @Test
    void streamingCallPassesSseDoneAndSettlesUsage() throws Exception {
        RegisteredUser user = register("stream@example.com");
        Configuration config = configure("gpt-stream", "1", "2");
        addRoute(config, "/stream/v1", 10, 10, true, "stream-upstream-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/stream/v1/chat/completions"))
                .willReturn(aResponse()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                data: {"id":"chunk-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"hel"}}]}

                                data: {"id":"chunk-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"lo"}}],"usage":{"prompt_tokens":90,"completion_tokens":10,"total_tokens":100}}

                                data: [DONE]

                                """)));

        MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_stream_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-stream", true)))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)))
                .andReturn();

        String body = completed.getResponse().getContentAsString();
        assertThat(body)
                .contains("data: {\"id\":\"chunk-1\"")
                .contains("\"model\":\"gpt-stream\"")
                .contains("data: [DONE]");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_gateway_stream_001'",
                String.class
        )).isEqualTo("settled");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT streaming FROM request_logs WHERE request_id = 'req_gateway_stream_001'",
                Boolean.class
        )).isTrue();
    }

    @Test
    void retryableServerErrorSwitchesRouteBeforeOutput() throws Exception {
        RegisteredUser user = register("retry@example.com");
        Configuration config = configure("gpt-retry", "1", "2");
        addRoute(config, "/retry-first/v1", 1, 10, true, "first-secret");
        addRoute(config, "/retry-second/v1", 20, 10, true, "second-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/retry-first/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("internal")));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/retry-second/v1/chat/completions"))
                .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(successJson("upstream-gpt-retry"))));

        MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_retry_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-retry", false)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("ok"));

        upstream.verify(1, postRequestedFor(urlEqualTo("/retry-first/v1/chat/completions")));
        upstream.verify(1, postRequestedFor(urlEqualTo("/retry-second/v1/chat/completions")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT retry_count FROM request_logs WHERE request_id = 'req_gateway_retry_001'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM upstream_attempt_logs WHERE request_id = 'req_gateway_retry_001'",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM upstream_attempt_logs
                 WHERE request_id = 'req_gateway_retry_001'
                   AND outcome = 'supplier_failure' AND error_category = 'upstream_5xx'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM upstream_attempt_logs
                 WHERE request_id = 'req_gateway_retry_001' AND outcome = 'success'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void degradedGatewayChannelRemainsRoutableButHealthyChannelTakesPriority() throws Exception {
        RegisteredUser user = register("circuit-switch@example.com");
        Configuration config = configure("gpt-circuit-switch", "1", "2");
        UUID failedChannelId = addRoute(config, "/circuit-first/v1", 1, 10, true, "circuit-first-secret");
        addRoute(config, "/circuit-second/v1", 20, 10, true, "circuit-second-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/circuit-first/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("internal")));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/circuit-second/v1/chat/completions"))
                .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(successJson("upstream-gpt-circuit-switch"))));

        for (int index = 1; index <= 4; index++) {
            MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                            .header("X-Request-Id", "req_gateway_circuit_00" + index)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(chatRequest("gpt-circuit-switch", false)))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            // 必须等待异步线程完成响应写入后再 dispatch，避免 MockMvc 测试响应头被两个线程并发修改。
            pending.getAsyncResult(10_000);
            mockMvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.choices[0].message.content").value("ok"));
        }

        // 自动熔断已取消；故障渠道仍可路由，但降级后排在健康渠道之后。
        upstream.verify(1, postRequestedFor(urlEqualTo("/circuit-first/v1/chat/completions")));
        upstream.verify(4, postRequestedFor(urlEqualTo("/circuit-second/v1/chat/completions")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM channels WHERE id = ?", String.class, failedChannelId
        )).isEqualTo("degraded");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM channels WHERE id = ?", Integer.class, failedChannelId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT circuit_open_until IS NULL FROM channels WHERE id = ?", Boolean.class, failedChannelId
        )).isTrue();
    }

    @Test
    void deterministicClientErrorDoesNotRetry() throws Exception {
        RegisteredUser user = register("no-retry@example.com");
        Configuration config = configure("gpt-no-retry", "1", "2");
        UUID rejectedChannelId = addRoute(config, "/bad-request/v1", 1, 10, true, "bad-secret");
        addRoute(config, "/must-not-run/v1", 20, 10, true, "unused-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/bad-request/v1/chat/completions"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"error\":{\"code\":\"invalid_messages\"}}")));

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_no_retry_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-no-retry", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_messages"));

        upstream.verify(1, postRequestedFor(urlEqualTo("/bad-request/v1/chat/completions")));
        upstream.verify(0, postRequestedFor(urlEqualTo("/must-not-run/v1/chat/completions")));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM upstream_attempt_logs
                 WHERE request_id = 'req_gateway_no_retry_001'
                   AND outcome = 'upstream_rejected' AND error_category IS NULL
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM channels WHERE id = ?", Integer.class, rejectedChannelId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM channels WHERE id = ?", String.class, rejectedChannelId
        )).isEqualTo("active");
    }

    @Test
    void scopeRateAndBalanceFailuresStopBeforeUpstream() throws Exception {
        RegisteredUser user = register("guards@example.com");
        Configuration allowed = configure("allowed-model", "1", "2");
        Configuration denied = configure("denied-model", "1", "2");
        addRoute(allowed, "/allowed/v1", 1, 10, true, "allowed-secret");
        addRoute(denied, "/denied/v1", 1, 10, true, "denied-secret");
        CreatedKey scopedKey = createKey(user.session(), allowed, List.of(allowed.modelId()), 1, 100_000L, 5);

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scopedKey.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("denied-model", false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("permission_denied"));

        mockMvc.perform(get("/v1/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + scopedKey.secret()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + scopedKey.secret()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));

        RegisteredUser poor = register("poor@example.com");
        CreatedKey poorKey = createKey(poor.session(), allowed, List.of(allowed.modelId()), 60, 100_000L, 5);
        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + poorKey.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("allowed-model", false)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.code").value("insufficient_balance"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    @Test
    void terminalUpstreamFailureReleasesFrozenBalance() throws Exception {
        RegisteredUser user = register("release@example.com");
        Configuration config = configure("gpt-release", "1", "2");
        addRoute(config, "/release/v1", 1, 10, true, "release-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/release/v1/chat/completions"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_release_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-release", false)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("upstream_error"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen_credits FROM wallet_accounts WHERE user_id = ?", BigDecimal.class, user.id()
        )).isEqualByComparingTo("0.00000000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_gateway_release_001'", String.class
        )).isEqualTo("released");
    }

    @Test
    void tpmLimitRejectsEstimatedTokensBeforeUpstream() throws Exception {
        RegisteredUser user = register("tpm@example.com");
        Configuration config = configure("gpt-tpm", "1", "2");
        addRoute(config, "/tpm/v1", 1, 10, true, "tpm-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 10L, 5);

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-tpm", false)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
        assertThat(redis.opsForValue().get(apiKeyConcurrencyKey(key))).isNull();
    }

    @Test
    void ipAllowlistRejectsDisallowedClientBeforeUpstream() throws Exception {
        RegisteredUser user = register("ip-guard@example.com");
        Configuration config = configure("gpt-ip-guard", "1", "2");
        addRoute(config, "/ip-guard/v1", 1, 10, true, "ip-guard-secret");
        CreatedKey key = createKey(
                user.session(), config, List.of(config.modelId()), 60, 100_000L, 5,
                List.of("203.0.113.0/24")
        );

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.25");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-ip-guard", false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ip_not_allowed"));

        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    @Test
    void apiKeyConcurrencyRejectsSecondInFlightRequestAndReleasesLease() throws Exception {
        RegisteredUser user = register("key-concurrency@example.com");
        Configuration config = configure("gpt-key-concurrency", "1", "2");
        addRoute(config, "/key-concurrency/v1", 1, 10, true, "key-concurrency-secret");
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 1);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/key-concurrency/v1/chat/completions"))
                .willReturn(aResponse()
                        .withFixedDelay(1200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(successJson("upstream-gpt-key-concurrency"))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> startChatRequest(
                    key, "req_gateway_key_concurrency_1", "gpt-key-concurrency", false
            ));
            awaitRedisValue(apiKeyConcurrencyKey(key), "1");

            mockMvc.perform(post("/v1/chat/completions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                            .header("X-Request-Id", "req_gateway_key_concurrency_2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(chatRequest("gpt-key-concurrency", false)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));

            completeAsync(first.get(5, TimeUnit.SECONDS));
            assertThat(redis.opsForValue().get(apiKeyConcurrencyKey(key))).isNull();
            upstream.verify(1, postRequestedFor(urlEqualTo("/key-concurrency/v1/chat/completions")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void subscriptionConcurrencyIsSharedByDifferentApiKeysAndConsoleSession() {
        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        NexusApiKeyPrincipal firstKey = quotaPrincipal(UUID.randomUUID(), userId, groupId);
        NexusApiKeyPrincipal secondKey = quotaPrincipal(UUID.randomUUID(), userId, groupId);
        ConsoleGatewayPrincipal console = new ConsoleGatewayPrincipal(userId, groupId);

        GatewayQuotaService.ApiKeyLease firstLease = quotaService.acquireApiKey(
                firstKey, 0L, true, subscriptionId, 1
        );
        assertThat(redis.opsForValue().get(subscriptionConcurrencyKey(subscriptionId))).isEqualTo("1");

        assertThatThrownBy(() -> quotaService.acquireApiKey(secondKey, 0L, true, subscriptionId, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED));
        assertThat(redis.opsForValue().get(apiKeyConcurrencyKey(secondKey.apiKeyId()))).isNull();

        assertThatThrownBy(() -> quotaService.acquireApiKey(console, 0L, true, subscriptionId, 1))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.opsForValue().get(consoleConcurrencyKey(userId))).isNull();

        quotaService.releaseApiKey(firstLease, 0L);
        assertThat(redis.opsForValue().get(subscriptionConcurrencyKey(subscriptionId))).isNull();

        GatewayQuotaService.ApiKeyLease consoleLease = quotaService.acquireApiKey(
                console, 0L, true, subscriptionId, 1
        );
        assertThat(redis.opsForValue().get(subscriptionConcurrencyKey(subscriptionId))).isEqualTo("1");
        quotaService.releaseApiKey(consoleLease, 0L);
        assertThat(redis.opsForValue().get(subscriptionConcurrencyKey(subscriptionId))).isNull();
        assertThat(redis.opsForValue().get(consoleConcurrencyKey(userId))).isNull();
    }

    @Test
    void channelConcurrencyQueuesSecondTextRequestAndReleasesReservations() throws Exception {
        RegisteredUser user = register("channel-concurrency@example.com");
        Configuration config = configure("gpt-channel-concurrency", "1", "2");
        UUID channelId = addRoute(
                config, "/channel-concurrency/v1", 1, 1, true, "channel-concurrency-secret"
        );
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
        fund(user.id());
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/channel-concurrency/v1/chat/completions"))
                .willReturn(aResponse()
                        .withFixedDelay(1200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(successJson("upstream-gpt-channel-concurrency"))));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> startChatRequest(
                    key, "req_gateway_channel_concurrency_1", "gpt-channel-concurrency", false
            ));
            awaitRedisValue(channelConcurrencyKey(channelId), "1");
            Future<MvcResult> second = executor.submit(() -> startChatRequest(
                    key, "req_gateway_channel_concurrency_2", "gpt-channel-concurrency", false
            ));
            // 两个 API 请求都已占位，但只有一个请求能够进入上游，证明第二个正在渠道队列中等待。
            awaitRedisValue(apiKeyConcurrencyKey(key), "2");
            assertThat(redis.opsForValue().get(channelConcurrencyKey(channelId))).isEqualTo("1");

            completeAsync(first.get(5, TimeUnit.SECONDS));
            completeAsync(second.get(5, TimeUnit.SECONDS));
            upstream.verify(2, postRequestedFor(urlEqualTo("/channel-concurrency/v1/chat/completions")));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM wallet_reservations WHERE request_id IN "
                            + "('req_gateway_channel_concurrency_1','req_gateway_channel_concurrency_2') "
                            + "AND status = 'settled'",
                    Integer.class
            )).isEqualTo(2);
            assertThat(redis.opsForValue().get(channelConcurrencyKey(channelId))).isNull();
            assertThat(redis.opsForValue().get(apiKeyConcurrencyKey(key))).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void streamFailureAfterFirstEventDoesNotSwitchRoute() throws Exception {
        HttpServer partialUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] firstEvent = "data: {\"id\":\"partial-1\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                .getBytes(StandardCharsets.UTF_8);
        partialUpstream.createContext("/partial/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
            exchange.sendResponseHeaders(200, firstEvent.length + 128L);
            try (var output = exchange.getResponseBody()) {
                output.write(firstEvent);
                output.flush();
            }
        });
        partialUpstream.start();
        try {
            RegisteredUser user = register("stream-break@example.com");
            Configuration config = configure("gpt-stream-break", "1", "2");
            addRouteAtBase(
                    config,
                    "http://127.0.0.1:" + partialUpstream.getAddress().getPort() + "/partial/v1",
                    1, 10, true, "partial-secret"
            );
            addRoute(config, "/stream-must-not-run/v1", 20, 10, true, "second-stream-secret");
            CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 5);
            fund(user.id());
            upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/stream-must-not-run/v1/chat/completions"))
                    .willReturn(aResponse()
                            .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .withBody("data: [DONE]\n\n")));

            MvcResult completed = completeAsync(startChatRequest(
                    key, "req_gateway_stream_break_001", "gpt-stream-break", true
            ));

            assertThat(completed.getResponse().getContentAsString()).contains("partial-1");
            upstream.verify(0, postRequestedFor(urlEqualTo("/stream-must-not-run/v1/chat/completions")));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT retry_count FROM request_logs WHERE request_id = 'req_gateway_stream_break_001'",
                    Integer.class
            )).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status_code FROM request_logs WHERE request_id = 'req_gateway_stream_break_001'",
                    Integer.class
            )).isEqualTo(502);
        } finally {
            partialUpstream.stop(0);
        }
    }

    @Test
    void unexpectedStreamPreparationFailureReleasesFundsAndLeases() throws Exception {
        RegisteredUser user = register("stream-cleanup@example.com");
        Configuration config = configure("gpt-stream-cleanup", "1", "2");
        UUID channelId = addRouteAtBase(
                config, "http://[invalid/v1", 1, 1, true, "stream-cleanup-secret"
        );
        CreatedKey key = createKey(user.session(), config, List.of(config.modelId()), 60, 100_000L, 1);
        fund(user.id());

        mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", "req_gateway_stream_cleanup_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest("gpt-stream-cleanup", true)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_reservations WHERE request_id = 'req_gateway_stream_cleanup_001'",
                String.class
        )).isEqualTo("released");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen_credits FROM wallet_accounts WHERE user_id = ?", BigDecimal.class, user.id()
        )).isEqualByComparingTo("0.00000000");
        assertThat(redis.opsForValue().get(apiKeyConcurrencyKey(key))).isNull();
        assertThat(redis.opsForValue().get(channelConcurrencyKey(channelId))).isNull();
    }

    private Configuration configure(String publicModel, String inputPrice, String outputPrice) {
        UUID modelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type,
                    max_output_tokens, supports_streaming, supports_tools,
                    input_price, output_price, cached_input_price, price_unit,
                    public_visible, status
                ) VALUES (?, ?, ?, 'openai', 'text', 4096, true, true, ?, ?, ?, 'million_tokens', true, 'active')
                """, modelId, publicModel, publicModel + " Display",
                new BigDecimal(inputPrice), new BigDecimal(outputPrice), new BigDecimal(inputPrice));
        UUID openAiInterfaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM api_interfaces WHERE interface_code = 'openai_chat'",
                UUID.class
        );
        jdbcTemplate.update("""
                INSERT INTO model_interfaces (model_id, interface_id)
                VALUES (?, ?)
                """, modelId, openAiInterfaceId);
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, ?, 1.000000, 'all', 'active')
                """, groupId, "group-" + publicModel, publicModel + " Group");
        return new Configuration(modelId, groupId, publicModel);
    }

    private Configuration configureImage(String publicModel) {
        UUID modelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID pricingVersionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type, billing_type,
                    unit_price, price_unit, adapter_key, public_visible, status
                ) VALUES (?, ?, ?, 'grok', 'image', 2, 0.100000000000, 'image',
                          'openai_compatible_image', true, 'active')
                """, modelId, publicModel, publicModel + " Display");
        UUID imageInterfaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM api_interfaces WHERE interface_code = 'openai_images'", UUID.class
        );
        jdbcTemplate.update(
                "INSERT INTO model_interfaces (model_id, interface_id) VALUES (?, ?)",
                modelId, imageInterfaceId
        );
        jdbcTemplate.update("""
                INSERT INTO model_pricing_versions (
                    id, model_id, version_no, billing_type, unit_price,
                    display_original_price, charge_desc, change_note, source_type
                ) VALUES (?, ?, 1, 2, 0.100000000000, 0.120000000000,
                          '按图片张数计费', 'image gateway integration test', 'manual')
                """, pricingVersionId, modelId);
        jdbcTemplate.update(
                "UPDATE ai_models SET active_pricing_version_id = ? WHERE id = ?", pricingVersionId, modelId
        );
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, ?, 1.000000, 'all', 'active')
                """, groupId, "group-" + publicModel, publicModel + " Group");
        return new Configuration(modelId, groupId, publicModel);
    }

    private Configuration configureMedia(String publicModel, String capabilityType, String interfaceCode) {
        UUID modelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID pricingVersionId = UUID.randomUUID();
        int billingType = "video".equals(capabilityType) ? 3 : 2;
        String adapterKey = "image".equals(capabilityType)
                ? "openai_compatible_image"
                : "jimeng_video".equals(interfaceCode) ? "jimeng_video" : "openai_compatible_video";
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type, billing_type,
                    unit_price, price_unit, adapter_key, public_visible, status
                ) VALUES (?, ?, ?, 'openai', ?, ?, 0.100000000000, 'request', ?, true, 'active')
                """, modelId, publicModel, publicModel + " Display", capabilityType, billingType, adapterKey);
        bindInterface(modelId, interfaceCode);
        jdbcTemplate.update("""
                INSERT INTO model_pricing_versions (
                    id, model_id, version_no, billing_type, unit_price,
                    display_original_price, charge_desc, change_note, source_type
                ) VALUES (?, ?, 1, ?, 0.100000000000, 0.120000000000,
                          ?, 'public capability integration test', 'manual')
                """, pricingVersionId, modelId, billingType,
                "video".equals(capabilityType) ? "按视频秒数计费" : "按任务请求计费");
        jdbcTemplate.update(
                "UPDATE ai_models SET active_pricing_version_id = ? WHERE id = ?", pricingVersionId, modelId
        );
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, ?, 1.000000, 'all', 'active')
                """, groupId, "group-" + publicModel, publicModel + " Group");
        return new Configuration(modelId, groupId, publicModel);
    }

    /** 为音频和向量公开能力建立最小模型、价格版本、接口关系和服务分组夹具。 */
    private Configuration configureCapability(
            String publicModel,
            String capabilityType,
            String interfaceCode,
            int billingType,
            String unitPrice,
            String priceUnit
    ) {
        UUID modelId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID pricingVersionId = UUID.randomUUID();
        BigDecimal price = new BigDecimal(unitPrice);
        BigDecimal inputPrice = billingType == 4 ? price : BigDecimal.ZERO;
        jdbcTemplate.update("""
                INSERT INTO ai_models (
                    id, public_name, display_name, provider, capability_type, billing_type,
                    unit_price, input_price, output_price, cached_input_price, price_unit,
                    public_visible, status
                ) VALUES (?, ?, ?, 'openai', ?, ?, ?, ?, 0, 0, ?, true, 'active')
                """, modelId, publicModel, publicModel + " Display", capabilityType, billingType,
                price, inputPrice, priceUnit);
        bindInterface(modelId, interfaceCode);
        jdbcTemplate.update("""
                INSERT INTO model_pricing_versions (
                    id, model_id, version_no, billing_type, unit_price,
                    display_original_price, charge_desc, change_note, source_type
                ) VALUES (?, ?, 1, ?, ?, ?, '公开能力测试计费', 'audio/embedding integration test', 'manual')
                """, pricingVersionId, modelId, billingType, price, price);
        jdbcTemplate.update(
                "UPDATE ai_models SET active_pricing_version_id = ? WHERE id = ?", pricingVersionId, modelId
        );
        jdbcTemplate.update("""
                INSERT INTO routing_groups (id, code, name, price_multiplier, audience, status)
                VALUES (?, ?, ?, 1.000000, 'all', 'active')
                """, groupId, "group-" + publicModel, publicModel + " Group");
        return new Configuration(modelId, groupId, publicModel);
    }

    private void bindInterface(UUID modelId, String interfaceCode) {
        UUID interfaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM api_interfaces WHERE interface_code = ?", UUID.class, interfaceCode
        );
        jdbcTemplate.update(
                "INSERT INTO model_interfaces (model_id, interface_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                modelId, interfaceId
        );
    }

    private UUID addRoute(
            Configuration config,
            String basePath,
            int priority,
            Integer concurrencyLimit,
            boolean retryable,
            String credential
    ) {
        return addRouteAtBase(
                config, upstream.baseUrl() + basePath, priority, concurrencyLimit, retryable, credential
        );
    }

    private UUID addRouteAtBase(
            Configuration config,
            String baseUrl,
            int priority,
            Integer concurrencyLimit,
            boolean retryable,
            String credential
    ) {
        UUID channelId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        String endpointType = jdbcTemplate.queryForObject(
                "SELECT capability_type FROM ai_models WHERE id = ?", String.class, config.modelId()
        );
        ChannelCredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(credential);
        jdbcTemplate.update("""
                INSERT INTO suppliers (
                    id, code, name, supplier_type, status, health_status,
                    billing_mode, settlement_currency, metadata
                ) VALUES (?, ?, ?, 'direct', 'active', 'unconfigured', 'postpaid', 'USD', '{}'::jsonb)
                """, supplierId, "supplier-" + supplierId, "Supplier " + supplierId);
        jdbcTemplate.update("""
                INSERT INTO channels (
                    id, supplier_id, name, provider_type, operation_code, endpoint_type,
                    base_url, status, timeout_ms,
                    concurrency_limit, priority, weight
                ) VALUES (?, ?, ?, 'openai', ?, ?, ?, 'active', 5000, ?, ?, 100)
                """, channelId, supplierId, "Channel " + channelId,
                operationCode(endpointType, baseUrl), endpointType,
                baseUrl, concurrencyLimit, priority);
        com.nexusapi.server.testing.TestChannelOptions.put(jdbcTemplate, channelId, config.modelId(), "upstream-" + config.publicModel(),
                "0.5000000000", "0.2500000000", "1.0000000000");
        jdbcTemplate.update("""
                INSERT INTO routing_group_suppliers (
                    group_id, supplier_id, priority, weight, status
                ) VALUES (?, ?, ?, 100, 'active')
                """, config.groupId(), supplierId, priority);
        jdbcTemplate.update("""
                INSERT INTO routing_group_supplier_credentials (
                    group_id, supplier_id, encrypted_credential, credential_key_version,
                    credential_fingerprint, updated_at, status
                ) VALUES (?, ?, ?, ?, ?, now(), 'active')
                """, config.groupId(), supplierId, encrypted.ciphertext(),
                encrypted.keyVersion(), encrypted.fingerprint());
        jdbcTemplate.update("""
                INSERT INTO routing_group_models (group_id, model_id, source_type, source_status)
                VALUES (?, ?, 'manual', 'active')
                ON CONFLICT (group_id, model_id) DO UPDATE SET source_status = 'active'
                """, config.groupId(), config.modelId());
        return channelId;
    }

    private String operationCode(String endpointType, String baseUrl) {
        String normalized = baseUrl.toLowerCase(java.util.Locale.ROOT);
        if (normalized.endsWith("/responses")) return "responses";
        if (normalized.endsWith("/images/tasks")) return "image_task_create";
        if (normalized.endsWith("/images/generations")) return "image_generations";
        if (normalized.endsWith("/audio/transcriptions")) return "audio_transcriptions";
        if (normalized.endsWith("/audio/speech")) return "audio_speech";
        if (normalized.endsWith("/embeddings")) return "embeddings";
        return switch (endpointType) {
            case "video" -> "video_create";
            case "image" -> "image_generations";
            case "audio" -> "audio_speech";
            case "embedding" -> "embeddings";
            default -> "chat_completions";
        };
    }

    private CreatedKey createKey(
            Cookie session,
            Configuration config,
            List<UUID> allowedModels,
            Integer rpm,
            Long tpm,
            Integer concurrency
    ) throws Exception {
        return createKey(session, config, allowedModels, rpm, tpm, concurrency, List.of());
    }

    private CreatedKey createKey(
            Cookie session,
            Configuration config,
            List<UUID> allowedModels,
            Integer rpm,
            Long tpm,
            Integer concurrency,
            List<String> ipAllowlist
    ) throws Exception {
        return createKeyWithGroupScope(
                session, config, allowedModels, rpm, tpm, concurrency,
                List.of(config.groupId()), config.groupId(), ipAllowlist
        );
    }

    private CreatedKey createKeyWithGroupScope(
            Cookie session,
            Configuration config,
            List<UUID> allowedModels,
            Integer rpm,
            Long tpm,
            Integer concurrency,
            List<UUID> allowedGroups,
            UUID defaultGroupId
    ) throws Exception {
        return createKeyWithGroupScope(
                session, config, allowedModels, rpm, tpm, concurrency, allowedGroups, defaultGroupId, List.of()
        );
    }

    private CreatedKey createKeyWithGroupScope(
            Cookie session,
            Configuration config,
            List<UUID> allowedModels,
            Integer rpm,
            Long tpm,
            Integer concurrency,
            List<UUID> allowedGroups,
            UUID defaultGroupId,
            List<String> ipAllowlist
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Wave 6 Gateway Key " + UUID.randomUUID());
        payload.put("service_group_id", defaultGroupId);
        payload.put("default_group_id", defaultGroupId);
        payload.put("allowed_model_ids", allowedModels);
        payload.put("allowed_group_ids", allowedGroups);
        payload.put("ip_allowlist", ipAllowlist);
        payload.put("rpm_limit", rpm);
        payload.put("tpm_limit", tpm);
        payload.put("concurrency_limit", concurrency);
        payload.put("credit_limit", 100);
        payload.put("expires_at", Instant.now().plusSeconds(3600).toString());
        MvcResult result = mockMvc.perform(post("/api/v1/api-keys")
                        .cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new CreatedKey(UUID.fromString(body.at("/data/id").asText()), body.at("/data/secret").asText());
    }

    private RegisteredUser register(String email) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk()).andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id").asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Wave 6 User",
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

    private void fund(UUID userId) {
        billingService.credit(
                userId,
                new BigDecimal("10.00000000"),
                CreditBucket.PERMANENT,
                CreditType.GRANT,
                "wave6-fund-" + userId,
                "test",
                userId.toString(),
                null,
                Map.of("purpose", "gateway_integration")
        );
    }

    private MvcResult startChatRequest(
            CreatedKey key,
            String requestId,
            String model,
            boolean stream
    ) throws Exception {
        return mockMvc.perform(post("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.secret())
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatRequest(model, stream)))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private MvcResult completeAsync(MvcResult pending) throws Exception {
        return mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void awaitRedisValue(String key, String expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (expected.equals(redis.opsForValue().get(key))) {
                return;
            }
            Thread.sleep(20L);
        }
        assertThat(redis.opsForValue().get(key)).as("Redis lease %s", key).isEqualTo(expected);
    }

    private String apiKeyConcurrencyKey(CreatedKey key) {
        return apiKeyConcurrencyKey(key.id());
    }

    private String apiKeyConcurrencyKey(UUID apiKeyId) {
        return "nexus:gateway:key:api-key:" + apiKeyId + ":concurrency";
    }

    private String consoleConcurrencyKey(UUID userId) {
        return "nexus:gateway:key:console:" + userId + ":concurrency";
    }

    private String subscriptionConcurrencyKey(UUID subscriptionId) {
        return "nexus:gateway:subscription:" + subscriptionId + ":concurrency";
    }

    private NexusApiKeyPrincipal quotaPrincipal(UUID apiKeyId, UUID userId, UUID groupId) {
        return new NexusApiKeyPrincipal(
                apiKeyId, userId, "nx-test", groupId, groupId, List.of(), List.of(groupId), List.of(),
                60, 100_000L, 5, null, Instant.now().plusSeconds(3600)
        );
    }

    private String channelConcurrencyKey(UUID channelId) {
        return "nexus:gateway:channel:" + channelId + ":concurrency";
    }

    private String chatRequest(String model, boolean stream) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "hello")),
                "max_tokens", 64,
                "stream", stream
        ));
    }

    private String successJson(String upstreamModel) {
        return """
                {"id":"chatcmpl-ok","object":"chat.completion","model":"%s",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}
                """.formatted(upstreamModel);
    }

    record RegisteredUser(UUID id, Cookie session) {
    }

    record CreatedKey(UUID id, String secret) {
    }

    record Configuration(UUID modelId, UUID groupId, String publicModel) {
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
