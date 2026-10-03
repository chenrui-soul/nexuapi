-- V20 仅补充数据库说明；回滚时移除对应注释，不触碰任何业务数据或表结构。

COMMENT ON TABLE model_pricing_versions IS NULL;
COMMENT ON TABLE model_pricing_rules IS NULL;
COMMENT ON TABLE model_context_tiers IS NULL;
COMMENT ON TABLE supplier_model_prices IS NULL;
COMMENT ON TABLE request_billing_details IS NULL;

DO $$
DECLARE
    item record;
BEGIN
    FOR item IN
        SELECT table_name, column_name
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name IN (
               'model_pricing_versions', 'model_pricing_rules', 'model_context_tiers',
               'supplier_model_prices', 'request_billing_details'
           )
    LOOP
        EXECUTE format('COMMENT ON COLUMN %I.%I IS NULL', item.table_name, item.column_name);
    END LOOP;
END $$;
