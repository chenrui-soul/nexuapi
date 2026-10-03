-- V35：接口文档字段类型增加 file，用于描述 multipart/form-data 文件字段。
-- 同步修正 OpenAI Images Generations 的 images[] 子字段，使文档类型与实际上传方式一致。
UPDATE api_interfaces
   SET request_schema = jsonb_set(
       request_schema,
       '{fields,1,children,0,type}',
       '"file"'::jsonb,
       false
   )
 WHERE interface_code = 'openai_images'
   AND request_schema #>> '{fields,1,name}' = 'images'
   AND request_schema #>> '{fields,1,children,0,name}' = 'item';
