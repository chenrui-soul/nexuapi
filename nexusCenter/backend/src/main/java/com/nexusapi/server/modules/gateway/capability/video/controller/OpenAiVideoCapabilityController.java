package com.nexusapi.server.modules.gateway.capability.video.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.VIDEO_CREATE;
import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.VIDEO_DETAIL;
import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.VIDEO_LIST;

/** 视频任务提交和列表查询的对外入口。 */
@RestController
@RequestMapping("/v1")
public class OpenAiVideoCapabilityController {
    private final ObjectMapper objectMapper;
    private final OpenAiGatewayService service;

    public OpenAiVideoCapabilityController(ObjectMapper objectMapper, OpenAiGatewayService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @PostMapping(value = "/videos", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> createVideo(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestBody ObjectNode body,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        String model = requiredText(body, "model");
        requiredText(body, "prompt");
        return ok(service.jsonCapability(
                principal, body, model, groupSelector, VIDEO_CREATE, null,
                ClientRequestMetadata.from(servletRequest)
        ));
    }

    @GetMapping(value = "/videos", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> listVideos(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestParam Map<String, String> parameters,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        if (parameters.size() > 20) throw validation("查询参数过多");
        ObjectNode query = objectMapper.createObjectNode();
        parameters.forEach((name, value) -> {
            if (name.length() > 64 || value.length() > 512) throw validation("查询参数格式无效");
            query.put(name, value);
        });
        String model = parameters.get("model");
        return ok(service.jsonCapability(
                principal, query, model, groupSelector, VIDEO_LIST, null,
                ClientRequestMetadata.from(servletRequest)
        ));
    }

    @GetMapping(value = "/videos/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> getVideo(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @PathVariable String taskId,
            @RequestParam(value = "model", required = false) String model,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        if (!taskId.matches("[A-Za-z0-9._:-]{1,160}")) throw validation("task_id 格式无效");
        ObjectNode query = objectMapper.createObjectNode();
        if (model != null) query.put("model", model);
        return ok(service.jsonCapability(
                principal, query, model, groupSelector, VIDEO_DETAIL, "/" + taskId,
                ClientRequestMetadata.from(servletRequest)
        ));
    }

    private String requiredText(ObjectNode body, String field) {
        if (!body.path(field).isTextual() || body.path(field).asText().isBlank()) {
            throw validation(field + " 不能为空");
        }
        return body.path(field).asText();
    }

    private ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
