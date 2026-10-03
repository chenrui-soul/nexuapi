-- V38：为已确认的正式上游能力补齐渠道模型映射，使 Responses 和 Seedance 视频形成可调用闭环。
-- 不为占位地址的异步图片任务创建模型映射，也不猜测尚未确认的 Grok/其他视频上游模型名。

-- 同一供应商的 Responses 渠道复用 Chat 渠道已经验证的上游模型名、成本和安全配置。
INSERT INTO channel_models (
    id, channel_id, model_id, upstream_model, priority, weight,
    cost_input_price, cost_cached_input_price, cost_output_price,
    status, config
)
SELECT gen_random_uuid(), responses.id, chat_mapping.model_id, chat_mapping.upstream_model,
       chat_mapping.priority, chat_mapping.weight,
       chat_mapping.cost_input_price, chat_mapping.cost_cached_input_price,
       chat_mapping.cost_output_price, chat_mapping.status, chat_mapping.config
  FROM channels responses
  JOIN channels chat
    ON chat.supplier_id = responses.supplier_id
   AND chat.interface_code = 'openai_chat'
  JOIN channel_models chat_mapping ON chat_mapping.channel_id = chat.id
  JOIN model_interfaces mi ON mi.model_id = chat_mapping.model_id
  JOIN api_interfaces api ON api.id = mi.interface_id AND api.interface_code = 'openai_responses'
 WHERE responses.interface_code = 'openai_responses'
   AND NOT EXISTS (
       SELECT 1 FROM channel_models existing
        WHERE existing.channel_id = responses.id
          AND existing.model_id = chat_mapping.model_id
          AND lower(existing.upstream_model) = lower(chat_mapping.upstream_model)
   );

-- 当前正式视频上游已确认 Seedance 命名规则；提交和列表查询渠道均使用同一上游模型名。
INSERT INTO channel_models (
    id, channel_id, model_id, upstream_model, priority, weight,
    cost_input_price, cost_cached_input_price, cost_output_price,
    status, config
)
SELECT gen_random_uuid(), channel.id, model.id,
       'bytedance/' || model.public_name, 100, 100,
       0, 0, 0, 'active', '{}'::jsonb
  FROM channels channel
  JOIN ai_models model ON model.public_name IN ('seedance-2.0', 'seedance-2.5')
 WHERE channel.interface_code IN ('jimeng_video', 'openai_video_list')
   AND NOT EXISTS (
       SELECT 1 FROM channel_models existing
        WHERE existing.channel_id = channel.id AND existing.model_id = model.id
   );

COMMENT ON TABLE channel_models IS '供应商渠道与平台模型映射；upstream_model 是真实发送给该渠道的模型名称，禁止包含凭证。';
