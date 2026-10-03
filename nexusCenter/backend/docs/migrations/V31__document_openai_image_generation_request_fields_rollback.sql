-- V31 回滚：删除本迁移新增的 OpenAI Images Generations 接口文档。
-- 若后续已经为模型建立关联，外键级联会同时删除对应 model_interfaces 关系。
DELETE FROM api_interfaces
 WHERE interface_code = 'openai_images'
   AND public_path = '/v1/images/generations';
