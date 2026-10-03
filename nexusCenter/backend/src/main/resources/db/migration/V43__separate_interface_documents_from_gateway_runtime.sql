-- V43：接口文档、模型文档关联与真实 Gateway 运行时彻底解耦。
-- 真实调用只使用模型能力、服务分组、供应商渠道、HTTP 方法、上游地址和分组凭证。

DROP INDEX IF EXISTS public.idx_channels_interface_route;
DROP INDEX IF EXISTS public.idx_channels_interface_codes_route;
DROP INDEX IF EXISTS public.uk_model_interfaces_model_code;

ALTER TABLE public.model_interfaces
    DROP CONSTRAINT IF EXISTS model_interfaces_interface_code_valid,
    DROP COLUMN IF EXISTS interface_code;

-- model_interfaces 现在只是文档关联；删除接口文档时同步清理关联，不得阻塞真实调用。
ALTER TABLE public.model_interfaces
    DROP CONSTRAINT IF EXISTS model_interfaces_interface_id_fkey;
ALTER TABLE public.model_interfaces
    ADD CONSTRAINT model_interfaces_interface_id_fkey
        FOREIGN KEY (interface_id) REFERENCES public.api_interfaces(id) ON DELETE CASCADE;

ALTER TABLE public.channels
    DROP CONSTRAINT IF EXISTS channels_interface_codes_array,
    DROP COLUMN IF EXISTS interface_codes,
    DROP COLUMN IF EXISTS interface_code;

CREATE INDEX idx_channels_runtime_capability_route
    ON public.channels (endpoint_type, request_method, status, supplier_id);

COMMENT ON TABLE public.model_interfaces IS
    '模型与接口文档的多对多关联；只用于管理端和用户接口文档展示，不参与鉴权、路由、健康或上游调用';
COMMENT ON COLUMN public.model_interfaces.model_id IS '关联的模型主表 ID，仅用于文档展示关系';
COMMENT ON COLUMN public.model_interfaces.interface_id IS '关联的接口文档 ID，仅用于展示；接口文档删除时级联清理';

COMMENT ON TABLE public.channels IS
    '供应商上游接口配置表；真实路由按能力类型、HTTP 方法、上游地址、服务分组供应商和分组凭证计算';
COMMENT ON COLUMN public.channels.endpoint_type IS '上游接口能力类型；真实路由用于匹配模型能力';
COMMENT ON COLUMN public.channels.request_method IS '调用上游地址使用的 HTTP 方法；真实路由用于区分同能力的 GET/POST 入口';
COMMENT ON COLUMN public.channels.base_url IS '供应商维护的完整上游接口地址；真实路由按程序固定能力入口匹配该地址路径';

