package com.nexusapi.server.modules.gateway.capability.embedding.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.EMBEDDINGS;

/** OpenAI Embeddings 向量能力的独立对外入口。 */
@RestController
@RequestMapping("/v1")
public class OpenAiEmbeddingCapabilityController {
    private final OpenAiGatewayService service;

    public OpenAiEmbeddingCapabilityController(OpenAiGatewayService service) {
        this.service = service;
    }

    @PostMapping(value = "/embeddings", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> embeddings(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestBody ObjectNode body,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        String model = requiredModel(body);
        JsonNode input = body == null ? null : body.get("input");
        validateInput(input);
        // OpenAI 接受字符串和字符串数组；平台统一按数组转发，兼容只实现批量协议的供应商。
        if (input.isTextual()) {
            var values = JsonNodeFactory.instance.arrayNode();
            values.add(input.asText());
            body.set("input", values);
        }
        if (!body.has("encoding_format")) body.put("encoding_format", "float");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.jsonCapability(
                principal, body, model, groupSelector, EMBEDDINGS, null,
                ClientRequestMetadata.from(servletRequest)
        ));
    }

    private String requiredModel(ObjectNode body) {
        if (body == null || !body.path("model").isTextual() || body.path("model").asText().isBlank()) {
            throw validation("model 不能为空");
        }
        return body.path("model").asText().strip();
    }

    /** OpenAI input 支持单个字符串或非空字符串数组，不接受对象和混合类型数组。 */
    private void validateInput(JsonNode input) {
        if (input == null || input.isNull()) throw validation("input 不能为空");
        if (input.isTextual()) {
            if (input.asText().isBlank()) throw validation("input 不能为空");
            return;
        }
        if (!input.isArray() || input.isEmpty()) throw validation("input 必须是字符串或非空字符串数组");
        for (JsonNode item : input) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw validation("input 数组只能包含非空字符串");
            }
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
