package com.nexusapi.server.modules.model.application;

import com.nexusapi.server.modules.model.infrastructure.persistence.ModelCatalogMapper;
import com.nexusapi.server.modules.subscription.service.SubscriptionAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ModelCatalogService {
    private final ModelCatalogMapper mapper;
    private final SubscriptionAccessService subscriptionAccessService;

    public ModelCatalogService(ModelCatalogMapper mapper, SubscriptionAccessService subscriptionAccessService) {
        this.mapper = mapper;
        this.subscriptionAccessService = subscriptionAccessService;
    }

    @Transactional(readOnly = true)
    public List<ModelCatalogItem> listPublicModels(UUID userId) {
        SubscriptionAccessService.AccessSnapshot access = subscriptionAccessService.snapshot(userId);
        if (access.restricted() && access.allowedModelIds().isEmpty()) return List.of();
        return mapper.findPublicModels(access.restricted() ? List.copyOf(access.allowedModelIds()) : null);
    }
}
