-- V42 回滚：恢复 model_interfaces 只通过 api_interfaces 识别接口的旧结构。
-- 回滚前必须确认应用 JAR 已退回 V41，否则运行时查询会缺少 interface_code。

ALTER TABLE model_interfaces
    DROP CONSTRAINT IF EXISTS model_interfaces_interface_id_fkey;
ALTER TABLE model_interfaces
    ADD CONSTRAINT model_protocols_protocol_definition_id_fkey
        FOREIGN KEY (interface_id) REFERENCES api_interfaces(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS uk_model_interfaces_model_code;
ALTER TABLE model_interfaces
    DROP CONSTRAINT IF EXISTS model_interfaces_interface_code_valid,
    DROP COLUMN IF EXISTS interface_code;

COMMENT ON TABLE model_interfaces IS
    '模型与接口文档的多对多关联；由模型维护页面决定模型支持哪些接口';

