package com.nexusapi.server.modules.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.adapter.MiniMaxH3VideoAdapter;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MiniMaxH3VideoAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsPlatformVideoFieldsToMiniMaxH3Contract() throws Exception {
        MiniMaxH3VideoAdapter adapter = new MiniMaxH3VideoAdapter(null);
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {"model":"h3","prompt":"A slow camera move","seconds":8,
                 "aspect_ratio":"9:16","mode":"t2va","resolution":"720p"}
                """);

        ObjectNode upstream = adapter.toUpstreamRequest(request, "MiniMax-H3", PublicGatewayOperation.VIDEO_CREATE);

        assertThat(upstream.path("model").asText()).isEqualTo("MiniMax-H3");
        assertThat(upstream.path("duration").asInt()).isEqualTo(8);
        assertThat(upstream.path("ratio").asText()).isEqualTo("9:16");
        assertThat(upstream.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(upstream.path("content").get(0).path("text").asText()).isEqualTo("A slow camera move");
        assertThat(upstream.has("prompt")).isFalse();
        assertThat(upstream.has("seconds")).isFalse();
        assertThat(upstream.has("aspect_ratio")).isFalse();
        assertThat(upstream.has("mode")).isFalse();
    }

    @Test
    void normalizesMiniMaxTaskResponse() throws Exception {
        MiniMaxH3VideoAdapter adapter = new MiniMaxH3VideoAdapter(null);
        ObjectNode upstream = (ObjectNode) objectMapper.readTree("""
                {"task_id":"task_123","status":"succeeded","url":"https://example.test/video.mp4"}
                """);

        ObjectNode client = (ObjectNode) adapter.toClientResponse(
                upstream, "minimax-h3-base", PublicGatewayOperation.VIDEO_DETAIL
        );

        assertThat(client.path("id").asText()).isEqualTo("task_123");
        assertThat(client.path("video_url").asText()).isEqualTo("https://example.test/video.mp4");
        assertThat(client.path("status").asText()).isEqualTo("completed");
        assertThat(client.path("model").asText()).isEqualTo("minimax-h3-base");
        assertThat(client.path("object").asText()).isEqualTo("video");
    }
}
