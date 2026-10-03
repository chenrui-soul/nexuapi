package com.nexusapi.server.modules.requestlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.requestlog.service.RequestPayloadSummaryService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPayloadSummaryServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RequestPayloadSummaryService service = new RequestPayloadSummaryService(objectMapper);

    @Test
    void requestAndResponseSummariesKeepShapeAndUsageButDropContent() throws Exception {
        String requestId = "req-summary-test";
        var request = objectMapper.readTree("""
                {"model":"gpt-5.6-sol","temperature":0.2,"messages":[{"role":"user","content":"secret prompt"}],"authorization":"do-not-store"}
                """);
        var response = objectMapper.readTree("""
                {"id":"chatcmpl-1","model":"gpt-upstream","choices":[{"message":{"content":"secret answer"}}],"usage":{"prompt_tokens":12,"completion_tokens":4}}
                """);

        service.captureRequest(requestId, request);
        service.captureResponse(requestId, response);
        var snapshot = service.take(requestId);

        assertThat(snapshot.requestJson()).contains("gpt-5.6-sol", "message_count", "input_chars", "redacted");
        assertThat(snapshot.requestJson()).doesNotContain("secret prompt", "authorization", "do-not-store");
        assertThat(snapshot.responseJson()).contains("choice_count", "prompt_tokens", "completion_tokens");
        assertThat(snapshot.responseJson()).doesNotContain("secret answer");
        assertThat(snapshot.requestSize()).isPositive();
        assertThat(snapshot.responseSize()).isPositive();
        assertThat(service.take(requestId).requestJson()).isEqualTo("{}");
    }

    @Test
    void statusSummaryIsBoundedAndSafe() throws Exception {
        service.captureResponseSummary("req-status", Map.of("status_code", 502, "error_code", "upstream_timeout"), 123L);
        var snapshot = service.take("req-status");
        assertThat(snapshot.responseJson()).contains("502", "upstream_timeout", "redacted");
        assertThat(snapshot.responseSize()).isEqualTo(123L);
    }

    @Test
    void detailKeepsBusinessPromptAndImageUrlButNeverStoresBinaryPayload() throws Exception {
        String requestId = "req-image-detail";
        var request = objectMapper.readTree("""
                {"model":"gpt-image-2","prompt":"一座漂浮的未来城市","n":2,"quality":"medium","authorization":"secret"}
                """);
        var response = objectMapper.readTree("""
                {"created":1710000000,"data":[{"url":"https://cdn.example.test/generated.png"},{"b64_json":"ZmFrZS1pbWFnZQ=="}]}
                """);

        service.captureRequest(requestId, request);
        service.captureResponse(requestId, response);
        var snapshot = service.take(requestId);

        assertThat(snapshot.requestDetailJson()).contains("一座漂浮的未来城市", "gpt-image-2", "quality");
        assertThat(snapshot.requestDetailJson()).doesNotContain("secret");
        assertThat(snapshot.responseDetailJson()).contains("https://cdn.example.test/generated.png");
        assertThat(snapshot.responseDetailJson()).contains("REDACTED");
        assertThat(snapshot.responseDetailJson()).doesNotContain("ZmFrZS1pbWFnZQ==");
    }
}
