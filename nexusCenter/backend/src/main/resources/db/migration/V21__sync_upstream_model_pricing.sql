-- 上游模型价格同步：将“模型资料是否跟随上游”和“平台售价是否跟随上游”拆成两个独立开关。
-- 价格版本仍保持不可变；同步只创建新版本并移动当前指针，不覆盖历史请求引用的旧版本。

ALTER TABLE ai_models
    ADD COLUMN pricing_source_managed BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN pricing_source_hash VARCHAR(64),
    ADD COLUMN pricing_source_synced_at TIMESTAMPTZ;

-- 已由菜菜模型市场托管、且尚未发布人工价格版本的模型，升级后默认开始跟随上游价格。
-- 已存在生效价格版本的模型保持人工模式，避免迁移自动改变管理员已确认的销售价格。
UPDATE ai_models
   SET pricing_source_managed = true
 WHERE sync_source = 'caicai_market'
   AND active_pricing_version_id IS NULL;

ALTER TABLE ai_models
    ADD CONSTRAINT ai_models_pricing_source_hash_valid CHECK (
        pricing_source_hash IS NULL OR pricing_source_hash ~ '^[0-9a-f]{64}$'
    );

ALTER TABLE model_pricing_versions
    ADD COLUMN source_type VARCHAR(64) NOT NULL DEFAULT 'manual',
    ADD COLUMN source_hash VARCHAR(64),
    ADD COLUMN source_observed_at TIMESTAMPTZ;

ALTER TABLE model_pricing_versions
    ADD CONSTRAINT model_pricing_versions_source_type_valid CHECK (length(btrim(source_type)) > 0),
    ADD CONSTRAINT model_pricing_versions_source_hash_valid CHECK (
        source_hash IS NULL OR source_hash ~ '^[0-9a-f]{64}$'
    );

-- 同一模型、同一上游来源、同一有效价格快照只允许保存一个版本，防止定时任务重复建版。
CREATE UNIQUE INDEX uq_model_pricing_versions_source_hash
    ON model_pricing_versions (model_id, source_type, source_hash)
    WHERE source_hash IS NOT NULL;

ALTER TABLE model_context_tiers
    ADD COLUMN cache_write_5m_ratio BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cache_write_1h_ratio BIGINT NOT NULL DEFAULT 0;

ALTER TABLE model_context_tiers
    ADD CONSTRAINT model_context_tiers_cache_ratios_nonnegative CHECK (
        cache_write_5m_ratio >= 0 AND cache_write_1h_ratio >= 0
    );

COMMENT ON COLUMN ai_models.pricing_source_managed IS '平台售价是否自动跟随上游模型市场；false 表示人工定价且同步不得覆盖';
COMMENT ON COLUMN ai_models.pricing_source_hash IS '最近一次已激活上游价格快照的 SHA-256；用于幂等去重，不包含凭证';
COMMENT ON COLUMN ai_models.pricing_source_synced_at IS '最近一次激活上游价格版本的时间';

COMMENT ON COLUMN model_pricing_versions.source_type IS '价格版本来源；manual 表示管理员发布，caicai_market 表示上游模型市场同步';
COMMENT ON COLUMN model_pricing_versions.source_hash IS '上游有效价格快照 SHA-256；人工版本为空';
COMMENT ON COLUMN model_pricing_versions.source_observed_at IS '上游价格快照被完整观测到的时间；人工版本为空';

COMMENT ON COLUMN model_context_tiers.cache_write_5m_ratio IS '本上下文分档的 5 分钟缓存写入倍率，万分位；0 表示沿用版本级默认规则';
COMMENT ON COLUMN model_context_tiers.cache_write_1h_ratio IS '本上下文分档的 1 小时缓存写入倍率，万分位；0 表示沿用版本级默认规则';
