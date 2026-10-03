package com.nexusapi.server.modules.routing.service;

import com.nexusapi.server.modules.routing.mapper.ServiceGroupCatalogMapper;
import com.nexusapi.server.modules.routing.vo.PublicServiceGroupResponse;
import com.nexusapi.server.modules.subscription.service.SubscriptionAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 用户侧公开服务分组目录业务层。 */
@Service
public class ServiceGroupCatalogService {
    private final ServiceGroupCatalogMapper mapper;
    private final SubscriptionAccessService subscriptionAccessService;

    public ServiceGroupCatalogService(
            ServiceGroupCatalogMapper mapper,
            SubscriptionAccessService subscriptionAccessService
    ) {
        this.mapper = mapper;
        this.subscriptionAccessService = subscriptionAccessService;
    }

    @Transactional(readOnly = true)
    public List<PublicServiceGroupResponse> listSelectableGroups(UUID userId) {
        List<PublicServiceGroupResponse> groups = mapper.findSelectableGroups(userId);
        return subscriptionAccessService.snapshot(userId).filterGroups(groups, PublicServiceGroupResponse::id);
    }

    /** 创作空间在进入 Gateway 前确认当前用户确实可见所选服务分组。 */
    @Transactional(readOnly = true)
    public boolean isSelectable(UUID userId, UUID groupId) {
        return mapper.findSelectableGroups(userId).stream().anyMatch(group -> group.id().equals(groupId));
    }
}
