"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { adminErrorMessage, formatAdminTime, listAuditLogs, type AuditLog } from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import {
  AdminPageHeader, Drawer, EmptyState, ErrorState, IconButton, LoadingState,
  Pagination, SearchBox, SecondaryButton, SelectFilter, StatusPill,
} from "./AdminUi";

const resourceLabels: Record<string, string> = {
  user: "用户", supplier: "供应商", ai_model: "模型", channel: "渠道",
  channel_model: "渠道模型", routing_group: "服务分组", group_route: "路由规则",
  api_key: "API 令牌", auth: "认证",
};

function formattedJson(value: Record<string, unknown>): string {
  return JSON.stringify(value ?? {}, null, 2);
}

/** 只读展示管理员与系统操作审计，快照内容在服务端写入前已执行敏感字段拦截。 */
export function AuditLogsAdminPage() {
  const [items, setItems] = useState<AuditLog[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [resourceType, setResourceType] = useState("all");
  const [actorType, setActorType] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<AuditLog | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const result = await listAuditLogs({ page, pageSize: 20, query, resourceType, actorType });
      setItems(result.items); setTotal(result.total);
    } catch (loadError) { setError(adminErrorMessage(loadError)); }
    finally { setLoading(false); }
  }, [actorType, page, query, resourceType]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 260 : 0);
    return () => window.clearTimeout(timer);
  }, [load, query]);

  const summary = useMemo(() => ({
    users: items.filter(item => item.actor_type === "user" || item.actor_type === "admin").length,
    system: items.filter(item => item.actor_type === "system").length,
    changes: items.filter(item => Object.keys(item.after_data ?? {}).length > 0).length,
  }), [items]);

  return <div>
    <AdminPageHeader eyebrow="SECURITY / AUDIT TRAIL" title="审计日志" description="按操作人、动作、资源和请求 ID 追踪关键变更；日志只读且不提供删除入口。"/>
    <section className="admin-summary-strip admin-animate"><div><span>日志总数</span><b>{total}</b></div><div><span>本页人工操作</span><b>{summary.users}</b></div><div><span>系统操作</span><b>{summary.system}</b></div><div><span>含变更快照</span><b className="good">{summary.changes}</b></div><p><AdminIcon name="shield" size={15}/>审计快照递归拦截密码、API 令牌、上游 APIKey、Authorization 与其他凭证字段。</p></section>
    <section className="admin-list-panel admin-animate">
      <div className="admin-list-toolbar"><SearchBox value={query} onChange={value => { setPage(1); setQuery(value); }} placeholder="搜索动作、资源 ID、请求 ID 或操作人"/><div><SelectFilter label="资源类型筛选" value={resourceType} onChange={value => { setPage(1); setResourceType(value); }}><option value="all">全部资源</option>{Object.entries(resourceLabels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</SelectFilter><SelectFilter label="主体类型筛选" value={actorType} onChange={value => { setPage(1); setActorType(value); }}><option value="all">全部主体</option><option value="user">用户</option><option value="admin">管理员</option><option value="system">系统</option></SelectFilter><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/></SecondaryButton></div></div>
      {loading && !items.length ? <LoadingState label="正在读取安全审计记录…"/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="没有匹配的审计记录" description="调整资源类型、主体类型或搜索条件；审计记录由服务端关键操作自动写入。"/> : <div className="admin-table-wrap"><table className="admin-table audit-log-table"><thead><tr><th>时间与操作人</th><th>动作</th><th>资源</th><th>来源</th><th>请求 ID</th><th aria-label="操作"/></tr></thead><tbody>{items.map(item => <tr key={item.id}><td><b className="table-primary">{formatAdminTime(item.created_at)}</b><small>{item.actor_display_name || (item.actor_type === "system" ? "系统任务" : "未知用户")}</small></td><td><code className="audit-action">{item.action}</code><small><StatusPill value={item.actor_type}/></small></td><td><b className="table-primary">{resourceLabels[item.resource_type] ?? item.resource_type}</b><small title={item.resource_id ?? ""}>{item.resource_id || "—"}</small></td><td><b className="table-primary">{item.ip_address || "内部来源"}</b><small>{item.user_agent_hash ? `UA ${item.user_agent_hash.slice(0, 12)}…` : "无 User-Agent 摘要"}</small></td><td><code className="request-id" title={item.request_id ?? ""}>{item.request_id || "—"}</code></td><td><div className="row-actions"><IconButton icon="logs" label="查看审计详情" onClick={() => setSelected(item)}/></div></td></tr>)}</tbody></table></div>}
      <Pagination page={page} pageSize={20} total={total} onChange={setPage}/>
    </section>

    <Drawer open={Boolean(selected)} title="审计详情" description="服务端生成的只读安全快照；任何凭证类字段在写入前都会被拒绝。" onClose={() => setSelected(null)} footer={<SecondaryButton onClick={() => setSelected(null)}>关闭</SecondaryButton>}>
      {selected && <div className="audit-detail"><section><span>EVENT</span><dl><div><dt>动作</dt><dd><code>{selected.action}</code></dd></div><div><dt>主体</dt><dd>{selected.actor_display_name || selected.actor_type}</dd></div><div><dt>资源</dt><dd>{resourceLabels[selected.resource_type] ?? selected.resource_type} · {selected.resource_id || "—"}</dd></div><div><dt>来源 IP</dt><dd>{selected.ip_address || "内部来源"}</dd></div><div><dt>请求 ID</dt><dd><code>{selected.request_id || "—"}</code></dd></div><div><dt>记录时间</dt><dd>{new Date(selected.created_at).toLocaleString("zh-CN", { hour12: false })}</dd></div></dl></section><section><span>BEFORE</span><pre>{formattedJson(selected.before_data)}</pre></section><section><span>AFTER</span><pre>{formattedJson(selected.after_data)}</pre></section><div className="admin-security-note"><AdminIcon name="shield" size={18}/><div><b>只读审计边界</b><p>此页面不提供编辑或删除。若快照含凭证类键名，服务端会拒绝原业务写入并回滚事务。</p></div></div></div>}
    </Drawer>
  </div>;
}
