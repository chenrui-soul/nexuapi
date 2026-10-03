package com.nexusapi.server.modules.routing.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.routing.dto.AdminGroupConfigurationRequest;
import com.nexusapi.server.modules.routing.dto.AdminGroupUserGrantRequest;
import com.nexusapi.server.modules.routing.dto.AdminRoutingGroupRequest;
import com.nexusapi.server.modules.routing.service.AdminRoutingService;
import com.nexusapi.server.modules.routing.vo.AdminGroupConfigurationResponse;
import com.nexusapi.server.modules.routing.vo.AdminGroupUserGrantResponse;
import com.nexusapi.server.modules.routing.vo.AdminRoutingGroupResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 管理员计费分组、倍率和分组路由配置入口。 */
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin")
public class AdminRoutingController {
    private final AdminRoutingService service;

    public AdminRoutingController(AdminRoutingService service) {
        this.service = service;
    }

    @GetMapping("/groups")
    ApiResponse<PageResponse<AdminRoutingGroupResponse>> listGroups(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.listGroups(page, pageSize, query, status));
    }

    @PostMapping("/groups")
    ResponseEntity<ApiResponse<AdminRoutingGroupResponse>> createGroup(
            Authentication authentication,
            @Valid @RequestBody AdminRoutingGroupRequest body,
            HttpServletRequest request
    ) {
        AdminRoutingGroupResponse created = service.createGroup(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/groups/{id}")
    ApiResponse<AdminRoutingGroupResponse> updateGroup(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody AdminRoutingGroupRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.updateGroup(
                principal(authentication).userId(), id, body, ClientRequestMetadata.from(request)
        ));
    }

    /** 管理员读取“需授权使用”分组的当前有效用户。 */
    @GetMapping("/groups/{id}/user-grants")
    ApiResponse<List<AdminGroupUserGrantResponse>> listGroupUserGrants(@PathVariable UUID id) {
        return ApiResponse.ok(service.listGroupUserGrants(id));
    }

    /** 管理员批量替换授权名单；CSRF 由控制台安全链统一校验。 */
    @PutMapping("/groups/{id}/user-grants")
    ApiResponse<List<AdminGroupUserGrantResponse>> updateGroupUserGrants(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody AdminGroupUserGrantRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.updateGroupUserGrants(
                principal(authentication).userId(), id, body, ClientRequestMetadata.from(request)
        ));
    }

    /** 加载分组下全部模型路由和分组凭证的脱敏状态。 */
    @GetMapping("/groups/{id}/configuration")
    ApiResponse<AdminGroupConfigurationResponse> getGroupConfiguration(@PathVariable UUID id) {
        return ApiResponse.ok(service.getConfiguration(id));
    }

    /** 原子保存模型勾选、路由参数和分组独立上游 APIKey。 */
    @PutMapping("/groups/{id}/configuration")
    ApiResponse<AdminGroupConfigurationResponse> updateGroupConfiguration(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody AdminGroupConfigurationRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.updateConfiguration(
                principal(authentication).userId(), id, body, ClientRequestMetadata.from(request)
        ));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
