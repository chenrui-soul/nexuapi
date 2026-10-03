package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * MiniMax H3 视频适配器。
 *
 * <p>MiniMax H3 使用 content[]、duration、ratio 等字段；平台仍保持统一的
 * prompt、seconds、aspect_ratio 视频入口。任务列表和详情沿用同一渠道地址，
 * 由渠道配置分别指向 MiniMax 对应的查询接口。</p>
 */
@Component
public class MiniMaxH3VideoAdapter implements VideoCapabilityAdapter {
    private final OpenAiCompatibleVideoAdapter responseDefaults;

    public MiniMaxH3VideoAdapter(OpenAiCompatibleVideoAdapter responseDefaults) {
        this.responseDefaults = responseDefaults;
    }

    @Override
    public String key() {
        return "minimax_h3_video";
    }

    @Override
    public ObjectNode toUpstreamRequest(ObjectNode request, String upstreamModel,
                                        PublicGatewayOperation operation) {
        ObjectNode payload = request.deepCopy();
        payload.put("model", upstreamModel);

        if (!payload.has("content") && payload.has("prompt")) {
            ArrayNode content = payload.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", payload.path("prompt").asText());
            payload.remove("prompt");
        }
        if (!payload.has("duration") && payload.has("seconds")) {
            payload.set("duration", payload.get("seconds"));
        }
        if (!payload.has("ratio") && payload.has("aspect_ratio")) {
            payload.set("ratio", payload.get("aspect_ratio"));
        }

        payload.remove("seconds");
        payload.remove("aspect_ratio");
        payload.remove("mode");
        return payload;
    }

    @Override
    public JsonNode execute(RuntimeRouteRow route, HttpMethod method, ObjectNode upstreamRequest,
                            String pathSuffix) {
        return responseDefaults.execute(route, method, upstreamRequest, pathSuffix);
    }

    @Override
    public JsonNode toClientResponse(JsonNode upstreamResponse, String publicModel,
                                     PublicGatewayOperation operation) {
        if (upstreamResponse == null || !upstreamResponse.isObject()) {
            return responseDefaults.toClientResponse(upstreamResponse, publicModel, operation);
        }
        ObjectNode response = upstreamResponse.deepCopy();
        JsonNode task = response.get("task");
        if (task != null && task.isObject()) {
            ObjectNode taskObject = (ObjectNode) task;
            taskObject.fields().forEachRemaining(entry -> {
                if (!response.has(entry.getKey())) response.set(entry.getKey(), entry.getValue());
            });
        }
        if (!response.has("id") && response.has("task_id")) {
            response.set("id", response.get("task_id"));
        }
        if (!response.has("video_url") && response.has("url")) {
            response.set("video_url", response.get("url"));
        }
        JsonNode content = response.get("content");
        if (!response.has("video_url") && content != null && content.isObject()
                && content.path("url").isTextual()) {
            response.set("video_url", content.get("url"));
        }
        String status = response.path("status").asText("");
        if ("running".equalsIgnoreCase(status)) response.put("status", "processing");
        if ("succeeded".equalsIgnoreCase(status)) response.put("status", "completed");
        response.put("model", publicModel);
        if (!response.has("object")) response.put("object", operation == PublicGatewayOperation.VIDEO_LIST
                ? "list" : "video");
        if (operation == PublicGatewayOperation.VIDEO_CREATE && !response.has("status")) {
            response.put("status", "queued");
        }
        return response;
    }
}
