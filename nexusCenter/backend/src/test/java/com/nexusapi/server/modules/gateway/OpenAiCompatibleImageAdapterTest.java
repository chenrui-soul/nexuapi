package com.nexusapi.server.modules.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.adapter.OpenAiCompatibleImageAdapter;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleImageAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsAsyncImageTaskModelAndNormalizesResponse() throws Exception {
        OpenAiCompatibleImageAdapter adapter = new OpenAiCompatibleImageAdapter(objectMapper, null);
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {"model":"public-image","prompt":"a glasshouse","size":"1024x1024"}
                """);

        ObjectNode upstream = adapter.toUpstreamTaskRequest(
                request, "upstream-image-v2", PublicGatewayOperation.IMAGE_TASK_DETAIL
        );

        assertThat(upstream.path("model").asText()).isEqualTo("upstream-image-v2");
        assertThat(upstream.path("prompt").asText()).isEqualTo("a glasshouse");

        ObjectNode client = (ObjectNode) adapter.toClientTaskResponse(
                objectMapper.readTree("""
                        {"task_id":"img_task_1","status":"queued"}
                        """),
                "public-image", PublicGatewayOperation.IMAGE_TASK_DETAIL
        );
        assertThat(client.path("model").asText()).isEqualTo("public-image");
        assertThat(client.path("object").asText()).isEqualTo("image_task");
        assertThat(client.path("task_id").asText()).isEqualTo("img_task_1");
    }
}
