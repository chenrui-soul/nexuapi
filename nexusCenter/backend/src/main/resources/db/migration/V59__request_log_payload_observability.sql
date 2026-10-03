-- 调用日志只保存结构化脱敏摘要，不保存请求/响应正文或凭证。
ALTER TABLE request_logs
    ADD COLUMN request_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN response_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN request_payload_size BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN response_payload_size BIGINT NOT NULL DEFAULT 0;

ALTER TABLE request_logs
    ADD CONSTRAINT request_logs_request_summary_object
        CHECK (jsonb_typeof(request_summary) = 'object'),
    ADD CONSTRAINT request_logs_response_summary_object
        CHECK (jsonb_typeof(response_summary) = 'object'),
    ADD CONSTRAINT request_logs_payload_sizes_non_negative
        CHECK (request_payload_size >= 0 AND response_payload_size >= 0);

COMMENT ON COLUMN request_logs.request_summary IS '脱敏请求参数摘要，仅包含模型、选项和数量等结构化字段，不含正文或凭证。';
COMMENT ON COLUMN request_logs.response_summary IS '脱敏响应结果摘要，仅包含状态、用量和资源数量等结构化字段，不含正文或二进制内容。';
COMMENT ON COLUMN request_logs.request_payload_size IS '原始请求体大小，单位字节；仅保存大小，不保存原文。';
COMMENT ON COLUMN request_logs.response_payload_size IS '原始响应体大小，单位字节；仅保存大小，不保存原文。';

COMMENT ON COLUMN request_logs_default.request_summary IS '继承自主表的脱敏请求参数摘要。';
COMMENT ON COLUMN request_logs_default.response_summary IS '继承自主表的脱敏响应结果摘要。';
COMMENT ON COLUMN request_logs_default.request_payload_size IS '继承自主表的原始请求体大小，单位字节。';
COMMENT ON COLUMN request_logs_default.response_payload_size IS '继承自主表的原始响应体大小，单位字节。';
