-- 管理侧排障载荷。值在网关内存中递归脱敏、限长后写入，禁止凭证和二进制内容落库。
ALTER TABLE request_logs
    ADD COLUMN request_detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN response_detail JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE request_logs
    ADD CONSTRAINT request_logs_request_detail_object
        CHECK (jsonb_typeof(request_detail) = 'object'),
    ADD CONSTRAINT request_logs_response_detail_object
        CHECK (jsonb_typeof(response_detail) = 'object');

COMMENT ON COLUMN request_logs.request_detail IS '管理员排障用的递归脱敏请求参数，限长保存，不含凭证、Cookie、令牌或二进制内容。';
COMMENT ON COLUMN request_logs.response_detail IS '管理员排障用的递归脱敏返回结果，限长保存，不含凭证、Cookie、令牌或二进制内容。';

COMMENT ON COLUMN request_logs_default.request_detail IS '继承自主表的管理员脱敏请求参数。';
COMMENT ON COLUMN request_logs_default.response_detail IS '继承自主表的管理员脱敏返回结果。';
