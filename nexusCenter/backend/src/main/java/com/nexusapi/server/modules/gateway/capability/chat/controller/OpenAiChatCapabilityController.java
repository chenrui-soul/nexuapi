package com.nexusapi.server.modules.gateway.capability.chat.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.apikey.security.NexusApiKeyPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.gateway.service.OpenAiGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/** OpenAI Chat 能力的对外入口；公共调用流程委托给 Gateway Service。 */
@RestController
@RequestMapping("/v1")
public class OpenAiChatCapabilityController {
    private static final MediaType UTF8_EVENT_STREAM =
            MediaType.parseMediaType("text/event-stream;charset=UTF-8");
    private final OpenAiGatewayService service;

    public OpenAiChatCapabilityController(OpenAiGatewayService service) {
        this.service = service;
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ObjectNode> models(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listModels(principal, ClientRequestMetadata.from(request)));
    }

    @PostMapping(value = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StreamingResponseBody> chatCompletions(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestBody ObjectNode body,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest request
    ) {
        OpenAiGatewayService.GatewayResult result = service.chatCompletions(
                principal,
                body,
                groupSelector,
                ClientRequestMetadata.from(request)
        );
        if (result instanceof OpenAiGatewayService.StreamResult stream) {
            return ResponseEntity.ok()
                    .contentType(UTF8_EVENT_STREAM)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Accel-Buffering", "no")
                    .body(stream.body());
        }
        OpenAiGatewayService.JsonResult json = (OpenAiGatewayService.JsonResult) result;
        // 同一个接口需要同时支持普通 JSON 与 SSE。统一返回 StreamingResponseBody，
        // 避免 ResponseEntity<?> 被 Spring 当作普通对象序列化，导致 SSE 无法进入异步输出链。
        StreamingResponseBody jsonBody = output -> {
            output.write(json.body().toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(jsonBody);
    }

    @PostMapping(value = "/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StreamingResponseBody> responses(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestBody ObjectNode body,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        if (!body.path("model").isTextual() || body.path("model").asText().isBlank()) {
            throw validation("model 不能为空");
        }
        if (!body.hasNonNull("input")) {
            throw validation("input 不能为空");
        }
        if (body.has("stream") && !body.path("stream").isBoolean()) {
            throw validation("stream 必须是布尔值");
        }
        OpenAiGatewayService.GatewayResult result = service.responses(
                principal,
                body,
                groupSelector,
                ClientRequestMetadata.from(servletRequest)
        );
        if (result instanceof OpenAiGatewayService.StreamResult stream) {
            return ResponseEntity.ok()
                    .contentType(UTF8_EVENT_STREAM)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Accel-Buffering", "no")
                    .body(stream.body());
        }
        OpenAiGatewayService.JsonResult json = (OpenAiGatewayService.JsonResult) result;
        StreamingResponseBody jsonBody = output -> {
            output.write(json.body().toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(jsonBody);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
