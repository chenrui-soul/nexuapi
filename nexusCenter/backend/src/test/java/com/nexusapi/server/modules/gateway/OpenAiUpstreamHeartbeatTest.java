package com.nexusapi.server.modules.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiUpstreamHeartbeatTest {
    @ParameterizedTest
    @ValueSource(strings = {": keepalive\n\n", "event: ping\n\n", "retry: 1000\n\n"})
    void ignoresHeartbeatFramesWithoutLosingDeltaOrCompletion(String heartbeat) {
        String created = "{\"type\":\"response.created\"}";
        String delta = "{\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}";
        String completed = "{\"type\":\"response.completed\"}";
        String wire = heartbeat + "data: " + created + "\n\n" + heartbeat
                + "data: " + delta + "\n\n" + heartbeat + "data: " + completed + "\n\n";
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body(wire).build())).build();
        ChannelCredentialCipher cipher = mock(ChannelCredentialCipher.class);
        when(cipher.decrypt(any(), anyInt())).thenReturn("test-credential");
        ObjectMapper mapper = new ObjectMapper();
        OpenAiUpstreamClient client = new OpenAiUpstreamClient(webClient, cipher, mapper);
        RuntimeRouteRow route = new RuntimeRouteRow();
        route.setBaseUrl("https://test.invalid/v1/responses");
        route.setTimeoutMs(1000);
        route.setEncryptedCredential(new byte[]{1});
        route.setCredentialKeyVersion(1);
        route.setConfigJson("{}");
        var iterator = client.openJsonStream(route, mapper.createObjectNode());
        var events = new ArrayList<String>();
        try {
            iterator.forEachRemaining(events::add);
        } finally {
            if (iterator instanceof Runnable cancel) cancel.run();
        }
        assertThat(events).containsExactly(created, delta, completed);
    }
}
