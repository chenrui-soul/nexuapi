-- V37：即梦和 Grok 视频协议共用平台 POST /v1/videos 入口。
-- 模型与 model_interfaces 关系负责在同一路径下唯一确定请求/返回字段，供应商渠道仍按 interface_code 精确路由。
UPDATE api_interfaces
   SET public_path = '/v1/videos',
       http_method = 'POST',
       request_content_type = 'application/json',
       updated_at = now(),
       version = version + 1
 WHERE interface_code IN ('jimeng_video', 'grok_video');

DO $migration$
DECLARE
    normalized_count INTEGER;
BEGIN
    SELECT count(*) INTO normalized_count
      FROM api_interfaces
     WHERE interface_code IN ('jimeng_video', 'grok_video')
       AND public_path = '/v1/videos'
       AND http_method = 'POST';
    IF normalized_count <> 2 THEN
        RAISE EXCEPTION 'V37 expected 2 normalized video interfaces, found %', normalized_count;
    END IF;
END
$migration$;
