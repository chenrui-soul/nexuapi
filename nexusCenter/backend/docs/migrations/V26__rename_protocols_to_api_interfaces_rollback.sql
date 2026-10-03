-- V26 回滚脚本：仅用于隔离环境验证或正式发布前的人工恢复，不由 Flyway 自动执行。
-- 先恢复删除的字段（历史数据无法恢复，只能使用默认值），再恢复旧表和旧字段名称。

ALTER TABLE api_interfaces ADD COLUMN adapter_key VARCHAR(80) NOT NULL DEFAULT 'openAiChatProtocolAdapter';
ALTER TABLE api_interfaces ADD COLUMN is_builtin BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE model_interfaces ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE model_interfaces ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE model_interfaces ADD COLUMN parameter_overrides JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE model_interfaces DROP CONSTRAINT model_interfaces_pkey;
ALTER TABLE model_interfaces ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE model_interfaces ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE model_interfaces ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE model_interfaces ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE model_interfaces
    ADD CONSTRAINT model_protocols_pkey PRIMARY KEY (id);
ALTER TABLE model_interfaces
    ADD CONSTRAINT model_protocols_model_id_protocol_definition_id_key UNIQUE (model_id, interface_id);
ALTER TABLE model_interfaces
    ADD CONSTRAINT model_protocols_parameter_overrides_object
        CHECK (jsonb_typeof(parameter_overrides) = 'object');

DROP INDEX idx_model_interfaces_interface;
ALTER INDEX idx_api_interfaces_catalog RENAME TO idx_protocol_definitions_catalog;

ALTER TABLE api_interfaces
    RENAME CONSTRAINT api_interfaces_capability_valid TO protocol_definitions_capability_valid;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT api_interfaces_transport_valid TO protocol_definitions_transport_valid;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT api_interfaces_method_valid TO protocol_definitions_method_valid;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT api_interfaces_status_valid TO protocol_definitions_status_valid;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT api_interfaces_request_schema_object TO protocol_definitions_request_schema_object;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT api_interfaces_response_schema_object TO protocol_definitions_response_schema_object;

ALTER TABLE model_interfaces RENAME COLUMN interface_id TO protocol_definition_id;
ALTER TABLE api_interfaces RENAME COLUMN interface_code TO protocol_code;
ALTER TABLE api_interfaces RENAME COLUMN interface_name TO protocol_name;
ALTER TABLE api_interfaces RENAME COLUMN interface_version TO protocol_version;
ALTER TABLE model_interfaces RENAME TO model_protocols;
ALTER TABLE api_interfaces RENAME TO protocol_definitions;

CREATE INDEX idx_model_protocols_protocol
    ON model_protocols (protocol_definition_id, enabled, model_id);
CREATE INDEX idx_model_protocols_model
    ON model_protocols (model_id, enabled, protocol_definition_id);
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
COMMENT ON COLUMN model_protocols.parameter_overrides IS '模型级协议参数差异；回滚后仅恢复为空对象，V26 已删除的历史值无法还原';
COMMENT ON COLUMN model_protocols.created_at IS '模型协议关联创建时间；回滚时使用当前时间补齐';
COMMENT ON COLUMN model_protocols.updated_at IS '模型协议关联最后更新时间；回滚时使用当前时间补齐';
COMMENT ON COLUMN model_protocols.version IS '关联记录乐观锁版本号；回滚时统一从 0 开始';
