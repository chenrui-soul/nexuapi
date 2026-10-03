-- V48 安全回滚：已有规则或计费事实时拒绝删除，正式环境应恢复迁移前备份。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM billing_time_rules)
       OR EXISTS (
            SELECT 1
              FROM request_billing_details
             WHERE time_rule_id IS NOT NULL
                OR time_multiplier <> 1
                OR base_usage_amount <> 0
       ) THEN
        RAISE EXCEPTION 'V48 contains time pricing configuration or billing facts; restore the pre-migration database backup instead';
    END IF;
END $$;

ALTER TABLE request_billing_details
    DROP CONSTRAINT IF EXISTS request_billing_details_base_usage_amount_nonnegative,
    DROP CONSTRAINT IF EXISTS request_billing_details_time_multiplier_valid,
    DROP COLUMN IF EXISTS base_usage_amount,
    DROP COLUMN IF EXISTS pricing_time,
    DROP COLUMN IF EXISTS time_multiplier,
    DROP COLUMN IF EXISTS time_rule_name,
    DROP COLUMN IF EXISTS time_rule_id;

DROP TABLE IF EXISTS billing_time_rule_models;
DROP TABLE IF EXISTS billing_time_rules;

