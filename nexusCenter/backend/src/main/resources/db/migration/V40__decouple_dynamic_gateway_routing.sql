-- V40：渠道可绑定多个平台开放接口，Gateway 动态路由不再强制依赖渠道模型映射。

ALTER TABLE public.channels
    ADD COLUMN interface_codes JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE public.channels
   SET interface_codes = jsonb_build_array(lower(interface_code))
 WHERE interface_code IS NOT NULL
   AND btrim(interface_code) != ''
   AND interface_codes = '[]'::jsonb;

ALTER TABLE public.channels
    ADD CONSTRAINT channels_interface_codes_array
        CHECK (jsonb_typeof(interface_codes) = 'array') NOT VALID;

ALTER TABLE public.channels
    VALIDATE CONSTRAINT channels_interface_codes_array;

CREATE INDEX idx_channels_interface_codes_route
    ON public.channels USING GIN (interface_codes);

-- 动态路由没有 channel_models 覆盖时，该字段为空；供应商、渠道和平台模型仍完整留痕。
ALTER TABLE public.upstream_attempt_logs
    ALTER COLUMN channel_model_id DROP NOT NULL;

COMMENT ON COLUMN public.channels.interface_codes IS
    '该上游渠道可承接的平台开放接口编码 JSON 数组；元素使用小写 api_interfaces.interface_code，同一 Base URL 可绑定多个接口。';
COMMENT ON COLUMN public.channels.interface_code IS
    '历史单接口兼容字段；新代码以 interface_codes 为准，并保留首个接口编码供旧客户端读取。';
COMMENT ON COLUMN public.upstream_attempt_logs.channel_model_id IS
    '可选渠道模型覆盖标识；为空表示本次动态路由直接使用 ai_models.public_name 作为上游模型名。';
COMMENT ON TABLE public.channels IS
    '供应商上游渠道配置表；interface_codes 描述一个 Base URL 可承接的多个平台开放接口，凭证由服务分组加密维护。';
