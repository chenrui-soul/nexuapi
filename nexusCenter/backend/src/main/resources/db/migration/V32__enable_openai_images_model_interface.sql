-- V32：将正式环境已有图片模型绑定到 OpenAI Images 接口。
-- 该关系只决定模型允许使用哪一个公开协议，不创建渠道、服务分组或上游凭证。
INSERT INTO model_interfaces (model_id, interface_id)
SELECT m.id, i.id
  FROM ai_models m
 CROSS JOIN api_interfaces i
 WHERE m.public_name IN ('gpt-image-2', 'grok-imagine-image-2.0')
   AND i.interface_code = 'openai_images'
ON CONFLICT (model_id, interface_id) DO NOTHING;

COMMENT ON TABLE model_interfaces IS '模型与对外接口文档的允许关系；模型可通过关系选择支持的请求/返回协议。';
