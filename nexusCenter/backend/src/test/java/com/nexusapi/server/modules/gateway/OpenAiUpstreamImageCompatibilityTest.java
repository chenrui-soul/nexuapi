package com.nexusapi.server.modules.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiUpstreamImageCompatibilityTest {
    @Test
    void retriesWithoutQualityWhenImageProviderRejectsQuality() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondBody = new AtomicReference<>();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            MockClientHttpRequest captured = new MockClientHttpRequest(request.method(), request.url());
            request.body().insert(captured, new org.springframework.web.reactive.function.BodyInserter.Context() {
                @Override
                public List<HttpMessageWriter<?>> messageWriters() {
                    return HandlerStrategies.withDefaults().messageWriters();
                }

                @Override
                public Optional<ServerHttpRequest> serverRequest() {
                    return Optional.empty();
                }

                @Override
                public Map<String, Object> hints() {
                    return Map.of();
                }
            }).block();
            if (calls.incrementAndGet() == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":{\"message\":\"quality 不在支持范围内\"}}")
                        .build());
            }
            secondBody.set(captured.getBodyAsString().block());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"created\":1730000000,\"data\":[{\"url\":\"https://img.test/compat.png\"}]}")
                    .build());
        }).build();
        ChannelCredentialCipher cipher = mock(ChannelCredentialCipher.class);
        when(cipher.decrypt(any(), anyInt())).thenReturn("test-credential");
        OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                webClient, cipher, new ObjectMapper()
        );
        RuntimeRouteRow route = route("https://test.invalid/v1");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", "provider-image");
        fields.put("prompt", "a glasshouse");
        fields.put("n", "1");
        fields.put("aspect_ratio", "1:1");
        fields.put("quality", "medium");

        var response = client.imageGeneration(route, fields, java.util.List.of(), java.util.List.of());

        assertThat(response.path("data").path(0).path("url").asText())
                .isEqualTo("https://img.test/compat.png");
        assertThat(calls).hasValue(2);
        assertThat(secondBody).hasValueSatisfying(body -> assertThat(body).doesNotContain("quality"));
    }

    private RuntimeRouteRow route(String baseUrl) {
        RuntimeRouteRow route = new RuntimeRouteRow();
        route.setBaseUrl(baseUrl);
        route.setTimeoutMs(2_000);
        route.setEncryptedCredential(new byte[]{1});
        route.setCredentialKeyVersion(1);
        route.setConfigJson("{}");
        return route;
    }
}
