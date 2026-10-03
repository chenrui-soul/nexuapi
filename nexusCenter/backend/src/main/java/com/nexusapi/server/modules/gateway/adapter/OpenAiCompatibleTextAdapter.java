package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Iterator;

/** 当前默认文本适配器：用于 OpenAI-compatible 上游。 */
@Component
public class OpenAiCompatibleTextAdapter implements TextCapabilityAdapter {
    private final ObjectMapper objectMapper;
    private final OpenAiUpstreamClient upstreamClient;

    public OpenAiCompatibleTextAdapter(ObjectMapper objectMapper, OpenAiUpstreamClient upstreamClient) {
        this.objectMapper = objectMapper;
        this.upstreamClient = upstreamClient;
    }

    @Override
    public String key() {
        return "openai_compatible_text";
    }

    @Override
    public ObjectNode toUpstreamRequest(ObjectNode request, String upstreamModel, boolean streaming) {
        ObjectNode payload = request.deepCopy();
        payload.put("model", upstreamModel);
        payload.put("stream", streaming);
        if (streaming) payload.withObject("stream_options").put("include_usage", true);
        return payload;
    }

    @Override
    public ObjectNode toResponsesUpstreamRequest(ObjectNode request, String upstreamModel) {
        ObjectNode payload = request.deepCopy();
        payload.put("model", upstreamModel);
        payload.put("stream", true);
        return payload;
    }

    @Override
    public JsonNode complete(RuntimeRouteRow route, ObjectNode upstreamRequest) {
        return upstreamClient.chatCompletion(route, upstreamRequest);
    }

    @Override
    public Iterator<String> openChatStream(RuntimeRouteRow route, ObjectNode upstreamRequest) {
        return upstreamClient.openChatCompletionStream(route, upstreamRequest);
    }

    @Override
    public Iterator<String> openResponsesStream(RuntimeRouteRow route, ObjectNode upstreamRequest) {
        return upstreamClient.openJsonStream(route, upstreamRequest);
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
    public String toClientStreamEvent(String upstreamEvent, String publicModel, boolean responsesProtocol) {
        if (upstreamEvent == null || upstreamEvent.isBlank() || "[DONE]".equals(upstreamEvent.strip())) {
            return upstreamEvent;
        }
        try {
            JsonNode parsed = objectMapper.readTree(upstreamEvent);
            if (!parsed.isObject()) return upstreamEvent;
            ObjectNode event = ((ObjectNode) parsed).deepCopy();
            if (responsesProtocol) {
                JsonNode response = event.path("response");
                if (response instanceof ObjectNode responseObject && responseObject.has("model")) {
                    responseObject.put("model", publicModel);
                }
            } else {
                event.put("model", publicModel);
            }
            return objectMapper.writeValueAsString(event);
        } catch (Exception ignored) {
            return upstreamEvent;
        }
    }

    @Override
    public boolean isFailureEvent(String event, boolean responsesProtocol) {
        if (!responsesProtocol || event == null || event.isBlank() || "[DONE]".equals(event.strip())) return false;
        try {
            String type = objectMapper.readTree(event).path("type").asText();
            return "response.failed".equals(type) || "error".equals(type);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean isCompletedEvent(String event, boolean responsesProtocol) {
        if (!responsesProtocol || event == null || event.isBlank() || "[DONE]".equals(event.strip())) return false;
        try {
            return "response.completed".equals(objectMapper.readTree(event).path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }
}
