-- 根据 gpt-5.6-sol 上游接口文档维护 OpenAI Chat Completions 字段。
-- 请求参数只登记上游明确开放的 7 个顶层字段；返回参数同时覆盖普通 JSON 和 SSE chunk。

DO $migration$
DECLARE
    updated_count INTEGER;
BEGIN
    UPDATE api_interfaces
       SET request_schema = $request$
           {
             "fields": [
               {
                 "name": "model",
                 "path": "model",
                 "type": "string",
                 "required": true,
                 "description": "要调用的文本模型 ID。",
                 "example": "gpt-5.6-sol",
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "messages",
                 "path": "messages",
                 "type": "array",
                 "required": true,
                 "description": "OpenAI Chat Completions 消息数组，后端按原始 JSON 读取并转发。",
                 "example": [{"role": "user", "content": "你好"}],
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "stream",
                 "path": "stream",
                 "type": "boolean",
                 "required": false,
                 "description": "是否启用 SSE 流式输出。",
                 "default_value": false,
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "temperature",
                 "path": "temperature",
                 "type": "number",
                 "required": false,
                 "description": "采样温度，用于控制输出随机性。",
                 "minimum": 0,
                 "maximum": 2,
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "max_tokens",
                 "path": "max_tokens",
                 "type": "integer",
                 "required": false,
                 "description": "本次请求最多生成的词元数。",
                 "minimum": 1,
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "tools",
                 "path": "tools",
                 "type": "array",
                 "required": false,
                 "description": "可供模型调用的工具定义，后端按 OpenAI tools 原始 JSON 读取并转发。",
                 "example": [{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object"}}}],
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "top_p",
                 "path": "top_p",
                 "type": "number",
                 "required": false,
                 "description": "核采样参数，用于限制参与采样的累计概率范围。",
                 "minimum": 0,
                 "maximum": 1,
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               }
             ]
           }
           $request$::jsonb,
           response_schema = $response$
           {
             "fields": [
               {
                 "name": "id",
                 "path": "id",
                 "type": "string",
                 "required": true,
                 "description": "本次 Chat Completion 或流式分片的唯一编号。",
                 "example": "chatcmpl-example",
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "object",
                 "path": "object",
                 "type": "string",
                 "required": true,
                 "description": "响应对象类型；普通响应为 chat.completion，流式响应为 chat.completion.chunk。",
                 "enum_values": ["chat.completion", "chat.completion.chunk"],
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "created",
                 "path": "created",
                 "type": "integer",
                 "required": true,
                 "description": "响应创建时间，Unix 秒级时间戳。",
                 "example": 1710000000,
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "model",
                 "path": "model",
                 "type": "string",
                 "required": true,
                 "description": "生成本次响应的模型名称；平台会转换为用户请求的公开模型名。",
                 "example": "gpt-5.6-sol",
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               },
               {
                 "name": "choices",
                 "path": "choices",
                 "type": "array",
                 "required": true,
                 "description": "模型生成结果列表；普通响应使用 message，流式响应使用 delta。",
                 "deprecated": false,
                 "sensitive": false,
                 "children": [
                   {
                     "name": "index",
                     "path": "choices[].index",
                     "type": "integer",
                     "required": true,
                     "description": "当前候选结果在 choices 数组中的下标。",
                     "minimum": 0,
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   },
                   {
                     "name": "message",
                     "path": "choices[].message",
                     "type": "object",
                     "required": false,
                     "description": "非流式响应返回的完整助手消息。",
                     "deprecated": false,
                     "sensitive": false,
                     "children": [
                       {
                         "name": "role",
                         "path": "choices[].message.role",
                         "type": "string",
                         "required": true,
                         "description": "返回消息的角色，通常为 assistant。",
                         "enum_values": ["assistant"],
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       },
                       {
                         "name": "content",
                         "path": "choices[].message.content",
                         "type": "string",
                         "required": false,
                         "description": "模型生成的完整文本；触发工具调用时可能为空。",
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       },
                       {
                         "name": "refusal",
                         "path": "choices[].message.refusal",
                         "type": "string",
                         "required": false,
                         "description": "模型拒绝回答时返回的拒绝说明。",
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       },
                       {
                         "name": "tool_calls",
                         "path": "choices[].message.tool_calls",
                         "type": "array",
                         "required": false,
                         "description": "模型请求客户端执行的工具调用列表。",
                         "deprecated": false,
                         "sensitive": false,
                         "children": [
                           {
                             "name": "id",
                             "path": "choices[].message.tool_calls[].id",
                             "type": "string",
                             "required": true,
                             "description": "工具调用唯一标识，后续 tool 消息需要使用该值关联。",
                             "deprecated": false,
                             "sensitive": false,
                             "children": []
                           },
                           {
                             "name": "type",
                             "path": "choices[].message.tool_calls[].type",
                             "type": "string",
                             "required": true,
                             "description": "工具调用类型，当前通常为 function。",
                             "enum_values": ["function"],
                             "deprecated": false,
                             "sensitive": false,
                             "children": []
                           },
                           {
                             "name": "function",
                             "path": "choices[].message.tool_calls[].function",
                             "type": "object",
                             "required": true,
                             "description": "需要执行的函数名称和 JSON 参数。",
                             "deprecated": false,
                             "sensitive": false,
                             "children": [
                               {
                                 "name": "name",
                                 "path": "choices[].message.tool_calls[].function.name",
                                 "type": "string",
                                 "required": true,
                                 "description": "需要调用的函数名称。",
                                 "deprecated": false,
                                 "sensitive": false,
                                 "children": []
                               },
                               {
                                 "name": "arguments",
                                 "path": "choices[].message.tool_calls[].function.arguments",
                                 "type": "string",
                                 "required": true,
                                 "description": "模型生成的 JSON 参数字符串；客户端执行前仍需自行校验。",
                                 "deprecated": false,
                                 "sensitive": false,
                                 "children": []
                               }
                             ]
                           }
                         ]
                       }
                     ]
                   },
                   {
                     "name": "delta",
                     "path": "choices[].delta",
                     "type": "object",
                     "required": false,
                     "description": "SSE 流式响应当前分片的增量消息。",
                     "deprecated": false,
                     "sensitive": false,
                     "children": [
                       {
                         "name": "role",
                         "path": "choices[].delta.role",
                         "type": "string",
                         "required": false,
                         "description": "流式首个分片中的消息角色。",
                         "enum_values": ["assistant"],
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       },
                       {
                         "name": "content",
                         "path": "choices[].delta.content",
                         "type": "string",
                         "required": false,
                         "description": "当前流式分片新增的文本内容。",
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       },
                       {
                         "name": "tool_calls",
                         "path": "choices[].delta.tool_calls",
                         "type": "array",
                         "required": false,
                         "description": "当前分片新增的工具调用内容。",
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       }
                     ]
                   },
                   {
                     "name": "finish_reason",
                     "path": "choices[].finish_reason",
                     "type": "string",
                     "required": false,
                     "description": "生成结束原因。",
                     "enum_values": ["stop", "length", "tool_calls", "content_filter", "function_call"],
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   },
                   {
                     "name": "logprobs",
                     "path": "choices[].logprobs",
                     "type": "object",
                     "required": false,
                     "description": "上游返回的 Token 概率信息；未启用时通常为空。",
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   }
                 ]
               },
               {
                 "name": "usage",
                 "path": "usage",
                 "type": "object",
                 "required": false,
                 "description": "本次请求的 Token 用量；流式请求是否返回取决于上游实现。",
                 "deprecated": false,
                 "sensitive": false,
                 "children": [
                   {
                     "name": "prompt_tokens",
                     "path": "usage.prompt_tokens",
                     "type": "integer",
                     "required": true,
                     "description": "输入消息消耗的 Token 数。",
                     "minimum": 0,
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   },
                   {
                     "name": "completion_tokens",
                     "path": "usage.completion_tokens",
                     "type": "integer",
                     "required": true,
                     "description": "模型输出消耗的 Token 数。",
                     "minimum": 0,
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   },
                   {
                     "name": "total_tokens",
                     "path": "usage.total_tokens",
                     "type": "integer",
                     "required": true,
                     "description": "输入与输出 Token 总数。",
                     "minimum": 0,
                     "deprecated": false,
                     "sensitive": false,
                     "children": []
                   },
                   {
                     "name": "prompt_tokens_details",
                     "path": "usage.prompt_tokens_details",
                     "type": "object",
                     "required": false,
                     "description": "输入 Token 分类明细。",
                     "deprecated": false,
                     "sensitive": false,
                     "children": [
                       {
                         "name": "cached_tokens",
                         "path": "usage.prompt_tokens_details.cached_tokens",
                         "type": "integer",
                         "required": false,
                         "description": "命中缓存的输入 Token 数。",
                         "minimum": 0,
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       }
                     ]
                   },
                   {
                     "name": "completion_tokens_details",
                     "path": "usage.completion_tokens_details",
                     "type": "object",
                     "required": false,
                     "description": "输出 Token 分类明细。",
                     "deprecated": false,
                     "sensitive": false,
                     "children": [
                       {
                         "name": "reasoning_tokens",
                         "path": "usage.completion_tokens_details.reasoning_tokens",
                         "type": "integer",
                         "required": false,
                         "description": "推理过程消耗的 Token 数。",
                         "minimum": 0,
                         "deprecated": false,
                         "sensitive": false,
                         "children": []
                       }
                     ]
                   }
                 ]
               },
               {
                 "name": "system_fingerprint",
                 "path": "system_fingerprint",
                 "type": "string",
                 "required": false,
                 "description": "上游服务配置指纹；上游未提供时可能为空。",
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               }
             ]
           }
           $response$::jsonb,
           description = 'gpt-5.6-sol 使用的 OpenAI Chat Completions 接口；请求参数按上游公开字段维护，支持普通 JSON 和 SSE 流式响应。',
           updated_at = now(),
           version = version + 1
     WHERE interface_code = 'openai_chat';

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    IF updated_count <> 1 THEN
        RAISE EXCEPTION 'V27 expected exactly one openai_chat api_interfaces row, updated %', updated_count;
    END IF;
END
$migration$;
