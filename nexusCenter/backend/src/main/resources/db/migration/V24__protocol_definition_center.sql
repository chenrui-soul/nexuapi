-- 协议标准中心 P0：公共协议定义 + 模型协议关联。
-- 本迁移只新增配置数据，不改变现有网关执行路径，便于独立验收和回滚。

CREATE TABLE protocol_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    protocol_code VARCHAR(64) NOT NULL UNIQUE,
    protocol_name VARCHAR(120) NOT NULL,
    protocol_version VARCHAR(32) NOT NULL,
    capability_type VARCHAR(24) NOT NULL,
    transport_mode VARCHAR(24) NOT NULL DEFAULT 'sync',
    http_method VARCHAR(8) NOT NULL DEFAULT 'POST',
    public_path VARCHAR(240) NOT NULL,
    request_content_type VARCHAR(80) NOT NULL DEFAULT 'application/json',
    request_schema JSONB NOT NULL DEFAULT '{"fields":[]}'::jsonb,
    response_schema JSONB NOT NULL DEFAULT '{"fields":[]}'::jsonb,
    adapter_key VARCHAR(80) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    is_builtin BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT protocol_definitions_capability_valid CHECK (
        capability_type IN ('text', 'image', 'audio', 'video', 'embedding', 'multimodal')
    ),
    CONSTRAINT protocol_definitions_transport_valid CHECK (
        transport_mode IN ('sync', 'stream', 'async_poll')
    ),
    CONSTRAINT protocol_definitions_method_valid CHECK (
        http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')
    ),
    CONSTRAINT protocol_definitions_status_valid CHECK (status IN ('active', 'disabled')),
    CONSTRAINT protocol_definitions_request_schema_object CHECK (jsonb_typeof(request_schema) = 'object'),
    CONSTRAINT protocol_definitions_response_schema_object CHECK (jsonb_typeof(response_schema) = 'object')
);

CREATE INDEX idx_protocol_definitions_catalog
    ON protocol_definitions (status, capability_type, updated_at DESC);

