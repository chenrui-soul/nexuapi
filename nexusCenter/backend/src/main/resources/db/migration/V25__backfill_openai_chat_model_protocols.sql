-- 协议执行链接入前，现有文本模型已经通过 OpenAI Chat Completions 入口提供服务。
-- 为这些存量模型补齐显式协议关联，避免升级后因缺少 model_protocols 配置而拒绝调用。

INSERT INTO model_protocols (
    id,
    model_id,
    protocol_definition_id,
    enabled,
    is_default
)
SELECT
    gen_random_uuid(),
    model.id,
    protocol.id,
    true,
    false
FROM ai_models model
JOIN protocol_definitions protocol
  ON protocol.protocol_code = 'openai_chat'
WHERE model.capability_type = 'text'
  AND model.status = 'active'
ON CONFLICT (model_id, protocol_definition_id) DO NOTHING;
