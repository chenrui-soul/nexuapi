-- V38 回滚：仅删除本迁移按接口和已确认模型生成的映射，不删除管理员后续新增的不同上游模型映射。
DELETE FROM channel_models cm
 USING channels c, ai_models m
 WHERE cm.channel_id = c.id
   AND cm.model_id = m.id
   AND c.interface_code IN ('jimeng_video', 'openai_video_list')
   AND m.public_name IN ('seedance-2.0', 'seedance-2.5')
   AND cm.upstream_model = 'bytedance/' || m.public_name
   AND cm.config = '{}'::jsonb;

DELETE FROM channel_models cm
 USING channels responses
 WHERE cm.channel_id = responses.id
   AND responses.interface_code = 'openai_responses'
   AND EXISTS (
       SELECT 1
         FROM channels chat
         JOIN channel_models source ON source.channel_id = chat.id
        WHERE chat.supplier_id = responses.supplier_id
          AND chat.interface_code = 'openai_chat'
          AND source.model_id = cm.model_id
          AND source.upstream_model = cm.upstream_model
          AND source.config = cm.config
   );
