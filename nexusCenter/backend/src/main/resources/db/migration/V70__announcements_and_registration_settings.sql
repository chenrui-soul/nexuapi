CREATE TABLE platform_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    registration_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO platform_settings (id) VALUES (1);

CREATE TABLE announcements (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published', 'withdrawn')),
    version BIGINT NOT NULL DEFAULT 0,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_announcements_published ON announcements (published_at DESC, id) WHERE status = 'published';
CREATE TABLE announcement_reads (
    announcement_id UUID NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, announcement_id)
);
COMMENT ON TABLE announcements IS '平台全员站内公告，草稿与撤回内容不向用户展示。';
COMMENT ON TABLE announcement_reads IS '公告已读状态，按用户独立保存。';
COMMENT ON TABLE platform_settings IS '即时生效的平台配置，版本号防止并发覆盖。';
