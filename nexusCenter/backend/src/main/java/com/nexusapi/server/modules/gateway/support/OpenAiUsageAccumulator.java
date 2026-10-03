package com.nexusapi.server.modules.gateway.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 从非流式响应或 SSE usage 块提取 Token；缺失 usage 时使用保守输入估算和输出文本估算兜底。 */
public class OpenAiUsageAccumulator {
    private final ObjectMapper objectMapper;
    private final TokenEstimator estimator;
    private final long estimatedInputTokens;
    private OpenAiUsage reported;
    private long outputCharacters;

    public OpenAiUsageAccumulator(ObjectMapper objectMapper, TokenEstimator estimator, long estimatedInputTokens) {
        this.objectMapper = objectMapper;
        this.estimator = estimator;
        this.estimatedInputTokens = estimatedInputTokens;
    }

    public void accept(JsonNode response) {
        JsonNode usage = response.path("usage");
        if (!usage.isObject() && response.path("response").isObject()) {
            // Responses 流式 usage 位于 response.completed.response.usage。
            usage = response.path("response").path("usage");
        }
        if (usage.isObject()) {
            long input = firstLong(usage, "prompt_tokens", "input_tokens");
            long output = firstLong(usage, "completion_tokens", "output_tokens");
            JsonNode inputDetails = usage.has("input_tokens_details")
                    ? usage.path("input_tokens_details") : usage.path("prompt_tokens_details");
            JsonNode outputDetails = usage.has("output_tokens_details")
                    ? usage.path("output_tokens_details") : usage.path("completion_tokens_details");
            long cached = inputDetails.path("cached_tokens").asLong(0L);
            long cacheWrite5m = firstLong(inputDetails, "cache_write_5m_tokens", "cache_creation_5m_input_tokens");
            long cacheWrite1h = firstLong(inputDetails, "cache_write_1h_tokens", "cache_creation_1h_input_tokens");
            long audioInput = inputDetails.path("audio_tokens").asLong(0L);
            long audioOutput = outputDetails.path("audio_tokens").asLong(0L);
            reported = new OpenAiUsage(
                    input, output, cached, cacheWrite5m, cacheWrite1h, audioInput, audioOutput
            );
        }
        JsonNode choices = response.path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode message = choice.has("delta") ? choice.path("delta") : choice.path("message");
                outputCharacters += textLength(message.path("content"));
                outputCharacters += textLength(message.path("reasoning_content"));
                outputCharacters += textLength(message.path("tool_calls"));
            }
        }
        if ("response.output_text.delta".equals(response.path("type").asText())) {
            outputCharacters += textLength(response.path("delta"));
        }
    }

    public void acceptSseData(String data) {
        if (data == null || data.isBlank() || "[DONE]".equals(data)) {
            return;
        }
        try {
            accept(objectMapper.readTree(data));
        } catch (Exception ignored) {
            // 无法解析的扩展事件仍会透传，但不会把原文写入日志或异常。
        }
    }

    public OpenAiUsage result() {
        if (reported != null && (reported.inputTokens() > 0 || reported.outputTokens() > 0)) {
            return reported;
        }
        return new OpenAiUsage(estimatedInputTokens, estimator.estimateTextTokens(outputCharacters), 0L);
    }

    /**
     * 流式请求失败时，估算输入 Token 只用于预冻结和正常完成后的计费兜底，不能单独作为
     * 失败请求的收费依据。只有已经向客户端写出生成内容，才允许在中途失败时按实际输出结算。
     */
    public boolean hasGeneratedOutput() {
        return outputCharacters > 0 || (reported != null && reported.outputTokens() > 0);
    }

    private long firstLong(JsonNode node, String first, String second) {
        return node.has(first) ? node.path(first).asLong(0L) : node.path(second).asLong(0L);
    }

    private long textLength(JsonNode value) {
        if (value.isTextual()) {
            return value.textValue().length();
        }
        if (value.isMissingNode() || value.isNull()) {
            return 0L;
        }
        return value.toString().length();
    }
}
