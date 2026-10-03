-- V28：根据 2026-08-22 对 gpt-5.6-sol 上游的真实非流式、流式和工具调用采样，补充实际返回字段。
-- 只更新接口文档 JSON，不保存上游响应正文、提示词或任何凭证。
DO $migration$
DECLARE
    updated_count integer;
BEGIN
    WITH sampled_schema AS (
        SELECT id,
               jsonb_set(
                 jsonb_set(
                   response_schema,
                   '{fields,4,children,1,children}',
                   COALESCE(response_schema #> '{fields,4,children,1,children}', '[]'::jsonb)
                   || jsonb_build_array(jsonb_build_object(
                       'name', 'reasoning_content',
                       'path', 'choices[].message.reasoning_content',
                       'type', 'string',
                       'required', false,
                       'description', '上游返回的推理内容；普通消息中可能为空，平台不保证所有模型都提供。',
                       'deprecated', false,
                       'sensitive', false,
                       'children', '[]'::jsonb
                   )),
                   true
                 ),
                 '{fields,4,children,2,children,2,children}',
                 $stream_tool_calls$
                 [
                   {
                     "name": "index",
                     "path": "choices[].delta.tool_calls[].index",
                     "type": "integer",
                     "required": false,
                     "description": "流式工具调用在当前候选结果中的序号。",
                     "minimum": 0,
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   },
                   {
                     "name": "id",
                     "path": "choices[].delta.tool_calls[].id",
                     "type": "string",
                     "required": false,
                     "description": "流式工具调用唯一标识；通常在首个相关分片中出现。",
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   },
                   {
                     "name": "type",
                     "path": "choices[].delta.tool_calls[].type",
                     "type": "string",
                     "required": false,
                     "description": "工具调用类型，当前实测为 function。",
                     "enum_values": ["function"],
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   },
                   {
                     "name": "function",
                     "path": "choices[].delta.tool_calls[].function",
                     "type": "object",
                     "required": false,
                     "description": "流式增量返回的函数名称和参数片段。",
                     "deprecated": false,
                     "sensitive": false,
                     "children": [
                       {
                         "name": "name",
                         "path": "choices[].delta.tool_calls[].function.name",
                         "type": "string",
                         "required": false,
                         "description": "需要调用的函数名称，通常在首个相关分片中出现。",
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       },
                       {
                         "name": "arguments",
                         "path": "choices[].delta.tool_calls[].function.arguments",
                         "type": "string",
                         "required": false,
                         "description": "当前分片新增的 JSON 参数字符串，客户端需要按顺序拼接。",
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       }
                     ]
                   }
                 ]
                 $stream_tool_calls$::jsonb,
                 true
               ) AS schema_value
          FROM api_interfaces
         WHERE interface_code = 'openai_chat'
    )
    UPDATE api_interfaces interface
       SET response_schema = jsonb_set(
               sampled_schema.schema_value,
               '{fields,4,children}',
               COALESCE(sampled_schema.schema_value #> '{fields,4,children}', '[]'::jsonb)
               || jsonb_build_array(jsonb_build_object(
                   'name', 'native_finish_reason',
                   'path', 'choices[].native_finish_reason',
                   'type', 'string',
                   'required', false,
                   'description', '上游原生结束原因；可能与平台兼容字段 finish_reason 同时返回。',
                   'deprecated', false,
                   'sensitive', false,
                   'children', '[]'::jsonb
               )),
               true
           ),
           description = 'gpt-5.6-sol 使用的 OpenAI Chat Completions 接口；返回字段已按真实上游非流式、流式和工具调用采样补齐。',
           updated_at = now(),
           version = version + 1
      FROM sampled_schema
     WHERE interface.id = sampled_schema.id;

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    IF updated_count <> 1 THEN
        RAISE EXCEPTION 'V28 expected exactly one openai_chat api_interfaces row, updated %', updated_count;
    END IF;
END
$migration$;
