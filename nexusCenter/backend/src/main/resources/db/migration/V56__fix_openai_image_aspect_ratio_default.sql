-- V56：图片生成接口的 aspect_ratio 不再使用上游不接受的 auto，默认固定为 1:1。
UPDATE api_interfaces
   SET request_schema = jsonb_set(
       request_schema,
       '{fields}',
       (
           SELECT jsonb_agg(
               CASE WHEN field ->> 'name' = 'aspect_ratio'
                    THEN jsonb_set(
                             jsonb_set(
                                 jsonb_set(field, '{default_value}', to_jsonb('1:1'::text)),
                                 '{description}', to_jsonb('输出图片的宽高比；未提供时默认使用 1:1。'::text)
                             ),
                             '{enum_values}', '["1:1", "3:4", "4:3", "9:16", "16:9", "2:3", "3:2", "9:19.5", "19.5:9", "9:20", "20:9", "1:2", "2:1"]'::jsonb
                         )
                    ELSE field
               END
           )
           FROM jsonb_array_elements(request_schema -> 'fields') AS item(field)
       )
   )
 WHERE interface_code = 'openai_images';