CREATE TABLE model_protocols (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    protocol_definition_id UUID NOT NULL REFERENCES protocol_definitions(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    is_default BOOLEAN NOT NULL DEFAULT false,
    parameter_overrides JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (model_id, protocol_definition_id),
    CONSTRAINT model_protocols_parameter_overrides_object CHECK (jsonb_typeof(parameter_overrides) = 'object')
);

CREATE INDEX idx_model_protocols_protocol ON model_protocols (protocol_definition_id, enabled, model_id);
CREATE INDEX idx_model_protocols_model ON model_protocols (model_id, enabled, protocol_definition_id);
CREATE UNIQUE INDEX uk_model_protocols_default
    ON model_protocols (model_id) WHERE enabled AND is_default;

COMMENT ON TABLE protocol_definitions IS '平台公共协议标准；维护接口入口、适配器以及带中文说明的请求和响应字段结构';
COMMENT ON COLUMN protocol_definitions.id IS '协议定义全局唯一标识';
COMMENT ON COLUMN protocol_definitions.protocol_code IS '稳定协议编码，例如 openai_chat、jimeng_video、grok_video';
COMMENT ON COLUMN protocol_definitions.protocol_name IS '管理员和用户接口文档显示的协议名称';
COMMENT ON COLUMN protocol_definitions.protocol_version IS '协议版本标签，不等同于数据库乐观锁版本';
COMMENT ON COLUMN protocol_definitions.capability_type IS '协议适用能力类型：文本、图片、音频、视频、向量或多模态';
COMMENT ON COLUMN protocol_definitions.transport_mode IS '交互方式：sync 同步、stream 流式、async_poll 异步任务轮询';
COMMENT ON COLUMN protocol_definitions.http_method IS '平台对外接口的 HTTP 方法';
COMMENT ON COLUMN protocol_definitions.public_path IS '平台对外暴露的接口路径模板';
COMMENT ON COLUMN protocol_definitions.request_content_type IS '客户端请求使用的 Content-Type';
COMMENT ON COLUMN protocol_definitions.request_schema IS '请求字段树 JSON；每个字段支持类型、必填、默认值、示例、枚举和中文说明';
COMMENT ON COLUMN protocol_definitions.response_schema IS '响应字段树 JSON；每个字段支持嵌套结构、示例和中文说明';
COMMENT ON COLUMN protocol_definitions.adapter_key IS 'Java 协议适配器注册键；配置定义格式，适配器执行实际转换';
COMMENT ON COLUMN protocol_definitions.description IS '协议用途和调用流程说明，不得保存凭证或用户隐私';
COMMENT ON COLUMN protocol_definitions.status IS '管理员启停状态：active 或 disabled';
COMMENT ON COLUMN protocol_definitions.is_builtin IS '是否为平台内置协议；内置协议仍通过乐观锁受控修改';
COMMENT ON COLUMN protocol_definitions.created_at IS '协议定义创建时间';
COMMENT ON COLUMN protocol_definitions.updated_at IS '协议定义最后更新时间';
COMMENT ON COLUMN protocol_definitions.version IS '管理员修改使用的乐观锁版本号';

COMMENT ON TABLE model_protocols IS '模型与公共协议的多对多关联；决定某个模型允许使用哪些协议';
COMMENT ON COLUMN model_protocols.id IS '模型协议关联全局唯一标识';
COMMENT ON COLUMN model_protocols.model_id IS '关联的模型主表 ID';
COMMENT ON COLUMN model_protocols.protocol_definition_id IS '关联的公共协议定义 ID';
COMMENT ON COLUMN model_protocols.enabled IS '该模型是否启用此协议';
COMMENT ON COLUMN model_protocols.is_default IS '是否为该模型默认协议；同一模型最多一个启用的默认协议';
COMMENT ON COLUMN model_protocols.parameter_overrides IS '模型级协议参数差异；P0 保留为空对象，后续用于覆盖范围和默认值';
COMMENT ON COLUMN model_protocols.created_at IS '模型协议关联创建时间';
COMMENT ON COLUMN model_protocols.updated_at IS '模型协议关联最后更新时间';
COMMENT ON COLUMN model_protocols.version IS '关联记录乐观锁版本号';

-- 首版内置三个代表性协议，字段说明用于验证管理员编辑和用户文档生成能力。
INSERT INTO protocol_definitions (
    protocol_code, protocol_name, protocol_version, capability_type, transport_mode,
    http_method, public_path, request_schema, response_schema, adapter_key, description, is_builtin
) VALUES
(
    'openai_chat', 'OpenAI Chat Completions', 'v1', 'text', 'stream', 'POST', '/v1/chat/completions',
    '{"fields":[
      {"name":"model","path":"model","type":"string","required":true,"description":"需要调用的模型名称","example":"gpt-5.6-sol","deprecated":false,"sensitive":false,"children":[]},
      {"name":"messages","path":"messages","type":"array","required":true,"description":"按时间顺序排列的对话消息列表","deprecated":false,"sensitive":false,"children":[
        {"name":"role","path":"messages[].role","type":"string","required":true,"description":"消息角色","enum_values":["system","user","assistant","tool"],"deprecated":false,"sensitive":false,"children":[]},
        {"name":"content","path":"messages[].content","type":"string","required":true,"description":"消息文本或多模态内容","deprecated":false,"sensitive":false,"children":[]}
      ]},
      {"name":"stream","path":"stream","type":"boolean","required":false,"description":"是否使用 SSE 流式返回","default_value":false,"deprecated":false,"sensitive":false,"children":[]}
    ]}'::jsonb,
    '{"fields":[
      {"name":"id","path":"id","type":"string","required":true,"description":"本次响应的唯一编号","example":"chatcmpl-example","deprecated":false,"sensitive":false,"children":[]},
      {"name":"choices","path":"choices","type":"array","required":true,"description":"模型生成结果列表","deprecated":false,"sensitive":false,"children":[
        {"name":"message","path":"choices[].message","type":"object","required":true,"description":"模型返回的消息","deprecated":false,"sensitive":false,"children":[]},
        {"name":"finish_reason","path":"choices[].finish_reason","type":"string","required":false,"description":"生成结束原因","deprecated":false,"sensitive":false,"children":[]}
      ]},
      {"name":"usage","path":"usage","type":"object","required":false,"description":"本次调用的 Token 用量","deprecated":false,"sensitive":false,"children":[]}
    ]}'::jsonb,
    'openAiChatProtocolAdapter', 'OpenAI 文本对话兼容协议，支持普通响应和 SSE 流式响应。', true
),
(
    'jimeng_video', '即梦视频生成协议', 'v1', 'video', 'async_poll', 'POST', '/v1/videos/jimeng',
    '{"fields":[
      {"name":"model","path":"model","type":"string","required":true,"description":"即梦视频模型名称","example":"seedance-2.0","deprecated":false,"sensitive":false,"children":[]},
      {"name":"prompt","path":"prompt","type":"string","required":true,"description":"视频画面和动作描述","deprecated":false,"sensitive":false,"children":[]},
      {"name":"duration","path":"duration","type":"integer","required":false,"description":"目标视频时长，单位为秒","default_value":5,"minimum":1,"maximum":30,"deprecated":false,"sensitive":false,"children":[]},
      {"name":"aspect_ratio","path":"aspect_ratio","type":"string","required":false,"description":"输出视频宽高比","enum_values":["16:9","9:16","1:1"],"deprecated":false,"sensitive":false,"children":[]}
    ]}'::jsonb,
    '{"fields":[
      {"name":"task_id","path":"task_id","type":"string","required":true,"description":"异步视频生成任务编号，用于查询任务状态","deprecated":false,"sensitive":false,"children":[]},
      {"name":"status","path":"status","type":"string","required":true,"description":"任务当前状态","enum_values":["queued","processing","succeeded","failed"],"deprecated":false,"sensitive":false,"children":[]}
    ]}'::jsonb,
    'jimengVideoProtocolAdapter', '即梦视频异步任务协议；提交后使用 task_id 查询状态和结果。', true
),
(
    'grok_video', 'Grok 视频生成协议', 'v1', 'video', 'async_poll', 'POST', '/v1/videos/grok',
    '{"fields":[
      {"name":"model","path":"model","type":"string","required":true,"description":"Grok 视频模型名称","example":"grok-video","deprecated":false,"sensitive":false,"children":[]},
      {"name":"prompt","path":"prompt","type":"string","required":true,"description":"视频生成提示词","deprecated":false,"sensitive":false,"children":[]},
      {"name":"image_url","path":"image_url","type":"string","required":false,"description":"图生视频时使用的参考图片地址","deprecated":false,"sensitive":false,"children":[]},
      {"name":"duration","path":"duration","type":"integer","required":false,"description":"目标视频时长，单位为秒","default_value":5,"minimum":1,"maximum":15,"deprecated":false,"sensitive":false,"children":[]}
    ]}'::jsonb,
    '{"fields":[
      {"name":"id","path":"id","type":"string","required":true,"description":"Grok 视频任务编号","deprecated":false,"sensitive":false,"children":[]},
      {"name":"status","path":"status","type":"string","required":true,"description":"任务状态","deprecated":false,"sensitive":false,"children":[]},
      {"name":"video_url","path":"video_url","type":"string","required":false,"description":"任务完成后返回的视频地址","deprecated":false,"sensitive":false,"children":[]}
    ]}'::jsonb,
    'grokVideoProtocolAdapter', 'Grok 视频任务协议；参数和返回结构与即梦协议独立维护。', true
);
