"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { adminErrorMessage, formatAdminTime, getAdminResourceOverview, listChannels, listModels, listSuppliers, type AdminResourceOverview, type Channel, type Model, type Supplier } from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, ErrorState, LoadingState, SecondaryButton, StatusPill } from "./AdminUi";

type OverviewData = {
  resources: AdminResourceOverview;
  suppliers: { items: Supplier[]; total: number };
  models: { items: Model[]; total: number };
  channels: { items: Channel[]; total: number };
};

/**
 * 运营总览只汇总现有管理接口返回的真实数量和健康信号，
 * 在后端尚无财务聚合接口前，不展示伪造的营收或调用量数据。
 */
export function AdminOverview() {
  const [data, setData] = useState<OverviewData | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [resources, suppliers, models, channels] = await Promise.all([
        getAdminResourceOverview(),
        listSuppliers({ pageSize: 5 }), listModels({ pageSize: 5 }), listChannels({ pageSize: 5 }),
      ]);
      setData({ resources, suppliers, models, channels });
      setUpdatedAt(new Date());
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

  return (
    <div className="admin-overview">
      <AdminPageHeader eyebrow="OPERATIONS / LIVE" title="运营总览" description="模型供给、渠道健康与配置风险的实时控制面。" action={<SecondaryButton onClick={() => void load()} disabled={loading}><AdminIcon name="refresh" size={15}/>{loading ? "刷新中" : "刷新数据"}</SecondaryButton>}/>
      {loading && !data ? <LoadingState/> : error && !data ? <ErrorState message={error} onRetry={() => void load()}/> : data && <>
        <section className="admin-metrics admin-animate">
          <article className="featured"><div><span>UPSTREAM COVERAGE</span><b>{data.resources.models.total}</b><small>已维护模型</small></div><div className="admin-mini-bars" aria-hidden="true">{[28,42,35,57,48,72,64,86,74,92].map((height, index) => <i style={{ height: `${height}%` }} key={index}/>)}</div><Link href="/admin/models">维护模型 <AdminIcon name="arrow" size={14}/></Link></article>
          <article><span className="metric-icon blue"><AdminIcon name="supplier"/></span><div><small>启用供应商</small><b>{data.resources.suppliers.active}<em> / {data.resources.suppliers.total}</em></b><p>合作资源池</p></div></article>
          <article><span className="metric-icon violet"><AdminIcon name="model"/></span><div><small>可用模型</small><b>{data.resources.models.active}<em> / {data.resources.models.total}</em></b><p>公开及内部目录</p></div></article>
          <article><span className="metric-icon cyan"><AdminIcon name="channel"/></span><div><small>健康渠道</small><b>{data.resources.channels.active}<em> / {data.resources.channels.total}</em></b><p>当前业务状态正常</p></div></article>
          <article><span className={`metric-icon ${data.resources.open_alert_count ? "red" : "green"}`}><AdminIcon name={data.resources.open_alert_count ? "warning" : "shield"}/></span><div><small>待处理风险</small><b>{data.resources.open_alert_count}</b><p>{data.resources.open_alert_count ? "需要管理员检查" : "当前没有告警"}</p></div></article>
        </section>

        <section className="admin-overview-grid">
          <article className="admin-panel admin-animate admin-system-map">
            <header><div><span>SUPPLY TOPOLOGY</span><h2>上游资源拓扑</h2></div><span className="live"><i/> LIVE</span></header>
            <div className="topology">
              <div className="topology-node source"><span><AdminIcon name="supplier"/></span><b>{data.resources.suppliers.total}</b><small>供应商</small></div>
              <div className="topology-line"><i/><i/><i/></div>
              <div className="topology-node core"><span>N</span><b>NEXUS ROUTER</b><small>策略与健康观测</small></div>
              <div className="topology-line reverse"><i/><i/><i/></div>
              <div className="topology-node target"><span><AdminIcon name="model"/></span><b>{data.resources.models.total}</b><small>公开模型</small></div>
            </div>
            <footer><div><i className="green"/><span>供应商配置</span><b>{data.resources.suppliers.active} active</b></div><div><i className="blue"/><span>渠道连接</span><b>{data.resources.channels.active} healthy</b></div><div><i className="violet"/><span>模型目录</span><b>{data.resources.models.active} enabled</b></div></footer>
          </article>

          <article className="admin-panel admin-animate admin-health-list">
            <header><div><span>CHANNEL HEALTH</span><h2>渠道运行状态</h2></div><Link href="/admin/channels">查看全部 <AdminIcon name="chevron" size={13}/></Link></header>
            <div>{data.channels.items.slice(0, 5).map(channel => <Link href="/admin/channels" className="health-row" key={channel.id}><span className={`provider-mark ${channel.status === "degraded" ? "warning" : ""}`}>{channel.name.charAt(0).toUpperCase()}</span><div><b>{channel.name}</b><small>{channel.supplier_name} · {channel.provider_type}</small></div><span><StatusPill value={channel.status}/><small>{channel.status === "disabled" ? "人工停用" : channel.status === "degraded" ? "需检查上游" : "当前可调用"}</small></span></Link>)}</div>
            {!data.channels.items.length && <p className="overview-empty">尚未配置渠道</p>}
          </article>

          <article className="admin-panel admin-animate admin-risk-panel">
            <header><div><span>CONTROL CHECK</span><h2>配置完整性</h2></div><AdminIcon name="shield" size={18}/></header>
            <div className="risk-score"><strong>{data.resources.open_alert_count ? Math.max(60, 100 - data.resources.open_alert_count * 8) : 100}</strong><span>/ 100</span><i style={{ "--score": `${data.resources.open_alert_count ? Math.max(60, 100 - data.resources.open_alert_count * 8) : 100}%` } as React.CSSProperties}/></div>
            <ul><li><AdminIcon name="check" size={15}/>管理员接口均启用角色校验</li><li><AdminIcon name="check" size={15}/>渠道凭证不在响应中回显</li><li className={data.resources.open_alert_count ? "warn" : ""}><AdminIcon name={data.resources.open_alert_count ? "warning" : "check"} size={15}/>{data.resources.open_alert_count ? `${data.resources.open_alert_count} 项健康风险待处理` : "供应商与渠道状态正常"}</li></ul>
          </article>

          <article className="admin-panel admin-animate admin-activity">
            <header><div><span>RECENT CONFIGURATION</span><h2>最近配置更新</h2></div><small>{updatedAt ? `更新于 ${updatedAt.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}` : ""}</small></header>
            <div>{[
              ...data.channels.items.slice(0, 2).map(item => ({ type: "渠道", name: item.name, meta: item.supplier_name, time: item.updated_at, icon: "channel" as const })),
              ...data.models.items.slice(0, 2).map(item => ({ type: "模型", name: item.display_name, meta: item.provider, time: item.updated_at, icon: "model" as const })),
              ...data.suppliers.items.slice(0, 1).map(item => ({ type: "供应商", name: item.name, meta: item.code, time: item.updated_at, icon: "supplier" as const })),
            ].sort((a, b) => +new Date(b.time) - +new Date(a.time)).slice(0, 5).map((item, index) => <div className="activity-row" key={`${item.type}-${item.name}-${index}`}><span><AdminIcon name={item.icon} size={15}/></span><div><b>{item.name}</b><small>{item.type} · {item.meta}</small></div><time>{formatAdminTime(item.time)}</time></div>)}</div>
          </article>
        </section>
      </>}
    </div>
  );
}
