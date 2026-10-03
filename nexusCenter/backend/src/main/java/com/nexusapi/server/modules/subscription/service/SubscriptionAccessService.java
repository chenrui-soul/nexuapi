package com.nexusapi.server.modules.subscription.service;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.subscription.entity.SubscriptionAccessRow;
import com.nexusapi.server.modules.subscription.mapper.SubscriptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 统一提供活动订阅的服务分组与模型权限判断。
 *
 * <p>没有活动订阅时保持原按余额调用规则；存在活动订阅时必须同时命中分组和模型快照，
 * 通用钱包余额只能作为扣费兜底，不能绕过套餐权限范围。</p>
 */
@Service
public class SubscriptionAccessService {
    private final SubscriptionMapper mapper;

    public SubscriptionAccessService(SubscriptionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public AccessSnapshot snapshot(UUID userId) {
        if (userId == null) {
            return AccessSnapshot.unrestricted();
        }
        SubscriptionAccessRow accessRow = mapper.findActiveSubscriptionAccess(userId);
        if (accessRow == null || accessRow.getSubscriptionId() == null) {
            return AccessSnapshot.unrestricted();
        }
        return new AccessSnapshot(
                accessRow.getSubscriptionId(),
                accessRow.getConcurrencyLimit(),
                Set.copyOf(mapper.findAllowedGroupIds(accessRow.getSubscriptionId())),
                Set.copyOf(mapper.findAllowedModelIds(accessRow.getSubscriptionId()))
        );
    }

    @Transactional(readOnly = true)
    public void assertGroupAllowed(UUID userId, UUID serviceGroupId) {
        assertGroupAllowed(snapshot(userId), serviceGroupId);
    }

    public void assertGroupAllowed(AccessSnapshot access, UUID serviceGroupId) {
        if (access.restricted() && !access.allowedGroupIds().contains(serviceGroupId)) {
            throw new BusinessException(
                    ErrorCode.SUBSCRIPTION_GROUP_NOT_ALLOWED,
                    "当前订阅套餐不包含该服务分组",
                    null
            );
        }
    }

    @Transactional(readOnly = true)
    public void assertModelAllowed(UUID userId, UUID modelId) {
        assertModelAllowed(snapshot(userId), modelId);
    }

    public void assertModelAllowed(AccessSnapshot access, UUID modelId) {
        if (access.restricted() && !access.allowedModelIds().contains(modelId)) {
            throw new BusinessException(
                    ErrorCode.SUBSCRIPTION_MODEL_NOT_ALLOWED,
                    "当前订阅套餐不包含该模型",
                    null
            );
        }
    }

    @Transactional(readOnly = true)
    public void assertModelsAllowed(UUID userId, List<UUID> modelIds) {
        assertModelsAllowed(snapshot(userId), modelIds);
    }

    public void assertModelsAllowed(AccessSnapshot access, List<UUID> modelIds) {
        if (modelIds.isEmpty()) return;
        if (access.restricted() && !access.allowedModelIds().containsAll(modelIds)) {
            throw new BusinessException(
                    ErrorCode.SUBSCRIPTION_MODEL_NOT_ALLOWED,
                    "模型白名单包含当前订阅套餐之外的模型",
                    null
            );
        }
    }

    public record AccessSnapshot(
            UUID subscriptionId,
            Integer subscriptionConcurrencyLimit,
            Set<UUID> allowedGroupIds,
            Set<UUID> allowedModelIds
    ) {
        public static AccessSnapshot unrestricted() {
            return new AccessSnapshot(null, null, Set.of(), Set.of());
        }

        public boolean restricted() {
            return subscriptionId != null;
        }

        public <T> List<T> filterGroups(List<T> source, java.util.function.Function<T, UUID> idExtractor) {
            if (!restricted()) return List.copyOf(source);
            return source.stream().filter(item -> allowedGroupIds.contains(idExtractor.apply(item))).toList();
        }

        public List<UUID> effectiveModelIds(List<UUID> apiKeyModelIds) {
            if (!restricted()) return List.copyOf(apiKeyModelIds);
            if (apiKeyModelIds.isEmpty()) return List.copyOf(allowedModelIds);
            return apiKeyModelIds.stream().filter(allowedModelIds::contains).toList();
        }
    }
}
