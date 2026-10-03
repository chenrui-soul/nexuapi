package com.nexusapi.server.common.security;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.web.RequestIdFilter;
import com.nexusapi.server.common.web.GatewayErrorAttributes;
import com.nexusapi.server.modules.apikey.security.ApiKeyAuthenticationFilter;
import com.nexusapi.server.modules.apikey.security.NexusApiKeyPrincipal;
import com.nexusapi.server.modules.apikey.service.ApiKeyAuthenticationService;
import com.nexusapi.server.modules.auth.security.SessionAuthenticationFilter;
import com.nexusapi.server.modules.auth.service.AuthService;
import com.nexusapi.server.modules.systemtoken.security.SystemAccessTokenAuthenticationFilter;
import com.nexusapi.server.modules.systemtoken.service.SystemAccessTokenAuthenticationService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Actual HTTP servlet ERROR/ASYNC dispatches; no database, Redis or upstream access. */
@SpringBootTest(classes = GatewayErrorDispatchIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayErrorDispatchIntegrationTest {
    @LocalServerPort int port;
    @MockitoBean ApiKeyAuthenticationService apiKeys;
    @MockitoBean AuthService sessions;
    @MockitoBean SystemAccessTokenAuthenticationService systemTokens;

    @BeforeEach
    void authenticateTestKey() {
        when(apiKeys.authenticate("test-key")).thenReturn(new NexusApiKeyPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "test", null, null,
                List.of(), List.of(), List.of(), null, null, null, null, null));
    }

    @Test void invalidKeyRemainsApi401() throws Exception {
        var response = get("/v1/ok", false);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("invalid_api_key").doesNotContain("AUTH_SESSION_EXPIRED");
    }

    @Test void normalAndAsyncRequestsSucceed() throws Exception {
        assertThat(get("/v1/ok", true).statusCode()).isEqualTo(200);
        assertThat(get("/v1/async-ok", true).body()).isEqualTo("ok");
    }

    @Test void firewallRejectionRemains400() throws Exception {
        for (String path : List.of("/v1//ok", "/v1/ok;invalid=1")) {
            var response = get(path, false);
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(response.body()).contains("Bad Request").doesNotContain("AUTH_SESSION_EXPIRED");
        }
    }

    @Test void servletErrorPreservesOriginalStatusAndCorrelation() throws Exception {
        var response = get("/v1/error-status", true);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("req_error_dispatch_test")
                .doesNotContain("AUTH_SESSION_EXPIRED", "sensitive-detail");
        assertThat(response.headers().firstValue("X-Request-Id")).contains("req_error_dispatch_test");
    }

    @Test void asyncFailureRemainsServerError() throws Exception {
        var response = get("/v1/async-error", true);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("AUTH_SESSION_EXPIRED", "sensitive-detail");
    }

    @Test void publicRequestCannotImpersonateAnErrorDispatch() throws Exception {
        assertThat(get("/error", false).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/admin/secret", false).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path, boolean valid) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + (valid ? "test-key" : "invalid"))
                    .header("X-Request-Id", "req_error_dispatch_test").GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class, JacksonAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @Import({SecurityConfiguration.class, RequestIdFilter.class, GatewayErrorAttributes.class,
            ApiKeyAuthenticationFilter.class, SessionAuthenticationFilter.class, SystemAccessTokenAuthenticationFilter.class,
            JsonAuthenticationEntryPoint.class, JsonApiKeyAuthenticationEntryPoint.class,
            JsonSystemAccessTokenEntryPoint.class, JsonAccessDeniedHandler.class, Endpoints.class})
    static class App {
        @Bean NexusProperties nexusProperties() {
            return new NexusProperties(new NexusProperties.Security(List.of("http://localhost"), null, null),
                    null, null, null, null);
        }
    }

    @RestController
    static class Endpoints {
        @GetMapping("/v1/ok") String ok() { return "ok"; }
        @GetMapping("/v1/async-ok") StreamingResponseBody asyncOk() {
            return output -> output.write("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        @GetMapping("/v1/error-status") void error(HttpServletResponse response) throws Exception {
            response.sendError(429, "sensitive-detail");
        }
        @GetMapping("/v1/async-error") StreamingResponseBody asyncError() {
            return output -> { throw new IllegalStateException("sensitive-detail"); };
        }
    }
}
