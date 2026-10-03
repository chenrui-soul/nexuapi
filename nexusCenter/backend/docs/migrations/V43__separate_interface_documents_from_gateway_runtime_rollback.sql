-- V43 回滚：恢复 V42 使用的模型/渠道接口编码字段。
-- 回滚会根据现有接口文档和渠道地址重新推导编码，不能恢复 V43 期间已经删除的历史手工值。

DROP INDEX IF EXISTS public.idx_channels_runtime_capability_route;

ALTER TABLE public.channels
    ADD COLUMN interface_code VARCHAR(80),
    ADD COLUMN interface_codes JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE public.channels
   SET interface_code = CASE
       WHEN endpoint_type = 'text' AND lower(rtrim(base_url, '/')) LIKE '%/responses' THEN 'openai_responses'
       WHEN endpoint_type IN ('text', 'multimodal') THEN 'openai_chat'
       WHEN endpoint_type = 'image' AND lower(rtrim(base_url, '/')) LIKE '%/images/tasks' THEN 'openai_image_tasks'
       WHEN endpoint_type = 'image' THEN 'openai_images'
       WHEN endpoint_type = 'video' AND request_method = 'GET' THEN 'openai_video_list'
       WHEN endpoint_type = 'video' THEN 'jimeng_video'
       WHEN endpoint_type = 'audio' AND lower(rtrim(base_url, '/')) LIKE '%/audio/transcriptions' THEN 'audio_transcription'
       WHEN endpoint_type = 'audio' THEN 'audio'
       WHEN endpoint_type = 'embedding' THEN 'embedding'
       ELSE NULL
   END;

UPDATE public.channels
   SET interface_codes = CASE
       WHEN interface_code IS NULL THEN '[]'::jsonb
       ELSE jsonb_build_array(interface_code)
   END;

ALTER TABLE public.channels
    ADD CONSTRAINT channels_interface_codes_array
        CHECK (jsonb_typeof(interface_codes) = 'array');
CREATE INDEX idx_channels_interface_route
    ON public.channels (interface_code, request_method, endpoint_type, status);
CREATE INDEX idx_channels_interface_codes_route
    ON public.channels USING GIN (interface_codes);

ALTER TABLE public.model_interfaces
    ADD COLUMN interface_code VARCHAR(64);
UPDATE public.model_interfaces relation
   SET interface_code = lower(interface.interface_code)
  FROM public.api_interfaces interface
 WHERE interface.id = relation.interface_id;
ALTER TABLE public.model_interfaces
    ALTER COLUMN interface_code SET NOT NULL,
    ADD CONSTRAINT model_interfaces_interface_code_valid
        CHECK (interface_code ~ '^[a-z0-9][a-z0-9_-]{0,63}$');
CREATE UNIQUE INDEX uk_model_interfaces_model_code
    ON public.model_interfaces (model_id, interface_code);

ALTER TABLE public.model_interfaces
    DROP CONSTRAINT IF EXISTS model_interfaces_interface_id_fkey;
ALTER TABLE public.model_interfaces
    ADD CONSTRAINT model_interfaces_interface_id_fkey
        FOREIGN KEY (interface_id) REFERENCES public.api_interfaces(id) ON DELETE RESTRICT;

