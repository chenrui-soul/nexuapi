-- V45 回滚：恢复迁移前的图片接口文档数据。
-- 注意：grouk_image 是已确认的无效文档，仅在确需完整回退 V45 时恢复。
UPDATE api_interfaces
   SET response_schema = '{"fields": []}'::jsonb,
       description = 'OpenAI 兼容图片生成接口；当前请求字段依据 grok-imagine-image-2.0 上游文档维护，返回字段等待真实响应确认后补充。',
       updated_at = now(),
       version = version + 1
 WHERE interface_code = 'openai_images'
   AND http_method = 'POST'
   AND public_path = '/v1/images/generations';

INSERT INTO api_interfaces (
    interface_code, interface_name, interface_version, capability_type, transport_mode,
    http_method, public_path, request_content_type, request_schema, response_schema,
    description, status, version
) VALUES (
    'grouk_image', 'grouk接口_图片生成', 'v1', 'image', 'sync', 'POST', '/v1/',
    'application/json',
    '{"fields":[{"name":"aspect_ratio","path":"aspect_ratio","type":"string","children":[],"required":false,"sensitive":false,"deprecated":false,"description":"默认 \"auto\" · 可选 \"auto\" / \"1:1\" / \"3:4\" / \"4:3\" / \"9:16\" / \"16:9\" / \"2:3\" / \"3:2\" / \"9:19.5\" / \"19.5:9\" / \"9:20\" / \"20:9\" / \"1:2\" / \"2:1\" · 输出图片的宽高比。auto 表示由模型自动选择。","enum_values":[]},{"name":"images","path":"images","type":"string","children":[],"required":false,"sensitive":false,"deprecated":false,"description":"可选参考图片，用于图片编辑或参考图生成。当前模型最多支持 5 张","enum_values":[]},{"name":"n","path":"n","type":"integer","children":[],"required":false,"sensitive":false,"deprecated":false,"description":"默认 1 · 范围 1 – 10 · 步长 1 · 生成图片数量，范围为 1～10。","enum_values":[]},{"name":"prompt","path":"prompt","type":"string","children":[],"required":false,"sensitive":false,"deprecated":false,"description":"图片生成或编辑提示词。","enum_values":[]},{"name":"quality","path":"quality","type":"string","children":[],"required":false,"sensitive":false,"deprecated":false,"description":"默认 \"medium\" · 可选 \"low\" / \"medium\" · 图片生成质量。medium 质量更高，通常耗时也更长。","enum_values":[]},{"name":"resolution","path":"resolution","type":"string","children":[],"required":false,"sensitive":false,"deprecated":false,"description":"可选 \"1k\" / \"2k\" · 输出图片分辨率档位。","enum_values":[]}]}'::jsonb,
    '{"fields":[{"name":"m","path":"12121212","type":"string","children":[],"required":false,"sensitive":false,"deprecated":false,"description":"","enum_values":[]}]}'::jsonb,
    NULL, 'active', 0
)
ON CONFLICT (interface_code) DO NOTHING;
