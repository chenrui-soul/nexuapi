package com.nexusapi.server.modules.routing.service;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.gateway.security.GatewayCallerPrincipal;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import com.nexusapi.server.modules.routing.mapper.GatewayRoutingMapper;
import com.nexusapi.server.modules.routing.model.RuntimeGroupRow;
import com.nexusapi.server.modules.routing.model.RuntimeContextTierRow;
import com.nexusapi.server.modules.routing.model.RuntimeModelRow;
import com.nexusapi.server.modules.routing.model.RuntimePricingRuleRow;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import com.nexusapi.server.modules.model.pricing.model.BillingType;
import com.nexusapi.server.modules.model.pricing.time.model.TimePricingSnapshot;
import com.nexusapi.server.modules.model.pricing.time.service.TimePricingRuntimeService;
import com.nexusapi.server.modules.subscription.service.SubscriptionAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.CHAT_COMPLETIONS;
import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.IMAGE_GENERATIONS;

/** 负责模型权限、分组选择和“优先级 + 权重”运行时路由。 */
@Service
public class GatewayRoutingService {
    private final GatewayRoutingMapper mapper;
    private final TimePricingRuntimeService timePricingRuntimeService;
    private final SubscriptionAccessService subscriptionAccessService;

    public GatewayRoutingService(
            GatewayRoutingMapper mapper,
            TimePricingRuntimeService timePricingRuntimeService,
            SubscriptionAccessService subscriptionAccessService
    ) {
        this.mapper = mapper;
        this.timePricingRuntimeService = timePricingRuntimeService;
        this.subscriptionAccessService = subscriptionAccessService;
    }

    /** 返回当前 API 令牌真正可调用且至少存在一条健康候选路由的模型。 */
    @Transactional(readOnly = true)
    public List<RuntimeModelRow> listAvailableModels(GatewayCallerPrincipal principal) {
        UUID groupId = boundServiceGroupId(principal);
        SubscriptionAccessService.AccessSnapshot access = subscriptionAccessService.snapshot(principal.userId());
        subscriptionAccessService.assertGroupAllowed(access, groupId);
        List<UUID> allowedModelIds = access.effectiveModelIds(principal.allowedModelIds());
        if (access.restricted() && allowedModelIds.isEmpty()) return List.of();
        return mapper.findAvailableModels(allowedModelIds, List.of(groupId));
    }

    /** Chat 兼容入口用它识别图片模型；权限和可用渠道仍由 resolveImage 统一校验。 */
    @Transactional(readOnly = true)
    public boolean isImageModel(String publicModel) {
        RuntimeModelRow model = mapper.findModelByPublicName(normalizeModelName(publicModel));
        return model != null && "image".equalsIgnoreCase(model.getCapabilityType());
    }

