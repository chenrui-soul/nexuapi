package com.nexusapi.server.modules.gateway.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.config.PricingProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.model.pricing.model.BillingType;
import com.nexusapi.server.modules.model.pricing.time.model.TimePricingSnapshot;
import com.nexusapi.server.modules.routing.model.RuntimeContextTierRow;
import com.nexusapi.server.modules.routing.model.RuntimeGroupRow;
import com.nexusapi.server.modules.routing.model.RuntimeModelRow;
import com.nexusapi.server.modules.routing.model.RuntimePricingRuleRow;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 使用 BigDecimal 计算预冻结和实际费用，禁止用 double 处理资金。 */
@Component
public class GatewayPricingService {
    private static final Logger log = LoggerFactory.getLogger(GatewayPricingService.class);
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    private static final BigDecimal CACHE_WRITE_5M_DEFAULT = new BigDecimal("1.25");
    private static final BigDecimal CACHE_WRITE_1H_DEFAULT = new BigDecimal("2");
    private static final BigDecimal ZERO = new BigDecimal("0.00000000");
    private static final BigDecimal ZERO_V2 = new BigDecimal("0.000000000000");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final PricingProperties properties;
    private final ObjectMapper objectMapper;

    public GatewayPricingService(PricingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public BigDecimal estimateReservation(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            long estimatedInputTokens,
            long maxOutputTokens
    ) {
        BigDecimal raw = tokenCost(estimatedInputTokens, model.getInputPrice())
                .add(tokenCost(maxOutputTokens, model.getOutputPrice()))
                .multiply(group.getPriceMultiplier());
        if (raw.signum() == 0) {
            return ZERO;
        }
        return raw.setScale(8, RoundingMode.CEILING);
    }

    public BigDecimal actualCharge(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            OpenAiUsage usage
    ) {
        long uncachedInput = Math.max(0L, usage.inputTokens() - usage.cachedInputTokens());
        BigDecimal raw = tokenCost(uncachedInput, model.getInputPrice())
                .add(tokenCost(usage.cachedInputTokens(), model.getCachedInputPrice()))
                .add(tokenCost(usage.outputTokens(), model.getOutputPrice()))
                .multiply(group.getPriceMultiplier());
        return raw.setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * 根据灰度模式返回预冻结金额。shadow 仍使用旧金额，V2 只在模型已发布价格版本时生效。
     */
    public ChargeDecision reservationDecision(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            long estimatedInputTokens,
            long maxOutputTokens,
            Map<String, String> parameters
    ) {
        return reservationDecision(
                model, group, rules, tiers, estimatedInputTokens, maxOutputTokens,
                parameters, TimePricingSnapshot.none(null)
        );
    }

    /** 预冻结显式接收请求级时段倍率快照，禁止结算阶段重新查询当前规则。 */
    public ChargeDecision reservationDecision(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            long estimatedInputTokens,
            long maxOutputTokens,
            Map<String, String> parameters,
            TimePricingSnapshot timePricing
    ) {
        BigDecimal legacy = estimateReservation(model, group, estimatedInputTokens, maxOutputTokens);
        PricingUsage usage = new PricingUsage(
                estimatedInputTokens, maxOutputTokens, 0, 0, 0, 0, 0,
                1, 0, 0, 1
        );
        return decide(model, group, rules, tiers, usage, parameters, legacy,
                RoundingMode.CEILING, null, timePricing);
    }

    /** 计算实际结算决策，并在 shadow 差异超过阈值时只记录脱敏数值。 */
    public ChargeDecision actualDecision(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            OpenAiUsage usage,
            Map<String, String> parameters,
            String requestId
    ) {
        return actualDecision(
                model, group, rules, tiers, usage, parameters, requestId, TimePricingSnapshot.none(null)
        );
    }

    /** 实际结算复用预冻结阶段的时段倍率快照。 */
    public ChargeDecision actualDecision(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            OpenAiUsage usage,
            Map<String, String> parameters,
            String requestId,
            TimePricingSnapshot timePricing
    ) {
        BigDecimal legacy = actualCharge(model, group, usage);
        PricingUsage pricingUsage = new PricingUsage(
                usage.inputTokens(), usage.outputTokens(), usage.cachedInputTokens(),
                usage.cacheWrite5mInputTokens(), usage.cacheWrite1hInputTokens(),
                usage.audioInputTokens(), usage.audioOutputTokens(),
                1, 0, 0, 1
        );
        return decide(model, group, rules, tiers, pricingUsage, parameters, legacy,
                RoundingMode.HALF_UP, requestId, timePricing);
    }

    /** 图片、音视频等非 Token 能力使用统一 V2 用量模型执行预冻结。 */
    public ChargeDecision mediaReservationDecision(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            PricingUsage usage,
            Map<String, String> parameters
    ) {
        return mediaReservationDecision(
                model, group, rules, tiers, usage, parameters, TimePricingSnapshot.none(null)
        );
    }

    /** 非 Token 能力预冻结同样使用请求级时段倍率快照。 */
    public ChargeDecision mediaReservationDecision(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            PricingUsage usage,
            Map<String, String> parameters,
            TimePricingSnapshot timePricing
    ) {
        BigDecimal legacy = legacyMediaCharge(model, group, usage);
        return decide(model, group, rules, tiers, usage, parameters, legacy,
                RoundingMode.CEILING, null, timePricing);
    }

    /** 图片、音视频等非 Token 能力按实际完成的业务用量结算。 */
    public ChargeDecision mediaActualDecision(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            PricingUsage usage,
            Map<String, String> parameters,
            String requestId
    ) {
        return mediaActualDecision(
                model, group, rules, tiers, usage, parameters, requestId, TimePricingSnapshot.none(null)
        );
    }

    /** 非 Token 能力实际结算复用请求开始时的时段倍率。 */
    public ChargeDecision mediaActualDecision(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            PricingUsage usage,
            Map<String, String> parameters,
            String requestId,
            TimePricingSnapshot timePricing
    ) {
        BigDecimal legacy = legacyMediaCharge(model, group, usage);
        return decide(model, group, rules, tiers, usage, parameters, legacy,
                RoundingMode.HALF_UP, requestId, timePricing);
    }

    /**
     * 通用 V2 计算入口。图片传 quantity，音视频传 durationMillis，TTS 传 characterCount。
     * 真实媒体 Gateway 尚未接入时也可由单元测试和管理端预览复用该纯计算逻辑。
     */
    public V2Calculation calculateV2(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            PricingUsage usage,
            Map<String, String> parameters,
            RoundingMode roundingMode
    ) {
        return calculateV2(
                model, group, rules, tiers, usage, parameters,
                TimePricingSnapshot.none(null), roundingMode
        );
    }

    /** 完整售价 = 模型基础用量积分 × 时段倍率 × 服务分组倍率。 */
    public V2Calculation calculateV2(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            PricingUsage usage,
            Map<String, String> parameters,
            TimePricingSnapshot timePricing,
            RoundingMode roundingMode
    ) {
        MatchedRule matchedRule = matchRule(model, rules, parameters);
        BillingType billingType = matchedRule.billingType();
        BigDecimal baseUnitPrice = model.getUnitPrice() == null ? ZERO_V2 : model.getUnitPrice();
        BigDecimal effectiveUnitPrice = (matchedRule.unitPrice() == null ? baseUnitPrice : matchedRule.unitPrice())
                .multiply(matchedRule.priceMultiplier());

        RuntimeContextTierRow matchedTier = null;
        long inputRatio = model.getInputTokenRatio();
        long outputRatio = model.getOutputTokenRatio();
        long audioInputRatio = model.getAudioInputTokenRatio();
        long audioOutputRatio = model.getAudioOutputTokenRatio();
        long cachedRatio = model.getCachedInputTokenRatio();
        long cacheWrite5mRatio = model.getCacheWrite5mTokenRatio();
        long cacheWrite1hRatio = model.getCacheWrite1hTokenRatio();
        if (billingType == BillingType.TOKEN && !tiers.isEmpty()) {
            matchedTier = matchingTier(tiers, usage.inputTokens());
            if (model.getContextTierMode() == 2) {
                long blendedInput = blendedTierRatio(tiers, usage.inputTokens(), true);
                long blendedCached = blendedTierRatio(tiers, usage.inputTokens(), false);
                inputRatio = blendedInput;
                cachedRatio = blendedCached;
                if (matchedTier != null) outputRatio = matchedTier.outputRatio();
            } else if (matchedTier != null) {
                // 分档倍率是上游和管理端共同使用的绝对万分位值，不再与版本级倍率二次相乘。
                inputRatio = matchedTier.inputRatio();
                outputRatio = matchedTier.outputRatio();
                cachedRatio = matchedTier.cachedInputRatio();
            }
            if (matchedTier != null) {
                cacheWrite5mRatio = matchedTier.cacheWrite5mRatio();
                cacheWrite1hRatio = matchedTier.cacheWrite1hRatio();
            }
        }
        cacheWrite5mRatio = resolvedCacheWriteRatio(cacheWrite5mRatio, inputRatio, CACHE_WRITE_5M_DEFAULT);
        cacheWrite1hRatio = resolvedCacheWriteRatio(cacheWrite1hRatio, inputRatio, CACHE_WRITE_1H_DEFAULT);

        BigDecimal raw = switch (billingType) {
            case REQUEST -> effectiveUnitPrice.multiply(BigDecimal.valueOf(Math.max(0, usage.requestCount())));
            case QUANTITY -> effectiveUnitPrice.multiply(BigDecimal.valueOf(Math.max(0, usage.quantity())));
            case VIDEO_SECOND, AUDIO_SECOND -> effectiveUnitPrice
                    .multiply(BigDecimal.valueOf(Math.max(0, usage.durationMillis())))
                    .divide(BigDecimal.valueOf(1000), 18, RoundingMode.HALF_UP);
            case CHARACTER -> effectiveUnitPrice
                    .multiply(BigDecimal.valueOf(Math.max(0, usage.characterCount())))
                    .divide(ONE_MILLION, 18, RoundingMode.HALF_UP);
            case TOKEN -> tokenV2Cost(
                    usage, effectiveUnitPrice, inputRatio, outputRatio, cachedRatio,
                    cacheWrite5mRatio, cacheWrite1hRatio, audioInputRatio, audioOutputRatio
            );
        };
        BigDecimal baseUsageAmount = raw.setScale(12, RoundingMode.HALF_UP);
        BigDecimal amount = raw
                .multiply(timePricing.multiplier())
                .multiply(group.getPriceMultiplier())
                .setScale(12, roundingMode);
        return new V2Calculation(
                amount,
                baseUsageAmount,
                billingType.code(),
                baseUnitPrice.setScale(12, RoundingMode.UNNECESSARY),
                effectiveUnitPrice.setScale(12, RoundingMode.HALF_UP),
                inputRatio,
                outputRatio,
                cachedRatio,
                cacheWrite5mRatio,
                cacheWrite1hRatio,
                audioInputRatio,
                audioOutputRatio,
                matchedRule.id(),
                matchedTier == null ? null : matchedTier.id()
        );
    }

    private ChargeDecision decide(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            List<RuntimePricingRuleRow> rules,
            List<RuntimeContextTierRow> tiers,
            PricingUsage usage,
            Map<String, String> parameters,
            BigDecimal legacy,
            RoundingMode roundingMode,
            String requestId,
            TimePricingSnapshot timePricing
    ) {
        boolean configured = model.getActivePricingVersionId() != null;
        BigDecimal legacyWithTime = legacy.multiply(timePricing.multiplier()).setScale(12, roundingMode);
        BigDecimal legacyBaseUsage = group.getPriceMultiplier().signum() == 0
                ? ZERO_V2
                : legacy.divide(group.getPriceMultiplier(), 24, RoundingMode.HALF_UP)
                        .setScale(12, RoundingMode.HALF_UP);
        if (properties.mode() == PricingProperties.Mode.V1 || !configured) {
            String mode = configured ? "v1" : (properties.mode() == PricingProperties.Mode.V1 ? "v1" : "v1_fallback");
            return legacyDecision(model, legacyWithTime, mode, timePricing, legacyBaseUsage);
        }
        V2Calculation v2 = calculateV2(model, group, rules, tiers, usage, parameters, timePricing, roundingMode);
        if (properties.mode() == PricingProperties.Mode.SHADOW) {
            BigDecimal delta = legacyWithTime.subtract(v2.amount()).abs();
            if (delta.compareTo(properties.shadowWarningThreshold()) > 0) {
                log.warn("Pricing shadow mismatch, requestId={}, modelId={}, legacy={}, v2={}, delta={}",
                        requestId, model.getId(), legacyWithTime, v2.amount(), delta);
            }
            return new ChargeDecision(
                    legacyWithTime, legacyWithTime, v2.amount(), "shadow", v2.billingType(),
                    model.getActivePricingVersionId(), v2.matchedRuleId(), v2.contextTierId(),
                    v2.baseUnitPrice(), v2.effectiveUnitPrice(), v2.inputTokenRatio(),
                    v2.outputTokenRatio(), v2.cachedInputTokenRatio(),
                    v2.cacheWrite5mTokenRatio(), v2.cacheWrite1hTokenRatio(),
                    v2.audioInputTokenRatio(), v2.audioOutputTokenRatio(),
                    timePricing.ruleId(), timePricing.ruleName(), timePricing.multiplier(),
                    timePricing.pricingTime(), timePricing.timezone(), v2.baseUsageAmount()
            );
        }
        return new ChargeDecision(
                v2.amount(), legacyWithTime, v2.amount(), "v2", v2.billingType(),
                model.getActivePricingVersionId(), v2.matchedRuleId(), v2.contextTierId(),
                v2.baseUnitPrice(), v2.effectiveUnitPrice(), v2.inputTokenRatio(),
                v2.outputTokenRatio(), v2.cachedInputTokenRatio(),
                v2.cacheWrite5mTokenRatio(), v2.cacheWrite1hTokenRatio(),
                v2.audioInputTokenRatio(), v2.audioOutputTokenRatio(),
                timePricing.ruleId(), timePricing.ruleName(), timePricing.multiplier(),
                timePricing.pricingTime(), timePricing.timezone(), v2.baseUsageAmount()
        );
    }

    private ChargeDecision legacyDecision(
            RuntimeModelRow model,
            BigDecimal legacy,
            String mode,
            TimePricingSnapshot timePricing,
            BigDecimal baseUsageAmount
    ) {
        BigDecimal inputPrice = model.getInputPrice() == null ? ZERO : model.getInputPrice();
        return new ChargeDecision(
                legacy,
                legacy.setScale(12),
                null,
                mode,
                BillingType.TOKEN.code(),
                null,
                null,
                null,
                inputPrice.setScale(12, RoundingMode.HALF_UP),
                inputPrice.setScale(12, RoundingMode.HALF_UP),
                10000,
                10000,
                10000,
                0,
                0,
                0,
                0,
                timePricing.ruleId(),
                timePricing.ruleName(),
                timePricing.multiplier(),
                timePricing.pricingTime(),
                timePricing.timezone(),
                baseUsageAmount
        );
    }

    private MatchedRule matchRule(
            RuntimeModelRow model,
            List<RuntimePricingRuleRow> rules,
            Map<String, String> parameters
    ) {
        Map<String, String> normalized = normalizeParameters(parameters);
        for (RuntimePricingRuleRow rule : rules.stream()
                .sorted(Comparator.comparingInt(RuntimePricingRuleRow::priority).thenComparing(RuntimePricingRuleRow::id))
                .toList()) {
            Map<String, String> conditions = readConditions(rule.matchConditionsJson());
            boolean matches = conditions.entrySet().stream().allMatch(entry -> {
                String actual = normalized.get(entry.getKey().toLowerCase(Locale.ROOT));
                return actual != null && actual.equalsIgnoreCase(entry.getValue());
            });
            if (matches) {
                BillingType type = rule.billingType() == null
                        ? BillingType.fromCode(model.getBillingType())
                        : BillingType.fromCode(rule.billingType());
                return new MatchedRule(
                        rule.id(), type, rule.unitPrice(), rule.priceMultiplier()
                );
            }
        }
        if (!rules.isEmpty() && "reject".equals(model.getPricingUnmatchedBehavior())) {
            throw new BusinessException(ErrorCode.PRICE_RULE_NOT_FOUND);
        }
        return new MatchedRule(null, BillingType.fromCode(model.getBillingType()), null, BigDecimal.ONE);
    }

    private RuntimeContextTierRow matchingTier(List<RuntimeContextTierRow> tiers, long inputTokens) {
        return tiers.stream()
                .filter(tier -> inputTokens >= tier.minInputTokens()
                        && (tier.maxInputTokens() == null || inputTokens < tier.maxInputTokens()))
                .min(Comparator.comparingInt(RuntimeContextTierRow::priority).thenComparing(RuntimeContextTierRow::id))
                .orElse(null);
    }

    /** 分段模式将各档倍率按输入 Token 数加权，未覆盖区间按 1 倍处理。 */
    private long blendedTierRatio(List<RuntimeContextTierRow> tiers, long inputTokens, boolean input) {
        if (inputTokens <= 0) return 10000;
        BigDecimal weighted = BigDecimal.ZERO;
        long covered = 0;
        for (RuntimeContextTierRow tier : tiers) {
            long upper = tier.maxInputTokens() == null ? inputTokens : Math.min(inputTokens, tier.maxInputTokens());
            long lower = Math.min(inputTokens, tier.minInputTokens());
            long count = Math.max(0, upper - lower);
            if (count == 0) continue;
            long ratio = input ? tier.inputRatio() : tier.cachedInputRatio();
            weighted = weighted.add(BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(ratio)));
            covered = Math.addExact(covered, count);
        }
        if (covered < inputTokens) {
            weighted = weighted.add(BigDecimal.valueOf(inputTokens - covered).multiply(TEN_THOUSAND));
        }
        return weighted.divide(BigDecimal.valueOf(inputTokens), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private BigDecimal tokenV2Cost(
            PricingUsage usage,
            BigDecimal unitPrice,
            long inputRatio,
            long outputRatio,
            long cachedRatio,
            long cacheWrite5mRatio,
            long cacheWrite1hRatio,
            long audioInputRatio,
            long audioOutputRatio
    ) {
        OpenAiUsage normalized = new OpenAiUsage(
                usage.inputTokens(), usage.outputTokens(), usage.cachedInputTokens(),
                usage.cacheWrite5mInputTokens(), usage.cacheWrite1hInputTokens(),
                usage.audioInputTokens(), usage.audioOutputTokens()
        );
        // 每类 Token 只进入一个费用项：普通输入/输出先扣除缓存和音频子集，避免重复计费。
        BigDecimal weightedTokens = weighted(normalized.ordinaryInputTokens(), inputRatio)
                .add(weighted(normalized.ordinaryOutputTokens(), outputRatio))
                .add(weighted(normalized.cachedInputTokens(), cachedRatio))
                .add(weighted(normalized.cacheWrite5mInputTokens(), cacheWrite5mRatio))
                .add(weighted(normalized.cacheWrite1hInputTokens(), cacheWrite1hRatio))
                .add(weighted(normalized.audioInputTokens(), audioInputRatio))
                .add(weighted(normalized.audioOutputTokens(), audioOutputRatio));
        return unitPrice.multiply(weightedTokens)
                .divide(ONE_MILLION, 24, RoundingMode.HALF_UP)
                .divide(TEN_THOUSAND, 24, RoundingMode.HALF_UP);
    }

    private BigDecimal weighted(long tokens, long ratio) {
        return BigDecimal.valueOf(Math.max(0L, tokens)).multiply(BigDecimal.valueOf(Math.max(0L, ratio)));
    }

    /** 版本或分档填 0 时按输入倍率推导默认缓存写入倍率，并保持万分位整数语义。 */
    private long resolvedCacheWriteRatio(long configured, long inputRatio, BigDecimal defaultMultiplier) {
        if (configured > 0) return configured;
        return BigDecimal.valueOf(Math.max(0L, inputRatio))
                .multiply(defaultMultiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private Map<String, String> normalizeParameters(Map<String, String> parameters) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (parameters == null) return normalized;
        parameters.forEach((key, value) -> {
            if (key != null && value != null) normalized.put(key.strip().toLowerCase(Locale.ROOT), value.strip());
        });
        return normalized;
    }

    private Map<String, String> readConditions(String json) {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid pricing rule conditions", exception);
        }
    }

    /**
     * 计算服务分组对外展示的每百万 Token 最终单价。
     * 模型市场和真实网关结算必须使用同一倍率规则，避免展示价格与扣费价格漂移。
     */
    public BigDecimal applyGroupMultiplier(BigDecimal basePricePerMillion, BigDecimal priceMultiplier) {
        return basePricePerMillion.multiply(priceMultiplier).stripTrailingZeros();
    }

    /**
     * 按最终实际 Token 计算供应商成本，并返回本次调用采用的三种成本单价快照。
     * P0 不做汇率换算，管理员必须保证售价和供应商成本使用同一核算口径。
     */
    public SupplierCostSnapshot supplierCost(RuntimeRouteRow route, OpenAiUsage usage) {
        long uncachedInput = Math.max(0L, usage.inputTokens() - usage.cachedInputTokens());
        BigDecimal raw = tokenCost(uncachedInput, route.getSupplierInputPrice())
                .add(tokenCost(usage.cachedInputTokens(), route.getSupplierCachedInputPrice()))
                .add(tokenCost(usage.outputTokens(), route.getSupplierOutputPrice()));
        return new SupplierCostSnapshot(
                raw.setScale(12, RoundingMode.HALF_UP),
                route.getSupplierCostCurrency(),
                route.getSupplierInputPrice(),
                route.getSupplierCachedInputPrice(),
                route.getSupplierOutputPrice()
        );
    }

    private BigDecimal tokenCost(long tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens).multiply(pricePerMillion).divide(ONE_MILLION, 18, RoundingMode.HALF_UP);
    }

    /** 兼容 V1/shadow 模式的媒体基准价；正式 V2 仍以已发布价格规则为准。 */
    private BigDecimal legacyMediaCharge(
            RuntimeModelRow model,
            RuntimeGroupRow group,
            PricingUsage usage
    ) {
        BillingType billingType = BillingType.fromCode(model.getBillingType());
        BigDecimal unitPrice = model.getUnitPrice() == null ? ZERO_V2 : model.getUnitPrice();
        BigDecimal raw = switch (billingType) {
            case REQUEST -> unitPrice.multiply(BigDecimal.valueOf(Math.max(0L, usage.requestCount())));
            case QUANTITY -> unitPrice.multiply(BigDecimal.valueOf(Math.max(0L, usage.quantity())));
            case VIDEO_SECOND, AUDIO_SECOND -> unitPrice
                    .multiply(BigDecimal.valueOf(Math.max(0L, usage.durationMillis())))
                    .divide(BigDecimal.valueOf(1000L), 18, RoundingMode.HALF_UP);
            case CHARACTER -> unitPrice
                    .multiply(BigDecimal.valueOf(Math.max(0L, usage.characterCount())))
                    .divide(ONE_MILLION, 18, RoundingMode.HALF_UP);
            case TOKEN -> actualCharge(model, group, new OpenAiUsage(
                    usage.inputTokens(), usage.outputTokens(), usage.cachedInputTokens(),
                    usage.cacheWrite5mInputTokens(), usage.cacheWrite1hInputTokens(),
                    usage.audioInputTokens(), usage.audioOutputTokens()
            ));
        };
        if (billingType == BillingType.TOKEN) return raw;
        return raw.multiply(group.getPriceMultiplier()).setScale(12, RoundingMode.HALF_UP);
    }

    public record PricingUsage(
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long cacheWrite5mInputTokens,
            long cacheWrite1hInputTokens,
            long audioInputTokens,
            long audioOutputTokens,
            long quantity,
            long durationMillis,
            long characterCount,
            long requestCount
    ) {
        /** 兼容非媒体计费测试和旧调用方；数量、时长、字符数、请求数保持原有位置。 */
        public PricingUsage(long inputTokens, long outputTokens, long cachedInputTokens,
                            long quantity, long durationMillis, long characterCount, long requestCount) {
            this(inputTokens, outputTokens, cachedInputTokens, 0, 0, 0, 0,
                    quantity, durationMillis, characterCount, requestCount);
        }
    }

    public record ChargeDecision(
            BigDecimal settlementAmount,
            BigDecimal legacyAmount,
            BigDecimal v2Amount,
            String engineMode,
            int billingType,
            UUID pricingVersionId,
            UUID matchedRuleId,
            UUID contextTierId,
            BigDecimal baseUnitPrice,
            BigDecimal effectiveUnitPrice,
            long inputTokenRatio,
            long outputTokenRatio,
            long cachedInputTokenRatio,
            long cacheWrite5mTokenRatio,
            long cacheWrite1hTokenRatio,
            long audioInputTokenRatio,
            long audioOutputTokenRatio,
            UUID timeRuleId,
            String timeRuleName,
            BigDecimal timeMultiplier,
            java.time.Instant pricingTime,
            String pricingTimezone,
            BigDecimal baseUsageAmount
    ) {
    }

    public record V2Calculation(
            BigDecimal amount,
            BigDecimal baseUsageAmount,
            int billingType,
            BigDecimal baseUnitPrice,
            BigDecimal effectiveUnitPrice,
            long inputTokenRatio,
            long outputTokenRatio,
            long cachedInputTokenRatio,
            long cacheWrite5mTokenRatio,
            long cacheWrite1hTokenRatio,
            long audioInputTokenRatio,
            long audioOutputTokenRatio,
            UUID matchedRuleId,
            UUID contextTierId
    ) {
    }

    private record MatchedRule(
            UUID id,
            BillingType billingType,
            BigDecimal unitPrice,
            BigDecimal priceMultiplier
    ) {
    }

    public record SupplierCostSnapshot(
            BigDecimal amount,
            String currency,
            BigDecimal inputPrice,
            BigDecimal cachedInputPrice,
            BigDecimal outputPrice
    ) {
    }
}
