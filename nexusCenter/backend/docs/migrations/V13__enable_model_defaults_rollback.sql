-- 回滚只恢复字段默认值；V13 已统一更新的模型数据不会自动反向恢复，避免猜测原始人工配置。
ALTER TABLE ai_models
    ALTER COLUMN supports_streaming SET DEFAULT false,
    ALTER COLUMN supports_tools SET DEFAULT false,
    ALTER COLUMN supports_structured_output SET DEFAULT false,
    ALTER COLUMN public_visible SET DEFAULT true,
    ALTER COLUMN status SET DEFAULT 'active';
