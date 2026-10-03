-- 独立文件上传能力：不关联用户、API Key、模型、服务分组、计费或上游渠道，仅记录上传元数据和结果。
CREATE TABLE file_upload_records (
    id UUID PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    upstream_provider VARCHAR(64) NOT NULL,
    upstream_file_id VARCHAR(255),
    upstream_file_url TEXT,
    status VARCHAR(24) NOT NULL,
    upstream_status INTEGER,
    error_code VARCHAR(64),
    error_summary VARCHAR(255),
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT file_upload_records_size_ck CHECK (file_size_bytes > 0),
    CONSTRAINT file_upload_records_status_ck CHECK (status IN ('uploading', 'completed', 'failed')),
    CONSTRAINT file_upload_records_upstream_status_ck CHECK (
        upstream_status IS NULL OR upstream_status BETWEEN 100 AND 599
    )
);

CREATE INDEX idx_file_upload_records_created
    ON file_upload_records (created_at DESC);
CREATE INDEX idx_file_upload_records_request
    ON file_upload_records (request_id);
CREATE INDEX idx_file_upload_records_incomplete
    ON file_upload_records (status, created_at) WHERE status <> 'completed';

COMMENT ON TABLE file_upload_records IS '独立公共文件上传审计记录；只保存元数据和上游结果，不关联用户或API密钥，不保存文件正文。';
COMMENT ON COLUMN file_upload_records.id IS '文件上传记录全局唯一标识。';
COMMENT ON COLUMN file_upload_records.request_id IS '平台请求标识，用于关联HTTP响应头和运行日志。';
COMMENT ON COLUMN file_upload_records.original_filename IS '移除客户端路径和控制字符后的文件名。';
COMMENT ON COLUMN file_upload_records.content_type IS '转发给上游的MIME类型；非法或通配类型按application/octet-stream记录。';
COMMENT ON COLUMN file_upload_records.file_size_bytes IS '文件正文大小，单位为字节。';
COMMENT ON COLUMN file_upload_records.content_sha256 IS '文件正文SHA-256十六进制摘要，用于审计和重复文件排查。';
COMMENT ON COLUMN file_upload_records.upstream_provider IS '独立上传服务提供方编码，当前固定为caicai。';
COMMENT ON COLUMN file_upload_records.upstream_file_id IS '从上游成功响应提取的文件标识。';
COMMENT ON COLUMN file_upload_records.upstream_file_url IS '从上游成功响应提取的文件访问或下载地址。';
COMMENT ON COLUMN file_upload_records.status IS '上传状态：uploading、completed或failed。';
COMMENT ON COLUMN file_upload_records.upstream_status IS '上游HTTP状态码；连接失败时为空。';
COMMENT ON COLUMN file_upload_records.error_code IS '脱敏后的固定错误分类。';
COMMENT ON COLUMN file_upload_records.error_summary IS '不含响应正文、地址和凭证的错误摘要。';
COMMENT ON COLUMN file_upload_records.response_body IS '上游成功JSON响应，便于还原文件标识和扩展字段。';
COMMENT ON COLUMN file_upload_records.created_at IS '上传记录创建时间。';
COMMENT ON COLUMN file_upload_records.completed_at IS '上传成功或失败的收口时间。';

ALTER TABLE api_interfaces DROP CONSTRAINT api_interfaces_capability_valid;
ALTER TABLE api_interfaces ADD CONSTRAINT api_interfaces_capability_valid CHECK (
    capability_type IN ('text', 'image', 'audio', 'video', 'embedding', 'multimodal', 'file')
);

INSERT INTO api_interfaces (
    interface_code, interface_name, interface_version, capability_type, transport_mode,
    http_method, public_path, request_content_type, request_schema, response_schema,
    description, status
) VALUES (
    'file_upload', '文件上传', 'v1', 'file', 'sync', 'POST', '/v1/files/upload',
    'multipart/form-data',
    '{"fields":[
      {"name":"file","path":"file","type":"file","required":true,"description":"用户上传的单个文件，最大20MB；文件正文只转发给上游，不写入平台数据库。","children":[]}
    ]}'::jsonb,
    '{"fields":[
      {"name":"id","path":"id","type":"string","required":false,"description":"上游返回的文件标识。","children":[]},
      {"name":"object","path":"object","type":"string","required":false,"description":"上游资源类型。","children":[]},
      {"name":"filename","path":"filename","type":"string","required":false,"description":"上游确认的文件名。","children":[]},
      {"name":"url","path":"url","type":"string","required":false,"description":"上游返回的文件访问地址。","children":[]}
    ]}'::jsonb,
    '独立公共文件上传接口；不要求平台API Key，不关联用户、模型、服务分组或计费，成功响应透传菜菜文件上传JSON。',
    'active'
) ON CONFLICT (interface_code) DO UPDATE SET
    interface_name = EXCLUDED.interface_name,
    interface_version = EXCLUDED.interface_version,
    capability_type = EXCLUDED.capability_type,
    transport_mode = EXCLUDED.transport_mode,
    http_method = EXCLUDED.http_method,
    public_path = EXCLUDED.public_path,
    request_content_type = EXCLUDED.request_content_type,
    request_schema = EXCLUDED.request_schema,
    response_schema = EXCLUDED.response_schema,
    description = EXCLUDED.description,
    status = 'active',
    updated_at = now();
