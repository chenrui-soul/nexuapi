-- 服务分组不再维护“模型到渠道”的人工路由规则。
-- 运行时改为根据分组开放模型、分组供应商关系、分组供应商凭证和供应商渠道能力直接生成候选。

DROP TABLE IF EXISTS group_routes;

COMMENT ON TABLE public.routing_group_models IS
    '服务分组与平台模型的开放目录关联；Gateway 以此确认分组允许调用哪些模型';
COMMENT ON TABLE public.routing_group_suppliers IS
    '服务分组与供应商资源关系；Gateway 以优先级和权重选择该分组可使用的供应商';
COMMENT ON TABLE public.channel_models IS
    '渠道模型的上游名称、供应商成本和安全扩展配置；不作为人工路由规则';
COMMENT ON COLUMN public.channels.endpoint_type IS
    '供应商渠道提供的上游能力类型；Gateway 与 ai_models.capability_type 匹配后形成运行时候选';

