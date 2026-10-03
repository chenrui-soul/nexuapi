-- V22 安全回滚脚本。
-- 如果已经同步到 last_status=2，旧约束无法无损恢复，因此主动拒绝回滚。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM public.routing_group_models
         WHERE upstream_last_status = 2
    ) THEN
        RAISE EXCEPTION
            'V22 rollback refused: routing_group_models contains upstream_last_status=2; restore the pre-release database backup instead';
    END IF;
END
$$;

ALTER TABLE public.routing_group_models
    DROP CONSTRAINT routing_group_models_last_status_valid;

ALTER TABLE public.routing_group_models
    ADD CONSTRAINT routing_group_models_last_status_valid
        CHECK (upstream_last_status IS NULL OR upstream_last_status IN (0, 1));

COMMENT ON COLUMN public.routing_group_models.upstream_last_status IS
    '上游返回的最后健康状态原始值：0 异常或未检查，1 成功；缺失时为空。';
