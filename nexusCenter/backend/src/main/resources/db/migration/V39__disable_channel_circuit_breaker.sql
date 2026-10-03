-- 暂停渠道自动熔断：保留兼容字段和健康观测，但失败不再阻断真实模型调用。

UPDATE public.channels
   SET status = 'active',
       consecutive_failures = 0,
       circuit_open_until = NULL,
       updated_at = now(),
       version = version + 1
 WHERE status = 'circuit_open'
    OR circuit_open_until IS NOT NULL;

-- 按取消熔断后的可调用口径重算供应商健康状态，避免旧 unavailable 结论继续拦截 Gateway。
UPDATE public.suppliers s
   SET health_status = aggregate.health_status,
       last_health_checked_at = now(),
       updated_at = CASE
           WHEN s.health_status IS DISTINCT FROM aggregate.health_status THEN now()
           ELSE s.updated_at
       END,
       version = CASE
           WHEN s.health_status IS DISTINCT FROM aggregate.health_status THEN s.version + 1
           ELSE s.version
       END
  FROM (
        SELECT s2.id AS supplier_id,
               CASE
                   WHEN count(c.id) FILTER (WHERE c.status != 'disabled') = 0 THEN 'unconfigured'
                   WHEN count(c.id) FILTER (WHERE c.status IN ('active', 'degraded')) = 0 THEN 'unavailable'
                   WHEN count(c.id) FILTER (WHERE c.status = 'degraded') > 0 THEN 'degraded'
                   ELSE 'healthy'
               END AS health_status
          FROM public.suppliers s2
          LEFT JOIN public.channels c ON c.supplier_id = s2.id
         GROUP BY s2.id
  ) aggregate
 WHERE s.id = aggregate.supplier_id;

-- 只关闭已经恢复出可用能力的旧分组告警；真正没有活动渠道的分组仍保持告警状态。
WITH available_groups AS (
    SELECT DISTINCT g.id
      FROM public.routing_groups g
      JOIN public.routing_group_models rgm
        ON rgm.group_id = g.id AND rgm.source_status = 'active'
      JOIN public.ai_models m ON m.id = rgm.model_id AND m.status = 'active'
      JOIN public.routing_group_suppliers rgs
        ON rgs.group_id = g.id AND rgs.status = 'active'
      JOIN public.routing_group_supplier_credentials rgsc
        ON rgsc.group_id = g.id AND rgsc.supplier_id = rgs.supplier_id
       AND rgsc.status = 'active' AND rgsc.encrypted_credential IS NOT NULL
      JOIN public.suppliers s ON s.id = rgs.supplier_id AND s.status = 'active'
      JOIN public.channels c
        ON c.supplier_id = s.id AND c.status IN ('active', 'degraded')
      JOIN public.channel_models cm
        ON cm.channel_id = c.id AND cm.model_id = m.id AND cm.status = 'active'
     WHERE g.status = 'active'
       AND (
            c.endpoint_type = 'multimodal'
            OR c.endpoint_type = m.capability_type
            OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text')
       )
)
UPDATE public.health_alerts a
   SET status = 'resolved',
       resolved_at = coalesce(resolved_at, now()),
       last_seen_at = now(),
       updated_at = now()
 WHERE a.alert_type = 'group_unavailable'
   AND a.status = 'open'
   AND a.group_id IN (SELECT id FROM available_groups);

COMMENT ON COLUMN public.channels.status IS
    '渠道业务状态：active、disabled、degraded；circuit_open 仅保留历史兼容，当前版本不再自动写入。';
COMMENT ON COLUMN public.channels.consecutive_failures IS
    '连续上游失败观测次数，仅用于健康诊断，不参与路由阻断或自动熔断。';
COMMENT ON COLUMN public.channels.circuit_open_until IS
    '历史熔断截止时间兼容字段；当前版本不再写入，也不参与 Gateway 选路。';
