-- V31：新增 OpenAI Images Generations 对外接口文档，并维护 Grok Imagine Image 2.0 已确认的请求参数。
-- 本迁移只新增 api_interfaces 文档记录，不新增模型关联，不实现 Gateway 转发，也不维护未经确认的返回字段。
DO $migration$
DECLARE
    inserted_count integer;
BEGIN
    INSERT INTO api_interfaces (
        interface_code,
        interface_name,
        interface_version,
        capability_type,
        transport_mode,
        http_method,
        public_path,
        request_content_type,
        request_schema,
        response_schema,
        description,
        status
    ) VALUES (
        'openai_images',
        'OpenAI Images Generations',
        'v1',
        'image',
        'sync',
        'POST',
        '/v1/images/generations',
        'multipart/form-data',
        $request$
        {
          "fields": [
            {
              "name": "aspect_ratio",
              "path": "aspect_ratio",
              "type": "string",
              "required": false,
              "description": "输出图片的宽高比；auto 表示由模型自动选择。",
              "default_value": "auto",
              "enum_values": ["auto", "1:1", "3:4", "4:3", "9:16", "16:9", "2:3", "3:2", "9:19.5", "19.5:9", "9:20", "20:9", "1:2", "2:1"],
              "deprecated": false,
              "sensitive": false,
              "children": []
            },
            {
              "name": "images",
              "path": "images",
              "type": "array",
              "required": false,
              "description": "可选参考图片文件数组，用于图片编辑或多参考图生成；当前模型最多支持 5 张。",
              "maximum": 5,
              "deprecated": false,
              "sensitive": false,
              "children": [
                {
                  "name": "item",
                  "path": "images[]",
                  "type": "string",
                  "required": false,
                  "description": "通过 multipart/form-data 上传的单张参考图片文件。",
                  "deprecated": false,
                  "sensitive": false,
                  "children": []
                }
              ]
            },
            {
              "name": "model",
              "path": "model",
              "type": "string",
              "required": true,
              "description": "模型 ID。",
              "example": "grok-imagine-image-2.0",
              "deprecated": false,
              "sensitive": false,
              "children": []
            },
            {
              "name": "n",
              "path": "n",
              "type": "integer",
              "required": false,
              "description": "生成图片数量，范围 1–10，步长 1。",
              "default_value": 1,
              "minimum": 1,
              "maximum": 10,
              "deprecated": false,
              "sensitive": false,
              "children": []
            },
            {
              "name": "prompt",
              "path": "prompt",
              "type": "string",
              "required": true,
              "description": "图片生成或编辑提示词。",
              "deprecated": false,
              "sensitive": false,
              "children": []
            },
            {
              "name": "quality",
              "path": "quality",
              "type": "string",
              "required": false,
              "description": "图片生成质量；medium 质量更高，通常耗时也更长。",
              "default_value": "medium",
              "enum_values": ["low", "medium"],
              "deprecated": false,
              "sensitive": false,
              "children": []
            },
            {
              "name": "extra_params",
              "path": "extra_params",
              "type": "object",
              "required": false,
              "description": "图片模型扩展参数。",
              "deprecated": false,
              "sensitive": false,
              "children": [
                {
                  "name": "resolution",
                  "path": "extra_params.resolution",
                  "type": "string",
                  "required": false,
                  "description": "输出图片分辨率档位。",
                  "enum_values": ["1k", "2k"],
                  "deprecated": false,
                  "sensitive": false,
                  "children": []
                }
              ]
            }
          ]
        }
        $request$::jsonb,
        '{"fields": []}'::jsonb,
        'OpenAI 兼容图片生成接口；当前请求字段依据 grok-imagine-image-2.0 上游文档维护，返回字段等待真实响应确认后补充。',
        'active'
    );
    GET DIAGNOSTICS inserted_count = ROW_COUNT;

    IF inserted_count <> 1 THEN
        RAISE EXCEPTION 'V31 expected exactly one openai_images api_interfaces row, inserted %', inserted_count;
    END IF;
END
$migration$;
