package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** 当前默认视频适配器：统一创建、列表和详情三个操作的 OpenAI-compatible 协议。 */
@Component
public class OpenAiCompatibleVideoAdapter implements VideoCapabilityAdapter {
    private final OpenAiUpstreamClient upstreamClient;

    public OpenAiCompatibleVideoAdapter(OpenAiUpstreamClient upstreamClient) {
        this.upstreamClient = upstreamClient;
    }

    @Override
    public String key() {
        return "openai_compatible_video";
    }

    @Override
    public ObjectNode toUpstreamRequest(ObjectNode request, String upstreamModel, PublicGatewayOperation operation) {
        ObjectNode payload = request.deepCopy();
        payload.put("model", upstreamModel);
        return payload;
    }

    @Override
    public JsonNode execute(RuntimeRouteRow route, HttpMethod method, ObjectNode upstreamRequest, String pathSuffix) {
        return upstreamClient.jsonRequest(route, method, upstreamRequest, pathSuffix);
    }

    @Override
    public JsonNode toClientResponse(JsonNode upstreamResponse, String publicModel,
                                     PublicGatewayOperation operation) {
        if (upstreamResponse == null || !upstreamResponse.isObject()) {
            throw new BusinessException(ErrorCode.UPSTREAM_PROTOCOL_ERROR);
        }
        ObjectNode response = ((ObjectNode) upstreamResponse).deepCopy();
        response.put("model", publicModel);
        if (!response.has("object")) response.put("object", "video");
        return response;
    }
}
