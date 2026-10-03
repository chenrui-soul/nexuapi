-- 为计费 V2 新增表补齐表级和字段级中文说明。
-- 独立迁移避免修改已发布 V18 的校验和，便于正式环境安全升级。

COMMENT ON TABLE model_pricing_versions IS '模型平台销售价格的不可变版本；历史请求必须引用调用时版本';
COMMENT ON TABLE model_pricing_rules IS '图片质量、尺寸、视频分辨率等条件价格规则，按 priority 从小到大匹配';
COMMENT ON TABLE model_context_tiers IS '文本模型长上下文价格分档，可按整次或分段模式计算';
COMMENT ON TABLE supplier_model_prices IS '上游供应商成本价格快照，只用于成本、毛利和溯源，不参与用户售价';
COMMENT ON TABLE request_billing_details IS '一次请求采用的价格版本、规则、用量、倍率和金额快照，不保存请求正文或凭证';

COMMENT ON COLUMN model_pricing_versions.id IS '价格版本主键 UUID';
COMMENT ON COLUMN model_pricing_versions.model_id IS '所属平台模型 ID';
COMMENT ON COLUMN model_pricing_versions.version_no IS '模型内单调递增的价格版本号';
COMMENT ON COLUMN model_pricing_versions.billing_type IS '计费类型：1 按次，2 按数量，3 按秒，4 按 Token，5 按字符';
COMMENT ON COLUMN model_pricing_versions.unit_price IS '该版本的平台销售基准积分单价';
COMMENT ON COLUMN model_pricing_versions.display_original_price IS '仅用于划线展示的参考原价，永不参与计费';
COMMENT ON COLUMN model_pricing_versions.input_token_ratio IS '输入 Token 倍率，万分位';
COMMENT ON COLUMN model_pricing_versions.output_token_ratio IS '输出 Token 倍率，万分位';
COMMENT ON COLUMN model_pricing_versions.cached_input_token_ratio IS '缓存命中输入 Token 倍率，万分位';
COMMENT ON COLUMN model_pricing_versions.cache_write_5m_token_ratio IS '5 分钟缓存写入倍率，万分位；0 使用默认规则';
COMMENT ON COLUMN model_pricing_versions.cache_write_1h_token_ratio IS '1 小时缓存写入倍率，万分位；0 使用默认规则';
COMMENT ON COLUMN model_pricing_versions.charge_desc IS '展示给用户的计费说明';
COMMENT ON COLUMN model_pricing_versions.context_tier_mode IS '长上下文分档模式：0/1 整次命中，2 分段累加';
COMMENT ON COLUMN model_pricing_versions.unmatched_behavior IS '条件规则未命中时使用基础价或拒绝请求';
COMMENT ON COLUMN model_pricing_versions.change_note IS '管理员填写的版本变更说明';
COMMENT ON COLUMN model_pricing_versions.created_by IS '发布该价格版本的管理员用户 ID';
COMMENT ON COLUMN model_pricing_versions.created_at IS '价格版本发布时间';

COMMENT ON COLUMN model_pricing_rules.id IS '条件计价规则主键 UUID';
COMMENT ON COLUMN model_pricing_rules.pricing_version_id IS '所属不可变价格版本 ID';
COMMENT ON COLUMN model_pricing_rules.priority IS '规则匹配优先级，数值越小越先匹配';
COMMENT ON COLUMN model_pricing_rules.name IS '管理员可读的规则名称';
COMMENT ON COLUMN model_pricing_rules.match_conditions IS '允许参与匹配的扁平业务参数 JSON，不得包含凭证';
COMMENT ON COLUMN model_pricing_rules.billing_type IS '命中后覆盖的计费类型；为空沿用版本基础类型';
COMMENT ON COLUMN model_pricing_rules.unit_price IS '命中后覆盖的积分单价；为空沿用版本基准价';
COMMENT ON COLUMN model_pricing_rules.price_multiplier IS '命中规则后叠加的价格倍率';
COMMENT ON COLUMN model_pricing_rules.created_at IS '条件计价规则创建时间';

