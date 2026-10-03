-- Wave 7A 收尾：受控探测路径、分组告警生命周期和通知抑制状态。

ALTER TABLE channels
    ADD COLUMN health_probe_path VARCHAR(256) NOT NULL DEFAULT '/models';

ALTER TABLE channels
    ADD CONSTRAINT channels_health_probe_path_safe CHECK (
        health_probe_path ~ '^/[A-Za-z0-9._~!$&''()*+,;=:@/-]*$'
        AND position('..' IN health_probe_path) = 0
        AND position('//' IN health_probe_path) = 0
    );

CREATE TABLE health_alerts (
    id BIGSERIAL PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES routing_groups(id) ON DELETE CASCADE,
    alert_type VARCHAR(32) NOT NULL DEFAULT 'group_unavailable',
    status VARCHAR(16) NOT NULL DEFAULT 'open',
    severity VARCHAR(16) NOT NULL DEFAULT 'critical',
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    notification_count INTEGER NOT NULL DEFAULT 0,
    suppressed_count INTEGER NOT NULL DEFAULT 0,
    opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_notified_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT health_alerts_type_valid CHECK (alert_type IN ('group_unavailable')),
    CONSTRAINT health_alerts_status_valid CHECK (status IN ('open', 'resolved')),
    CONSTRAINT health_alerts_severity_valid CHECK (severity IN ('critical')),
    CONSTRAINT health_alerts_counts_valid CHECK (
        occurrence_count > 0 AND notification_count >= 0 AND suppressed_count >= 0
    ),
    CONSTRAINT health_alerts_resolution_consistent CHECK (
        (status = 'open' AND resolved_at IS NULL)
        OR (status = 'resolved' AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_health_alerts_open_group_type
    ON health_alerts (group_id, alert_type)
    WHERE status = 'open';

CREATE INDEX idx_health_alerts_admin_list
    ON health_alerts (status, updated_at DESC, id DESC);

CREATE INDEX idx_health_alerts_group_time
    ON health_alerts (group_id, opened_at DESC);

COMMENT ON COLUMN public.channels.health_probe_path IS '拼接在渠道 API 基础地址后的受控相对健康探测路径；禁止绝对 URL、查询参数、片段和路径穿越。';

COMMENT ON TABLE public.health_alerts IS '分组健康告警生命周期表，用于记录故障打开、重复抑制、通知次数和恢复时间。';
COMMENT ON COLUMN public.health_alerts.id IS '健康告警自增标识。';
COMMENT ON COLUMN public.health_alerts.group_id IS '发生不可用状态的路由分组标识。';
COMMENT ON COLUMN public.health_alerts.alert_type IS '告警类型；当前支持 group_unavailable。';
COMMENT ON COLUMN public.health_alerts.status IS '告警生命周期状态：open 或 resolved。';
COMMENT ON COLUMN public.health_alerts.severity IS '告警严重度；当前分组完全不可用为 critical。';
COMMENT ON COLUMN public.health_alerts.title IS '不含凭证和上游正文的告警标题。';
COMMENT ON COLUMN public.health_alerts.summary IS '固定模板生成的脱敏告警摘要。';
COMMENT ON COLUMN public.health_alerts.occurrence_count IS '同一打开告警累计观察到不可用状态的次数。';
COMMENT ON COLUMN public.health_alerts.notification_count IS '该告警已实际发送的站内通知次数。';
COMMENT ON COLUMN public.health_alerts.suppressed_count IS '冷却窗口内被抑制的重复通知次数。';
COMMENT ON COLUMN public.health_alerts.opened_at IS '告警首次打开时间。';
COMMENT ON COLUMN public.health_alerts.last_seen_at IS '最近一次确认分组仍不可用的时间。';
COMMENT ON COLUMN public.health_alerts.last_notified_at IS '最近一次发送故障通知的时间。';
COMMENT ON COLUMN public.health_alerts.resolved_at IS '分组恢复可用并解析告警的时间。';
COMMENT ON COLUMN public.health_alerts.created_at IS '告警记录创建时间。';
COMMENT ON COLUMN public.health_alerts.updated_at IS '告警记录最后更新时间。';
