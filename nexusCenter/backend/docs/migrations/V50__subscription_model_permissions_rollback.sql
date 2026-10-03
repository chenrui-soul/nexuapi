-- V50 订阅模型权限回滚脚本。
-- 执行前必须停止所有依赖套餐模型权限的 V50 及后续应用实例。

BEGIN;

DROP TABLE IF EXISTS subscription_models;
DROP TABLE IF EXISTS plan_models;

DELETE FROM flyway_schema_history WHERE version = '50';

COMMIT;
