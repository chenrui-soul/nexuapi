package com.nexusapi.server.modules.dashboard.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.dashboard.service.UserAnalyticsService;
import com.nexusapi.server.modules.dashboard.vo.UserGroupStatusResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户服务分组状态入口，只返回当前用户可见分组的匿名真实请求统计。 */
@RestController
@RequestMapping("/api/v1/status")
public class UserGroupStatusController {
    private final UserAnalyticsService service;

    public UserGroupStatusController(UserAnalyticsService service) {
        this.service = service;
    }

    /** 查询用户可见分组最近最多 60 次真实业务请求状态。 */
    @GetMapping("/groups")
    ApiResponse<UserGroupStatusResponse> groups(Authentication authentication) {
        return ApiResponse.ok(service.groupStatus(principal(authentication).userId()));
    }

    /** userId 只接受已认证 Session 中的服务端主体。 */
    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
