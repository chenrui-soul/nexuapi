package com.nexusapi.server.modules.model.api;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.model.application.ModelMarketDetailResponse;
import com.nexusapi.server.modules.model.application.ModelMarketResponse;
import com.nexusapi.server.modules.model.application.ModelMarketService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 用户模型市场聚合接口；默认展示全部启用且公开的模型，可按可见服务分组筛选。 */
@Validated
@RestController
@RequestMapping("/api/v1/model-market")
public class ModelMarketController {
    private final ModelMarketService service;

    public ModelMarketController(ModelMarketService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<ModelMarketResponse> list(
            Authentication authentication,
            @RequestParam(name = "service_group_id", required = false) UUID serviceGroupId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) @Size(max = 80) String provider,
            @RequestParam(name = "capability_type", required = false) @Size(max = 24) String capabilityType,
            @RequestParam(name = "supports_streaming", required = false) Boolean supportsStreaming,
            @RequestParam(name = "supports_tools", required = false) Boolean supportsTools,
            @RequestParam(defaultValue = "name") String sort
    ) {
        UUID userId = authentication != null && authentication.getPrincipal() instanceof NexusUserPrincipal principal
                ? principal.userId() : null;
        return ApiResponse.ok(service.list(userId, serviceGroupId, page, pageSize, query, provider, capabilityType,
                supportsStreaming, supportsTools, sort));
    }

    /** 返回独立详情页需要的公开资料；模型接口关系在这里只作为文档展示依据。 */
    @GetMapping("/{modelId}")
    ApiResponse<ModelMarketDetailResponse> detail(Authentication authentication, @PathVariable UUID modelId) {
        UUID userId = authentication != null && authentication.getPrincipal() instanceof NexusUserPrincipal principal
                ? principal.userId() : null;
        return ApiResponse.ok(service.detail(userId, modelId));
    }
}
