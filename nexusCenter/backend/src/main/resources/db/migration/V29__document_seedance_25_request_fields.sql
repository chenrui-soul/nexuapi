-- V29：根据 seedance-2.5 接口页面补充即梦视频请求字段。
-- 只维护 api_interfaces 的 JSON 文档，不改变网关执行逻辑、模型关联或正式数据。
-- 字段备注必须与接口页面保持一致：duration 不是 seconds，extra_params.generate_audio 为嵌套扩展参数。
DO $migration$
DECLARE
    updated_count integer;
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
             "description": "调用的模型名称；seedance-2.5 渠道侧通常映射为 bytedance/seedance-2.5。",
             "example": "seedance-2.5",
             "deprecated": false,
             "sensitive": false,
             "children": []
           },
           {
             "name": "prompt",
             "path": "prompt",
             "type": "string",
             "required": true,
             "description": "描述需要生成的视频内容，当前视频工作台要求填写。",
             "example": "海边黄昏，镜头慢慢推近海浪",
             "deprecated": false,
             "sensitive": false,
             "children": []
           },
           {
             "name": "aspect_ratio",
             "path": "aspect_ratio",
             "type": "string",
             "required": false,
             "description": "画面宽高比例，与 resolution 配合使用。默认 16:9。",
             "default_value": "16:9",
             "enum_values": ["16:9", "4:3", "1:1", "3:4", "9:16", "21:9"],
             "deprecated": false,
             "sensitive": false,
             "children": []
           },
           {
             "name": "duration",
             "path": "duration",
             "type": "integer",
             "required": false,
             "description": "视频时长，单位为秒；默认 5，支持 4–30 秒，步长 1。",
             "default_value": 5,
             "minimum": 4,
             "maximum": 30,
             "deprecated": false,
             "sensitive": false,
             "children": []
           },
           {
             "name": "resolution",
             "path": "resolution",
             "type": "string",
             "required": false,
             "description": "分辨率档位，与 aspect_ratio 配合使用；渠道规则会映射为对应尺寸。默认 480p。",
             "default_value": "480p",
             "enum_values": ["480p", "720p"],
             "deprecated": false,
             "sensitive": false,
             "children": []
           },
           {
             "name": "first_frame_url",
             "path": "first_frame_url",
             "type": "string",
             "required": false,
             "description": "首帧图片；工作台上传后以图片 URL 传入。",
             "deprecated": false,
             "sensitive": false,
             "children": []
           },
           {
             "name": "last_frame_url",
             "path": "last_frame_url",
             "type": "string",
             "required": false,
             "description": "尾帧图片；工作台上传后以图片 URL 传入。",
             "deprecated": false,
             "sensitive": false,
             "children": []
           },
           {
             "name": "input_references",
             "path": "input_references",
             "type": "array",
             "required": false,
             "description": "用于指导视频生成的多张参考图片。",
             "deprecated": false,
             "sensitive": false,
             "children": [
               {
                 "name": "item",
                 "path": "input_references[]",
                 "type": "string",
                 "required": false,
                 "description": "参考图片 URL；上传文件由网关先转换为可传递的图片地址。",
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               }
             ]
           },
           {
             "name": "extra_params",
             "path": "extra_params",
             "type": "object",
             "required": false,
             "description": "渠道或模型扩展参数对象；未声明的扩展字段应放在此对象内。",
             "deprecated": false,
             "sensitive": false,
             "children": [
               {
                 "name": "generate_audio",
                 "path": "extra_params.generate_audio",
                 "type": "boolean",
                 "required": false,
                 "description": "是否在生成视频的同时生成音频；默认 true。",
                 "default_value": true,
                 "deprecated": false,
                 "sensitive": false,
                 "children": []
               }
             ]
           }
         ]
       }
       $request$::jsonb,
       description = '即梦 seedance-2.5 视频生成请求接口；请求字段按接口页面维护，支持首尾帧、多图参考和 extra_params 扩展。',
       updated_at = now(),
       version = version + 1
     WHERE interface_code = 'jimeng_video';

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    IF updated_count <> 1 THEN
        RAISE EXCEPTION 'V29 expected exactly one jimeng_video api_interfaces row, updated %', updated_count;
    END IF;
END
$migration$;
