package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Grok 视频适配器。首版沿用既有视频协议实现，保留独立注册键便于后续演进。 */
@Component
public class GrokVideoAdapter implements VideoCapabilityAdapter {
    private final OpenAiCompatibleVideoAdapter legacy;

    public GrokVideoAdapter(OpenAiCompatibleVideoAdapter legacy) {
        this.legacy = legacy;
    }

    @Override
    public String key() {
        return "grok_video";
    }

    @Override
    public ObjectNode toUpstreamRequest(ObjectNode request, String upstreamModel,
                                        PublicGatewayOperation operation) {
        return legacy.toUpstreamRequest(request, upstreamModel, operation);
    }

    @Override
    public JsonNode execute(RuntimeRouteRow route, HttpMethod method, ObjectNode upstreamRequest,
                            String pathSuffix) {
        return legacy.execute(route, method, upstreamRequest, pathSuffix);
    }

    @Override
    public JsonNode toClientResponse(JsonNode upstreamResponse, String publicModel,
                                    PublicGatewayOperation operation) {
        return legacy.toClientResponse(upstreamResponse, publicModel, operation);
    }
}
