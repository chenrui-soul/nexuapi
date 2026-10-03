"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import {
  adminErrorMessage, formatAdminTime, formatDashboardAmount, getSupplierDetail, type SupplierDetail,
} from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, EmptyState, ErrorState, LoadingState, StatusPill } from "./AdminUi";

function percentage(value: number): string {
  return `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 3 }).format(value)}%`;
}

/** 供应商详情页：把商业主体、渠道、上游接口和稳定性串成一条可审计链路。 */
export function SupplierDetailAdminPage({ supplierId }: { supplierId: string }) {
  const [detail, setDetail] = useState<SupplierDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { setDetail(await getSupplierDetail(supplierId)); }
    catch (loadError) { setError(adminErrorMessage(loadError)); }
    finally { setLoading(false); }
  }, [supplierId]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  if (loading && !detail) return <LoadingState label="正在汇总供应商详情…"/>;
  if (error && !detail) return <ErrorState message={error} onRetry={() => void load()}/>;
  if (!detail) return <EmptyState title="供应商不存在" description="该供应商可能已被删除，或当前账号无权查看。"/>;

  const { supplier, summary } = detail;
  return <div>
    <AdminPageHeader
      eyebrow="UPSTREAM / SUPPLIER DETAIL"
      title={supplier.name}
      description="查看该合作主体实际承载的渠道、最近 30 天成本与真实上游稳定性。"
      action={<><Link className="admin-button secondary" href="/admin/suppliers"><AdminIcon name="arrow" size={15}/>返回供应商列表</Link><Link className="admin-button primary" href={`/admin/suppliers/${supplierId}/endpoints`}><AdminIcon name="channel" size={15}/>管理上游接口</Link></>}
    />

    {error && <p className="analytics-inline-error" role="alert">刷新失败，当前展示上一次成功数据：{error}</p>}

    <section className="supplier-detail-identity admin-animate">
      <div className="supplier-detail-mark">{supplier.name.charAt(0).toUpperCase()}</div>
      <div className="supplier-detail-title"><span>COMMERCIAL OWNER</span><h2>{supplier.name}</h2><code>{supplier.code}</code></div>
      <dl>
        <div><dt>合作类型</dt><dd>{supplier.supplier_type}</dd></div>
        <div><dt>结算方式</dt><dd>{supplier.billing_mode.replaceAll("_", " ")}</dd></div>
        <div><dt>结算币种</dt><dd>{supplier.settlement_currency}</dd></div>
        <div><dt>最近探测</dt><dd>{formatAdminTime(supplier.last_health_checked_at)}</dd></div>
      </dl>
      <div className="supplier-detail-status"><StatusPill value={supplier.health_status}/><StatusPill value={supplier.status}/></div>
    </section>

    <section className="supplier-detail-metrics admin-animate" aria-label="最近 30 天经营指标">
      <article><span><AdminIcon name="channel" size={17}/></span><div><small>可用渠道</small><b>{summary.available_channel_count}<em> / {summary.channel_count}</em></b><p>满足合作与业务状态要求</p></div></article>
      <article><span><AdminIcon name="pulse" size={17}/></span><div><small>请求成功率</small><b>{percentage(summary.success_rate)}</b><p>{summary.success_count} / {summary.request_count} 次最终请求</p></div></article>
      <article><span><AdminIcon name="server" size={17}/></span><div><small>上游尝试成功率</small><b>{percentage(summary.attempt_success_rate)}</b><p>{summary.attempt_success_count} / {summary.attempt_count} 次真实尝试</p></div></article>
      <article><span><AdminIcon name="wallet" size={17}/></span><div><small>供应商成本</small><b>{formatDashboardAmount(summary.supplier_cost_amount)}</b><p>{supplier.settlement_currency} · 最近 {detail.period.days} 天</p></div></article>
      <article><span><AdminIcon name="analytics" size={17}/></span><div><small>平台收入 / 毛利</small><b>{formatDashboardAmount(summary.billed_amount)}</b><p>毛利 {formatDashboardAmount(summary.gross_margin_amount)} {supplier.settlement_currency}</p></div></article>
    </section>

    <section className="supplier-detail-flow admin-animate" aria-label="供应商配置链路">
      <div><span><AdminIcon name="supplier" size={16}/></span><b>供应商</b><small>合作与结算主体</small></div><i><AdminIcon name="chevron" size={13}/></i>
      <div><span><AdminIcon name="channel" size={16}/></span><b>{summary.channel_count} 个渠道</b><small>地址、协议与凭证</small></div><i><AdminIcon name="chevron" size={13}/></i>
      <div><span><AdminIcon name="route" size={16}/></span><b>服务分组与路由</b><small>价格与访问策略</small></div>
    </section>

    <section className="admin-list-panel supplier-detail-section admin-animate">
      <header><div><span>ASSOCIATED CHANNELS</span><h2>关联渠道</h2></div><small>最多展示 50 条 · 地址已脱敏</small></header>
      {!detail.channels.length ? <EmptyState title="尚未配置渠道" description="为这个供应商创建渠道后，才能进行真实上游调用。"/> : <div className="admin-table-wrap"><table className="admin-table supplier-detail-channel-table"><thead><tr><th>渠道</th><th>协议 / 地址</th><th>最近上游结果</th><th>状态</th></tr></thead><tbody>{detail.channels.map(channel => <tr key={channel.id}><td><div className="resource-name"><span className="resource-avatar channel">{channel.name.charAt(0).toUpperCase()}</span><div><b>{channel.name}</b><code>{channel.id}</code></div></div></td><td><b className="table-primary">{channel.provider_type}</b><code className="supplier-detail-url">{channel.base_url_origin}</code></td><td><b className="table-primary">{channel.last_attempt_outcome ?? "暂无调用"}</b><small>{channel.last_error_category ?? "无错误分类"} · {formatAdminTime(channel.last_attempt_at)}</small></td><td><StatusPill value={channel.status}/></td></tr>)}</tbody></table></div>}
    </section>
  </div>;
}
