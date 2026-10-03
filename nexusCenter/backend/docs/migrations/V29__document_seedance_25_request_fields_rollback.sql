-- V29 回滚：恢复 V24/V26 中即梦视频的原始 4 个请求字段。
-- 回滚只恢复接口文档 JSON，不删除接口记录；版本递增用于避免覆盖管理员并发编辑。
UPDATE api_interfaces
   SET request_schema = $request$
   {
     "fields": [
       {"name":"model","path":"model","type":"string","required":true,"description":"即梦视频模型名称","example":"seedance-2.0","deprecated":false,"sensitive":false,"children":[]},
       {"name":"prompt","path":"prompt","type":"string","required":true,"description":"视频画面和动作描述","deprecated":false,"sensitive":false,"children":[]},
       {"name":"duration","path":"duration","type":"integer","required":false,"description":"目标视频时长，单位为秒","default_value":5,"minimum":1,"maximum":30,"deprecated":false,"sensitive":false,"children":[]},
       {"name":"aspect_ratio","path":"aspect_ratio","type":"string","required":false,"description":"输出视频宽高比","enum_values":["16:9","9:16","1:1"],"deprecated":false,"sensitive":false,"children":[]}
     ]
   }
   $request$::jsonb,
   description = '即梦视频异步任务协议；提交后使用 task_id 查询状态和结果。',
   updated_at = now(),
   version = version + 1
 WHERE interface_code = 'jimeng_video';
