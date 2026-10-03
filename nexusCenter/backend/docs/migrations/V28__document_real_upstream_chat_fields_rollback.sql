-- V28 回滚：移除真实上游采样补充的 reasoning_content 和 native_finish_reason 字段。
-- 其余 V27 字段保持不变，版本继续递增以避免覆盖并发编辑。
UPDATE api_interfaces
   SET response_schema = jsonb_set(
           jsonb_set(
             jsonb_set(
                 response_schema,
                 '{fields,4,children}',
                 (
                     SELECT jsonb_agg(item.field ORDER BY item.position)
                       FROM jsonb_array_elements(response_schema #> '{fields,4,children}')
                            WITH ORDINALITY AS item(field, position)
                      WHERE item.field ->> 'name' <> 'native_finish_reason'
                 ),
                 true
             ),
             '{fields,4,children,1,children}',
             (
                 SELECT jsonb_agg(item.field ORDER BY item.position)
                   FROM jsonb_array_elements(response_schema #> '{fields,4,children,1,children}')
                        WITH ORDINALITY AS item(field, position)
                  WHERE item.field ->> 'name' <> 'reasoning_content'
             ),
             true
           ),
           '{fields,4,children,2,children,2,children}',
           '[]'::jsonb,
           true
       ),
       description = 'gpt-5.6-sol 使用的 OpenAI Chat Completions 接口；请求参数按上游公开字段维护，支持普通 JSON 和 SSE 流式响应。',
       updated_at = now(),
       version = version + 1
 WHERE interface_code = 'openai_chat';
