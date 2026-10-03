-- 供应商接口通过平台固定能力编码参与真实路由，彻底移除对 URL 尾部的运行时推断。
ALTER TABLE public.channels
    ADD COLUMN operation_code VARCHAR(64);

-- 优先根据明确路径回填；旧的 API 根地址再按能力类型和请求方法使用兼容默认值。
UPDATE public.channels
   SET operation_code = CASE
       WHEN request_method = 'POST' AND lower(rtrim(base_url, '/')) LIKE '%/chat/completions' THEN 'chat_completions'
       WHEN request_method = 'POST' AND lower(rtrim(base_url, '/')) LIKE '%/responses' THEN 'responses'
       WHEN request_method = 'POST' AND lower(rtrim(base_url, '/')) LIKE '%/images/generations' THEN 'image_generations'
       WHEN request_method = 'POST' AND lower(rtrim(base_url, '/')) LIKE '%/images/tasks' THEN 'image_tasks'
       WHEN request_method = 'POST' AND lower(rtrim(base_url, '/')) LIKE '%/audio/speech' THEN 'audio_speech'
       WHEN request_method = 'POST' AND lower(rtrim(base_url, '/')) LIKE '%/audio/transcriptions' THEN 'audio_transcriptions'
       WHEN request_method = 'POST' AND lower(rtrim(base_url, '/')) LIKE '%/embeddings' THEN 'embeddings'
       WHEN endpoint_type = 'video' AND request_method = 'GET' THEN 'video_list'
       WHEN endpoint_type = 'video' AND request_method = 'POST' THEN 'video_create'
       WHEN endpoint_type = 'image' THEN 'image_generations'
       WHEN endpoint_type = 'audio' THEN 'audio_speech'
       WHEN endpoint_type = 'embedding' THEN 'embeddings'
       ELSE 'chat_completions'
   END;

ALTER TABLE public.channels
    ALTER COLUMN operation_code SET NOT NULL,
    ADD CONSTRAINT channels_operation_code_valid CHECK (operation_code IN (
        'chat_completions', 'responses', 'image_generations', 'image_tasks',
        'video_create', 'video_list', 'video_detail', 'audio_speech',
        'audio_transcriptions', 'embeddings'
    ));

DROP INDEX IF EXISTS public.idx_channels_runtime_capability_route;
CREATE INDEX idx_channels_operation_route
    ON public.channels (operation_code, status, supplier_id);

COMMENT ON COLUMN public.channels.operation_code IS
    '平台固定的真实调用能力编码；管理员从已注册目录选择，用于精确匹配供应商接口，不读取接口文档或猜测 URL';
COMMENT ON COLUMN public.channels.endpoint_type IS
    '由 operation_code 派生的粗粒度模型能力类型，用于模型候选预筛选，不单独决定具体调用入口';
COMMENT ON COLUMN public.channels.request_method IS
    '由 operation_code 派生的上游 HTTP 方法，不允许与能力编码分别维护';
COMMENT ON TABLE public.channels IS
    '供应商独立维护的上游接口配置；真实路由按 operation_code、模型能力、供应商状态和运行时健康状态计算';
