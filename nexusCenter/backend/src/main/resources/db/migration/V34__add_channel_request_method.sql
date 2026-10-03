-- 供应商上游接口需要明确维护实际请求使用的 HTTP 方法。
-- 旧接口全部按现有 Gateway 行为回填为 POST，保证迁移前后调用语义不变。
ALTER TABLE public.channels
    ADD COLUMN request_method VARCHAR(8) NOT NULL DEFAULT 'POST';

ALTER TABLE public.channels
    ADD CONSTRAINT channels_request_method_valid
        CHECK (request_method IN ('GET', 'POST'));

COMMENT ON COLUMN public.channels.request_method IS
    '调用该供应商上游接口使用的 HTTP 请求方法，目前仅允许 GET 或 POST；旧数据默认 POST';
