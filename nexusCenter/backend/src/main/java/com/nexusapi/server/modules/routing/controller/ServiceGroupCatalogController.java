package com.nexusapi.server.modules.routing.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.routing.service.ServiceGroupCatalogService;
import com.nexusapi.server.modules.routing.vo.PublicServiceGroupResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/** 用户创建 API 令牌时读取公开服务分组的只读入口。 */
@RestController
@RequestMapping("/api/v1/service-groups")
public class ServiceGroupCatalogController {
    private final ServiceGroupCatalogService service;

    public ServiceGroupCatalogController(ServiceGroupCatalogService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<PublicServiceGroupResponse>> list(Authentication authentication) {
        UUID userId = authentication != null && authentication.getPrincipal() instanceof NexusUserPrincipal principal
                ? principal.userId() : null;
        return ApiResponse.ok(service.listSelectableGroups(userId));
    }
}
