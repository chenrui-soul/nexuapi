package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;

import java.util.Iterator;

/** 文本对话协议适配器：负责请求、普通响应及 SSE 事件的标准化。 */
public interface TextCapabilityAdapter extends CapabilityAdapter {
    ObjectNode toUpstreamRequest(ObjectNode request, String upstreamModel, boolean streaming);

    ObjectNode toResponsesUpstreamRequest(ObjectNode request, String upstreamModel);

    JsonNode complete(RuntimeRouteRow route, ObjectNode upstreamRequest);

    Iterator<String> openChatStream(RuntimeRouteRow route, ObjectNode upstreamRequest);

    Iterator<String> openResponsesStream(RuntimeRouteRow route, ObjectNode upstreamRequest);

    JsonNode toClientResponse(JsonNode upstreamResponse, String publicModel);

    String toClientStreamEvent(String upstreamEvent, String publicModel, boolean responsesProtocol);

    boolean isFailureEvent(String event, boolean responsesProtocol);

    boolean isCompletedEvent(String event, boolean responsesProtocol);
}
