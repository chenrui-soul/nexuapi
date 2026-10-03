-- Wave 5：为管理员配置模型、渠道、分组和价格增加乐观锁及凭证轮换元数据。
-- 旧迁移保持不可变，本文件只做向前兼容的增量扩展。

ALTER TABLE ai_models
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE channels
    ADD COLUMN credential_fingerprint VARCHAR(16),
    ADD COLUMN credential_updated_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE channel_models
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE routing_groups
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE group_routes
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- NOT VALID 不阻塞可能存在的历史配置，但会立即约束所有新写入和更新数据。
ALTER TABLE ai_models
    ADD CONSTRAINT ai_models_prices_non_negative
        CHECK (input_price >= 0 AND output_price >= 0 AND cached_input_price >= 0) NOT VALID;

ALTER TABLE channel_models
    ADD CONSTRAINT channel_models_costs_non_negative
        CHECK (cost_input_price >= 0 AND cost_output_price >= 0) NOT VALID;

ALTER TABLE channels
    ADD CONSTRAINT channels_priority_non_negative CHECK (priority >= 0) NOT VALID,
    ADD CONSTRAINT channels_concurrency_positive
        CHECK (concurrency_limit IS NULL OR concurrency_limit > 0) NOT VALID;

ALTER TABLE channel_models
    ADD CONSTRAINT channel_models_priority_non_negative CHECK (priority >= 0) NOT VALID;

ALTER TABLE group_routes
    ADD CONSTRAINT group_routes_priority_non_negative CHECK (priority >= 0) NOT VALID;

CREATE INDEX idx_ai_models_admin_list ON ai_models (status, updated_at DESC);
CREATE INDEX idx_channels_admin_list ON channels (status, updated_at DESC);
CREATE INDEX idx_routing_groups_admin_list ON routing_groups (status, updated_at DESC);

