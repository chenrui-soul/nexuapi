package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.springframework.http.HttpMethod;

/** 视频协议适配器：同一实现覆盖创建、列表、详情三类视频任务操作。 */
public interface VideoCapabilityAdapter extends CapabilityAdapter {
    ObjectNode toUpstreamRequest(ObjectNode request, String upstreamModel, PublicGatewayOperation operation);

    JsonNode execute(
            RuntimeRouteRow route,
            HttpMethod method,
            ObjectNode upstreamRequest,
            String pathSuffix
    );

    JsonNode toClientResponse(JsonNode upstreamResponse, String publicModel, PublicGatewayOperation operation);
}