COMMENT ON COLUMN model_context_tiers.id IS '长上下文分档主键 UUID';
COMMENT ON COLUMN model_context_tiers.pricing_version_id IS '所属不可变价格版本 ID';
COMMENT ON COLUMN model_context_tiers.priority IS '同起点分档的匹配优先级';
COMMENT ON COLUMN model_context_tiers.min_input_tokens IS '本分档包含的最小输入 Token 数';
COMMENT ON COLUMN model_context_tiers.max_input_tokens IS '本分档不包含的最大输入 Token 数；为空表示无上限';
COMMENT ON COLUMN model_context_tiers.input_ratio IS '本分档输入 Token 倍率，万分位';
COMMENT ON COLUMN model_context_tiers.output_ratio IS '本分档输出 Token 倍率，万分位';
COMMENT ON COLUMN model_context_tiers.cached_input_ratio IS '本分档缓存命中输入 Token 倍率，万分位';
COMMENT ON COLUMN model_context_tiers.created_at IS '长上下文分档创建时间';

COMMENT ON COLUMN supplier_model_prices.id IS '供应商模型成本快照主键 UUID';
COMMENT ON COLUMN supplier_model_prices.supplier_id IS '成本所属供应商 ID';
COMMENT ON COLUMN supplier_model_prices.model_id IS '成本对应的平台模型 ID';
COMMENT ON COLUMN supplier_model_prices.source_version IS '上游价格数据的版本或溯源标识';
COMMENT ON COLUMN supplier_model_prices.billing_type IS '上游成本计费类型';
COMMENT ON COLUMN supplier_model_prices.unit_price IS '上游成本基准单价，不参与用户售价';
COMMENT ON COLUMN supplier_model_prices.currency IS '上游成本结算币种或积分单位';
COMMENT ON COLUMN supplier_model_prices.raw_pricing IS '脱敏后的上游原始价格结构 JSON';
COMMENT ON COLUMN supplier_model_prices.observed_at IS '本次上游价格被观测到的时间';
COMMENT ON COLUMN supplier_model_prices.created_at IS '供应商模型成本快照创建时间';

COMMENT ON COLUMN request_billing_details.id IS '请求计费明细主键 UUID';
COMMENT ON COLUMN request_billing_details.request_id IS '平台全链路请求幂等编号';
COMMENT ON COLUMN request_billing_details.user_id IS '发起请求的用户 ID';
COMMENT ON COLUMN request_billing_details.api_key_id IS '本次调用使用的用户 API 令牌 ID';
COMMENT ON COLUMN request_billing_details.model_id IS '本次调用的平台模型 ID';
COMMENT ON COLUMN request_billing_details.group_id IS 'API 令牌绑定的服务分组 ID';
COMMENT ON COLUMN request_billing_details.pricing_version_id IS '请求开始时锁定的价格版本 ID';
COMMENT ON COLUMN request_billing_details.matched_rule_id IS '本次请求命中的条件计价规则 ID';
COMMENT ON COLUMN request_billing_details.context_tier_id IS '本次请求命中的长上下文分档 ID';
COMMENT ON COLUMN request_billing_details.engine_mode IS '计费引擎模式：v1、兼容回退、影子或 v2';
COMMENT ON COLUMN request_billing_details.billing_type IS '本次请求实际采用的计费类型';
COMMENT ON COLUMN request_billing_details.base_unit_price IS '模型价格版本中的销售基准积分单价';
COMMENT ON COLUMN request_billing_details.effective_unit_price IS '叠加条件规则和服务分组后的有效积分单价';
COMMENT ON COLUMN request_billing_details.input_token_ratio IS '本次请求采用的输入 Token 倍率，万分位';
COMMENT ON COLUMN request_billing_details.output_token_ratio IS '本次请求采用的输出 Token 倍率，万分位';
COMMENT ON COLUMN request_billing_details.cached_input_token_ratio IS '本次请求采用的缓存命中倍率，万分位';
COMMENT ON COLUMN request_billing_details.group_multiplier IS '请求所属服务分组的用户售价倍率';
COMMENT ON COLUMN request_billing_details.usage_snapshot IS '仅包含计费所需数量、时长、字符或 Token 的脱敏用量快照';
COMMENT ON COLUMN request_billing_details.calculation_snapshot IS '不含正文和凭证的计费公式参数快照';
COMMENT ON COLUMN request_billing_details.legacy_amount IS '影子模式或回退模式计算出的旧版金额';
COMMENT ON COLUMN request_billing_details.calculated_amount IS '当前计费引擎计算出的应结算金额';
COMMENT ON COLUMN request_billing_details.settled_amount IS '最终写入资金账本的实际结算金额';
COMMENT ON COLUMN request_billing_details.status IS '计费明细状态：已结算、已释放或失败';
COMMENT ON COLUMN request_billing_details.created_at IS '请求计费明细创建时间';
