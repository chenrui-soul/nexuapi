package com.nexusapi.server.modules.model.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.gateway.support.GatewayPricingService;
import com.nexusapi.server.modules.model.infrastructure.persistence.ModelMarketGroupRow;
import com.nexusapi.server.modules.model.infrastructure.persistence.ModelMarketInterfaceRow;
import com.nexusapi.server.modules.model.infrastructure.persistence.ModelMarketMapper;
import com.nexusapi.server.modules.model.infrastructure.persistence.ModelMarketRow;
import com.nexusapi.server.modules.model.pricing.model.BillingType;
import com.nexusapi.server.modules.protocol.dto.ProtocolSchemaField;
import com.nexusapi.server.modules.routing.service.ServiceGroupCatalogService;
import com.nexusapi.server.modules.routing.vo.PublicServiceGroupResponse;
import com.nexusapi.server.modules.subscription.service.SubscriptionAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 用户模型市场业务层；目录展示与 Gateway 运行时可路由性保持职责分离。 */
@Service
public class ModelMarketService {
    private static final TypeReference<Map<String, List<ProtocolSchemaField>>> SCHEMA_TYPE = new TypeReference<>() { };
    private final ModelMarketMapper mapper;
    private final ServiceGroupCatalogService groupService;
    private final GatewayPricingService pricingService;
    private final ObjectMapper objectMapper;
    private final SubscriptionAccessService subscriptionAccessService;

    public ModelMarketService(
            ModelMarketMapper mapper,
            ServiceGroupCatalogService groupService,
            GatewayPricingService pricingService,
            ObjectMapper objectMapper,
            SubscriptionAccessService subscriptionAccessService
    ) {
        this.mapper = mapper;
        this.groupService = groupService;
        this.pricingService = pricingService;
        this.objectMapper = objectMapper;
        this.subscriptionAccessService = subscriptionAccessService;
    }

