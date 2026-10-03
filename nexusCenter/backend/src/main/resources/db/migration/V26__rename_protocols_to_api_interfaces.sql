-- 接口文档中心重命名：接口定义使用 api_interfaces，模型支持关系使用 model_interfaces。
-- 本迁移保留 V24/V25 的历史数据，只移除已确认没有业务用途的适配器、内置标记和关联覆盖字段。

ALTER TABLE protocol_definitions RENAME TO api_interfaces;
ALTER TABLE model_protocols RENAME TO model_interfaces;

ALTER TABLE api_interfaces RENAME COLUMN protocol_code TO interface_code;
ALTER TABLE api_interfaces RENAME COLUMN protocol_name TO interface_name;
ALTER TABLE api_interfaces RENAME COLUMN protocol_version TO interface_version;
ALTER TABLE model_interfaces RENAME COLUMN protocol_definition_id TO interface_id;

ALTER TABLE api_interfaces DROP COLUMN adapter_key;
ALTER TABLE api_interfaces DROP COLUMN is_builtin;

DROP INDEX idx_model_protocols_protocol;
DROP INDEX idx_model_protocols_model;
DROP INDEX uk_model_protocols_default;
ALTER TABLE model_interfaces DROP COLUMN enabled;
ALTER TABLE model_interfaces DROP COLUMN is_default;
ALTER TABLE model_interfaces DROP COLUMN parameter_overrides;
ALTER TABLE model_interfaces DROP CONSTRAINT model_protocols_pkey;
ALTER TABLE model_interfaces DROP CONSTRAINT model_protocols_model_id_protocol_definition_id_key;
ALTER TABLE model_interfaces DROP COLUMN id;
ALTER TABLE model_interfaces DROP COLUMN created_at;
ALTER TABLE model_interfaces DROP COLUMN updated_at;
ALTER TABLE model_interfaces DROP COLUMN version;
ALTER TABLE model_interfaces ADD PRIMARY KEY (model_id, interface_id);

ALTER INDEX idx_protocol_definitions_catalog RENAME TO idx_api_interfaces_catalog;
CREATE INDEX idx_model_interfaces_interface ON model_interfaces (interface_id, model_id);

ALTER TABLE api_interfaces
    RENAME CONSTRAINT protocol_definitions_capability_valid TO api_interfaces_capability_valid;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT protocol_definitions_transport_valid TO api_interfaces_transport_valid;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT protocol_definitions_method_valid TO api_interfaces_method_valid;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT protocol_definitions_status_valid TO api_interfaces_status_valid;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT protocol_definitions_request_schema_object TO api_interfaces_request_schema_object;
ALTER TABLE api_interfaces
    RENAME CONSTRAINT protocol_definitions_response_schema_object TO api_interfaces_response_schema_object;

COMMENT ON TABLE api_interfaces IS '平台公共接口文档；维护调用入口、请求 JSON 字段和响应 JSON 字段，不保存适配器或模型关系';
COMMENT ON COLUMN api_interfaces.id IS '接口定义全局唯一标识';
COMMENT ON COLUMN api_interfaces.interface_code IS '稳定接口编码，例如 openai_chat、jimeng_video、grok_video';
COMMENT ON COLUMN api_interfaces.interface_name IS '管理员和接口文档显示的接口名称';
COMMENT ON COLUMN api_interfaces.interface_version IS '接口自身版本标签，不等同于数据库乐观锁版本';
COMMENT ON COLUMN api_interfaces.capability_type IS '接口适用能力类型：文本、图片、音频、视频、向量或多模态';
COMMENT ON COLUMN api_interfaces.transport_mode IS '交互方式：sync 同步、stream 流式、async_poll 异步任务轮询';
COMMENT ON COLUMN api_interfaces.http_method IS '平台对外接口使用的 HTTP 方法';
COMMENT ON COLUMN api_interfaces.public_path IS '平台对外暴露的接口路径模板';
COMMENT ON COLUMN api_interfaces.request_content_type IS '客户端请求使用的 Content-Type';
COMMENT ON COLUMN api_interfaces.request_schema IS '请求字段树 JSON；保存类型、必填、默认值、示例、枚举和中文说明';
COMMENT ON COLUMN api_interfaces.response_schema IS '响应字段树 JSON；保存嵌套结构、示例和中文说明';
COMMENT ON COLUMN api_interfaces.description IS '接口用途和调用流程说明，不得保存凭证或用户隐私';
COMMENT ON COLUMN api_interfaces.status IS '管理员启停状态：active 或 disabled';
COMMENT ON COLUMN api_interfaces.created_at IS '接口定义创建时间';
COMMENT ON COLUMN api_interfaces.updated_at IS '接口定义最后更新时间';
COMMENT ON COLUMN api_interfaces.version IS '管理员修改使用的乐观锁版本号';

COMMENT ON TABLE model_interfaces IS '模型与接口文档的多对多关联；由模型维护页面决定模型支持哪些接口';
COMMENT ON COLUMN model_interfaces.model_id IS '关联的模型主表 ID';
COMMENT ON COLUMN model_interfaces.interface_id IS '关联的 api_interfaces 接口定义 ID';
