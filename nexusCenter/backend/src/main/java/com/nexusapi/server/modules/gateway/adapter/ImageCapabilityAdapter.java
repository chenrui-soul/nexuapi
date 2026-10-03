package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.capability.image.model.OpenAiImageGenerationRequest;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

/** 图片生成协议适配器：负责字段、上传形态和固定图片响应的标准化。 */
public interface ImageCapabilityAdapter extends CapabilityAdapter {
    Map<String, String> toUpstreamFields(OpenAiImageGenerationRequest request, String upstreamModel);

    JsonNode generate(
            com.nexusapi.server.modules.routing.model.RuntimeRouteRow route,
            Map<String, String> fields,
            List<OpenAiUpstreamClient.UploadPart> images,
            List<String> imageUrls
    );

    JsonNode toClientResponse(JsonNode upstreamResponse, String publicModel);

    /** 异步图片任务使用 JSON 请求，但仍必须经过同一图片适配器。 */
    ObjectNode toUpstreamTaskRequest(ObjectNode request, String upstreamModel, PublicGatewayOperation operation);

    JsonNode executeTask(RuntimeRouteRow route, HttpMethod method, ObjectNode upstreamRequest, String pathSuffix);

    JsonNode toClientTaskResponse(JsonNode upstreamResponse, String publicModel, PublicGatewayOperation operation);
}