    /** 读取单模型的公开详情、文档关联和用户可见分组价格。 */
    @Transactional(readOnly = true)
    public ModelMarketDetailResponse detail(UUID userId, UUID modelId) {
        ModelMarketRow row = mapper.findById(modelId);
        if (row == null) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE);
        }
        SubscriptionAccessService.AccessSnapshot access = subscriptionAccessService.snapshot(userId);
        subscriptionAccessService.assertModelAllowed(access, modelId);
        List<ModelMarketInterface> interfaces = mapper.findInterfaces(modelId).stream()
                .map(this::toInterface)
                .toList();
        List<ModelMarketGroupPrice> groups = access.filterGroups(
                        mapper.findGroupPrices(modelId, userId), ModelMarketGroupRow::id
                ).stream()
                .map(group -> toGroupPrice(row, group))
                .toList();
        return new ModelMarketDetailResponse(toItem(row), interfaces, groups);
    }

    @Transactional(readOnly = true)
    public ModelMarketResponse list(
            UUID userId,
            UUID requestedGroupId,
            int page,
            int pageSize,
            String query,
            String provider,
            String capabilityType,
            Boolean supportsStreaming,
            Boolean supportsTools,
            String sort
    ) {
        PublicServiceGroupResponse group = selectGroup(userId, requestedGroupId);
        SubscriptionAccessService.AccessSnapshot access = subscriptionAccessService.snapshot(userId);
        List<UUID> allowedModelIds = access.restricted() ? List.copyOf(access.allowedModelIds()) : null;
        String normalizedSort = normalizeSort(sort);
        String normalizedQuery = normalize(query, 100);
        String normalizedProvider = normalize(provider, 80);
        String normalizedCapability = normalize(capabilityType, 24);
        int offset = (page - 1) * pageSize;
        if (access.restricted() && allowedModelIds.isEmpty()) {
            return new ModelMarketResponse(
                    List.of(), 0, page, pageSize, group == null ? null : group.id(),
                    group == null ? null : group.name(), group == null ? null : group.priceMultiplier()
            );
        }
        List<ModelMarketItem> items = mapper.findPage(
                        group == null ? null : group.id(), userId, allowedModelIds,
                        normalizedQuery, normalizedProvider, normalizedCapability,
                        supportsStreaming, supportsTools, normalizedSort, offset, pageSize
                ).stream()
                .map(this::toItem)
                .toList();
        return new ModelMarketResponse(
                items,
                mapper.count(group == null ? null : group.id(), allowedModelIds,
                        normalizedQuery, normalizedProvider, normalizedCapability,
                        supportsStreaming, supportsTools),
                page,
                pageSize,
                group == null ? null : group.id(),
                group == null ? null : group.name(),
                group == null ? null : group.priceMultiplier()
        );
    }

    private PublicServiceGroupResponse selectGroup(UUID userId, UUID requested) {
        // 未指定分组时展示全部公开模型，不把目录是否可见绑定到任何上游运行资源。
        if (requested == null) return null;
        List<PublicServiceGroupResponse> groups = groupService.listSelectableGroups(userId);
        return groups.stream()
                .filter(group -> group.id().equals(requested))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_AVAILABLE));
    }

    private ModelMarketItem toItem(ModelMarketRow row) {
        return new ModelMarketItem(
                row.id(), row.publicName(), row.displayName(), row.provider(), row.capabilityType(),
                row.contextWindow(), row.maxOutputTokens(), row.supportsStreaming(), row.supportsTools(),
                row.supportsStructuredOutput(), row.serviceGroupId(), row.serviceGroupName(), row.priceMultiplier(),
                row.inputPrice(), row.outputPrice(), row.cachedInputPrice(),
                effectivePrice(row.inputPrice(), row.priceMultiplier()),
                effectivePrice(row.outputPrice(), row.priceMultiplier()),
                effectivePrice(row.cachedInputPrice(), row.priceMultiplier()),
                row.priceUnit(), row.billingType(), BillingType.fromCode(row.billingType()).unit(),
                row.activePricingVersionId() == null ? row.inputPrice() : row.unitPrice(),
                effectivePrice(row.activePricingVersionId() == null ? row.inputPrice() : row.unitPrice(), row.priceMultiplier()),
                row.displayOriginalPrice(), row.inputTokenRatio(), row.outputTokenRatio(),
                row.audioInputTokenRatio(), row.audioOutputTokenRatio(), row.cachedInputTokenRatio(),
                row.cacheWrite5mTokenRatio(), row.cacheWrite1hTokenRatio(), row.chargeDesc(), row.activePricingVersionId(),
                row.serviceGroupId() == null ? "catalog" : "in_group",
                row.recommendedServiceGroupId(), row.recommendedServiceGroupName(), row.recommendedPriceMultiplier(),
                row.recommendedServiceGroupId() == null ? null : effectivePrice(row.inputPrice(), row.recommendedPriceMultiplier()),
                row.recommendedServiceGroupId() == null ? null : effectivePrice(row.outputPrice(), row.recommendedPriceMultiplier()),
                row.recommendedServiceGroupId() == null ? null : effectivePrice(row.cachedInputPrice(), row.recommendedPriceMultiplier()),
                row.recommendedServiceGroupId() == null ? null : effectivePrice(
                        row.activePricingVersionId() == null ? row.inputPrice() : row.unitPrice(),
                        row.recommendedPriceMultiplier())
        );
    }

    private ModelMarketInterface toInterface(ModelMarketInterfaceRow row) {
        return new ModelMarketInterface(
                row.id(), row.interfaceCode(), row.interfaceName(), row.interfaceVersion(), row.capabilityType(),
                row.transportMode(), row.httpMethod(), row.publicPath(), row.requestContentType(), row.description(),
                readFields(row.requestSchemaJson()), readFields(row.responseSchemaJson())
        );
    }

    private ModelMarketGroupPrice toGroupPrice(ModelMarketRow model, ModelMarketGroupRow group) {
        return new ModelMarketGroupPrice(
                group.id(), group.code(), group.name(), group.description(), group.priceMultiplier(),
                effectivePrice(model.inputPrice(), group.priceMultiplier()),
                effectivePrice(model.outputPrice(), group.priceMultiplier()),
                effectivePrice(model.cachedInputPrice(), group.priceMultiplier()),
                effectivePrice(model.activePricingVersionId() == null ? model.inputPrice() : model.unitPrice(),
                        group.priceMultiplier())
        );
    }

    private List<ProtocolSchemaField> readFields(String json) {
        try {
            Map<String, List<ProtocolSchemaField>> schema = objectMapper.readValue(json, SCHEMA_TYPE);
            return List.copyOf(schema.getOrDefault("fields", List.of()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid public model interface schema", exception);
        }
    }

    private java.math.BigDecimal effectivePrice(
            java.math.BigDecimal basePrice,
            java.math.BigDecimal priceMultiplier
    ) {
        return priceMultiplier == null ? basePrice : pricingService.applyGroupMultiplier(basePrice, priceMultiplier);
    }

    private String normalizeSort(String value) {
        if (value == null || value.isBlank() || "name".equals(value) || "price_asc".equals(value)
                || "price_desc".equals(value)) return value == null || value.isBlank() ? "name" : value;
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不支持的模型排序方式", null);
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "筛选参数过长", null);
        }
        return normalized;
    }
}
