-- 先扩展校验约束，再迁移历史值；否则 UPDATE 会被旧约束拒绝。
ALTER TABLE public.channels
    DROP CONSTRAINT IF EXISTS channels_operation_code_valid;

ALTER TABLE public.channels
    ADD CONSTRAINT channels_operation_code_valid_transition CHECK (operation_code IN (
        'chat_completions', 'responses', 'image_generations',
        'image_tasks',
        'image_task_create', 'image_task_detail',
        'video_create', 'video_list', 'video_detail', 'audio_speech',
        'audio_transcriptions', 'embeddings'
    ));

-- 异步图片任务创建与查询使用独立运行时操作编码；清理历史 image_tasks 配置。
UPDATE public.channels
   SET operation_code = 'image_task_create',
       updated_at = now(),
       version = version + 1
 WHERE operation_code = 'image_tasks'
   AND request_method = 'POST';

UPDATE public.channels
   SET operation_code = 'image_task_detail',
       updated_at = now(),
       version = version + 1
 WHERE operation_code = 'image_tasks'
   AND request_method = 'GET';

ALTER TABLE public.channels
    DROP CONSTRAINT channels_operation_code_valid_transition;

ALTER TABLE public.channels
    ADD CONSTRAINT channels_operation_code_valid CHECK (operation_code IN (
        'chat_completions', 'responses', 'image_generations',
        'image_task_create', 'image_task_detail',
        'video_create', 'video_list', 'video_detail', 'audio_speech',
        'audio_transcriptions', 'embeddings'
    ));
