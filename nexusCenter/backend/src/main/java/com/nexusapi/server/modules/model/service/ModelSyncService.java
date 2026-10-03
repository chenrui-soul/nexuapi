package com.nexusapi.server.modules.model.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.common.config.ModelSyncProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.model.dto.ModelSyncSettingsRequest;
import com.nexusapi.server.modules.model.entity.ModelSyncRunRow;
import com.nexusapi.server.modules.model.entity.ModelSyncSettingsRow;
import com.nexusapi.server.modules.model.entity.ModelSyncTargetRow;
import com.nexusapi.server.modules.model.entity.RoutingGroupSyncTargetRow;
import com.nexusapi.server.modules.model.entity.SyncedGroupModelRow;
import com.nexusapi.server.modules.model.entity.SyncedModelRow;
import com.nexusapi.server.modules.model.entity.SyncedRoutingGroupRow;
import com.nexusapi.server.modules.model.mapper.ModelSyncMapper;
import com.nexusapi.server.modules.model.pricing.entity.ModelContextTierRow;
import com.nexusapi.server.modules.model.pricing.entity.ModelPricingRuleRow;
import com.nexusapi.server.modules.model.pricing.entity.ModelPricingVersionRow;
import com.nexusapi.server.modules.model.pricing.entity.PricingModelStateRow;
import com.nexusapi.server.modules.model.pricing.mapper.AdminModelPricingMapper;
import com.nexusapi.server.modules.model.pricing.model.BillingType;
import com.nexusapi.server.modules.model.vo.ModelSyncRunResponse;
import com.nexusapi.server.modules.model.vo.ModelSyncStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 模型市场同步业务层：配置控制、分布式防重、完整快照校验和安全幂等写入。 */
@Service
public class ModelSyncService {
    private static final Logger log = LoggerFactory.getLogger(ModelSyncService.class);
    private static final String SOURCE = "caicai_market";
    private static final String LOCK_KEY = "nexus:model-sync:lock";
    /** 列表接口缺少任一字段时，必须通过精确详情接口补齐后才允许入库。 */
    private static final List<String> DETAIL_REQUIRED_FIELDS = List.of(
            "billing_type", "unit_price", "display_original_price",
            "input_token_ratio", "output_token_ratio",
            "audio_input_token_ratio", "audio_output_token_ratio",
            "cached_input_token_ratio", "cache_write_5m_token_ratio", "cache_write_1h_token_ratio",
            "context_tier_mode", "pricing_rules_active", "context_tiers_active"
    );
    private static final UUID SETTINGS_AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$");
    private static final Pattern PRICE_CONDITION_KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
    private static final BigDecimal UPSTREAM_UNITS_PER_CREDIT = new BigDecimal("500000");
    private static final long MAX_TOKEN_RATIO = 1_000_000_000_000L;
    private static final Set<String> SAFE_METADATA_FIELDS = Set.of(
            "model_name", "show_name", "model_type", "billing_type", "unit_price", "display_original_price",
            "input_token_ratio", "output_token_ratio", "audio_input_token_ratio", "audio_output_token_ratio",
            "cached_input_token_ratio", "cache_write_5m_token_ratio", "cache_write_1h_token_ratio",
            "context_threshold", "high_context_input_ratio", "high_context_output_ratio", "context_tiers",
            "context_tier_mode", "cover_image", "icon", "max_context", "capabilities", "reasoning_efforts",
            "reasoning_effort_default", "tags", "description", "available_groups", "available_group_ids",
            "charge_desc", "pricing_rules", "pricing_rules_active", "context_tiers_active",
            "param_rules", "field_mappings", "doc_fields"
    );
    private static final Set<String> SAFE_GROUP_METADATA_FIELDS = Set.of(
            "id", "name", "description", "rate", "billing_type", "rpm_limit", "daily_limit", "tpm_limit",
            "is_default", "is_visible", "sort_order", "status", "priority", "auto_enabled",
            "ignore_recharge_tier", "enable_invite_unlock", "invite_unlock_threshold", "create_time",
            "update_time", "unlocked", "unlock_threshold", "invite_unlock_progress"
    );
    private static final Set<String> SENSITIVE_MARKERS = Set.of(
            "authorization", "credential", "secret", "token", "apikey", "password", "accesstoken", "refreshtoken"
    );
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final ModelSyncMapper mapper;
    private final AdminModelPricingMapper pricingMapper;
    private final CaicaiModelMarketClient marketClient;
    private final CaicaiPriceGroupClient priceGroupClient;
    private final ModelSyncProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final AdminAuditService auditService;

    public ModelSyncService(
            ModelSyncMapper mapper,
            AdminModelPricingMapper pricingMapper,
            CaicaiModelMarketClient marketClient,
            CaicaiPriceGroupClient priceGroupClient,
            ModelSyncProperties properties,
            ObjectMapper objectMapper,
            StringRedisTemplate redis,
            TransactionTemplate transactions,
            AdminAuditService auditService
    ) {
        this.mapper = mapper;
        this.pricingMapper = pricingMapper;
        this.marketClient = marketClient;
        this.priceGroupClient = priceGroupClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.transactions = transactions;
        this.auditService = auditService;
    }

    public ModelSyncStatusResponse status() {
        ModelSyncSettingsRow settings = requireSettings();
        ModelSyncRunRow lastRun = mapper.findLatestRun();
        return toStatus(settings, lastRun, isRunning(lastRun));
    }

    public ModelSyncStatusResponse updateSettings(
            UUID actorUserId,
            ModelSyncSettingsRequest request,
            ClientRequestMetadata metadata
    ) {
        return transactions.execute(status -> {
            ModelSyncRunRow beforeRun = mapper.findLatestRun();
            ModelSyncStatusResponse before = toStatus(requireSettings(), beforeRun, isRunning(beforeRun));
            if (mapper.updateSettings(actorUserId, request.enabled(), request.intervalMinutes(), request.version()) != 1) {
                throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
            }
            ModelSyncRunRow afterRun = mapper.findLatestRun();
            ModelSyncStatusResponse after = toStatus(requireSettings(), afterRun, isRunning(afterRun));
            auditService.record(
                    actorUserId, "admin.model-sync.settings.update", "model_sync_settings",
                    SETTINGS_AUDIT_ID, before, after, metadata
            );
            return after;
        });
    }

