package com.nexusapi.server.modules.requestlog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成调用日志的安全摘要。只保留调试所需的结构、计数和用量，绝不持久化消息正文、凭证或二进制内容。
 */
@Service
public class RequestPayloadSummaryService {
    private static final int MAX_SUMMARY_BYTES = 8 * 1024;
    private static final int MAX_DETAIL_BYTES = 64 * 1024;
    private static final int MAX_TEXT_CHARS = 16 * 1024;
    private static final int MAX_ARRAY_ITEMS = 200;
    private static final int MAX_PENDING_SNAPSHOTS = 20_000;
    private final ObjectMapper objectMapper;
    private final Map<String, Snapshot> snapshots = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
                    return size() > MAX_PENDING_SNAPSHOTS;
                }
            }
    );

    public RequestPayloadSummaryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void captureRequest(String requestId, JsonNode request) {
        if (requestId == null) return;
        synchronized (snapshots) {
            Snapshot current = snapshots.getOrDefault(requestId, Snapshot.empty());
            snapshots.put(requestId, current.withRequest(summarizeRequest(request), detailJson(request), byteSize(request)));
        }
    }

    public void captureResponse(String requestId, JsonNode response) {
        if (requestId == null) return;
        synchronized (snapshots) {
            Snapshot current = snapshots.getOrDefault(requestId, Snapshot.empty());
            snapshots.put(requestId, current.withResponse(summarizeResponse(response), detailJson(response), byteSize(response)));
        }
    }

    public void captureResponseSummary(String requestId, Map<String, ?> values, long payloadSize) {
        if (requestId == null) return;
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("redacted", true);
        values.forEach((key, value) -> {
            if (value == null || key == null || key.isBlank()) return;
            if (value instanceof Number number) summary.put(key, number.longValue());
            else if (value instanceof Boolean bool) summary.put(key, bool);
            else summary.put(key, String.valueOf(value));
        });
        synchronized (snapshots) {
            Snapshot current = snapshots.getOrDefault(requestId, Snapshot.empty());
            snapshots.put(requestId, current.withResponse(toJson(summary), toJson(summary), Math.max(0L, payloadSize)));
        }
    }

    /** 为 multipart/二进制请求记录结构摘要，避免把文件内容转成 JSON。 */
    public void captureRequestSummary(String requestId, Map<String, ?> values, long payloadSize) {
        if (requestId == null) return;
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("redacted", true);
        values.forEach((key, value) -> {
            if (value == null || key == null || key.isBlank()) return;
            if (value instanceof Number number) summary.put(key, number.longValue());
            else if (value instanceof Boolean bool) summary.put(key, bool);
            else summary.put(key, String.valueOf(value));
        });
        synchronized (snapshots) {
            Snapshot current = snapshots.getOrDefault(requestId, Snapshot.empty());
            snapshots.put(requestId, current.withRequest(toJson(summary), toJson(summary), Math.max(0L, payloadSize)));
        }
    }

    public Snapshot take(String requestId) {
        if (requestId == null) return Snapshot.empty();
        Snapshot snapshot = snapshots.remove(requestId);
        return snapshot == null ? Snapshot.empty() : snapshot;
    }

    private String summarizeRequest(JsonNode request) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("redacted", true);
        if (request == null || !request.isObject()) return toJson(summary);
        copyScalar(request, summary, "model", "stream", "temperature", "top_p", "max_tokens", "max_completion_tokens",
                "n", "size", "quality", "response_format", "aspect_ratio", "duration", "voice", "speed");
        JsonNode messages = request.get("messages");
        if (messages != null && messages.isArray()) {
            summary.put("message_count", messages.size());
            summary.put("input_chars", textChars(messages));
        }
        JsonNode input = request.get("input");
        if (input != null) {
            summary.put("input_items", input.isArray() ? input.size() : 1);
            summary.put("input_chars", textChars(input));
        }
        JsonNode tools = request.get("tools");
        if (tools != null && tools.isArray()) summary.put("tool_count", tools.size());
        JsonNode data = request.get("data");
        if (data != null && data.isArray()) summary.put("data_count", data.size());
        return toJson(summary);
    }

    private String summarizeResponse(JsonNode response) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("redacted", true);
        if (response == null || !response.isObject()) return toJson(summary);
        // 不回显上游 request id 或真实模型名，避免把内部路由信息暴露到用户控制台。
        copyScalar(response, summary, "object", "created", "status");
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray()) summary.put("choice_count", choices.size());
        JsonNode data = response.get("data");
        if (data != null && data.isArray()) summary.put("data_count", data.size());
        JsonNode usage = response.get("usage");
        if (usage != null && usage.isObject()) summary.set("usage", numericObject(usage));
        JsonNode error = response.get("error");
        if (error != null && error.isObject()) copyScalar(error, summary, "code", "type", "param");
        return toJson(summary);
    }

    /** 管理员排障用的完整载荷：保留业务字段和消息正文，但递归移除凭证、Cookie 与二进制内容。 */
    private String detailJson(JsonNode source) {
        JsonNode sanitized = sanitize(source, 0);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(sanitized);
            if (bytes.length <= MAX_DETAIL_BYTES) return new String(bytes, StandardCharsets.UTF_8);
            return "{\"redacted\":true,\"truncated\":true,\"reason\":\"payload_limit\"}";
        } catch (JsonProcessingException ignored) {
            return "{\"redacted\":true,\"truncated\":true}";
        }
    }

    private JsonNode sanitize(JsonNode source, int depth) {
        if (source == null || depth > 12) return objectMapper.getNodeFactory().textNode("[REDACTED]");
        if (source.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            source.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                String normalized = key.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (isSensitiveKey(normalized)) {
                    result.put(key, "[REDACTED]");
                } else {
                    result.set(key, sanitize(entry.getValue(), depth + 1));
                }
            });
            return result;
        }
        if (source.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            int count = 0;
            for (JsonNode item : source) {
                if (count++ >= MAX_ARRAY_ITEMS) break;
                result.add(sanitize(item, depth + 1));
            }
            if (source.size() > MAX_ARRAY_ITEMS) result.add(objectMapper.getNodeFactory().textNode("[TRUNCATED_ITEMS]"));
            return result;
        }
        if (source.isTextual()) {
            String value = source.asText();
            if (value.startsWith("data:") || looksLikeBase64(value)) return objectMapper.getNodeFactory().textNode("[REDACTED_BINARY]");
            return objectMapper.getNodeFactory().textNode(value.length() > MAX_TEXT_CHARS
                    ? value.substring(0, MAX_TEXT_CHARS) + "…[TRUNCATED]" : value);
        }
        return source;
    }

    private boolean isSensitiveKey(String key) {
        return key.contains("authorization") || key.contains("cookie") || key.contains("password")
                || key.contains("secret") || key.contains("credential") || key.equals("token")
                || key.endsWith("token") || key.contains("apikey") || key.contains("accesskey")
                || key.contains("base64") || key.equals("b64json") || key.contains("imageurl") || key.contains("audiourl")
                || key.contains("audiodata") || key.contains("inputaudio") || key.contains("filedata");
    }

    private boolean looksLikeBase64(String value) {
        return value.length() > 512 && value.matches("[A-Za-z0-9+/=\\r\\n]+") && value.indexOf(' ') < 0;
    }

    private ObjectNode numericObject(JsonNode source) {
        ObjectNode result = objectMapper.createObjectNode();
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) result.set(entry.getKey(), entry.getValue());
        });
        return result;
    }

    private void copyScalar(JsonNode source, ObjectNode target, String... names) {
        for (String name : names) {
            JsonNode value = source.get(name);
            if (value == null || value.isContainerNode()) continue;
            if (value.isNumber()) target.set(name, value);
            else if (value.isBoolean()) target.set(name, value);
            else if (value.isTextual()) target.put(name, value.asText().length() > 160 ? value.asText().substring(0, 160) : value.asText());
        }
    }

    private long textChars(JsonNode node) {
        if (node == null) return 0L;
        if (node.isTextual()) return node.asText().codePointCount(0, node.asText().length());
        long total = 0L;
        if (node.isContainerNode()) for (JsonNode child : node) total = Math.min(1_000_000L, total + textChars(child));
        return total;
    }

    private long byteSize(JsonNode node) {
        if (node == null) return 0L;
        try { return objectMapper.writeValueAsBytes(node).length; }
        catch (JsonProcessingException ignored) { return 0L; }
    }

    private String toJson(JsonNode value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            if (bytes.length <= MAX_SUMMARY_BYTES) return new String(bytes, StandardCharsets.UTF_8);
            return "{\"redacted\":true,\"truncated\":true}";
        } catch (JsonProcessingException exception) {
            return "{\"redacted\":true}";
        }
    }

    public record Snapshot(String requestJson, String responseJson, String requestDetailJson, String responseDetailJson,
                           long requestSize, long responseSize) {
        static Snapshot empty() { return new Snapshot("{}", "{}", "{}", "{}", 0L, 0L); }
        Snapshot withRequest(String json, String detailJson, long size) {
            return new Snapshot(json, responseJson, detailJson, responseDetailJson, size, responseSize);
        }
        Snapshot withResponse(String json, String detailJson, long size) {
            return new Snapshot(requestJson, json, requestDetailJson, detailJson, requestSize, size);
        }
    }
}
