-- V37 回滚：恢复两个历史视频文档入口；不改变模型和渠道关系。
UPDATE api_interfaces SET public_path = '/v1/videos/jimeng', updated_at = now(), version = version + 1
 WHERE interface_code = 'jimeng_video';
UPDATE api_interfaces SET public_path = '/v1/videos/grok', updated_at = now(), version = version + 1
 WHERE interface_code = 'grok_video';
