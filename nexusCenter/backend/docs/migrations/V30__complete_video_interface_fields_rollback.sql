-- V30 回滚：恢复 V29 时即梦返回字段和 V24/V26 时 Grok 请求/返回字段。
-- 仅恢复接口文档 JSON，不删除接口定义；版本递增避免覆盖并发编辑。
UPDATE api_interfaces
   SET response_schema = '{"fields":[
       {"name":"task_id","path":"task_id","type":"string","required":true,"description":"异步视频生成任务编号，用于查询任务状态","deprecated":false,"sensitive":false,"children":[]},
       {"name":"status","path":"status","type":"string","required":true,"description":"任务当前状态","enum_values":["queued","processing","succeeded","failed"],"deprecated":false,"sensitive":false,"children":[]}
     ]}'::jsonb,
       description = '即梦视频异步任务协议；提交后使用 task_id 查询状态和结果。',
       updated_at = now(),
       version = version + 1
 WHERE interface_code = 'jimeng_video';

UPDATE api_interfaces
   SET request_schema = '{"fields":[
       {"name":"model","path":"model","type":"string","required":true,"description":"Grok 视频模型名称","example":"grok-video","deprecated":false,"sensitive":false,"children":[]},
       {"name":"prompt","path":"prompt","type":"string","required":true,"description":"视频生成提示词","deprecated":false,"sensitive":false,"children":[]},
       {"name":"image_url","path":"image_url","type":"string","required":false,"description":"图生视频时使用的参考图片地址","deprecated":false,"sensitive":false,"children":[]},
       {"name":"duration","path":"duration","type":"integer","required":false,"description":"目标视频时长，单位为秒","default_value":5,"minimum":1,"maximum":15,"deprecated":false,"sensitive":false,"children":[]}
     ]}'::jsonb,
       response_schema = '{"fields":[
       {"name":"id","path":"id","type":"string","required":true,"description":"Grok 视频任务编号","deprecated":false,"sensitive":false,"children":[]},
       {"name":"status","path":"status","type":"string","required":true,"description":"任务状态","deprecated":false,"sensitive":false,"children":[]},
       {"name":"video_url","path":"video_url","type":"string","required":false,"description":"任务完成后返回的视频地址","deprecated":false,"sensitive":false,"children":[]}
     ]}'::jsonb,
       description = 'Grok 视频任务协议；参数和返回结构与即梦协议独立维护。',
       updated_at = now(),
       version = version + 1
 WHERE interface_code = 'grok_video';
