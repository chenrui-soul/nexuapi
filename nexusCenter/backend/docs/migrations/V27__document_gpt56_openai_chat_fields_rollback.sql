-- V27 回滚：恢复 V24/V26 发布时的最小 OpenAI Chat Completions 字段文档。
-- 乐观锁版本继续递增，避免回滚过程中覆盖管理员并发修改。

UPDATE api_interfaces
   SET request_schema = '{"fields":[
         {"name":"model","path":"model","type":"string","required":true,"description":"需要调用的模型名称","example":"gpt-5.6-sol","deprecated":false,"sensitive":false,"children":[]},
         {"name":"messages","path":"messages","type":"array","required":true,"description":"按时间顺序排列的对话消息列表","deprecated":false,"sensitive":false,"children":[
           {"name":"role","path":"messages[].role","type":"string","required":true,"description":"消息角色","enum_values":["system","user","assistant","tool"],"deprecated":false,"sensitive":false,"children":[]},
           {"name":"content","path":"messages[].content","type":"string","required":true,"description":"消息文本或多模态内容","deprecated":false,"sensitive":false,"children":[]}
         ]},
         {"name":"stream","path":"stream","type":"boolean","required":false,"description":"是否使用 SSE 流式返回","default_value":false,"deprecated":false,"sensitive":false,"children":[]}
       ]}'::jsonb,
       response_schema = '{"fields":[
         {"name":"id","path":"id","type":"string","required":true,"description":"本次响应的唯一编号","example":"chatcmpl-example","deprecated":false,"sensitive":false,"children":[]},
         {"name":"choices","path":"choices","type":"array","required":true,"description":"模型生成结果列表","deprecated":false,"sensitive":false,"children":[
           {"name":"message","path":"choices[].message","type":"object","required":true,"description":"模型返回的消息","deprecated":false,"sensitive":false,"children":[]},
           {"name":"finish_reason","path":"choices[].finish_reason","type":"string","required":false,"description":"生成结束原因","deprecated":false,"sensitive":false,"children":[]}
         ]},
         {"name":"usage","path":"usage","type":"object","required":false,"description":"本次调用的 Token 用量","deprecated":false,"sensitive":false,"children":[]}
       ]}'::jsonb,
       description = 'OpenAI 文本对话兼容协议，支持普通响应和 SSE 流式响应。',
       updated_at = now(),
       version = version + 1
 WHERE interface_code = 'openai_chat';
