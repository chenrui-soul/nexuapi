"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  adminErrorMessage, formatAdminTime, listChannelHealth, listGroupHealth, listHealthAlerts,
  listHealthChecks, probeChannelHealth, type ChannelHealth, type GroupHealth, type HealthAlert,
  type HealthCheck,
} from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import {
  AdminPageHeader, EmptyState, ErrorState, IconButton, LoadingState, SecondaryButton,
  StatusPill, Toast,
} from "./AdminUi";

type HealthTab = "channels" | "groups" | "alerts" | "history";

/**
 * 健康与告警页集中展示渠道状态、分组可路由性、告警生命周期和探测历史。
 * 页面只消费后端脱敏响应，手动探测时也不会接触或持久化渠道凭证。
 */
export function HealthAdminPage() {
  const [tab, setTab] = useState<HealthTab>("channels");
  const [channels, setChannels] = useState<ChannelHealth[]>([]);
  const [groups, setGroups] = useState<GroupHealth[]>([]);
  const [alerts, setAlerts] = useState<HealthAlert[]>([]);
  const [checks, setChecks] = useState<HealthCheck[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [probingId, setProbingId] = useState<string | null>(null);
  const [toast, setToast] = useState({ message: "", tone: "success" as "success" | "error" });

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [channelPage, groupPage, alertPage, checkPage] = await Promise.all([
        listChannelHealth({ pageSize: 100 }),
        listGroupHealth({ pageSize: 100 }),
        listHealthAlerts({ pageSize: 100 }),
        listHealthChecks({ pageSize: 100 }),
      ]);
      setChannels(channelPage.items);
      setGroups(groupPage.items);
      setAlerts(alertPage.items);
      setChecks(checkPage.items);
    } catch (loadError) {
      setError(adminErrorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const summary = useMemo(() => ({
    healthyChannels: channels.filter(item => item.channel_status === "active" && item.latest_check_status === "healthy").length,
    degradedChannels: channels.filter(item => item.channel_status === "degraded").length,
    unavailableGroups: groups.filter(item => item.health_status === "unavailable").length,
    openAlerts: alerts.filter(item => item.status === "open").length,
  }), [alerts, channels, groups]);

  const runProbe = async (channel: ChannelHealth) => {
    setProbingId(channel.channel_id);
    try {
      const result = await probeChannelHealth(channel.channel_id);
      setToast({
        message: result.outcome === "healthy"
          ? `${channel.channel_name} 探测成功，耗时 ${result.latency_ms}ms`
          : `${channel.channel_name} 探测完成：${result.category ?? result.outcome}`,
        tone: result.outcome === "healthy" ? "success" : "error",
      });
      await load();
    } catch (probeError) {
      setToast({ message: adminErrorMessage(probeError), tone: "error" });
    } finally {
      setProbingId(null);
    }
  };

  const tabs: { key: HealthTab; label: string; count: number }[] = [
    { key: "channels", label: "渠道状态", count: channels.length },
    { key: "groups", label: "分组可用性", count: groups.length },
    { key: "alerts", label: "告警事件", count: alerts.length },
    { key: "history", label: "探测历史", count: checks.length },
  ];

  return <div>
    <AdminPageHeader eyebrow="OPERATIONS / HEALTH" title="健康与告警" description="自动记录渠道探测结果和分组可用性；探测失败只形成观测信号，不会自动停止接口路由。" action={<SecondaryButton onClick={() => void load()} disabled={loading}><AdminIcon name="refresh" size={14}/>{loading ? "刷新中…" : "刷新状态"}</SecondaryButton>}/>
    <section className="admin-summary-strip admin-animate health-summary"><div><span>健康渠道</span><b className="good">{summary.healthyChannels}</b></div><div><span>波动渠道</span><b className={summary.degradedChannels ? "bad" : ""}>{summary.degradedChannels}</b></div><div><span>不可用分组</span><b className={summary.unavailableGroups ? "bad" : ""}>{summary.unavailableGroups}</b></div><div><span>打开告警</span><b className={summary.openAlerts ? "bad" : ""}>{summary.openAlerts}</b></div><p><AdminIcon name="shield" size={15}/>探测响应正文、Authorization 与渠道凭证不会进入该页面。</p></section>
    <div className="admin-tabs admin-animate" role="tablist" aria-label="健康数据分类">{tabs.map(item => <button type="button" role="tab" aria-selected={tab === item.key} className={tab === item.key ? "active" : ""} onClick={() => setTab(item.key)} key={item.key}>{item.label}<span>{item.count}</span></button>)}</div>
    <section className="admin-list-panel admin-animate health-panel">
      {loading && !channels.length && !groups.length ? <LoadingState label="正在聚合健康状态…"/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : tab === "channels" ? <ChannelHealthTable items={channels} probingId={probingId} onProbe={runProbe}/> : tab === "groups" ? <GroupHealthTable items={groups}/> : tab === "alerts" ? <HealthAlertTable items={alerts}/> : <HealthCheckTable items={checks}/>} 
    </section>
    <Toast message={toast.message} tone={toast.tone} onClose={() => setToast(current => ({ ...current, message: "" }))}/>
  </div>;
}

function ChannelHealthTable({ items, probingId, onProbe }: { items: ChannelHealth[]; probingId: string | null; onProbe: (item: ChannelHealth) => void }) {
  if (!items.length) return <EmptyState title="暂无渠道健康数据" description="创建并配置渠道后，系统会自动执行健康探测。"/>;
  return <div className="admin-table-wrap"><table className="admin-table health-channel-table"><thead><tr><th>渠道</th><th>当前状态</th><th>最近探测</th><th>最近错误</th><th>探测路径</th><th aria-label="操作"/></tr></thead><tbody>{items.map(item => <tr key={item.channel_id}><td><div className="resource-name"><span className="resource-avatar channel">{item.channel_name.charAt(0).toUpperCase()}</span><div><b>{item.channel_name}</b><small>{item.supplier_name}</small></div></div></td><td><StatusPill value={item.channel_status}/></td><td><StatusPill value={item.latest_check_status ?? "unknown"}/><small>{item.latest_latency_ms === null ? "无耗时记录" : `${item.latest_latency_ms}ms`} · {formatAdminTime(item.latest_checked_at)}</small></td><td><code className="health-summary-code">{item.latest_error_summary ?? "未记录"}</code></td><td><code className="health-path">{item.health_probe_path}</code></td><td><IconButton icon={probingId === item.channel_id ? "pulse" : "refresh"} label="立即探测" onClick={() => { if (!probingId) onProbe(item); }}/></td></tr>)}</tbody></table></div>;
}

function GroupHealthTable({ items }: { items: GroupHealth[] }) {
  if (!items.length) return <EmptyState title="暂无分组健康数据" description="配置路由分组与渠道映射后，这里会显示实时可路由数量。"/>;
  return <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>服务分组</th><th>健康状态</th><th>配置状态</th><th>能力接口</th><th>最近转换</th></tr></thead><tbody>{items.map(item => <tr key={item.group_id}><td><div className="resource-name"><span className="resource-avatar route">{item.group_name.charAt(0).toUpperCase()}</span><div><b>{item.group_name}</b><code>{item.group_code}</code></div></div></td><td><StatusPill value={item.health_status}/></td><td><StatusPill value={item.configuration_status}/></td><td><b className={item.available_route_count ? "health-route-count" : "health-danger-text"}>{item.available_route_count} / {item.configured_route_count}</b><small>当前可用 / 已配置</small></td><td>{formatAdminTime(item.latest_checked_at)}</td></tr>)}</tbody></table></div>;
}

function HealthAlertTable({ items }: { items: HealthAlert[] }) {
  if (!items.length) return <EmptyState title="当前没有健康告警" description="分组完全不可用时会自动打开告警，恢复后保留生命周期记录。"/>;
  return <div className="admin-table-wrap"><table className="admin-table health-alert-table"><thead><tr><th>告警</th><th>状态</th><th>发生次数</th><th>通知 / 抑制</th><th>打开时间</th><th>恢复时间</th></tr></thead><tbody>{items.map(item => <tr key={item.id}><td><div className="health-alert-title"><b>{item.title}</b><span>{item.summary}</span><code>{item.group_code}</code></div></td><td><StatusPill value={item.status}/></td><td><b>{item.occurrence_count}</b></td><td><b>{item.notification_count} / {item.suppressed_count}</b><small>通知批次 / 冷却抑制</small></td><td>{formatAdminTime(item.opened_at)}</td><td>{formatAdminTime(item.resolved_at)}</td></tr>)}</tbody></table></div>;
}

function HealthCheckTable({ items }: { items: HealthCheck[] }) {
  if (!items.length) return <EmptyState title="暂无探测历史" description="主动探测和分组状态转换会在这里形成脱敏记录。"/>;
  return <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>检查目标</th><th>类型</th><th>结论</th><th>耗时</th><th>安全摘要</th><th>检查时间</th></tr></thead><tbody>{items.map(item => <tr key={item.id}><td><b className="table-primary">{item.target_name ?? item.target_id}</b></td><td><span className="admin-tag">{item.target_type === "channel" ? "渠道" : "分组"}</span></td><td><StatusPill value={item.status}/></td><td>{item.latency_ms === null ? "—" : `${item.latency_ms}ms`}</td><td><code className="health-summary-code">{item.error_summary ?? "正常"}</code></td><td>{formatAdminTime(item.checked_at)}</td></tr>)}</tbody></table></div>;
}
