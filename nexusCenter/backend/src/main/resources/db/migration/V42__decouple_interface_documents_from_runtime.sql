-- V42：接口文档与真实调用解耦。
-- api_interfaces 继续保存展示文档；model_interfaces.interface_code 保存模型运行时支持的稳定接口编码。

ALTER TABLE model_interfaces
    ADD COLUMN interface_code VARCHAR(64);

UPDATE model_interfaces relation
   SET interface_code = lower(interface.interface_code)
  FROM api_interfaces interface
 WHERE interface.id = relation.interface_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM model_interfaces WHERE interface_code IS NULL) THEN
        RAISE EXCEPTION 'model_interfaces.interface_code backfill failed';
    END IF;
END
$$;

ALTER TABLE model_interfaces
    ALTER COLUMN interface_code SET NOT NULL,
    ADD CONSTRAINT model_interfaces_interface_code_valid
        CHECK (interface_code ~ '^[a-z0-9][a-z0-9_-]{0,63}$');

CREATE UNIQUE INDEX uk_model_interfaces_model_code
    ON model_interfaces (model_id, interface_code);

-- 接口文档删除不得级联删除模型运行时能力；关联中的 interface_id 仅用于管理页面回显文档。
ALTER TABLE model_interfaces
    DROP CONSTRAINT model_protocols_protocol_definition_id_fkey;
ALTER TABLE model_interfaces
    ADD CONSTRAINT model_interfaces_interface_id_fkey
        FOREIGN KEY (interface_id) REFERENCES api_interfaces(id) ON DELETE RESTRICT;

COMMENT ON TABLE model_interfaces IS
    '模型支持的运行时接口关系；interface_code 决定真实调用，interface_id 仅关联 api_interfaces 文档用于管理展示';
COMMENT ON COLUMN model_interfaces.model_id IS '关联的模型主表 ID';
COMMENT ON COLUMN model_interfaces.interface_id IS '关联的接口文档 ID，仅用于管理页面展示，不参与真实调用判断';
COMMENT ON COLUMN model_interfaces.interface_code IS '模型运行时支持的稳定接口编码；真实调用不得读取 api_interfaces 的状态、路径、方法或字段文档';

