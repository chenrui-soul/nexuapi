package com.nexusapi.server.modules.gateway.capability.image.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.apikey.security.NexusApiKeyPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.gateway.capability.image.model.OpenAiImageGenerationRequest;
import com.nexusapi.server.modules.gateway.service.OpenAiGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** OpenAI 图片生成能力的对外入口；协议适配完成后交由统一 Gateway 编排。 */
@RestController
@RequestMapping("/v1")
public class OpenAiImageCapabilityController {
    private final ObjectMapper objectMapper;
    private final OpenAiGatewayService service;

    public OpenAiImageCapabilityController(ObjectMapper objectMapper, OpenAiGatewayService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @PostMapping(
            value = "/images/generations",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<JsonNode> imageGenerations(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestParam("model") String model,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "n", required = false) String quantity,
            @RequestParam(value = "aspect_ratio", required = false) String aspectRatio,
            @RequestParam(value = "quality", required = false) String quality,
            @RequestParam(value = "extra_params", required = false) String extraParams,
            @RequestParam(value = "extra_params.resolution", required = false) String dottedResolution,
            @RequestParam(value = "extra_params[resolution]", required = false) String bracketResolution,
            @RequestParam(value = "resolution", required = false) String directResolution,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @RequestPart(value = "images[]", required = false) List<MultipartFile> bracketImages,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        OpenAiImageGenerationRequest request = OpenAiImageGenerationRequest.create(
                objectMapper, model, prompt, quantity, aspectRatio, quality, extraParams,
                dottedResolution, bracketResolution, directResolution, images, bracketImages
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.imageGenerations(
                        principal, request, groupSelector, ClientRequestMetadata.from(servletRequest)
                ));
    }

    @PostMapping(
            value = "/images/generations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<JsonNode> imageGenerationsJson(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestBody JsonNode body,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        OpenAiImageGenerationRequest request = OpenAiImageGenerationRequest.createJson(body);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.imageGenerations(
                        principal, request, groupSelector, ClientRequestMetadata.from(servletRequest)
                ));
    }
}
