-- V32 回滚：只删除本迁移为两个既有图片模型创建的接口关系，不影响模型、接口和渠道数据。
DELETE FROM model_interfaces
 WHERE model_id IN (
       SELECT id
         FROM ai_models
        WHERE public_name IN ('gpt-image-2', 'grok-imagine-image-2.0')
   )
   AND interface_id = (SELECT id FROM api_interfaces WHERE interface_code = 'openai_images');
