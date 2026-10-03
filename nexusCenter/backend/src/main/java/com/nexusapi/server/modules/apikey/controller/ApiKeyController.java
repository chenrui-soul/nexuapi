package com.nexusapi.server.modules.apikey.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.apikey.dto.ApiKeyCreateRequest;
import com.nexusapi.server.modules.apikey.dto.ApiKeyStatusRequest;
import com.nexusapi.server.modules.apikey.dto.ApiKeyUpdateRequest;
import com.nexusapi.server.modules.apikey.service.ApiKeyService;
import com.nexusapi.server.modules.apikey.vo.ApiKeyCreatedResponse;
import com.nexusapi.server.modules.apikey.vo.ApiKeyItemResponse;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 控制台 API 令牌管理入口。
 *
 * <p>Controller 只负责 HTTP 参数校验、提取当前登录用户和采集请求元数据，
 * API 令牌的权限判断、状态流转与安全规则统一放在 {@link ApiKeyService} 中。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {
    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    /** 查询当前登录用户自己的 API 令牌，支持分页、搜索和状态筛选。 */
    @GetMapping
    ApiResponse<PageResponse<ApiKeyItemResponse>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.list(principal(authentication).userId(), page, pageSize, query, status));
    }

    /** 创建 API 令牌；完整 Secret 只会通过本次创建响应返回一次。 */
    @PostMapping
    ResponseEntity<ApiResponse<ApiKeyCreatedResponse>> create(
            Authentication authentication,
            @Valid @RequestBody ApiKeyCreateRequest body,
            HttpServletRequest request
    ) {
        ApiKeyCreatedResponse created = service.create(
                principal(authentication).userId(),
                body,
                ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /** 返回当前用户本人 Key 的完整值；历史未加密 Key 需重新生成。 */
    @GetMapping("/{id}/secret")
    ApiResponse<Map<String, String>> reveal(
            Authentication authentication,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(Map.of("secret", service.reveal(
                principal(authentication).userId(), id, ClientRequestMetadata.from(request))));
    }

    /** 更新 API 令牌的完整可编辑配置快照，版本冲突由 Service 层处理。 */
    @PatchMapping("/{id}")
    ApiResponse<ApiKeyItemResponse> update(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody ApiKeyUpdateRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.update(
                principal(authentication).userId(),
                id,
                body,
                ClientRequestMetadata.from(request)
        ));
    }

    /** 在启用和禁用状态之间切换，不允许恢复已经撤销的 Key。 */
    @PutMapping("/{id}/status")
    ApiResponse<ApiKeyItemResponse> changeStatus(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody ApiKeyStatusRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.changeStatus(
                principal(authentication).userId(),
                id,
                body,
                ClientRequestMetadata.from(request)
        ));
    }

    /** 永久撤销 API 令牌；接口按幂等语义返回撤销结果。 */
    @DeleteMapping("/{id}")
    ApiResponse<Map<String, Boolean>> revoke(
            Authentication authentication,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        boolean revoked = service.revoke(
                principal(authentication).userId(),
                id,
                ClientRequestMetadata.from(request)
        );
        return ApiResponse.ok(Map.of("revoked", revoked));
    }

    /**
     * 只信任 Spring Security 已认证的 Principal，不从请求参数接收 userId，
     * 避免攻击者通过伪造用户编号访问其他用户的 API 令牌。
     */
    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
