package com.nexusapi.server.modules.requestlog.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.requestlog.service.UserRequestLogService;
import com.nexusapi.server.modules.requestlog.vo.UserRequestLogResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 用户控制台调用日志入口，只读取当前登录用户自己的请求。 */
@Validated
@RestController
@RequestMapping("/api/v1/request-logs")
public class UserRequestLogController {
    private final UserRequestLogService service;

    public UserRequestLogController(UserRequestLogService service) {
        this.service = service;
    }

    /** 分页查询当前用户自己的调用日志。 */
    @GetMapping
    ApiResponse<PageResponse<UserRequestLogResponse>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @Size(max = 160) String model,
            @RequestParam(defaultValue = "24h") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        return ApiResponse.ok(service.list(
                principal(authentication).userId(), page, pageSize, query, status, model, period, from, to
        ));
    }

    /** 查询当前用户自己的单条调用日志详情。 */
    @GetMapping("/{requestId}")
    ApiResponse<UserRequestLogResponse> get(
            Authentication authentication,
            @PathVariable @Size(max = 80) String requestId
    ) {
        return ApiResponse.ok(service.get(principal(authentication).userId(), requestId));
    }

    /** userId 只来自已认证 Session，客户端不能通过参数切换用户边界。 */
    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
