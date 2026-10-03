-- V30：补齐视频接口文档的请求/返回字段。
-- 即梦返回字段按异步视频任务实际生命周期维护；Grok 在保留既有 image_url/duration 字段的基础上补充帧图、分辨率和扩展参数。
-- 本迁移只更新 api_interfaces JSON 文档，不改变模型关联、路由执行或计费逻辑。
DO $migration$
DECLARE
    updated_jimeng integer;
    updated_grok integer;
    video_response jsonb := $response$
    {
      "fields": [
        {"name":"id","path":"id","type":"string","required":false,"description":"视频任务唯一编号；查询任务和下载结果时使用。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"task_id","path":"task_id","type":"string","required":false,"description":"即梦兼容任务编号；部分渠道返回该字段，和 id 作用相同。","deprecated":true,"sensitive":false,"children":[]},
        {"name":"object","path":"object","type":"string","required":false,"description":"响应对象类型，通常为 video。","example":"video","deprecated":false,"sensitive":false,"children":[]},
        {"name":"model","path":"model","type":"string","required":false,"description":"本次任务实际使用的模型名称。","example":"seedance-2.5","deprecated":false,"sensitive":false,"children":[]},
        {"name":"status","path":"status","type":"string","required":true,"description":"异步任务状态。","enum_values":["queued","processing","completed","succeeded","failed","unknown"],"deprecated":false,"sensitive":false,"children":[]},
        {"name":"progress","path":"progress","type":"integer","required":false,"description":"任务进度，范围 0–100。","minimum":0,"maximum":100,"deprecated":false,"sensitive":false,"children":[]},
        {"name":"created_at","path":"created_at","type":"integer","required":false,"description":"任务创建时间，Unix 秒级时间戳。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"completed_at","path":"completed_at","type":"integer","required":false,"description":"任务完成时间，Unix 秒级时间戳；未完成时不返回。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"prompt","path":"prompt","type":"string","required":false,"description":"本次任务使用的视频提示词。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"video_url","path":"video_url","type":"string","required":false,"description":"视频生成完成后的访问地址。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"error","path":"error","type":"object","required":false,"description":"任务失败时返回的错误信息。","deprecated":false,"sensitive":false,"children":[
          {"name":"code","path":"error.code","type":"string","required":false,"description":"视频任务失败错误码。","deprecated":false,"sensitive":false,"children":[]},
          {"name":"message","path":"error.message","type":"string","required":false,"description":"视频任务失败原因。","deprecated":false,"sensitive":false,"children":[]}
        ]}
      ]
    }
    $response$::jsonb;
    grok_request jsonb := $grok_request$
    {
      "fields": [
        {"name":"model","path":"model","type":"string","required":true,"description":"Grok 视频模型名称。","example":"grok-imagine-video","deprecated":false,"sensitive":false,"children":[]},
        {"name":"prompt","path":"prompt","type":"string","required":true,"description":"视频生成提示词。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"image_url","path":"image_url","type":"string","required":false,"description":"图生视频使用的参考图片地址。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"duration","path":"duration","type":"integer","required":false,"description":"目标视频时长，单位为秒；Grok 接口支持 1–15 秒。","default_value":5,"minimum":1,"maximum":15,"deprecated":false,"sensitive":false,"children":[]},
        {"name":"aspect_ratio","path":"aspect_ratio","type":"string","required":false,"description":"输出视频宽高比。","enum_values":["16:9","1:1","9:16"],"deprecated":false,"sensitive":false,"children":[]},
        {"name":"resolution","path":"resolution","type":"string","required":false,"description":"输出分辨率档位；实际可用值由模型决定。","enum_values":["480p","720p","1080p"],"deprecated":false,"sensitive":false,"children":[]},
        {"name":"first_frame_url","path":"first_frame_url","type":"string","required":false,"description":"首帧参考图片地址。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"last_frame_url","path":"last_frame_url","type":"string","required":false,"description":"尾帧参考图片地址。","deprecated":false,"sensitive":false,"children":[]},
        {"name":"input_references","path":"input_references","type":"array","required":false,"description":"多张参考图片地址列表。","deprecated":false,"sensitive":false,"children":[
          {"name":"item","path":"input_references[]","type":"string","required":false,"description":"参考图片 URL。","deprecated":false,"sensitive":false,"children":[]}
        ]},
        {"name":"extra_params","path":"extra_params","type":"object","required":false,"description":"Grok 渠道扩展参数对象。","deprecated":false,"sensitive":false,"children":[
          {"name":"generate_audio","path":"extra_params.generate_audio","type":"boolean","required":false,"description":"是否同时生成音频；仅在模型支持时生效。","default_value":true,"deprecated":false,"sensitive":false,"children":[]}
        ]}
      ]
    }
    $grok_request$::jsonb;
BEGIN
    UPDATE api_interfaces
       SET response_schema = video_response,
           description = '即梦 seedance-2.5 视频异步任务接口；返回字段覆盖提交、轮询、完成和失败状态。',
           updated_at = now(),
           version = version + 1
     WHERE interface_code = 'jimeng_video';
    GET DIAGNOSTICS updated_jimeng = ROW_COUNT;

    UPDATE api_interfaces
       SET request_schema = grok_request,
           response_schema = jsonb_set(video_response, '{fields}', (video_response -> 'fields') - 1, true),
           description = 'Grok 视频异步任务接口；保留 image_url/duration 兼容字段并补充帧图、分辨率和扩展参数。',
           updated_at = now(),
           version = version + 1
     WHERE interface_code = 'grok_video';
    GET DIAGNOSTICS updated_grok = ROW_COUNT;

    IF updated_jimeng <> 1 OR updated_grok <> 1 THEN
        RAISE EXCEPTION 'V30 expected one jimeng_video and one grok_video row, updated jimeng %, grok %', updated_jimeng, updated_grok;
    END IF;
END
$migration$;