    /**
     * 解析一次调用的模型、计费分组和候选渠道。
     *
     * <p>数据库负责状态 fail-closed，Service 负责 API 令牌白名单和同优先级权重顺序。</p>
     */
    @Transactional(readOnly = true)
    public RoutePlan resolve(
            GatewayCallerPrincipal principal,
            String publicModel,
            String groupSelector,
            boolean streaming
    ) {
        String modelName = normalizeModelName(publicModel);
        RuntimeModelRow model = mapper.findModelByPublicName(modelName);
        if (model == null) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE);
        }
        if (!principal.allowedModelIds().isEmpty() && !principal.allowedModelIds().contains(model.getId())) {
            throw new BusinessException(ErrorCode.API_KEY_SCOPE_DENIED, "API 令牌不允许调用该模型", null);
        }
        SubscriptionAccessService.AccessSnapshot access = subscriptionAccessService.snapshot(principal.userId());
        subscriptionAccessService.assertModelAllowed(access, model.getId());
        if (streaming && !model.isSupportsStreaming()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "该模型不支持流式响应", null);
        }
        if (model.getActivePricingVersionId() == null && !"million_tokens".equals(model.getPriceUnit())) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "模型价格单位暂不受 Gateway 支持", null);
        }
        if (model.getActivePricingVersionId() != null
                && model.getBillingType() != BillingType.TOKEN.code()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "Chat Completions 只支持按 Token 计费模型", null);
        }

        RuntimeGroupRow group = resolveGroup(principal, access, groupSelector);
        List<RuntimeRouteRow> candidates = weightedOrder(
                findCandidates(group.getId(), model.getId(), CHAT_COMPLETIONS)
        );
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "当前模型没有可用上游渠道", null);
        }
        List<RuntimePricingRuleRow> pricingRules = model.getActivePricingVersionId() == null
                ? List.of() : mapper.findPricingRules(model.getActivePricingVersionId());
        List<RuntimeContextTierRow> contextTiers = model.getActivePricingVersionId() == null
                ? List.of() : mapper.findContextTiers(model.getActivePricingVersionId());
        TimePricingSnapshot timePricing = timePricingRuntimeService.resolve(model.getId(), Instant.now());
        return routePlan(model, group, candidates, pricingRules, contextTiers, timePricing, access);
    }

    /** 为同步图片生成解析路由，只接受图片能力及按请求数/图片数量计费的已发布价格。 */
    @Transactional(readOnly = true)
    public RoutePlan resolveImage(
            GatewayCallerPrincipal principal,
            String publicModel,
            String groupSelector
    ) {
        String modelName = normalizeModelName(publicModel);
        RuntimeModelRow model = mapper.findModelByPublicName(modelName);
        if (model == null) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE);
        }
        if (!principal.allowedModelIds().isEmpty() && !principal.allowedModelIds().contains(model.getId())) {
            throw new BusinessException(ErrorCode.API_KEY_SCOPE_DENIED, "API 令牌不允许调用该模型", null);
        }
        SubscriptionAccessService.AccessSnapshot access = subscriptionAccessService.snapshot(principal.userId());
        subscriptionAccessService.assertModelAllowed(access, model.getId());
        if (!"image".equalsIgnoreCase(model.getCapabilityType())) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "该模型不支持图片生成能力", null);
        }
        if (model.getActivePricingVersionId() == null) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "图片模型尚未发布计费版本", null);
        }
        BillingType billingType = BillingType.fromCode(model.getBillingType());
        if (!billingType.supportsCapability("image")) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "图片模型计费类型不受支持", null);
        }

        RuntimeGroupRow group = resolveGroup(principal, access, groupSelector);
        List<RuntimeRouteRow> candidates = weightedOrder(
                findCandidates(group.getId(), model.getId(), IMAGE_GENERATIONS)
        );
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "当前模型没有可用上游渠道", null);
        }
        return routePlan(
                model,
                group,
                candidates,
                mapper.findPricingRules(model.getActivePricingVersionId()),
                mapper.findContextTiers(model.getActivePricingVersionId()),
                timePricingRuntimeService.resolve(model.getId(), Instant.now()),
                access
        );
    }

    /**
     * 按程序固定的对外能力入口解析模型、分组、价格版本和候选渠道。
     *
     * <p>计费类型由调用入口决定如何解释；本方法只校验模型权限、能力和已发布价格。</p>
     */
    @Transactional(readOnly = true)
    public RoutePlan resolveCapability(
            GatewayCallerPrincipal principal,
            String publicModel,
            String groupSelector,
            PublicGatewayOperation operation
    ) {
        String modelName = normalizeModelName(publicModel);
        RuntimeModelRow model = mapper.findModelByPublicName(modelName);
        if (model == null) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE);
        }
        if (!principal.allowedModelIds().isEmpty() && !principal.allowedModelIds().contains(model.getId())) {
            throw new BusinessException(ErrorCode.API_KEY_SCOPE_DENIED, "API 令牌不允许调用该模型", null);
        }
        SubscriptionAccessService.AccessSnapshot access = subscriptionAccessService.snapshot(principal.userId());
        subscriptionAccessService.assertModelAllowed(access, model.getId());
        String requiredCapability = operation.capabilityType();
        if (!requiredCapability.equalsIgnoreCase(model.getCapabilityType())
                && !"multimodal".equalsIgnoreCase(model.getCapabilityType())) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "模型能力与当前接口不匹配", null);
        }
        if (model.getActivePricingVersionId() == null
                && !("text".equalsIgnoreCase(requiredCapability)
                && "million_tokens".equals(model.getPriceUnit()))) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "模型尚未发布计费版本", null);
        }
        RuntimeGroupRow group = resolveGroup(principal, access, groupSelector);
        List<RuntimeRouteRow> candidates = weightedOrder(
                findCandidates(group.getId(), model.getId(), operation)
        );
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "当前模型没有匹配该能力入口的可用上游渠道", null);
        }
        return routePlan(
                model,
                group,
                candidates,
                model.getActivePricingVersionId() == null ? List.of()
                        : mapper.findPricingRules(model.getActivePricingVersionId()),
                model.getActivePricingVersionId() == null ? List.of()
                        : mapper.findContextTiers(model.getActivePricingVersionId()),
                timePricingRuntimeService.resolve(model.getId(), Instant.now()),
                access
        );
    }

    /** 将活动订阅的并发快照随路由计划传递到 Gateway，避免调用层再次查询并产生口径漂移。 */
    private RoutePlan routePlan(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimeRouteRow> candidates,
            List<RuntimePricingRuleRow> pricingRules,
            List<RuntimeContextTierRow> contextTiers,
            TimePricingSnapshot timePricing,
            SubscriptionAccessService.AccessSnapshot access
    ) {
        return new RoutePlan(model, group, candidates, pricingRules, contextTiers, timePricing,
                access.subscriptionId(), access.subscriptionConcurrencyLimit());
    }

    /**
     * 真实候选只由能力、HTTP 方法和供应商维护的完整上游地址决定。
     * 接口文档及模型文档关联均不会参与此查询。
     */
    private List<RuntimeRouteRow> findCandidates(
            UUID groupId,
            UUID modelId,
            PublicGatewayOperation operation
    ) {
        return mapper.findCandidates(
                groupId,
                modelId,
                operation.operationCode()
        );
    }

    private RuntimeGroupRow resolveGroup(
            GatewayCallerPrincipal principal,
            SubscriptionAccessService.AccessSnapshot access,
            String rawSelector
    ) {
        RuntimeGroupRow group = mapper.findGroupById(boundServiceGroupId(principal));
        if (group == null) {
            throw new BusinessException(ErrorCode.GROUP_NOT_AVAILABLE);
        }
        subscriptionAccessService.assertGroupAllowed(access, group.getId());
        if (rawSelector != null && !rawSelector.isBlank()) {
            String selector = rawSelector.strip();
            if (selector.length() > 64 || selector.codePoints().anyMatch(Character::isISOControl)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "服务分组格式无效", null);
            }
            // 兼容旧请求头，但只允许重复声明 Key 已绑定的同一分组，禁止按请求跨组切换。
            if (!selector.equalsIgnoreCase(group.getCode()) && !selector.equals(group.getId().toString())) {
                throw new BusinessException(ErrorCode.API_KEY_SCOPE_DENIED, "API 令牌已绑定其他服务分组", null);
            }
        }
        return group;
    }

    private UUID boundServiceGroupId(GatewayCallerPrincipal principal) {
        UUID groupId = principal.serviceGroupId() == null ? principal.defaultGroupId() : principal.serviceGroupId();
        if (groupId == null) {
            throw new BusinessException(ErrorCode.GROUP_NOT_AVAILABLE, "API 令牌未绑定服务分组", null);
        }
        return groupId;
    }

    /**
     * 不同优先级严格按数值从小到大；同优先级使用指数竞赛实现无放回加权排序。
     * 权重越大，随机得分越小，越可能排在前面，同时后续失败仍可切换到其他候选。
     */
    private List<RuntimeRouteRow> weightedOrder(List<RuntimeRouteRow> source) {
        List<WeightedRoute> weighted = new ArrayList<>(source.size());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (RuntimeRouteRow route : source) {
            double uniform = Math.max(random.nextDouble(), Double.MIN_VALUE);
            double score = -Math.log(uniform) / Math.max(1L, route.getEffectiveWeight());
            weighted.add(new WeightedRoute(route, score));
        }
        weighted.sort(Comparator
                .comparingLong((WeightedRoute value) -> value.route().getEffectivePriority())
                .thenComparingDouble(WeightedRoute::score));
        return weighted.stream().map(WeightedRoute::route).toList();
    }

    private String normalizeModelName(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model 不能为空", null);
        }
        String normalized = value.strip();
        if (normalized.length() > 160 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model 格式无效", null);
        }
        return normalized;
    }

    public record RoutePlan(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimeRouteRow> candidates,
            List<RuntimePricingRuleRow> pricingRules,
            List<RuntimeContextTierRow> contextTiers,
            TimePricingSnapshot timePricing,
            UUID subscriptionId,
            Integer subscriptionConcurrencyLimit
    ) {
        public RoutePlan {
            candidates = List.copyOf(candidates);
            pricingRules = List.copyOf(pricingRules);
            contextTiers = List.copyOf(contextTiers);
            if (timePricing == null) {
                throw new IllegalArgumentException("timePricing must not be null");
            }
        }
    }

    private record WeightedRoute(RuntimeRouteRow route, double score) {
    }
}
