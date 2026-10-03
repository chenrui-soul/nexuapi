-- V35 回滚：恢复 OpenAI Images Generations images[] 子字段原有的 string 类型。
UPDATE api_interfaces
   SET request_schema = jsonb_set(
       request_schema,
       '{fields,1,children,0,type}',
       '"string"'::jsonb,
       false
   )
 WHERE interface_code = 'openai_images'
   AND request_schema #>> '{fields,1,name}' = 'images'
   AND request_schema #>> '{fields,1,children,0,name}' = 'item';
