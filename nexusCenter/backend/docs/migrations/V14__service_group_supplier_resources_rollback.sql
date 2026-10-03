-- 仅在停机窗口执行。回滚前必须确认新版本期间没有仅依赖 service_group_id 的有效 API Key。
DROP INDEX IF EXISTS idx_routing_group_supplier_credentials_lookup;
DROP TABLE IF EXISTS routing_group_supplier_credentials;

DROP INDEX IF EXISTS idx_routing_group_suppliers_route;
DROP TABLE IF EXISTS routing_group_suppliers;

DROP INDEX IF EXISTS idx_api_keys_service_group_status;
ALTER TABLE api_keys
    DROP CONSTRAINT IF EXISTS api_keys_service_group_required,
    DROP CONSTRAINT IF EXISTS fk_api_keys_service_group,
    DROP COLUMN IF EXISTS service_group_id;

ALTER TABLE channels
    DROP CONSTRAINT IF EXISTS channels_endpoint_type_valid,
    DROP COLUMN IF EXISTS endpoint_type;
