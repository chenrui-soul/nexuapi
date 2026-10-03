-- V41：补齐音频合成、音频转写和向量接口文档，并建立已确认模型的接口支持关系。
-- Speech 与 Transcriptions 同属 Audio Controller，但请求内容、响应格式和计费口径保持独立。

INSERT INTO api_interfaces (
    interface_code, interface_name, interface_version, capability_type, transport_mode,
    http_method, public_path, request_content_type, request_schema, response_schema,
    description, status
) VALUES
(
    'audio', 'OpenAI Audio Speech', 'v1', 'audio', 'sync', 'POST', '/v1/audio/speech',
    'application/json',
    '{"fields":[
      {"name":"model","path":"model","type":"string","required":true,"description":"平台公开的语音合成模型名称。","children":[]},
      {"name":"input","path":"input","type":"string","required":true,"description":"需要转换为语音的文本；tts-1 和 tts-1-hd 按 Unicode 字符数计费。","children":[]},
      {"name":"voice","path":"voice","type":"string","required":true,"description":"上游支持的音色名称。","children":[]},
      {"name":"response_format","path":"response_format","type":"string","required":false,"default_value":"mp3","enum_values":["mp3","opus","aac","flac","wav","pcm"],"description":"返回音频格式。","children":[]},
      {"name":"speed","path":"speed","type":"number","required":false,"default_value":1,"minimum":0.25,"maximum":4,"description":"语音播放速度。","children":[]}
    ]}'::jsonb,
    '{"fields":[
      {"name":"audio","path":"body","type":"file","required":true,"description":"按 response_format 返回的二进制音频正文。","children":[]}
    ]}'::jsonb,
    'OpenAI 兼容语音合成接口；只关联 TTS 模型，返回二进制音频并按输入字符数结算。',
    'active'
),
(
    'audio_transcription', 'OpenAI Audio Transcriptions', 'v1', 'audio', 'sync', 'POST',
    '/v1/audio/transcriptions', 'multipart/form-data',
    '{"fields":[
      {"name":"file","path":"file","type":"file","required":true,"description":"待识别音频文件，支持 mp3、mp4、mpeg、mpga、m4a、wav、webm，单文件最大 20MB。","children":[]},
      {"name":"model","path":"model","type":"string","required":true,"description":"平台公开的音频转写模型名称。","children":[]},
      {"name":"language","path":"language","type":"string","required":false,"description":"输入音频的语言代码。","children":[]},
      {"name":"prompt","path":"prompt","type":"string","required":false,"description":"用于改善专有名词和上下文识别的提示文本。","children":[]},
      {"name":"response_format","path":"response_format","type":"string","required":false,"default_value":"json","enum_values":["json","verbose_json","text","srt","vtt"],"description":"客户端需要的转写结果格式。","children":[]},
      {"name":"temperature","path":"temperature","type":"number","required":false,"minimum":0,"maximum":1,"description":"采样温度。","children":[]},
      {"name":"timestamp_granularities[]","path":"timestamp_granularities[]","type":"array","required":false,"enum_values":["segment","word"],"description":"verbose_json 时间戳粒度。","children":[]}
    ]}'::jsonb,
    '{"fields":[
      {"name":"text","path":"text","type":"string","required":true,"description":"识别出的完整文本。","children":[]},
      {"name":"language","path":"language","type":"string","required":false,"description":"上游识别出的语言。","children":[]},
      {"name":"duration","path":"duration","type":"number","required":false,"description":"实际识别音频时长，单位为秒；用于平台结算。","children":[]},
      {"name":"segments","path":"segments","type":"array","required":false,"description":"分段时间戳和文本。","children":[]},
      {"name":"words","path":"words","type":"array","required":false,"description":"单词级时间戳。","children":[]}
    ]}'::jsonb,
    'OpenAI 兼容音频转写接口；当前关联 whisper-1，按上游实际返回的音频秒数结算。',
    'active'
),
(
    'embedding', 'OpenAI Embeddings', 'v1', 'embedding', 'sync', 'POST', '/v1/embeddings',
    'application/json',
    '{"fields":[
      {"name":"model","path":"model","type":"string","required":true,"description":"平台公开的向量模型名称。","children":[]},
      {"name":"input","path":"input","type":"string|array","required":true,"description":"单个文本或非空文本数组。","children":[]},
      {"name":"encoding_format","path":"encoding_format","type":"string","required":false,"default_value":"float","enum_values":["float","base64"],"description":"向量编码格式。","children":[]},
      {"name":"dimensions","path":"dimensions","type":"integer","required":false,"minimum":1,"description":"支持该参数的模型输出向量维度。","children":[]},
      {"name":"user","path":"user","type":"string","required":false,"description":"客户端最终用户稳定标识。","children":[]}
    ]}'::jsonb,
    '{"fields":[
      {"name":"object","path":"object","type":"string","required":true,"description":"固定为 list。","children":[]},
      {"name":"data","path":"data","type":"array","required":true,"description":"向量结果列表。","children":[]},
      {"name":"model","path":"model","type":"string","required":true,"description":"平台公开模型名称。","children":[]},
      {"name":"usage","path":"usage","type":"object","required":true,"description":"输入和总 Token 用量，用于平台结算。","children":[]}
    ]}'::jsonb,
    'OpenAI 兼容向量接口；按请求接口与模型支持关系路由，并按 usage Token 结算。',
    'active'
)
ON CONFLICT (interface_code) DO UPDATE SET
    interface_name = EXCLUDED.interface_name,
    interface_version = EXCLUDED.interface_version,
    capability_type = EXCLUDED.capability_type,
    transport_mode = EXCLUDED.transport_mode,
    http_method = EXCLUDED.http_method,
    public_path = EXCLUDED.public_path,
    request_content_type = EXCLUDED.request_content_type,
    request_schema = EXCLUDED.request_schema,
    response_schema = EXCLUDED.response_schema,
    description = EXCLUDED.description,
    status = 'active',
    updated_at = now();

-- TTS 模型只支持 Speech；Whisper 只支持 Transcriptions，避免同一音频能力串用错误协议。
INSERT INTO model_interfaces (model_id, interface_id)
SELECT model.id, api.id
  FROM ai_models model
  JOIN api_interfaces api ON api.interface_code = 'audio'
 WHERE model.public_name IN ('tts-1', 'tts-1-hd')
ON CONFLICT DO NOTHING;

INSERT INTO model_interfaces (model_id, interface_id)
SELECT model.id, api.id
  FROM ai_models model
  JOIN api_interfaces api ON api.interface_code = 'audio_transcription'
 WHERE model.public_name = 'whisper-1'
ON CONFLICT DO NOTHING;

INSERT INTO model_interfaces (model_id, interface_id)
SELECT model.id, api.id
  FROM ai_models model
  JOIN api_interfaces api ON api.interface_code = 'embedding'
 WHERE model.capability_type = 'embedding'
ON CONFLICT DO NOTHING;

COMMENT ON TABLE model_interfaces IS
    '模型与公开接口文档的多对多关联；音频模型必须分别关联 Speech 或 Transcriptions，不能只按 capability_type 推断请求协议。';
