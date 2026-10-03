-- 协议适配器目录由管理员维护；implementation_key 绑定后端已注册的实际代码实现。
CREATE TABLE IF NOT EXISTS gateway_adapters (
    id UUID PRIMARY KEY,
    adapter_key VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    capability_type VARCHAR(24) NOT NULL CHECK (capability_type IN ('text', 'image', 'video')),
    implementation_key VARCHAR(80) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_gateway_adapters_catalog
    ON gateway_adapters (capability_type, status, updated_at DESC);

INSERT INTO gateway_adapters (
    id, adapter_key, display_name, capability_type, implementation_key, description, built_in
) VALUES
    ('a0000000-0000-4000-8000-000000000001', 'openai_compatible_text', 'OpenAI 兼容文本', 'text', 'openai_compatible_text', 'Chat Completions 与 Responses 文本协议。', TRUE),
    ('a0000000-0000-4000-8000-000000000002', 'openai_compatible_image', 'OpenAI 兼容图片', 'image', 'openai_compatible_image', '图片生成与异步图片任务 JSON / multipart 协议。', TRUE),
    ('a0000000-0000-4000-8000-000000000003', 'openai_compatible_video', 'OpenAI 兼容视频', 'video', 'openai_compatible_video', '视频任务创建、列表和详情协议。', TRUE),
    ('a0000000-0000-4000-8000-000000000004', 'jimeng_video', '即梦视频', 'video', 'jimeng_video', '即梦视频适配器，当前实现沿用统一视频协议。', TRUE),
    ('a0000000-0000-4000-8000-000000000005', 'grok_video', 'Grok 视频', 'video', 'grok_video', 'Grok 视频适配器，当前实现沿用统一视频协议。', TRUE),
    ('a0000000-0000-4000-8000-000000000006', 'minimax_h3_video', 'MiniMax H3 视频', 'video', 'minimax_h3_video', 'MiniMax H3 content / duration / ratio 协议。', TRUE)
ON CONFLICT (adapter_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    capability_type = EXCLUDED.capability_type,
    implementation_key = EXCLUDED.implementation_key,
    description = EXCLUDED.description,
    built_in = TRUE,
    updated_at = now();

ALTER TABLE ai_models DROP CONSTRAINT IF EXISTS ai_models_adapter_key_valid;

