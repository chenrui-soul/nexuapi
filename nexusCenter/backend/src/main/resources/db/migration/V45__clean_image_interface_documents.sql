-- V45：清理误维护的 grouk_image 接口文档，并补齐 OpenAI 图片生成接口的响应字段。
-- api_interfaces / model_interfaces 仅用于接口文档展示，不参与 Gateway 路由和上游调用。
DO $migration$
DECLARE
    invalid_interface_count integer;
    invalid_relation_count integer;
    deleted_count integer;
    updated_count integer;
BEGIN
    SELECT count(*) INTO invalid_interface_count
      FROM api_interfaces
     WHERE interface_code = 'grouk_image';

    IF invalid_interface_count > 1 THEN
        RAISE EXCEPTION 'V45 expected at most one grouk_image row, found %', invalid_interface_count;
    END IF;

    IF invalid_interface_count = 1 AND NOT EXISTS (
        SELECT 1 FROM api_interfaces
         WHERE interface_code = 'grouk_image'
           AND http_method = 'POST'
           AND public_path = '/v1/'
    ) THEN
        RAISE EXCEPTION 'V45 refused to delete grouk_image because method or path differs from the confirmed invalid document';
    END IF;

    SELECT count(*) INTO invalid_relation_count
      FROM model_interfaces relation
      JOIN api_interfaces interface ON interface.id = relation.interface_id
     WHERE interface.interface_code = 'grouk_image';

    IF invalid_relation_count <> 0 THEN
        RAISE EXCEPTION 'V45 refused to delete grouk_image because it unexpectedly has % model relation(s)', invalid_relation_count;
    END IF;

    DELETE FROM api_interfaces
     WHERE interface_code = 'grouk_image'
       AND http_method = 'POST'
       AND public_path = '/v1/';
    GET DIAGNOSTICS deleted_count = ROW_COUNT;

    IF deleted_count <> invalid_interface_count THEN
        RAISE EXCEPTION 'V45 expected to delete % grouk_image row(s), deleted %', invalid_interface_count, deleted_count;
    END IF;

    UPDATE api_interfaces
       SET response_schema = $response$
           {
             "fields": [
               {"name":"created","path":"created","type":"integer","required":true,"description":"图片生成响应的 Unix 时间戳（秒）。","deprecated":false,"sensitive":false,"children":[]},
               {"name":"background","path":"background","type":"string","required":false,"description":"实际采用的图片背景模式；仅部分兼容模型返回。","deprecated":false,"sensitive":false,"children":[]},
               {"name":"output_format","path":"output_format","type":"string","required":false,"description":"实际输出的图片格式；仅部分兼容模型返回。","deprecated":false,"sensitive":false,"children":[]},
               {"name":"quality","path":"quality","type":"string","required":false,"description":"实际采用的图片质量档位；仅部分兼容模型返回。","deprecated":false,"sensitive":false,"children":[]},
               {"name":"size","path":"size","type":"string","required":false,"description":"实际输出的图片尺寸；仅部分兼容模型返回。","deprecated":false,"sensitive":false,"children":[]},
               {
                 "name":"data","path":"data","type":"array","required":true,
                 "description":"生成的图片结果列表。每一项通常返回 URL 或 Base64 图片数据。",
                 "deprecated":false,"sensitive":false,
                 "children":[
                   {"name":"url","path":"data[].url","type":"string","required":false,"description":"生成图片的可访问地址；上游返回 URL 时提供。","deprecated":false,"sensitive":false,"children":[]},
                   {"name":"b64_json","path":"data[].b64_json","type":"string","required":false,"description":"Base64 编码的图片内容；上游返回内嵌图片时提供。","deprecated":false,"sensitive":false,"children":[]},
                   {"name":"revised_prompt","path":"data[].revised_prompt","type":"string","required":false,"description":"上游实际用于生成图片的修订提示词；仅部分模型返回。","deprecated":false,"sensitive":false,"children":[]}
                 ]
               },
               {
                 "name":"usage","path":"usage","type":"object","required":false,
                 "description":"上游返回的图片生成用量；仅部分兼容模型返回。",
                 "deprecated":false,"sensitive":false,
                 "children":[
                   {"name":"input_tokens","path":"usage.input_tokens","type":"integer","required":false,"description":"图片生成输入 Token 数。","deprecated":false,"sensitive":false,"children":[]},
                   {"name":"output_tokens","path":"usage.output_tokens","type":"integer","required":false,"description":"图片生成输出 Token 数。","deprecated":false,"sensitive":false,"children":[]},
                   {"name":"total_tokens","path":"usage.total_tokens","type":"integer","required":false,"description":"图片生成总 Token 数。","deprecated":false,"sensitive":false,"children":[]},
                   {
                     "name":"input_tokens_details","path":"usage.input_tokens_details","type":"object","required":false,
                     "description":"图片与文本输入 Token 的分类明细。","deprecated":false,"sensitive":false,
                     "children":[
                       {"name":"image_tokens","path":"usage.input_tokens_details.image_tokens","type":"integer","required":false,"description":"图片输入 Token 数。","deprecated":false,"sensitive":false,"children":[]},
                       {"name":"text_tokens","path":"usage.input_tokens_details.text_tokens","type":"integer","required":false,"description":"文本输入 Token 数。","deprecated":false,"sensitive":false,"children":[]}
                     ]
                   }
                 ]
               }
             ]
           }
           $response$::jsonb,
           description = 'OpenAI 兼容图片生成接口；请求字段按已接入模型维护，响应 JSON 由平台按上游结果原样返回。',
           updated_at = now(),
           version = version + 1
     WHERE interface_code = 'openai_images'
       AND http_method = 'POST'
       AND public_path = '/v1/images/generations';
    GET DIAGNOSTICS updated_count = ROW_COUNT;

    IF updated_count <> 1 THEN
        RAISE EXCEPTION 'V45 expected exactly one openai_images row, updated %', updated_count;
    END IF;
END
$migration$;
