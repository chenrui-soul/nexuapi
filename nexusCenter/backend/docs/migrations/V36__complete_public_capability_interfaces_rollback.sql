-- V36 回滚：仅撤销本迁移新增的接口归属字段/索引和三条缺失接口文档，不删除历史渠道、模型或调用日志。
DELETE FROM api_interfaces
 WHERE interface_code IN ('openai_responses','openai_video_list','openai_image_tasks')
   AND NOT EXISTS (
       SELECT 1 FROM model_interfaces mi WHERE mi.interface_id = api_interfaces.id
   );
DROP INDEX IF EXISTS idx_channels_interface_route;
ALTER TABLE channels DROP COLUMN IF EXISTS interface_code;
