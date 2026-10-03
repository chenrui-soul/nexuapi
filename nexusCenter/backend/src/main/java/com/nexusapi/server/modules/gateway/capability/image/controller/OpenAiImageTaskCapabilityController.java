package com.nexusapi.server.modules.gateway.capability.image.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.IMAGE_TASK_DETAIL;
import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.IMAGE_TASK_CREATE;

/** 异步图片任务查询入口。 */
@RestController
@RequestMapping("/v1")
public class OpenAiImageTaskCapabilityController {
    private final OpenAiGatewayService service;

    public OpenAiImageTaskCapabilityController(OpenAiGatewayService service) {
        this.service = service;
    }

    @PostMapping(value = "/images/tasks", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> createImageTask(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestBody ObjectNode body,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        String model = requiredText(body, "model");
        requiredText(body, "prompt");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.jsonCapability(
                principal, body, model, groupSelector, IMAGE_TASK_CREATE, null,
                ClientRequestMetadata.from(servletRequest)
        ));
    }

    @GetMapping(value = "/images/tasks/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> getImageTask(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @PathVariable String taskId,
            @RequestParam(value = "model", required = false) String model,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        if (!taskId.matches("[A-Za-z0-9._:-]{1,160}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "task_id 格式无效", null);
        }
        ObjectNode query = JsonNodeFactory.instance.objectNode();
        if (model != null && !model.isBlank()) query.put("model", model);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.jsonCapability(
                principal, query, model, groupSelector, IMAGE_TASK_DETAIL, "/" + taskId,
                ClientRequestMetadata.from(servletRequest)
        ));
    }

    private String requiredText(ObjectNode body, String field) {
        if (!body.path(field).isTextual() || body.path(field).asText().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + " 不能为空", null);
        }
        return body.path(field).asText();
    }

}
