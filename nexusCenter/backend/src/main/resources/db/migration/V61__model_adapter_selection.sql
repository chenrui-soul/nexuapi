-- 将协议适配器归属提升到模型维护，渠道模型映射只保留上游模型和路由参数。
ALTER TABLE ai_models
    ADD COLUMN adapter_key VARCHAR(80);

UPDATE ai_models
   SET adapter_key = CASE capability_type
       WHEN 'image' THEN 'openai_compatible_image'
       WHEN 'video' THEN 'openai_compatible_video'
       ELSE 'openai_compatible_text'
   END
 WHERE adapter_key IS NULL;

ALTER TABLE ai_models
    ALTER COLUMN adapter_key SET DEFAULT 'openai_compatible_text',
    ALTER COLUMN adapter_key SET NOT NULL;

ALTER TABLE ai_models
    ADD CONSTRAINT ai_models_adapter_key_valid CHECK (
        adapter_key IN (
            'openai_compatible_text', 'openai_compatible_image',
            'openai_compatible_video', 'jimeng_video', 'grok_video', 'minimax_h3_video'
        )
    );

UPDATE channel_models
   SET config = config - 'adapter_key'
 WHERE config ? 'adapter_key';