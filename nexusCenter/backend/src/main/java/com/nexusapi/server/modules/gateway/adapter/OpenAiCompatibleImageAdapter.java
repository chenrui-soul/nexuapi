package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.capability.image.model.OpenAiImageGenerationRequest;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前默认图片适配器：用于 OpenAI-compatible 图片上游。 */
@Component
public class OpenAiCompatibleImageAdapter implements ImageCapabilityAdapter {
    private final ObjectMapper objectMapper;
    private final OpenAiUpstreamClient upstreamClient;

    public OpenAiCompatibleImageAdapter(ObjectMapper objectMapper, OpenAiUpstreamClient upstreamClient) {
        this.objectMapper = objectMapper;
        this.upstreamClient = upstreamClient;
    }

    @Override
    public String key() {
        return "openai_compatible_image";
    }

    @Override
    public Map<String, String> toUpstreamFields(OpenAiImageGenerationRequest request, String upstreamModel) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", upstreamModel);
        fields.put("prompt", request.prompt());
        fields.put("n", String.valueOf(request.quantity()));
        fields.put("aspect_ratio", request.aspectRatio());
        fields.put("quality", request.quality());
        if (request.resolution() != null) {
            ObjectNode extra = objectMapper.createObjectNode();
            extra.put("resolution", request.resolution());
            fields.put("extra_params", extra.toString());
        }
        return fields;
    }

    @Override
    public JsonNode generate(RuntimeRouteRow route, Map<String, String> fields,
                             List<OpenAiUpstreamClient.UploadPart> images,
                             List<String> imageUrls) {
        return upstreamClient.imageGeneration(route, fields, images, imageUrls);
    }

    @Override
    public JsonNode toClientResponse(JsonNode upstreamResponse, String publicModel) {
        if (upstreamResponse == null || !upstreamResponse.isObject()) {
            throw new BusinessException(ErrorCode.UPSTREAM_PROTOCOL_ERROR);
        }
        ObjectNode response = ((ObjectNode) upstreamResponse).deepCopy();
        response.put("model", publicModel);
        return response;
    }

    @Override
    public ObjectNode toUpstreamTaskRequest(ObjectNode request, String upstreamModel,
                                            PublicGatewayOperation operation) {
        ObjectNode payload = request.deepCopy();
        payload.put("model", upstreamModel);
        return payload;
    }

    @Override
    public JsonNode executeTask(RuntimeRouteRow route, HttpMethod method,
                                ObjectNode upstreamRequest, String pathSuffix) {
        return upstreamClient.jsonRequest(route, method, upstreamRequest, pathSuffix);
    }

    @Override
    public JsonNode toClientTaskResponse(JsonNode upstreamResponse, String publicModel,
                                         PublicGatewayOperation operation) {
        if (upstreamResponse == null || !upstreamResponse.isObject()) {
            throw new BusinessException(ErrorCode.UPSTREAM_PROTOCOL_ERROR);
        }
        ObjectNode response = ((ObjectNode) upstreamResponse).deepCopy();
        response.put("model", publicModel);
        if (!response.has("object")) response.put("object", "image_task");
        return response;
    }
}
