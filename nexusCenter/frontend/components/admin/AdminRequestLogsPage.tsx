"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { adminErrorMessage, formatAdminTime, formatDashboardAmount, getAdminRequestLog, listAdminRequestLogs, type AdminRequestLog } from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, Drawer, EmptyState, ErrorState, IconButton, LoadingState, Pagination, SearchBox, SecondaryButton, SelectFilter, StatusPill } from "./AdminUi";

const jsonText = (value: unknown) => {
  try { return JSON.stringify(value ?? {}, null, 2); }
  catch { return "无法展示该载荷（数据格式异常）"; }
};
const objectValue = (value: unknown): Record<string, unknown> =>
  value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
const numberValue = (value: unknown) => typeof value === "number" && Number.isFinite(value) ? value : Number(value ?? 0) || 0;
const durationText = (value: number | null | undefined) => value == null ? "—" : value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(2)} s`;

function collectImageUrls(value: unknown, urls: string[] = [], depth = 0): string[] {
  if (depth > 8 || value == null) return urls;
  if (Array.isArray(value)) {
    value.forEach(item => collectImageUrls(item, urls, depth + 1));
    return urls;
  }
  if (typeof value !== "object") return urls;
  Object.entries(value as Record<string, unknown>).forEach(([key, nested]) => {
    if (typeof nested === "string" && /^(https?:\/\/)/i.test(nested)
      && /^(url|image_url|image)$/i.test(key)) {
      if (!urls.includes(nested)) urls.push(nested);
    } else {
      collectImageUrls(nested, urls, depth + 1);
    }
  });
  return urls.slice(0, 12);
}

function PayloadBlock({ title, value, bytes }: { title: string; value: unknown; bytes: number | null | undefined }) {
  const safeValue = objectValue(value);
  const truncated = Boolean(safeValue.truncated);
  const imageUrls = title.includes("RESPONSE") ? collectImageUrls(safeValue) : [];
  return <section className="admin-request-payload"><header><div><span>{title}</span><b>完整业务字段（业务内容原样展示）</b></div><small>{bytes ? `${numberValue(bytes).toLocaleString("zh-CN")} bytes 原始大小` : "无原始大小"}</small></header>{imageUrls.length > 0 && <div className="admin-request-image-grid" aria-label="图片生成结果">{imageUrls.map((url, index) => <a href={url} target="_blank" rel="noreferrer" key={`${url}-${index}`}><img src={url} alt={`生成图片 ${index + 1}`} loading="lazy" referrerPolicy="no-referrer"/><span>打开原图</span></a>)}</div>}<pre>{jsonText(safeValue)}</pre>{(truncated || safeValue.redacted) && <p><AdminIcon name="shield" size={14}/> 凭证、二进制和超长内容仍会隐藏或截断；业务字段已完整展示。</p>}</section>;
}

export function AdminRequestLogsPage() {
  const [items, setItems] = useState<AdminRequestLog[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [model, setModel] = useState("all");
  const [period, setPeriod] = useState("7d");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<AdminRequestLog | null>(null);
  // Keep the log table within the one-screen admin layout. Additional records
  // are available through the pagination controls instead of page scrolling.
  const [pageSize, setPageSize] = useState(11);

  useEffect(() => {
    const updatePageSize = () => {
      if (window.innerWidth < 761) {
        setPageSize(8);
        return;
      }
      // 414px accounts for the page header, summary strip, toolbar, table
      // header, pager and shell spacing; 72px is the measured row height.
      setPageSize(Math.max(3, Math.min(20, Math.floor((window.innerHeight - 414) / 72))));
    };
    updatePageSize();
    window.addEventListener("resize", updatePageSize);
    return () => window.removeEventListener("resize", updatePageSize);
  }, []);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const result = await listAdminRequestLogs({ page, pageSize, query, status: status === "all" ? undefined : status as "success" | "failed", model: model === "all" ? undefined : model, period: period as "24h" | "7d" | "30d" });
      setItems(result.items); setTotal(result.total);
    } catch (reason) { setError(adminErrorMessage(reason)); }
    finally { setLoading(false); }
  }, [model, page, period, query, status]);

  useEffect(() => { const timer = window.setTimeout(() => void load(), query ? 260 : 0); return () => window.clearTimeout(timer); }, [load, query]);

  const models = useMemo(() => Array.from(new Set(items.map(item => item.public_model))).sort(), [items]);
  const successCount = items.filter(item => item.status === "success").length;
  const totalTokens = items.reduce((sum, item) => sum + item.input_tokens + item.output_tokens, 0);
  const openDetail = async (item: AdminRequestLog) => {
    setSelected(item);
    try { setSelected(await getAdminRequestLog(item.request_id)); } catch { setSelected(item); }
  };

  return <div className="request-logs-page">
    <AdminPageHeader eyebrow="OBSERVABILITY / REQUEST PAYLOADS" title="调用日志" description="查看所有用户的调用记录、完整业务入参和返回结果；图片生成结果支持直接预览。"/>
    <section className="admin-summary-strip admin-animate"><div><span>筛选结果</span><b>{total.toLocaleString("zh-CN")}</b></div><div><span>当前页成功</span><b className="good">{successCount}</b></div><div><span>当前页 Token</span><b>{totalTokens.toLocaleString("zh-CN")}</b></div><div><span>当前页扣费</span><b>{formatDashboardAmount(items.reduce((sum, item) => sum + Number(item.billed_amount || 0), 0).toString())}</b></div><p><AdminIcon name="shield" size={15}/> 业务字段完整展示；凭证、二进制和超长内容仍受保护。</p></section>
    <section className="admin-list-panel admin-animate">
      <div className="admin-list-toolbar"><SearchBox value={query} onChange={value => { setPage(1); setQuery(value); }} placeholder="搜索请求 ID、用户、模型、供应商、令牌或分组"/><div><SelectFilter label="状态筛选" value={status} onChange={value => { setPage(1); setStatus(value); }}><option value="all">全部状态</option><option value="success">成功</option><option value="failed">失败</option></SelectFilter><SelectFilter label="模型筛选" value={model} onChange={value => { setPage(1); setModel(value); }}><option value="all">全部模型</option>{models.map(value => <option value={value} key={value}>{value}</option>)}</SelectFilter><SelectFilter label="时间范围" value={period} onChange={value => { setPage(1); setPeriod(value); }}><option value="24h">最近 24 小时</option><option value="7d">最近 7 天</option><option value="30d">最近 30 天</option></SelectFilter><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/></SecondaryButton></div></div>
      {loading && !items.length ? <LoadingState label="正在读取调用日志…"/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="没有匹配的调用记录" description="调整搜索条件或时间范围；调用日志由网关自动写入。"/> : <div className="admin-table-wrap"><table className="admin-table admin-request-log-table"><thead><tr><th>时间 / 用户</th><th>模型</th><th>供应商</th><th>结果</th><th>Token</th><th>请求 ID</th><th aria-label="操作"/></tr></thead><tbody>{items.map(item => <tr key={item.id}><td><b className="table-primary">{formatAdminTime(item.started_at)}</b><small>{item.user_display_name || "未知用户"} · {item.api_key_name || "未命名 Key"}</small></td><td><b className="table-primary">{item.public_model}</b><small>{item.service_group_name || "默认分组"}</small></td><td className="supplier-cell"><b className="table-primary" title={item.supplier_name || "未记录"}>{item.supplier_name || "未记录"}</b><small title={item.supplier_code || "未记录供应商编码"}>{item.supplier_code || "—"}</small></td><td><StatusPill value={item.status}/><small>{item.status_code ?? "—"} · {durationText(item.duration_ms)}</small></td><td><b className="table-primary">{(item.input_tokens + item.output_tokens).toLocaleString("zh-CN")}</b><small>入 {item.input_tokens.toLocaleString("zh-CN")} / 出 {item.output_tokens.toLocaleString("zh-CN")}</small></td><td><code className="request-id" title={item.request_id}>{item.request_id}</code></td><td><div className="row-actions"><IconButton icon="logs" label="查看请求与返回详情" onClick={() => void openDetail(item)}/></div></td></tr>)}</tbody></table></div>}
      <Pagination page={page} pageSize={pageSize} total={total} onChange={setPage}/>
    </section>
    <Drawer open={Boolean(selected)} title="调用详情" description="请求参数和返回结果的业务字段完整展示；图片生成结果可直接预览，凭证和二进制原文不会进入日志。" onClose={() => setSelected(null)} className="request-log-drawer" footer={<SecondaryButton onClick={() => setSelected(null)}>关闭</SecondaryButton>}>
      {selected && <div className="admin-request-detail"><section className="admin-request-meta"><span>REQUEST</span><h3>{selected.public_model || "未知模型"}</h3><code>{selected.request_id || "—"}</code><dl><div><dt>用户</dt><dd>{selected.user_display_name || "未知用户"}</dd></div><div><dt>状态</dt><dd><StatusPill value={selected.status || "failed"}/>{selected.status_code ?? "—"}</dd></div><div><dt>时间</dt><dd>{selected.started_at ? new Date(selected.started_at).toLocaleString("zh-CN", { hour12: false }) : "—"}</dd></div><div><dt>耗时 / 重试</dt><dd>{durationText(selected.duration_ms)} / {numberValue(selected.retry_count)} 次</dd></div><div><dt>输入 / 输出 / 缓存</dt><dd>{numberValue(selected.input_tokens).toLocaleString("zh-CN")} / {numberValue(selected.output_tokens).toLocaleString("zh-CN")} / {numberValue(selected.cached_tokens).toLocaleString("zh-CN")}</dd></div><div><dt>扣费</dt><dd>{formatDashboardAmount(selected.billed_amount)}</dd></div></dl></section><PayloadBlock title="REQUEST PAYLOAD" value={selected.request_detail} bytes={selected.request_payload_size}/><PayloadBlock title="RESPONSE PAYLOAD" value={selected.response_detail} bytes={selected.response_payload_size}/><section className="admin-request-summary-grid"><PayloadBlock title="REQUEST SUMMARY" value={selected.request_summary} bytes={0}/><PayloadBlock title="RESPONSE SUMMARY" value={selected.response_summary} bytes={0}/></section><p className="admin-security-note"><AdminIcon name="shield" size={18}/><span>业务字段已完整展示；Authorization、Cookie、API Key、密码、Token、图片/音频 Base64 和文件原文仍不会写入日志。</span></p></div>}
    </Drawer>
  </div>;
}

