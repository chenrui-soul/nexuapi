# 图片生成与编辑接口

## 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口地址 | `POST https://nexusapi.center/v1/images/generations` |
| 鉴权方式 | `Authorization: Bearer <API_KEY>` |
| 服务分组 | `X-Nexus-Group: gpt_tj`（可选，使用 Key 默认分组时可省略） |
| 普通生成 | `multipart/form-data` |
| URL 引用图编辑 | `application/json` |
| 推荐模型 | `gpt-image-2.5` |

同一路径根据 `Content-Type` 选择请求格式。新增的 JSON 图片编辑请求不会改变原有 multipart 图片生成行为。

## URL 引用图编辑

### 请求头

```http
Authorization: Bearer sk-你的API-Key
Content-Type: application/json
X-Nexus-Group: gpt_tj
```

### 请求参数

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `model` | string | 是 | - | 模型名称，例如 `gpt-image-2.5` |
| `prompt` | string | 是 | - | 图片编辑要求 |
| `images` | string[] | 否 | `[]` | 引用图片 URL，最多 5 个；只支持绝对 HTTP/HTTPS URL |
| `n` | integer | 否 | `1` | 生成数量，范围 1-10 |
| `aspect_ratio` | string | 否 | `1:1` | 输出宽高比 |
| `quality` | string | 否 | `medium` | 支持 `low`、`medium` |
| `resolution` | string | 否 | 上游默认 | 支持 `1k`、`2k` |
| `extra_params.resolution` | string | 否 | 上游默认 | `resolution` 的嵌套写法，不能与顶层值冲突 |

`images` 中必须填写纯 URL，不能填写 Markdown 链接，例如 `[图片](https://example.com/a.png)`。

### cURL 示例

```bash
curl "https://nexusapi.center/v1/images/generations" \
  -H "Authorization: Bearer $CAICAI_API_KEY" \
  -H "Content-Type: application/json" \
  -H "X-Nexus-Group: gpt_tj" \
  -d '{
    "model": "gpt-image-2.5",
    "prompt": "去掉截图黑边和界面按钮，使用纯白背景。保持裙子的轮廓、配色、领口、袖口、口袋，以及棋盘格、花朵和链条图案的位置",
    "aspect_ratio": "1:1",
    "images": [
      "https://example.com/reference.png"
    ]
  }'
```

### 成功响应

```json
{
  "created": 1789673445,
  "data": [
    {
      "url": "https://example.com/edited.png"
    }
  ],
  "usage": {
    "total_tokens": 1931,
    "input_tokens": 1545,
    "output_tokens": 386,
    "input_tokens_details": {
      "text_tokens": 63,
      "image_tokens": 1482
    },
    "output_tokens_details": {
      "image_tokens": 386
    }
  },
  "model": "gpt-image-2.5"
}
```

## 普通图片生成

原有接口继续使用 `multipart/form-data`。没有参考图时只提交文本字段；上传参考图时可重复提交 `images` 文件字段。

```bash
curl "https://nexusapi.center/v1/images/generations" \
  -H "Authorization: Bearer $CAICAI_API_KEY" \
  -H "X-Nexus-Group: gpt_tj" \
  -F "model=gpt-image-2.5" \
  -F "prompt=一张电影感黄昏城市照片" \
  -F "n=1" \
  -F "quality=medium" \
  -F "aspect_ratio=1:1" \
  -F "resolution=1k"
```

上传本地参考图：

```bash
curl "https://nexusapi.center/v1/images/generations" \
  -H "Authorization: Bearer $CAICAI_API_KEY" \
  -H "X-Nexus-Group: gpt_tj" \
  -F "model=gpt-image-2.5" \
  -F "prompt=保留主体，替换为纯白背景" \
  -F "aspect_ratio=1:1" \
  -F "images=@./reference.png"
```

单张上传图片最大 20 MB，所有上传图片总计最大 50 MB，最多 5 张；支持 JPEG、PNG 和 WebP。

## 先上传文件再编辑

本地图片也可以先上传到独立文件接口，再把返回的 `url` 传入 JSON 图片编辑请求。

```bash
curl "https://nexusapi.center/v1/files/upload" \
  -F "file=@./reference.png"
```

文件上传接口不需要平台 API Key。成功后读取响应中的 `url` 字段。

## 常见错误

| HTTP 状态 | 错误码或场景 | 说明 |
| --- | --- | --- |
| 400 | `validation_error` | 参数类型、数量、URL、尺寸或文件格式不合法 |
| 401 | `unauthorized` | API Key 缺失、无效、过期或停用 |
| 402 | `insufficient_balance` | 账户余额不足 |
| 403 | `permission_denied` | Key 无权访问模型或分组 |
| 429 | `rate_limit_exceeded` | RPM、并发或其他配额超限 |
| 502 | `upstream_error` | 上游拒绝、协议异常或网络失败 |

建议每次请求设置唯一的 `X-Request-Id`，方便在调用日志中定位完整链路。
