-- 特定模型时段加价规则：规则按星期和时间循环生效，不设置起止日期。
-- 请求进入 Gateway 时只匹配一次，最终倍率快照写入 request_billing_details。

CREATE TABLE billing_time_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    multiplier NUMERIC(20, 10) NOT NULL,
    days_of_week SMALLINT[] NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT billing_time_rules_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT billing_time_rules_multiplier_valid CHECK (multiplier >= 1 AND multiplier <= 100),
    CONSTRAINT billing_time_rules_days_valid CHECK (
        cardinality(days_of_week) BETWEEN 1 AND 7
        AND days_of_week <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::SMALLINT[]
    ),
    CONSTRAINT billing_time_rules_time_range_valid CHECK (start_time <> end_time)
);

CREATE TABLE billing_time_rule_models (
    rule_id UUID NOT NULL REFERENCES billing_time_rules(id) ON DELETE CASCADE,
    model_id UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, model_id)
);

CREATE INDEX idx_billing_time_rule_models_model
    ON billing_time_rule_models (model_id, rule_id);

ALTER TABLE request_billing_details
    ADD COLUMN time_rule_id UUID REFERENCES billing_time_rules(id) ON DELETE SET NULL,
    ADD COLUMN time_rule_name VARCHAR(120),
    ADD COLUMN time_multiplier NUMERIC(20, 10) NOT NULL DEFAULT 1,
    ADD COLUMN pricing_time TIMESTAMPTZ,
    ADD COLUMN base_usage_amount NUMERIC(30, 12) NOT NULL DEFAULT 0,
    ADD CONSTRAINT request_billing_details_time_multiplier_valid CHECK (time_multiplier > 0),
    ADD CONSTRAINT request_billing_details_base_usage_amount_nonnegative CHECK (base_usage_amount >= 0);

COMMENT ON TABLE billing_time_rules IS '管理员为特定模型配置的循环时段加价规则；只影响用户售价，不影响供应商成本';
COMMENT ON COLUMN billing_time_rules.id IS '时段加价规则唯一标识';
COMMENT ON COLUMN billing_time_rules.name IS '管理员可识别的时段规则名称';
COMMENT ON COLUMN billing_time_rules.multiplier IS '模型基础用量积分的时段倍率；1 表示不加价，最多保留 10 位小数';
COMMENT ON COLUMN billing_time_rules.days_of_week IS '适用星期，ISO 规则：1 周一至 7 周日；跨天时表示时段开始所在星期';
COMMENT ON COLUMN billing_time_rules.start_time IS '每天生效开始时间，使用 Asia/Shanghai 业务时区';
COMMENT ON COLUMN billing_time_rules.end_time IS '每天生效结束时间；小于开始时间表示跨到次日';
COMMENT ON COLUMN billing_time_rules.enabled IS '是否允许新请求匹配该规则；停用不影响已进入平台的请求快照';
COMMENT ON COLUMN billing_time_rules.created_by IS '创建规则的管理员用户标识；管理员删除后保留规则';
COMMENT ON COLUMN billing_time_rules.created_at IS '规则创建时间';
COMMENT ON COLUMN billing_time_rules.updated_at IS '规则最后更新时间';
COMMENT ON COLUMN billing_time_rules.version IS '乐观锁版本号，防止多个管理员相互覆盖';

COMMENT ON TABLE billing_time_rule_models IS '时段加价规则与模型的多对多关联；一个规则可同时应用于多个模型';
COMMENT ON COLUMN billing_time_rule_models.rule_id IS '关联的时段加价规则标识';
COMMENT ON COLUMN billing_time_rule_models.model_id IS '关联的 AI 模型标识，必须使用 ai_models.id';
COMMENT ON COLUMN billing_time_rule_models.created_at IS '模型加入规则的时间';

COMMENT ON COLUMN request_billing_details.time_rule_id IS '请求进入平台时命中的时段规则标识；规则删除后可为空';
COMMENT ON COLUMN request_billing_details.time_rule_name IS '请求进入平台时命中的时段规则名称快照';
COMMENT ON COLUMN request_billing_details.time_multiplier IS '请求全生命周期固定使用的时段倍率快照';
COMMENT ON COLUMN request_billing_details.pricing_time IS '匹配时段倍率的请求进入时间点';
COMMENT ON COLUMN request_billing_details.base_usage_amount IS '模型基础用量积分，不包含时段倍率和服务分组倍率';

