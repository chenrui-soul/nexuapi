package com.nexusapi.server.modules.creation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.gateway.capability.image.model.OpenAiImageGenerationRequest;
import com.nexusapi.server.modules.gateway.security.ConsoleGatewayPrincipal;
import com.nexusapi.server.modules.gateway.service.OpenAiGatewayService;
import com.nexusapi.server.modules.gateway.upstream.UpstreamCallException;
import com.nexusapi.server.modules.routing.service.ServiceGroupCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.UUID;

import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.VIDEO_CREATE;

/** 用户创作空间入口；服务分组、计费、调用日志和上游请求全部复用现有 Gateway。 */
@RestController
@RequestMapping("/api/v1/creation-space")
public class CreationSpaceController {
    private final ObjectMapper objectMapper;
    private final OpenAiGatewayService gatewayService;
    private final ServiceGroupCatalogService groupService;

    public CreationSpaceController(
            ObjectMapper objectMapper,
            OpenAiGatewayService gatewayService,
            ServiceGroupCatalogService groupService
    ) {
        this.objectMapper = objectMapper;
        this.gatewayService = gatewayService;
        this.groupService = groupService;
    }

    @PostMapping("/chat")
    ResponseEntity<?> chat(
            Authentication authentication,
            @RequestBody ObjectNode request,
            HttpServletRequest servletRequest
    ) {
        ConsoleGatewayPrincipal caller = caller(authentication, request);
        ObjectNode payload = request.deepCopy();
        payload.remove("service_group_id");
        boolean streaming = payload.path("stream").asBoolean(false);
        try {
            OpenAiGatewayService.GatewayResult result = gatewayService.chatCompletions(
                    caller, payload, caller.serviceGroupId().toString(), ClientRequestMetadata.from(servletRequest)
            );
            if (streaming && result instanceof OpenAiGatewayService.StreamResult stream) {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/event-stream;charset=UTF-8"))
                        .cacheControl(CacheControl.noStore())
                        .header("X-Accel-Buffering", "no")
                        .body(stream.body());
            }
            if (!(result instanceof OpenAiGatewayService.JsonResult json)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            // 控制台普通响应继续保持 ApiResponse 包装；流式响应按 SSE 原始事件返回。
            StreamingResponseBody jsonBody = output -> {
                output.write(objectMapper.writeValueAsBytes(ApiResponse.ok(json.body())));
                output.flush();
            };
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        } catch (UpstreamCallException exception) {
            throw upstream(exception);
        }
    }

    @PostMapping("/images")
    ApiResponse<JsonNode> image(
            Authentication authentication,
            @RequestBody ObjectNode request,
            HttpServletRequest servletRequest
    ) {
        ConsoleGatewayPrincipal caller = caller(authentication, request);
        OpenAiImageGenerationRequest imageRequest = OpenAiImageGenerationRequest.create(
                objectMapper,
                text(request, "model"),
                text(request, "prompt"),
                optionalText(request, "n"),
                optionalText(request, "aspect_ratio"),
                optionalText(request, "quality"),
                null, null, null, optionalText(request, "resolution"),
                List.of(), List.of()
        );
        try {
            return ApiResponse.ok(gatewayService.imageGenerations(
                    caller, imageRequest, caller.serviceGroupId().toString(),
                    ClientRequestMetadata.from(servletRequest)
            ));
        } catch (UpstreamCallException exception) {
            throw upstream(exception);
        }
    }

    @PostMapping("/videos")
    ApiResponse<JsonNode> video(
            Authentication authentication,
            @RequestBody ObjectNode request,
            HttpServletRequest servletRequest
    ) {
        ConsoleGatewayPrincipal caller = caller(authentication, request);
        text(request, "model");
        text(request, "prompt");
        ObjectNode payload = request.deepCopy();
        payload.remove("service_group_id");
        try {
            return ApiResponse.ok(gatewayService.jsonCapability(
                    caller, payload, payload.path("model").asText(), caller.serviceGroupId().toString(),
                    VIDEO_CREATE, null, ClientRequestMetadata.from(servletRequest)
            ));
        } catch (UpstreamCallException exception) {
            throw upstream(exception);
        }
    }

    private ConsoleGatewayPrincipal caller(Authentication authentication, ObjectNode request) {
        NexusUserPrincipal user = principal(authentication);
        String rawGroupId = text(request, "service_group_id");
        UUID groupId;
        try { groupId = UUID.fromString(rawGroupId); }
        catch (IllegalArgumentException exception) { throw validation("服务分组格式无效"); }
        if (!groupService.isSelectable(user.userId(), groupId)) throw new BusinessException(ErrorCode.GROUP_NOT_AVAILABLE);
        return new ConsoleGatewayPrincipal(user.userId(), groupId);
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }

    private String text(ObjectNode request, String field) {
        if (request == null || !request.path(field).isTextual() || request.path(field).asText().isBlank()) {
            throw validation(field + " 不能为空");
        }
        return request.path(field).asText();
    }

    private String optionalText(ObjectNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) return null;
        return value.isTextual() ? value.asText() : value.asText();
    }

    private BusinessException upstream(UpstreamCallException exception) {
        if (exception.clientStatus().value() == 429) {
            return new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "上游当前请求过多，请稍后重试", 1);
        }
        if (exception.clientStatus().is4xxClientError()) {
            return new BusinessException(ErrorCode.VALIDATION_ERROR, "上游未接受当前创作参数", null);
        }
        return new BusinessException(ErrorCode.UPSTREAM_ERROR);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