    /** 管理员立即执行入口；即使自动同步关闭，也允许完成一次受控同步。 */
    public ModelSyncRunResponse runManual(UUID actorUserId, ClientRequestMetadata metadata) {
        String owner = UUID.randomUUID().toString();
        if (!acquireLock(owner)) {
            throw new BusinessException(ErrorCode.MODEL_SYNC_IN_PROGRESS);
        }
        try {
            ModelSyncRunResponse result = execute("manual", actorUserId);
            transactions.executeWithoutResult(status -> auditService.record(
                    actorUserId, "admin.model-sync.run", "model_sync_run", result.id(), null, result, metadata
            ));
            return result;
        } catch (CaicaiModelMarketClient.ModelMarketException failure) {
            throw new BusinessException(ErrorCode.MODEL_SYNC_FAILED, failure.getMessage(), Map.of("reason", failure.code()));
        } catch (CaicaiPriceGroupClient.PriceGroupException failure) {
            throw new BusinessException(ErrorCode.MODEL_SYNC_FAILED, failure.getMessage(), Map.of("reason", failure.code()));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.MODEL_SYNC_FAILED);
        } finally {
            releaseLock(owner);
        }
    }

    /** 调度器入口；先获取全局锁，再原子领取到期计划，避免多实例重复执行。 */
    public boolean runScheduledIfDue() {
        String owner = UUID.randomUUID().toString();
        if (!acquireLock(owner)) {
            return false;
        }
        try {
            Integer claimed = transactions.execute(status -> mapper.claimDueSchedule());
            if (claimed == null || claimed != 1) {
                return false;
            }
            execute("scheduled", null);
            return true;
        } finally {
            releaseLock(owner);
        }
    }

    private ModelSyncRunResponse execute(String triggerType, UUID actorUserId) {
        UUID runId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            mapper.failStaleRuns(Instant.now().minus(properties.lockTtl()));
            mapper.insertRun(runId, triggerType, actorUserId);
        });
        try {
            CaicaiPriceGroupClient.PriceGroupSnapshot groupSnapshot = priceGroupClient.fetchVisible();
            CaicaiModelMarketClient.MarketSnapshot snapshot = enrichMissingDetails(marketClient.fetchAll());
            PreparedSnapshot prepared = prepare(groupSnapshot, snapshot);
            transactions.executeWithoutResult(status -> {
                ApplyResult result = apply(prepared);
                ApplyCounts counts = result.models();
                GroupApplyCounts groups = result.groups();
                mapper.completeRun(
                        runId, snapshot.total(), snapshot.models().size(), counts.inserted(), counts.updated(),
                        counts.unchanged(), prepared.skipped() + counts.sourceConflicts(),
                        prepared.groups().size(), groups.inserted(), groups.updated(), groups.unchanged(), groups.stale()
                );
            });
            ModelSyncRunRow completed = mapper.findRun(runId);
            log.info(
                    "model_market_sync_completed runId={} trigger={} fetched={} inserted={} updated={} unchanged={} skipped={} groups={} groupInserted={} groupUpdated={} groupStale={}",
                    runId, triggerType, completed.getFetchedCount(), completed.getInsertedCount(),
                    completed.getUpdatedCount(), completed.getUnchangedCount(), completed.getSkippedCount(),
                    completed.getGroupTotal(), completed.getGroupInsertedCount(), completed.getGroupUpdatedCount(),
                    completed.getGroupStaleCount()
            );
            return toRun(completed);
        } catch (CaicaiModelMarketClient.ModelMarketException failure) {
            markFailed(runId, failure.code(), failure.getMessage());
            log.warn("model_market_sync_failed runId={} trigger={} category={}", runId, triggerType, failure.code());
            throw failure;
        } catch (CaicaiPriceGroupClient.PriceGroupException failure) {
            markFailed(runId, failure.code(), failure.getMessage());
            log.warn("model_market_sync_failed runId={} trigger={} category={}", runId, triggerType, failure.code());
            throw failure;
        } catch (RuntimeException failure) {
            markFailed(runId, "internal_sync_error", "模型同步内部处理失败");
            log.warn(
                    "model_market_sync_failed runId={} trigger={} category=internal_sync_error type={}",
                    runId, triggerType, failure.getClass().getSimpleName()
            );
            throw failure;
        }
    }

    /**
     * 列表接口是主快照来源；只有关键字段缺失时才按模型名读取详情，避免定时任务产生不必要的 N+1 请求。
     * 详情请求失败直接终止整轮，保证不会把半完整模型写入数据库。
     */
    private CaicaiModelMarketClient.MarketSnapshot enrichMissingDetails(
            CaicaiModelMarketClient.MarketSnapshot snapshot
    ) {
        List<JsonNode> enriched = new ArrayList<>(snapshot.models().size());
        for (JsonNode source : snapshot.models()) {
            if (!needsDetail(source)) {
                enriched.add(source);
                continue;
            }
            String modelName = source.path("model_name").asText("").strip();
            if (modelName.isEmpty()) {
                throw contractFailure("模型列表缺少 model_name，无法请求单模型详情");
            }
            JsonNode detail = marketClient.fetchByModelName(modelName);
            if (!detail.isObject()) {
                throw contractFailure("单模型详情不是对象");
            }
            ObjectNode merged = source.deepCopy();
            merged.setAll((ObjectNode) detail);
            enriched.add(merged);
        }
        return new CaicaiModelMarketClient.MarketSnapshot(snapshot.total(), List.copyOf(enriched));
    }

    private boolean needsDetail(JsonNode source) {
        if (source == null || !source.isObject()) return true;
        return DETAIL_REQUIRED_FIELDS.stream().anyMatch(field -> {
            JsonNode value = source.get(field);
            return value == null || value.isNull();
        });
    }

    /**
     * 先在内存中完成分组、模型和关联的交叉校验，只有整套快照合法才进入数据库事务。
     */
    private PreparedSnapshot prepare(
            CaicaiPriceGroupClient.PriceGroupSnapshot groupSnapshot,
            CaicaiModelMarketClient.MarketSnapshot modelSnapshot
    ) {
        Instant seenAt = Instant.now();
        Map<String, SyncedRoutingGroupRow> groups = new LinkedHashMap<>();
        for (JsonNode source : groupSnapshot.groups()) {
            SyncedRoutingGroupRow group = toSyncedRoutingGroup(source, seenAt);
            if (groups.putIfAbsent(group.getSourceGroupId(), group) != null) {
                throw contractFailure("上游服务分组 ID 重复");
            }
        }

        Map<String, PreparedModel> selected = new LinkedHashMap<>();
        int skipped = 0;
        for (JsonNode source : modelSnapshot.models()) {
            SyncedModelRow model = toSyncedModel(source, seenAt);
            PreparedModel row = model == null ? null : new PreparedModel(
                    model, prepareMemberships(source, groups), preparePricing(source, model.getCapabilityType(), seenAt)
            );
            if (row == null) {
                skipped++;
                continue;
            }
            String normalized = row.model().getPublicName().toLowerCase(Locale.ROOT);
            PreparedModel existing = selected.get(normalized);
            if (existing == null) {
                selected.put(normalized, row);
                continue;
            }
            // 本地模型名按不区分大小写保持唯一；冲突时优先全小写名称，再按字典序稳定选择。
            selected.put(normalized, preferred(existing, row, normalized));
            skipped++;
        }
        return new PreparedSnapshot(List.copyOf(groups.values()), List.copyOf(selected.values()), skipped, seenAt);
    }

    private PreparedModel preferred(PreparedModel first, PreparedModel second, String normalized) {
        if (second.model().getPublicName().equals(normalized) && !first.model().getPublicName().equals(normalized)) {
            return second;
        }
        if (first.model().getPublicName().equals(normalized)) {
            return first;
        }
        return Comparator.comparing((PreparedModel value) -> value.model().getPublicName())
                .compare(first, second) <= 0 ? first : second;
    }

    private ApplyResult apply(PreparedSnapshot snapshot) {
        UUID supplierId = mapper.findSupplierIdByCode(properties.sourceSupplierCode());
        if (supplierId == null) {
            throw new IllegalStateException("model sync source supplier is missing");
        }
        GroupApplyResult groupResult = applyGroups(snapshot.groups(), supplierId, snapshot.seenAt());
        Map<String, UUID> localGroupIds = groupResult.localIds();
        List<SyncedModelRow> models = snapshot.models().stream().map(PreparedModel::model).toList();
        ApplyCounts counts = applyModels(models);

        List<String> normalizedNames = models.stream()
                .map(row -> row.getPublicName().toLowerCase(Locale.ROOT))
                .toList();
        Map<String, ModelSyncTargetRow> localModels = loadModelTargets(normalizedNames);
        applyPricing(snapshot.models(), localModels);
        List<SyncedGroupModelRow> memberships = new ArrayList<>();
        for (PreparedModel preparedModel : snapshot.models()) {
            String modelKey = preparedModel.model().getPublicName().toLowerCase(Locale.ROOT);
            ModelSyncTargetRow target = localModels.get(modelKey);
            // 其他同步来源已占用同名模型时，不抢占其分组归属。
            if (target == null || !SOURCE.equals(target.getSyncSource())) {
                continue;
            }
            for (PreparedMembership membership : preparedModel.memberships()) {
                UUID groupId = localGroupIds.get(membership.sourceGroupId());
                if (groupId == null) {
                    throw new IllegalStateException("validated source group was not persisted");
                }
                memberships.add(toGroupModel(groupId, target.getId(), membership, snapshot.seenAt()));
            }
        }
        if (!memberships.isEmpty()) {
            mapper.upsertRoutingGroupModels(memberships);
        }
        mapper.markMissingRoutingGroupModelsStale(supplierId, SOURCE, snapshot.seenAt());
        return new ApplyResult(counts, groupResult.counts());
    }

    private ApplyCounts applyModels(List<SyncedModelRow> models) {
        List<String> normalizedNames = models.stream()
                .map(row -> row.getPublicName().toLowerCase(Locale.ROOT))
                .toList();
        Map<String, ModelSyncTargetRow> targets = loadModelTargets(normalizedNames);

        List<SyncedModelRow> inserts = new ArrayList<>();
        int updated = 0;
        int unchanged = 0;
        for (SyncedModelRow model : models) {
            String key = model.getPublicName().toLowerCase(Locale.ROOT);
            ModelSyncTargetRow target = targets.get(key);
            if (target == null) {
                inserts.add(model);
                continue;
            }
            if (target.getSyncSource() != null && !SOURCE.equals(target.getSyncSource())) {
                // 其他来源已经声明所有权时，不抢占来源身份，也不覆盖任何字段。
                continue;
            }
            boolean changed = !model.getSourcePayloadHash().equals(target.getSourcePayloadHash());
            mapper.updateModelSnapshot(target.getId(), model, changed);
            if (changed) updated++; else unchanged++;
        }
        if (!inserts.isEmpty()) {
            mapper.insertModels(inserts);
        }
        int sourceConflicts = models.size() - inserts.size() - updated - unchanged;
        return new ApplyCounts(inserts.size(), updated, unchanged, Math.max(0, sourceConflicts));
    }

    /**
     * 只为开启“跟随上游”的模型创建价格版本。模型行锁、来源哈希和数据库唯一索引共同保证幂等，
     * 管理员发布人工价格后 pricingSourceManaged=false，同步会在这里直接跳过。
     */
    private void applyPricing(
            List<PreparedModel> models,
            Map<String, ModelSyncTargetRow> localModels
    ) {
        for (PreparedModel prepared : models) {
            ModelSyncTargetRow target = localModels.get(prepared.model().getPublicName().toLowerCase(Locale.ROOT));
            if (target == null || !SOURCE.equals(target.getSyncSource())
                    || !target.isPricingSourceManaged()) {
                continue;
            }

            PricingModelStateRow state = pricingMapper.lockModel(target.getId());
            if (state == null || !state.pricingSourceManaged()) {
                continue;
            }

            ModelPricingVersionRow version = pricingMapper.findSourceVersionByHash(
                    target.getId(), SOURCE, prepared.pricing().sourceHash()
            );
            // 哈希相同只能说明上游价格没有变化，不能证明当前激活版本仍是该上游版本。
            // Shadow 验收或异常恢复可能留下“跟随上游=true，但激活版本为人工版本”的不一致状态；
            // 此时必须重新指向已存在的上游版本，避免管理员页面继续展示旧的人工价格。
            if (version != null && version.id().equals(state.activePricingVersionId())) {
                continue;
            }
            if (version == null) {
                version = createSourcePricingVersion(target.getId(), prepared.pricing());
            }
            if (pricingMapper.followSourceVersion(target.getId(), version.id(), state.version()) != 1) {
                throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
            }
        }
    }

    /** 上游快照先完整校验并计算哈希，落库时再分配本地 UUID 和单调版本号。 */
    private ModelPricingVersionRow createSourcePricingVersion(UUID modelId, PreparedPricing pricing) {
        UUID versionId = UUID.randomUUID();
        ModelPricingVersionRow version = new ModelPricingVersionRow(
                versionId,
                modelId,
                pricingMapper.nextVersionNo(modelId),
                pricing.billingType(),
                pricing.unitPrice(),
                pricing.displayOriginalPrice(),
                pricing.inputTokenRatio(),
                pricing.outputTokenRatio(),
                pricing.audioInputTokenRatio(),
                pricing.audioOutputTokenRatio(),
                pricing.cachedInputTokenRatio(),
                pricing.cacheWrite5mTokenRatio(),
                pricing.cacheWrite1hTokenRatio(),
                pricing.chargeDesc(),
                pricing.contextTierMode(),
                "base",
                SOURCE,
                pricing.sourceHash(),
                pricing.observedAt(),
                "上游模型市场价格同步",
                null,
                null
        );
        if (pricingMapper.insertVersion(version) != 1) {
            throw new IllegalStateException("failed to insert source pricing version");
        }
        for (PreparedPricingRule rule : pricing.rules()) {
            if (pricingMapper.insertRule(new ModelPricingRuleRow(
                    UUID.randomUUID(), versionId, rule.priority(), rule.name(),
                    writeJson(rule.conditions()), rule.billingType(), rule.unitPrice(), BigDecimal.ONE
            )) != 1) {
                throw new IllegalStateException("failed to insert source pricing rule");
            }
        }
        for (PreparedContextTier tier : pricing.contextTiers()) {
            if (pricingMapper.insertContextTier(new ModelContextTierRow(
                    UUID.randomUUID(), versionId, tier.priority(), tier.minInputTokens(), tier.maxInputTokens(),
                    tier.inputRatio(), tier.outputRatio(), tier.cachedInputRatio(),
                    tier.cacheWrite5mRatio(), tier.cacheWrite1hRatio()
            )) != 1) {
                throw new IllegalStateException("failed to insert source context tier");
            }
        }
        return version;
    }

    /**
     * 将上游内部单位转换为平台积分，并把规则和长上下文分档规范化为可重复哈希的安全快照。
     */
    private PreparedPricing preparePricing(JsonNode source, String capabilityType, Instant observedAt) {
        int billingType = localBillingType(
                requiredUpstreamBillingType(source.get("billing_type"), "模型计费类型"), capabilityType
        );
        if (!BillingType.fromCode(billingType).supportsCapability(capabilityType)) {
            throw contractFailure("上游模型计费类型与能力类型不兼容");
        }
        BigDecimal unitPrice = requiredCreditPrice(source.get("unit_price"), "模型基础单价");
        BigDecimal displayOriginalPrice = requiredCreditPrice(
                source.get("display_original_price"), "模型展示原价"
        );
        long inputRatio = requiredRatio(source.get("input_token_ratio"), "输入 Token 倍率");
        long outputRatio = requiredRatio(source.get("output_token_ratio"), "输出 Token 倍率");
        long audioInputRatio = requiredRatio(source.get("audio_input_token_ratio"), "音频输入 Token 倍率");
        long audioOutputRatio = requiredRatio(source.get("audio_output_token_ratio"), "音频输出 Token 倍率");
        long cachedRatio = requiredRatio(source.get("cached_input_token_ratio"), "缓存输入倍率");
        long cacheWrite5mRatio = requiredRatio(source.get("cache_write_5m_token_ratio"), "5 分钟缓存写入倍率");
        long cacheWrite1hRatio = requiredRatio(source.get("cache_write_1h_token_ratio"), "1 小时缓存写入倍率");
        int contextTierMode = requiredIntInRange(source.get("context_tier_mode"), 0, 2, "上下文分档模式");
        String chargeDesc = optionalText(source.get("charge_desc"), 1000, "计费说明");

        boolean pricingRulesActive = requiredBoolean(source.get("pricing_rules_active"), "条件计价启用状态");
        boolean contextTiersActive = requiredBoolean(source.get("context_tiers_active"), "上下文分档启用状态");
        List<PreparedPricingRule> rules = pricingRulesActive
                ? preparePricingRules(source.get("pricing_rules"), capabilityType)
                : List.of();
        List<PreparedContextTier> contextTiers = contextTiersActive
                ? prepareContextTiers(
                        source, billingType, inputRatio, outputRatio, cachedRatio,
                        cacheWrite5mRatio, cacheWrite1hRatio
                )
                : List.of();

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("billing_type", billingType);
        canonical.put("unit_price", unitPrice.toPlainString());
        canonical.put("display_original_price", displayOriginalPrice.toPlainString());
        canonical.put("input_token_ratio", inputRatio);
        canonical.put("output_token_ratio", outputRatio);
        canonical.put("audio_input_token_ratio", audioInputRatio);
        canonical.put("audio_output_token_ratio", audioOutputRatio);
        canonical.put("cached_input_token_ratio", cachedRatio);
        canonical.put("cache_write_5m_token_ratio", cacheWrite5mRatio);
        canonical.put("cache_write_1h_token_ratio", cacheWrite1hRatio);
        canonical.put("charge_desc", chargeDesc);
        canonical.put("context_tier_mode", contextTierMode);
        canonical.put("rules", rules);
        canonical.put("context_tiers", contextTiers);

        return new PreparedPricing(
                billingType, unitPrice, displayOriginalPrice, inputRatio, outputRatio,
                audioInputRatio, audioOutputRatio, cachedRatio,
                cacheWrite5mRatio, cacheWrite1hRatio, chargeDesc, contextTierMode,
                rules, contextTiers, sha256(writeJson(canonical)), observedAt
        );
    }

    private List<PreparedPricingRule> preparePricingRules(JsonNode node, String capabilityType) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > 100) {
            throw contractFailure("上游条件计价规则结构无效");
        }
        List<PreparedPricingRule> result = new ArrayList<>(node.size());
        for (int index = 0; index < node.size(); index++) {
            JsonNode sourceRule = node.get(index);
            if (!sourceRule.isObject()) throw contractFailure("上游条件计价规则条目无效");
            String name = optionalText(sourceRule.get("rule_name"), 120, "条件计价规则名称");
            if (name == null) throw contractFailure("上游条件计价规则缺少名称");
            Map<String, String> conditions = pricingConditions(sourceRule.get("conditions"));
            Integer upstreamOverrideType = optionalUpstreamBillingType(sourceRule.get("billing_type"));
            Integer overrideType = upstreamOverrideType == null
                    ? null : localBillingType(upstreamOverrideType, capabilityType);
            if (overrideType != null && !BillingType.fromCode(overrideType).supportsCapability(capabilityType)) {
                throw contractFailure("上游条件规则计费类型与模型能力不兼容");
            }
            BigDecimal overridePrice = optionalCreditPrice(sourceRule.get("unit_price"), "条件规则单价");
            result.add(new PreparedPricingRule(
                    (index + 1) * 10, name, conditions, overrideType, overridePrice
            ));
        }
        return List.copyOf(result);
    }

    /** 条件只能是扁平标量，禁止凭证字段、嵌套对象和表达式进入 Gateway 匹配器。 */
    private Map<String, String> pricingConditions(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty() || node.size() > 16) {
            throw contractFailure("上游条件计价规则参数无效");
        }
        Map<String, String> sorted = new java.util.TreeMap<>();
        node.properties().forEach(entry -> {
            String key = entry.getKey().strip().toLowerCase(Locale.ROOT);
            String compact = key.replaceAll("[^a-z0-9]", "");
            if (!PRICE_CONDITION_KEY.matcher(key).matches()
                    || SENSITIVE_MARKERS.stream().anyMatch(compact::contains)) {
                throw contractFailure("上游条件计价规则包含不允许的参数名");
            }
            JsonNode valueNode = entry.getValue();
            if (!(valueNode.isTextual() || valueNode.isNumber() || valueNode.isBoolean())) {
                throw contractFailure("上游条件计价规则参数必须是标量");
            }
            String value = valueNode.asText().strip();
            if (value.isEmpty() || value.length() > 160
                    || value.codePoints().anyMatch(Character::isISOControl)) {
                throw contractFailure("上游条件计价规则参数值无效");
            }
            if (sorted.putIfAbsent(key, value) != null) {
                throw contractFailure("上游条件计价规则包含归一化后重复的参数名");
            }
        });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private List<PreparedContextTier> prepareContextTiers(
            JsonNode source,
            int billingType,
            long baseInputRatio,
            long baseOutputRatio,
            long baseCachedRatio,
            long baseCacheWrite5mRatio,
            long baseCacheWrite1hRatio
    ) {
        if (billingType != BillingType.TOKEN.code()) {
            throw contractFailure("非 Token 模型不能启用长上下文分档");
        }
        JsonNode node = source.get("context_tiers");
        if (node == null || node.isNull()) {
            return prepareLegacyContextTiers(
                    source, baseInputRatio, baseOutputRatio, baseCachedRatio,
                    baseCacheWrite5mRatio, baseCacheWrite1hRatio
            );
        }
        if (!node.isArray() || node.size() > 50) {
            throw contractFailure("上游长上下文分档结构无效");
        }
        List<PreparedContextTier> result = new ArrayList<>(node.size());
        long minInputTokens = 0;
        boolean openEndedSeen = false;
        for (int index = 0; index < node.size(); index++) {
            if (openEndedSeen) throw contractFailure("上游无上限上下文分档必须放在最后");
            JsonNode sourceTier = node.get(index);
            if (!sourceTier.isObject()) throw contractFailure("上游长上下文分档条目无效");
            long inclusiveUpper = requiredNonnegativeLong(sourceTier.get("up_to"), "上下文分档上限");
            Long maxInputTokens = null;
            if (inclusiveUpper > 0) {
                try {
                    maxInputTokens = Math.addExact(inclusiveUpper, 1L);
                } catch (ArithmeticException failure) {
                    throw contractFailure("上游长上下文分档上限溢出");
                }
                if (maxInputTokens <= minInputTokens) {
                    throw contractFailure("上游长上下文分档范围重叠或倒序");
                }
            } else {
                openEndedSeen = true;
            }
            long inputRatio = inheritedRatio(sourceTier.get("input_ratio"), baseInputRatio, "分档输入倍率");
            long outputRatio = inheritedRatio(sourceTier.get("output_ratio"), baseOutputRatio, "分档输出倍率");
            long cachedRatio = inheritedRatio(sourceTier.get("cached_input_ratio"), baseCachedRatio, "分档缓存倍率");
            long cacheWrite5mRatio = inheritedRatio(
                    sourceTier.get("cache_write_5m_ratio"), baseCacheWrite5mRatio, "分档 5 分钟缓存写入倍率"
            );
            long cacheWrite1hRatio = inheritedRatio(
                    sourceTier.get("cache_write_1h_ratio"), baseCacheWrite1hRatio, "分档 1 小时缓存写入倍率"
            );
            result.add(new PreparedContextTier(
                    (index + 1) * 10, minInputTokens, maxInputTokens,
                    inputRatio, outputRatio, cachedRatio, cacheWrite5mRatio, cacheWrite1hRatio
            ));
            if (maxInputTokens != null) minInputTokens = maxInputTokens;
        }
        return List.copyOf(result);
    }

    /**
     * 兼容上游旧版单档字段。threshold 表示基础档最后一个 Token，超过阈值后应用高上下文倍率；
     * null 且全部旧字段为 0 表示尚未配置分档，不应让整轮模型同步失败。
     */
    private List<PreparedContextTier> prepareLegacyContextTiers(
            JsonNode source,
            long baseInputRatio,
            long baseOutputRatio,
            long baseCachedRatio,
            long baseCacheWrite5mRatio,
            long baseCacheWrite1hRatio
    ) {
        long threshold = requiredNonnegativeLong(source.get("context_threshold"), "旧版上下文阈值");
        long highInputRatio = requiredRatio(source.get("high_context_input_ratio"), "旧版高上下文输入倍率");
        long highOutputRatio = requiredRatio(source.get("high_context_output_ratio"), "旧版高上下文输出倍率");
        if (threshold == 0) {
            if (highInputRatio != 0 || highOutputRatio != 0) {
                throw contractFailure("上游旧版长上下文倍率缺少有效阈值");
            }
            return List.of();
        }
        long highTierStart;
        try {
            highTierStart = Math.addExact(threshold, 1L);
        } catch (ArithmeticException failure) {
            throw contractFailure("上游旧版上下文阈值溢出");
        }
        long resolvedHighInput = highInputRatio == 0 ? baseInputRatio : highInputRatio;
        long resolvedHighOutput = highOutputRatio == 0 ? baseOutputRatio : highOutputRatio;
        return List.of(
                new PreparedContextTier(
                        10, 0, highTierStart,
                        baseInputRatio, baseOutputRatio, baseCachedRatio,
                        baseCacheWrite5mRatio, baseCacheWrite1hRatio
                ),
                new PreparedContextTier(
                        20, highTierStart, null,
                        resolvedHighInput, resolvedHighOutput, baseCachedRatio,
                        baseCacheWrite5mRatio, baseCacheWrite1hRatio
                )
        );
    }

    private long inheritedRatio(JsonNode node, long inherited, String field) {
        long value = requiredRatio(node, field);
        return value == 0 ? inherited : value;
    }

    private int requiredUpstreamBillingType(JsonNode node, String field) {
        return requiredIntInRange(node, 1, 5, field);
    }

    private Integer optionalUpstreamBillingType(JsonNode node) {
        return node == null || node.isNull()
                ? null : requiredUpstreamBillingType(node, "条件规则计费类型");
    }

    /** 上游仍使用 3 表示所有按秒资源，本地根据能力类型拆分为视频秒 3 和音频秒 6。 */
    private int localBillingType(int upstreamBillingType, String capabilityType) {
        return upstreamBillingType == BillingType.VIDEO_SECOND.code() && "audio".equals(capabilityType)
                ? BillingType.AUDIO_SECOND.code()
                : upstreamBillingType;
    }

    private int requiredIntInRange(JsonNode node, int min, int max, String field) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw contractFailure("上游" + field + "无效");
        }
        int value = node.intValue();
        if (value < min || value > max) throw contractFailure("上游" + field + "超出范围");
        return value;
    }

    private long requiredRatio(JsonNode node, String field) {
        long value = requiredNonnegativeLong(node, field);
        if (value > MAX_TOKEN_RATIO) throw contractFailure("上游" + field + "超出安全上限");
        return value;
    }

    private long requiredNonnegativeLong(JsonNode node, String field) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw contractFailure("上游" + field + "无效");
        }
        long value = node.longValue();
        if (value < 0) throw contractFailure("上游" + field + "不能为负数");
        return value;
    }

    private BigDecimal requiredCreditPrice(JsonNode node, String field) {
        BigDecimal value = optionalCreditPrice(node, field);
        if (value == null) throw contractFailure("上游" + field + "缺失");
        return value;
    }

    private BigDecimal optionalCreditPrice(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber()) throw contractFailure("上游" + field + "必须使用整数内部单位");
        BigDecimal internal = node.decimalValue();
        if (internal.signum() < 0) throw contractFailure("上游" + field + "不能为负数");
        try {
            return internal.divide(UPSTREAM_UNITS_PER_CREDIT, 12, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw contractFailure("上游" + field + "无法精确换算为平台积分");
        }
    }

    private boolean requiredBoolean(JsonNode node, String field) {
        if (node == null || !node.isBoolean()) throw contractFailure("上游" + field + "缺失或无效");
        return node.booleanValue();
    }

    private String optionalText(JsonNode node, int maxLength, String field) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw contractFailure("上游" + field + "格式无效");
        String normalized = normalizedText(node.textValue(), maxLength);
        if (normalized == null && !node.textValue().isBlank()) {
            throw contractFailure("上游" + field + "格式无效");
        }
        return normalized;
    }

    private Map<String, ModelSyncTargetRow> loadModelTargets(List<String> normalizedNames) {
        Map<String, ModelSyncTargetRow> targets = new LinkedHashMap<>();
        if (!normalizedNames.isEmpty()) {
            for (ModelSyncTargetRow target : mapper.findTargets(normalizedNames)) {
                String key = target.getPublicName().toLowerCase(Locale.ROOT);
                if (targets.putIfAbsent(key, target) != null) {
                    throw new IllegalStateException("case-insensitive local model identity conflict");
                }
            }
        }
        return targets;
    }

    private GroupApplyResult applyGroups(List<SyncedRoutingGroupRow> groups, UUID supplierId, Instant seenAt) {
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (SyncedRoutingGroupRow group : groups) {
            RoutingGroupSyncTargetRow target = mapper.findRoutingGroupBySource(supplierId, group.getSourceGroupId());
            if (target != null) {
                boolean changed = !group.getSourcePayloadHash().equals(target.getSourcePayloadHash())
                        || !"active".equals(target.getSourceStatus());
                if (mapper.updateRoutingGroupSource(target.getId(), supplierId, group, changed) != 1) {
                    throw new IllegalStateException("failed to update routing group source");
                }
                if (changed) updated++; else unchanged++;
                continue;
            }
            List<RoutingGroupSyncTargetRow> sameName = mapper.findUnboundRoutingGroupsByName(group.getSourceGroupName());
            if (sameName.size() == 1) {
                if (mapper.bindRoutingGroupSource(sameName.getFirst().getId(), supplierId, SOURCE, group) != 1) {
                    throw new IllegalStateException("failed to bind existing routing group source");
                }
                updated++;
            } else {
                if (mapper.insertRoutingGroupFromSource(supplierId, SOURCE, group) != 1) {
                    throw new IllegalStateException("failed to insert routing group source");
                }
                inserted++;
            }
        }
        int stale = mapper.markMissingRoutingGroupsStale(supplierId, SOURCE, seenAt);
        Map<String, UUID> result = new LinkedHashMap<>();
        for (RoutingGroupSyncTargetRow row : mapper.findRoutingGroupsBySource(supplierId, SOURCE)) {
            result.put(row.getSourceGroupId(), row.getId());
        }
        if (groups.stream().anyMatch(group -> !result.containsKey(group.getSourceGroupId()))) {
            throw new IllegalStateException("source routing group snapshot was not fully persisted");
        }
        return new GroupApplyResult(result, new GroupApplyCounts(inserted, updated, unchanged, stale));
    }

    private SyncedRoutingGroupRow toSyncedRoutingGroup(JsonNode source, Instant seenAt) {
        String sourceGroupId = normalizedText(source.path("id").asText(null), 160);
        String sourceGroupName = normalizedText(source.path("name").asText(null), 120);
        if (sourceGroupId == null || sourceGroupName == null) {
            throw contractFailure("上游服务分组缺少 ID 或名称");
        }
        UUID sourceUuid;
        try {
            sourceUuid = UUID.fromString(sourceGroupId);
        } catch (IllegalArgumentException failure) {
            throw contractFailure("上游服务分组 ID 格式无效");
        }
        long rate = source.path("rate").asLong(-1);
        int billingType = source.path("billing_type").asInt(-1);
        if (rate < 0 || billingType < 0) {
            throw contractFailure("上游服务分组倍率或计费类型无效");
        }
        ObjectNode metadata = safeMetadata(source, SAFE_GROUP_METADATA_FIELDS);
        String metadataJson = writeJson(metadata);

        SyncedRoutingGroupRow row = new SyncedRoutingGroupRow();
        row.setId(UUID.randomUUID());
        row.setSourceGroupId(sourceGroupId);
        row.setSourceGroupName(sourceGroupName);
        row.setLocalCode("caicai_" + sourceUuid.toString().replace("-", ""));
        row.setSourceRate(rate);
        row.setSourceBillingType(billingType);
        row.setSourceMetadataJson(metadataJson);
        row.setSourcePayloadHash(sha256(metadataJson));
        row.setSeenAt(seenAt);
        return row;
    }

    private List<PreparedMembership> prepareMemberships(
            JsonNode source,
            Map<String, SyncedRoutingGroupRow> groups
    ) {
        JsonNode ids = source.get("available_group_ids");
        JsonNode names = source.get("available_groups");
        // 上游部分历史模型会用 null 和 [] 两种方式表达“没有可用分组”，两者业务语义相同。
        boolean idsEmpty = ids == null || ids.isNull() || (ids.isArray() && ids.isEmpty());
        boolean namesEmpty = names == null || names.isNull() || (names.isArray() && names.isEmpty());
        if (idsEmpty && namesEmpty) {
            return List.of();
        }
        if (idsEmpty || namesEmpty || !ids.isArray() || !names.isArray() || ids.size() != names.size()) {
            throw contractFailure("上游模型分组 ID 和名称数组不一致");
        }

        Map<String, UpstreamHealth> healthByGroup = parseGroupHealth(source.get("group_statuses"), groups);
        List<PreparedMembership> memberships = new ArrayList<>(ids.size());
        Set<String> membershipIds = new LinkedHashSet<>();
        for (int index = 0; index < ids.size(); index++) {
            String sourceGroupId = normalizedText(ids.get(index).asText(null), 160);
            String sourceGroupName = normalizedText(names.get(index).asText(null), 120);
            SyncedRoutingGroupRow group = sourceGroupId == null ? null : groups.get(sourceGroupId);
            if (group == null || sourceGroupName == null || !group.getSourceGroupName().equals(sourceGroupName)) {
                throw contractFailure("上游模型引用了未知或名称不匹配的服务分组");
            }
            if (!membershipIds.add(sourceGroupId)) {
                throw contractFailure("上游模型包含重复服务分组");
            }
            memberships.add(new PreparedMembership(
                    sourceGroupId,
                    healthByGroup.getOrDefault(sourceGroupId, UpstreamHealth.empty())
            ));
        }
        if (healthByGroup.keySet().stream().anyMatch(id -> !membershipIds.contains(id))) {
            throw contractFailure("上游模型健康状态包含未声明的服务分组");
        }
        return List.copyOf(memberships);
    }

    private Map<String, UpstreamHealth> parseGroupHealth(
            JsonNode statuses,
            Map<String, SyncedRoutingGroupRow> groups
    ) {
        if (statuses == null || statuses.isNull()) {
            return Map.of();
        }
        if (!statuses.isArray()) {
            throw contractFailure("上游模型分组健康状态结构无效");
        }
        Map<String, UpstreamHealth> result = new LinkedHashMap<>();
        for (JsonNode status : statuses) {
            if (!status.isObject()) {
                throw contractFailure("上游模型分组健康条目结构无效");
            }
            String groupId = normalizedText(status.path("group_id").asText(null), 160);
            String groupName = normalizedText(status.path("group_name").asText(null), 120);
            SyncedRoutingGroupRow group = groupId == null ? null : groups.get(groupId);
            if (group == null || groupName == null || !group.getSourceGroupName().equals(groupName)) {
                throw contractFailure("上游模型分组健康状态无法溯源到可见分组");
            }
            Integer lastStatus = optionalInt(status.get("last_status"));
            Integer successRate = optionalInt(status.get("success_rate"));
            int failures = status.path("consecutive_failures").asInt(0);
            // 上游状态：0 未探测/陈旧，1 最近成功，2 最近失败。
            if (lastStatus != null && (lastStatus < 0 || lastStatus > 2)) {
                throw contractFailure("上游分组最后健康状态无效");
            }
            if (successRate != null && (successRate < 0 || successRate > 10_000)) {
                throw contractFailure("上游分组成功率超出范围");
            }
            if (failures < 0) {
                throw contractFailure("上游分组连续失败次数无效");
            }
            List<Integer> history = healthHistory(status.get("history"));
            UpstreamHealth health = new UpstreamHealth(
                    lastStatus, successRate, failures, writeJson(history),
                    epochSecond(status.path("last_checked_at").asLong(0)),
                    epochSecond(status.path("last_success_at").asLong(0))
            );
            if (result.putIfAbsent(groupId, health) != null) {
                throw contractFailure("上游模型分组健康状态重复");
            }
        }
        return result;
    }

    private List<Integer> healthHistory(JsonNode history) {
        if (history == null || history.isNull()) {
            return List.of();
        }
        if (!history.isArray() || history.size() > 1_000) {
            throw contractFailure("上游分组健康历史结构无效");
        }
        List<Integer> values = new ArrayList<>(history.size());
        for (JsonNode value : history) {
            int normalized = value.asInt(-1);
            if (normalized != 0 && normalized != 1) {
                throw contractFailure("上游分组健康历史值无效");
            }
            values.add(normalized);
        }
        return List.copyOf(values);
    }

    private Integer optionalInt(JsonNode value) {
        return value == null || value.isNull() ? null : value.asInt();
    }

    private Instant epochSecond(long value) {
        if (value <= 0) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(value);
        } catch (RuntimeException failure) {
            throw contractFailure("上游分组健康时间无效");
        }
    }

    private SyncedGroupModelRow toGroupModel(
            UUID groupId,
            UUID modelId,
            PreparedMembership membership,
            Instant seenAt
    ) {
        UpstreamHealth health = membership.health();
        SyncedGroupModelRow row = new SyncedGroupModelRow();
        row.setId(UUID.randomUUID());
        row.setGroupId(groupId);
        row.setModelId(modelId);
        row.setUpstreamLastStatus(health.lastStatus());
        row.setUpstreamSuccessRate(health.successRate());
        row.setUpstreamConsecutiveFailures(health.consecutiveFailures());
        row.setUpstreamHealthHistoryJson(health.historyJson());
        row.setUpstreamLastCheckedAt(health.lastCheckedAt());
        row.setUpstreamLastSuccessAt(health.lastSuccessAt());
        row.setSeenAt(seenAt);
        return row;
    }

    private CaicaiModelMarketClient.ModelMarketException contractFailure(String summary) {
        return new CaicaiModelMarketClient.ModelMarketException("upstream_contract_error", summary, false);
    }

    private SyncedModelRow toSyncedModel(JsonNode source, Instant seenAt) {
        String modelName = normalizedText(source.path("model_name").asText(null), 160);
        if (modelName == null || !MODEL_NAME.matcher(modelName).matches()) {
            return null;
        }
        String capabilityType = capabilityType(source.path("model_type").asInt(-1));
        if (capabilityType == null) {
            return null;
        }
        Set<String> capabilities = stringSet(source.get("capabilities"));
        List<String> inputModalities = inputModalities(capabilityType, modelName, capabilities);
        List<String> outputModalities = outputModalities(capabilityType, modelName);
        ObjectNode metadata = safeMetadata(source);
        String metadataJson = writeJson(metadata);

        SyncedModelRow row = new SyncedModelRow();
        row.setId(UUID.randomUUID());
        row.setPublicName(modelName);
        String displayName = normalizedText(source.path("show_name").asText(null), 160);
        row.setDisplayName(displayName == null ? modelName : displayName);
        row.setCapabilityType(capabilityType);
        row.setInputModalitiesJson(writeJson(inputModalities));
        row.setOutputModalitiesJson(writeJson(outputModalities));
        long maxContext = source.path("max_context").asLong(0);
        row.setContextWindow(maxContext > 0 ? maxContext : null);
        // 同步模型采用平台统一的可用能力默认值；管理员后续编辑会关闭 source_managed，避免再次同步覆盖人工配置。
        row.setSupportsStreaming(true);
        row.setSupportsTools(true);
        row.setSupportsStructuredOutput(true);
        row.setPriceUnit(priceUnit(source.path("billing_type").asInt(-1)));
        row.setSyncSource(SOURCE);
        row.setSourceModelKey(modelName);
        row.setSourceMetadataJson(metadataJson);
        row.setSourcePayloadHash(sha256(metadataJson));
        row.setSeenAt(seenAt);
        return row;
    }

    private ObjectNode safeMetadata(JsonNode source) {
        return safeMetadata(source, SAFE_METADATA_FIELDS);
    }

    private ObjectNode safeMetadata(JsonNode source, Set<String> allowedFields) {
        ObjectNode metadata = objectMapper.createObjectNode();
        allowedFields.stream().sorted().forEach(field -> {
            JsonNode value = source.get(field);
            if (value != null && isSafeJson(value)) {
                metadata.set(field, value.deepCopy());
            }
        });
        return metadata;
    }

    private boolean isSafeJson(JsonNode node) {
        if (node.isObject()) {
            var fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (SENSITIVE_MARKERS.stream().anyMatch(
                        marker -> normalized.equals(marker) || normalized.startsWith(marker) || normalized.endsWith(marker)
                ) || !isSafeJson(field.getValue())) {
                    return false;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (!isSafeJson(child)) return false;
            }
        }
        return true;
    }

    private Set<String> stringSet(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual()) values.add(value.asText().strip().toLowerCase(Locale.ROOT));
            });
        }
        return Set.copyOf(values);
    }

    private List<String> inputModalities(String type, String modelName, Set<String> capabilities) {
        LinkedHashSet<String> modalities = new LinkedHashSet<>();
        switch (type) {
            case "image", "video" -> {
                modalities.add("text");
                modalities.add("image");
            }
            case "audio" -> modalities.add(modelName.toLowerCase(Locale.ROOT).startsWith("tts-") ? "text" : "audio");
            default -> modalities.add("text");
        }
        if (capabilities.contains("vision")) modalities.add("image");
        if (capabilities.contains("file")) modalities.add("file");
        return List.copyOf(modalities);
    }

    private List<String> outputModalities(String type, String modelName) {
        return switch (type) {
            case "image" -> List.of("image");
            case "video" -> List.of("video");
            case "audio" -> List.of(modelName.toLowerCase(Locale.ROOT).startsWith("tts-") ? "audio" : "text");
            default -> List.of("text");
        };
    }

    private String capabilityType(int modelType) {
        return switch (modelType) {
            case 1 -> "image";
            case 2 -> "video";
            case 3 -> "text";
            case 4 -> "audio";
            case 5 -> "embedding";
            default -> null;
        };
    }

    private String priceUnit(int billingType) {
        return switch (billingType) {
            case 1 -> "request";
            case 2 -> "image";
            case 3 -> "second";
            case 5 -> "character";
            case 6 -> "second";
            default -> "million_tokens";
        };
    }

    private String normalizedText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return normalized;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize model sync snapshot", failure);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private void markFailed(UUID runId, String code, String summary) {
        transactions.executeWithoutResult(status -> mapper.failRun(runId, code, summary));
    }

    private boolean acquireLock(String owner) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, owner, properties.lockTtl()));
        } catch (RuntimeException failure) {
            log.warn("model_market_sync_lock_unavailable type={}", failure.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.MODEL_SYNC_FAILED, "模型同步锁服务暂时不可用", null);
        }
    }

    private void releaseLock(String owner) {
        try {
            redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), owner);
        } catch (RuntimeException failure) {
            // 锁自带 TTL，释放失败不会永久阻塞后续同步，也不能反向改变已提交的数据结果。
            log.warn("model_market_sync_unlock_failed type={}", failure.getClass().getSimpleName());
        }
    }

    private ModelSyncSettingsRow requireSettings() {
        ModelSyncSettingsRow settings = mapper.findSettings();
        if (settings == null) {
            throw new IllegalStateException("model sync settings are missing");
        }
        return settings;
    }

    private ModelSyncStatusResponse toStatus(ModelSyncSettingsRow settings, ModelSyncRunRow lastRun, boolean running) {
        return new ModelSyncStatusResponse(
                settings.isEnabled(), settings.getIntervalMinutes(), settings.getNextRunAt(), running,
                settings.getVersion(), lastRun == null ? null : toRun(lastRun)
        );
    }

    private boolean isRunning(ModelSyncRunRow run) {
        return run != null && "running".equals(run.getStatus())
                && run.getStartedAt().isAfter(Instant.now().minus(properties.lockTtl()));
    }

    private ModelSyncRunResponse toRun(ModelSyncRunRow row) {
        return new ModelSyncRunResponse(
                row.getId(), row.getTriggerType(), row.getStatus(), row.getUpstreamTotal(), row.getFetchedCount(),
                row.getInsertedCount(), row.getUpdatedCount(), row.getUnchangedCount(), row.getSkippedCount(),
                row.getGroupTotal(), row.getGroupInsertedCount(), row.getGroupUpdatedCount(),
                row.getGroupUnchangedCount(), row.getGroupStaleCount(),
                row.getErrorCode(), row.getErrorSummary(), row.getStartedAt(), row.getCompletedAt()
        );
    }

    private record PreparedSnapshot(
            List<SyncedRoutingGroupRow> groups,
            List<PreparedModel> models,
            int skipped,
            Instant seenAt
    ) {
    }

    private record PreparedModel(
            SyncedModelRow model,
            List<PreparedMembership> memberships,
            PreparedPricing pricing
    ) {
    }

    private record PreparedMembership(String sourceGroupId, UpstreamHealth health) {
    }

    /** 通过完整契约校验、已换算为平台积分的上游价格快照。 */
    private record PreparedPricing(
            int billingType,
            BigDecimal unitPrice,
            BigDecimal displayOriginalPrice,
            long inputTokenRatio,
            long outputTokenRatio,
            long audioInputTokenRatio,
            long audioOutputTokenRatio,
            long cachedInputTokenRatio,
            long cacheWrite5mTokenRatio,
            long cacheWrite1hTokenRatio,
            String chargeDesc,
            int contextTierMode,
            List<PreparedPricingRule> rules,
            List<PreparedContextTier> contextTiers,
            String sourceHash,
            Instant observedAt
    ) {
    }

    /** 上游数组顺序已转换成唯一 priority 的扁平条件规则。 */
    private record PreparedPricingRule(
            int priority,
            String name,
            Map<String, String> conditions,
            Integer billingType,
            BigDecimal unitPrice
    ) {
    }

    /** 上游包含上限已转换为本地左闭右开范围，0 倍率已在导入时解析为继承值。 */
    private record PreparedContextTier(
            int priority,
            long minInputTokens,
            Long maxInputTokens,
            long inputRatio,
            long outputRatio,
            long cachedInputRatio,
            long cacheWrite5mRatio,
            long cacheWrite1hRatio
    ) {
    }

    private record UpstreamHealth(
            Integer lastStatus,
            Integer successRate,
            int consecutiveFailures,
            String historyJson,
            Instant lastCheckedAt,
            Instant lastSuccessAt
    ) {
        private static UpstreamHealth empty() {
            return new UpstreamHealth(null, null, 0, "[]", null, null);
        }
    }

    private record ApplyCounts(int inserted, int updated, int unchanged, int sourceConflicts) {
    }

    /** 模型和服务分组在同一事务内落库后的独立统计结果。 */
    private record ApplyResult(ApplyCounts models, GroupApplyCounts groups) {
    }

    /** 服务分组统计只描述来源快照变化，不修改本地价格、权限或启停策略。 */
    private record GroupApplyCounts(int inserted, int updated, int unchanged, int stale) {
    }

    /** 服务分组本地标识用于随后建立模型归属，计数用于管理端展示。 */
    private record GroupApplyResult(Map<String, UUID> localIds, GroupApplyCounts counts) {
    }
}
