-- V34 回滚：移除供应商上游接口请求方法配置。
ALTER TABLE public.channels
    DROP CONSTRAINT IF EXISTS channels_request_method_valid;

ALTER TABLE public.channels
    DROP COLUMN IF EXISTS request_method;
