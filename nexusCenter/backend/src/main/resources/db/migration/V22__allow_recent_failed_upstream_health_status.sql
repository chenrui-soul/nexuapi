-- 上游模型目录把 last_status=2 定义为“最近一次健康探测失败”。
-- V12 的旧约束只允许 0/1，会导致包含真实失败状态的整轮模型与价格同步回滚。
ALTER TABLE public.routing_group_models
    DROP CONSTRAINT routing_group_models_last_status_valid;

ALTER TABLE public.routing_group_models
    ADD CONSTRAINT routing_group_models_last_status_valid
        CHECK (upstream_last_status IS NULL OR upstream_last_status IN (0, 1, 2));

COMMENT ON COLUMN public.routing_group_models.upstream_last_status IS
    '上游返回的最后健康状态原始值：0 未探测或陈旧，1 最近成功，2 最近失败；缺失时为空。';
