package com.nexusapi.server.modules.model.pricing.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.model.pricing.dto.AdminModelPricingActivateRequest;
import com.nexusapi.server.modules.model.pricing.dto.AdminModelPricingDeleteRequest;
import com.nexusapi.server.modules.model.pricing.dto.AdminModelPricingRequest;
import com.nexusapi.server.modules.model.pricing.dto.AdminModelPricingSourceModeRequest;
import com.nexusapi.server.modules.model.pricing.service.AdminModelPricingService;
import com.nexusapi.server.modules.model.pricing.vo.AdminModelPricingResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 管理员模型计费版本入口；版本发布和历史激活均受 Session、角色和 CSRF 保护。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/models/{modelId}/pricing")
public class AdminModelPricingController {
    private final AdminModelPricingService service;

    public AdminModelPricingController(AdminModelPricingService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<AdminModelPricingResponse> get(@PathVariable UUID modelId) {
        return ApiResponse.ok(service.get(modelId));
    }

    /** 保存即生成新的不可变版本并立即激活；旧版本不会被覆盖。 */
    @PutMapping
    ApiResponse<AdminModelPricingResponse> publish(
            Authentication authentication,
            @PathVariable UUID modelId,
            @Valid @RequestBody AdminModelPricingRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.publish(
                principal(authentication).userId(), modelId, body, ClientRequestMetadata.from(request)
        ));
    }

    /** 回滚价格只激活历史版本，不修改已结算请求或账本。 */
    @PostMapping("/versions/{versionId}/activate")
    ApiResponse<AdminModelPricingResponse> activate(
            Authentication authentication,
            @PathVariable UUID modelId,
            @PathVariable UUID versionId,
            @Valid @RequestBody AdminModelPricingActivateRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.activate(
                principal(authentication).userId(), modelId, versionId, body,
                ClientRequestMetadata.from(request)
        ));
    }

    /** 只删除未生效且从未进入请求计费明细的历史版本。 */
    @DeleteMapping("/versions/{versionId}")
    ApiResponse<AdminModelPricingResponse> deleteVersion(
            Authentication authentication,
            @PathVariable UUID modelId,
            @PathVariable UUID versionId,
            @Valid @RequestBody AdminModelPricingDeleteRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.deleteVersion(
                principal(authentication).userId(), modelId, versionId, body,
                ClientRequestMetadata.from(request)
        ));
    }

    /** 切换人工定价或跟随上游；该接口不会改变模型资料本身的同步开关。 */
    @PutMapping("/source-mode")
    ApiResponse<AdminModelPricingResponse> updateSourceMode(
            Authentication authentication,
            @PathVariable UUID modelId,
            @Valid @RequestBody AdminModelPricingSourceModeRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.updateSourceMode(
                principal(authentication).userId(), modelId, body, ClientRequestMetadata.from(request)
        ));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
