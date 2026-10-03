-- V36：补齐用户开放能力接口，并将供应商渠道明确绑定到对应 api_interfaces。
-- 路由最终依据“模型支持接口 + 服务分组供应商渠道 + 渠道接口归属”选择上游，避免同能力不同接口串线。

ALTER TABLE channels
    ADD COLUMN interface_code VARCHAR(80);

COMMENT ON COLUMN channels.interface_code IS '该供应商渠道对应的平台接口编码；用于区分同一能力下的 Chat、Responses、视频提交/查询等不同上游接口。';
CREATE INDEX idx_channels_interface_route ON channels (interface_code, request_method, endpoint_type, status);

DO $migration$
DECLARE
    interface_count INTEGER;
BEGIN
    INSERT INTO api_interfaces (
        interface_code, interface_name, interface_version, capability_type, transport_mode,
        http_method, public_path, request_content_type, request_schema, response_schema,
        description, status
    ) VALUES
    (
        'openai_responses', 'OpenAI Responses', 'v1', 'text', 'sync', 'POST', '/v1/responses',
        'application/json',
        '{"fields":[{"name":"model","path":"model","type":"string","required":true,"description":"平台公开模型名称。","children":[]},{"name":"input","path":"input","type":"object|array|string","required":true,"description":"Responses 输入，可为文本、消息数组或多模态内容。","children":[]},{"name":"instructions","path":"instructions","type":"string","required":false,"description":"系统级指令。","children":[]},{"name":"stream","path":"stream","type":"boolean","required":false,"description":"是否以事件流返回。","default_value":false,"children":[]},{"name":"max_output_tokens","path":"max_output_tokens","type":"integer","required":false,"description":"最大输出 Token 数。","children":[]}]}'::jsonb,
        '{"fields":[{"name":"id","path":"id","type":"string","required":true,"description":"Responses 响应 ID。","children":[]},{"name":"object","path":"object","type":"string","required":false,"description":"响应对象类型。","children":[]},{"name":"status","path":"status","type":"string","required":false,"description":"响应状态。","children":[]},{"name":"output","path":"output","type":"array","required":false,"description":"模型输出内容。","children":[]},{"name":"usage","path":"usage","type":"object","required":false,"description":"Token 用量。","children":[]}]}'::jsonb,
        'OpenAI Responses 文本和多模态响应接口；请求与返回字段按上游 JSON 原样转发。', 'active'
    ),
    (
        'openai_video_list', 'OpenAI Videos List', 'v1', 'video', 'sync', 'GET', '/v1/videos',
        'application/json',
        '{"fields":[{"name":"model","path":"model","type":"string","required":false,"description":"用于选择视频查询渠道的模型名称；不填写时使用当前分组首个可用视频模型。","children":[]},{"name":"limit","path":"limit","type":"integer","required":false,"description":"返回任务数量上限。","children":[]},{"name":"after","path":"after","type":"string","required":false,"description":"分页游标。","children":[]}]}'::jsonb,
        '{"fields":[{"name":"object","path":"object","type":"string","required":false,"description":"列表对象类型。","children":[]},{"name":"data","path":"data","type":"array","required":true,"description":"视频任务列表。","children":[]},{"name":"has_more","path":"has_more","type":"boolean","required":false,"description":"是否还有下一页。","children":[]}]}'::jsonb,
        '视频任务列表/查询接口；平台只返回上游 JSON，不暴露供应商凭证和内部路由。', 'active'
    ),
    (
        'openai_image_tasks', 'OpenAI Image Tasks', 'v1', 'image', 'async_poll', 'POST', '/v1/images/tasks',
        'application/json',
        '{"fields":[{"name":"model","path":"model","type":"string","required":true,"description":"平台公开图片模型名称。","children":[]},{"name":"prompt","path":"prompt","type":"string","required":true,"description":"图片生成提示词。","children":[]},{"name":"input","path":"input","type":"array","required":false,"description":"异步人物/参考图输入。","children":[]}]}'::jsonb,
        '{"fields":[{"name":"id","path":"id","type":"string","required":true,"description":"平台任务 ID。","children":[]},{"name":"status","path":"status","type":"string","required":true,"description":"任务状态。","children":[]},{"name":"data","path":"data","type":"array","required":false,"description":"完成后的图片结果。","children":[]},{"name":"error","path":"error","type":"object","required":false,"description":"失败原因。","children":[]}]}'::jsonb,
        '异步图片任务提交接口；平台任务 ID 由网关生成并映射到上游任务。', 'active'
    )
    ON CONFLICT (interface_code) DO UPDATE SET status = 'active', updated_at = now();

    SELECT count(*) INTO interface_count
      FROM api_interfaces
     WHERE interface_code IN ('openai_responses','openai_video_list','openai_image_tasks')
       AND status = 'active';
    IF interface_count <> 3 THEN
        RAISE EXCEPTION 'V36 expected 3 public capability interfaces, found %', interface_count;
    END IF;
END
$migration$;

-- 既有正式渠道按已维护的名称/上游路径回填；无法判断的渠道保持 NULL，兼容旧测试夹具。
UPDATE channels
   SET interface_code = CASE
       WHEN base_url LIKE '%/chat/completions' OR lower(name) LIKE '%chat%' THEN 'openai_chat'
       WHEN base_url LIKE '%/responses' OR lower(name) LIKE '%response%' THEN 'openai_responses'
       WHEN base_url LIKE '%/images/generations' OR lower(name) LIKE '%图片生成%' THEN 'openai_images'
       WHEN base_url LIKE '%/images/tasks' OR lower(name) LIKE '%异步图片%' THEN 'openai_image_tasks'
       WHEN endpoint_type = 'video' AND request_method = 'GET' THEN 'openai_video_list'
       WHEN endpoint_type = 'video' AND request_method = 'POST' THEN
            CASE WHEN lower(name) LIKE '%grok%' THEN 'grok_video' ELSE 'jimeng_video' END
       ELSE interface_code
   END
 WHERE interface_code IS NULL;

-- 模型支持关系由模型能力自动补齐到本轮开放入口；管理员后续仍可在模型维护页增删具体关系。
INSERT INTO model_interfaces (model_id, interface_id)
SELECT m.id, i.id
  FROM ai_models m
  CROSS JOIN api_interfaces i
 WHERE i.interface_code = 'openai_responses'
   AND m.capability_type IN ('text', 'multimodal')
ON CONFLICT DO NOTHING;
INSERT INTO model_interfaces (model_id, interface_id)
SELECT m.id, i.id
  FROM ai_models m
  CROSS JOIN api_interfaces i
 WHERE i.interface_code = 'openai_video_list'
   AND m.capability_type = 'video'
ON CONFLICT DO NOTHING;
INSERT INTO model_interfaces (model_id, interface_id)
SELECT m.id, i.id
  FROM ai_models m
  CROSS JOIN api_interfaces i
 WHERE i.interface_code = 'openai_image_tasks'
   AND m.capability_type = 'image'
ON CONFLICT DO NOTHING;

COMMENT ON TABLE channels IS '供应商上游渠道配置表；interface_code 明确渠道对应的平台接口，凭证加密保存。';
