"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  adminErrorMessage, compactNumber, formatAdminTime, formatDashboardAmount, formatDashboardLatency, getAdminDashboard,
  listSuppliers, type DashboardOverview, type DashboardQuery, type DashboardRankingItem, type DashboardTrendPoint,
  type Supplier,
} from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, EmptyState, ErrorState, LoadingState, SecondaryButton } from "./AdminUi";

type Preset = "today" | "7d" | "30d" | "90d";
type RankingTab = "suppliers" | "models" | "channels" | "groups";

const presetLabels: { value: Preset; label: string }[] = [
  { value: "today", label: "今天" },
  { value: "7d", label: "近 7 天" },
  { value: "30d", label: "近 30 天" },
  { value: "90d", label: "近 90 天" },
];

const rankingLabels: Record<RankingTab, string> = {
  suppliers: "供应商", models: "模型", channels: "渠道", groups: "分组",
};

/**
 * Wave 8 经营分析页只展示服务端计算后的脱敏统计快照。
 * 历史金额与毛利不在浏览器重新计算，避免浮点误差改变财务口径。
 */
export function AnalyticsAdminPage() {
  const [data, setData] = useState<DashboardOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [preset, setPreset] = useState<Preset>("7d");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [rankingTab, setRankingTab] = useState<RankingTab>("suppliers");
  const [activeQuery, setActiveQuery] = useState<DashboardQuery>({ preset: "7d" });
  const [supplierId, setSupplierId] = useState("");
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [checkedAt, setCheckedAt] = useState<Date | null>(null);

  const load = useCallback(async (query: DashboardQuery) => {
    setLoading(true);
    setError("");
    try {
      setData(await getAdminDashboard(query));
      setActiveQuery(query);
      setCheckedAt(new Date());
    } catch (loadError) {
      setError(adminErrorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => void load({ preset: "7d" }), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    void listSuppliers({ page: 1, pageSize: 100 })
      .then(result => setSuppliers(result.items))
      .catch(() => setSuppliers([]));
  }, []);

  const choosePreset = (value: Preset) => {
    setPreset(value);
    void load({ preset: value, supplierId: supplierId || undefined });
  };

  const applyCustomRange = () => {
    if (!customFrom || !customTo) {
      setError("请选择完整的开始和结束日期");
      return;
    }
    const from = new Date(`${customFrom}T00:00:00+08:00`);
    const to = new Date(`${customTo}T00:00:00+08:00`);
    to.setUTCDate(to.getUTCDate() + 1);
    if (!Number.isFinite(+from) || !Number.isFinite(+to) || from >= to) {
      setError("自定义日期范围无效");
      return;
    }
    void load({ preset: "custom", from: from.toISOString(), to: to.toISOString(), supplierId: supplierId || undefined });
  };

  const chooseSupplier = (value: string) => {
    setSupplierId(value);
    void load({ ...activeQuery, supplierId: value || undefined });
  };

  const ranking = data?.rankings[rankingTab] ?? [];
  const stale = data?.last_aggregated_at && checkedAt
    ? checkedAt.getTime() - new Date(data.last_aggregated_at).getTime() > 15 * 60 * 1000
    : true;

  return <div className="analytics-page">
    <AdminPageHeader
      eyebrow="OPERATIONS / ANALYTICS"
      title="经营分析"
      description="按完成时快照查看供应商调用量、使用积分、Token、成本、毛利和真实上游稳定性。"
      action={<SecondaryButton onClick={() => void load(activeQuery)} disabled={loading}><AdminIcon name="refresh" size={14}/>{loading ? "刷新中…" : "刷新数据"}</SecondaryButton>}
    />

    <section className="analytics-toolbar admin-animate" aria-label="经营统计时间范围">
      <div className="analytics-presets">{presetLabels.map(item => <button
        type="button" key={item.value} className={data?.preset === item.value ? "active" : ""}
        aria-pressed={data?.preset === item.value} onClick={() => choosePreset(item.value)}
      >{item.label}</button>)}</div>
      <label className="analytics-supplier-filter"><span>供应商</span><select value={supplierId} onChange={event => chooseSupplier(event.target.value)}>
        <option value="">全部供应商</option>
        {suppliers.map(supplier => <option value={supplier.id} key={supplier.id}>{supplier.name}</option>)}
      </select></label>
      <div className="analytics-custom-range">
        <label><span>开始日期</span><input type="date" value={customFrom} onChange={event => setCustomFrom(event.target.value)}/></label>
        <i aria-hidden="true">—</i>
        <label><span>结束日期</span><input type="date" value={customTo} onChange={event => setCustomTo(event.target.value)}/></label>
        <button type="button" onClick={applyCustomRange}>应用</button>
      </div>
      <p className={stale ? "stale" : ""}><i/>{data?.last_aggregated_at
        ? `${stale ? "聚合可能延迟 · " : "数据已聚合 · "}${formatAdminTime(data.last_aggregated_at)}`
        : "等待首次经营数据聚合"}</p>
    </section>

    {loading && !data ? <LoadingState/> : error && !data ? <ErrorState message={error} onRetry={() => void load(activeQuery)}/> : data && <>
      {error && <div className="analytics-inline-error" role="alert">{error}</div>}
      <MetricGrid data={data}/>
      <section className="analytics-grid">
        <TrendPanel points={data.trend} bucketSize={data.bucket_size}/>
        <QualityPanel data={data}/>
      </section>
      <section className="admin-panel analytics-ranking admin-animate">
        <header><div><span>DIMENSION PERFORMANCE</span><h2>经营维度排名</h2></div><small>按毛利优先，最多显示 10 项</small></header>
        <div className="admin-tabs analytics-tabs" role="tablist" aria-label="经营排名维度">{(Object.keys(rankingLabels) as RankingTab[]).map(key => <button
          type="button" role="tab" aria-selected={rankingTab === key} className={rankingTab === key ? "active" : ""}
          onClick={() => setRankingTab(key)} key={key}
        >{rankingLabels[key]}<span>{data.rankings[key].length}</span></button>)}</div>
        <RankingTable items={ranking} dimension={rankingTab}/>
      </section>
    </>}
  </div>;
}

function MetricGrid({ data }: { data: DashboardOverview }) {
  const summary = data.summary;
  const metrics = [
    { label: "最终请求", value: compactNumber(summary.request_count), meta: `${summary.success_count} 成功 / ${summary.failure_count} 失败`, tone: "blue" },
    { label: "使用积分", value: formatDashboardAmount(summary.billed_amount), meta: `平台实际扣除 · ${data.settlement_currency ?? "平台额度"}`, tone: "violet" },
    { label: "使用 Token", value: compactNumber(summary.input_tokens + summary.output_tokens), meta: `输入 ${compactNumber(summary.input_tokens)} · 输出 ${compactNumber(summary.output_tokens)}`, tone: "blue" },
    { label: "供应商成本", value: formatDashboardAmount(summary.supplier_cost_amount), meta: `${data.settlement_currency ?? "平台额度"} · 完成时成本快照`, tone: "cyan" },
    { label: "平台毛利", value: formatDashboardAmount(summary.gross_margin_amount), meta: `毛利率 ${formatPercent(summary.gross_margin_rate)}`, tone: isNegativeAmount(summary.gross_margin_amount) ? "red" : "green" },
    { label: "最终成功率", value: formatPercent(summary.success_rate), meta: `P95 ${formatDashboardLatency(summary.latency_p95_ms)}`, tone: summary.success_rate >= 99 ? "green" : "blue" },
    { label: "上游尝试稳定性", value: formatPercent(summary.attempt_success_rate), meta: `${summary.attempt_supplier_failure_count} 次供应商责任失败`, tone: summary.attempt_supplier_failure_count ? "red" : "green" },
  ];
  return <section className="analytics-metrics admin-animate">{metrics.map(item => <article key={item.label}>
    <span className={`metric-icon ${item.tone}`}><AdminIcon name={item.tone === "red" ? "warning" : "analytics"}/></span>
    <div><small>{item.label}</small><b>{item.value}</b><p>{item.meta}</p></div>
  </article>)}</section>;
}

function TrendPanel({ points, bucketSize }: { points: DashboardTrendPoint[]; bucketSize: "hour" | "day" }) {
  return <article className="admin-panel analytics-trend admin-animate">
    <header><div><span>USAGE & MARGIN</span><h2>使用积分、成本与毛利趋势</h2></div><small>{bucketSize === "hour" ? "按小时" : "按日"}</small></header>
    {!points.length ? <EmptyState title="暂无趋势数据" description="首次聚合完成后，这里会显示真实经营曲线。"/> : <>
      <TrendChart points={points}/>
      <footer><span><i className="income"/>使用积分</span><span><i className="cost"/>成本</span><span><i className="margin"/>毛利</span></footer>
    </>}
  </article>;
}

export function TrendChart({ points }: { points: DashboardTrendPoint[] }) {
  const chart = useMemo(() => {
    // 折线坐标允许使用近似 number；财务展示仍使用服务端返回的精确十进制字符串。
    const values = points.flatMap(point => [point.billed_amount, point.supplier_cost_amount, point.gross_margin_amount].map(chartAmount));
    const minimum = Math.min(0, ...values);
    const maximum = Math.max(1, ...values);
    const range = maximum - minimum || 1;
    const path = (selector: (point: DashboardTrendPoint) => number) => points.map((point, index) => {
      const x = points.length === 1 ? 50 : (index / (points.length - 1)) * 100;
      const y = 92 - ((selector(point) - minimum) / range) * 78;
      return `${index ? "L" : "M"}${x.toFixed(2)},${y.toFixed(2)}`;
    }).join(" ");
    return {
      axisValues: [maximum, (maximum + minimum) / 2, minimum],
      income: path(point => chartAmount(point.billed_amount)), cost: path(point => chartAmount(point.supplier_cost_amount)),
      margin: path(point => chartAmount(point.gross_margin_amount)), zeroY: 92 - ((0 - minimum) / range) * 78,
    };
  }, [points]);

  return <div className="analytics-chart">
    <div className="analytics-y-axis" aria-hidden="true">{chart.axisValues.map((value, index) => <span key={index}>{formatDashboardAmount(value.toFixed(3))}</span>)}</div>
    <svg viewBox="0 0 100 100" role="img" aria-label="收入、成本与毛利趋势折线图" preserveAspectRatio="none">
      {[20, 40, 60, 80].map(y => <line x1="0" x2="100" y1={y} y2={y} className="grid" key={y}/>)}
      <line x1="0" x2="100" y1={chart.zeroY} y2={chart.zeroY} className="zero"/>
      <path d={chart.income} className="income"/><path d={chart.cost} className="cost"/><path d={chart.margin} className="margin"/>
    </svg>
    <div className="analytics-axis"><span>{formatChartTime(points[0]?.bucket_start)}</span><span>{formatChartTime(points.at(-1)?.bucket_start)}</span></div>
  </div>;
}

function QualityPanel({ data }: { data: DashboardOverview }) {
  const summary = data.summary;
  const maximumError = Math.max(1, ...data.error_distribution.map(item => item.occurrence_count));
  return <article className="admin-panel analytics-quality admin-animate">
    <header><div><span>UPSTREAM QUALITY</span><h2>调用质量与错误归因</h2></div><AdminIcon name="pulse" size={18}/></header>
    <div className="quality-stats"><div><span>请求平均延迟</span><b>{formatDashboardLatency(summary.average_latency_ms)}</b></div><div><span>上游尝试 P95</span><b>{formatDashboardLatency(summary.attempt_latency_p95_ms)}</b></div><div><span>缓存 Token</span><b>{compactNumber(summary.cached_tokens)}</b></div></div>
    <div className="error-distribution">{data.error_distribution.length ? data.error_distribution.map(item => <div key={item.category}><span><b>{errorLabel(item.category)}</b><em>{item.occurrence_count}</em></span><i><b style={{ width: `${Math.max(5, item.occurrence_count / maximumError * 100)}%` }}/></i></div>) : <p>当前区间没有可归因的上游错误。</p>}</div>
  </article>;
}

function RankingTable({ items, dimension }: { items: DashboardRankingItem[]; dimension: RankingTab }) {
  if (!items.length) return <EmptyState title={`暂无${rankingLabels[dimension]}经营数据`} description="产生真实模型调用并完成聚合后，这里会显示排名。"/>;
  return <div className="admin-table-wrap"><table className="admin-table analytics-ranking-table"><thead><tr><th>排名</th><th>{rankingLabels[dimension]}</th><th>请求 / 成功率</th><th>积分 / 成本</th><th>毛利</th><th>平均延迟</th><th>上游稳定性</th></tr></thead><tbody>{items.map((item, index) => <tr key={item.dimension_id}><td><span className={`analytics-rank rank-${index + 1}`}>{index + 1}</span></td><td><div className="resource-name"><span className="resource-avatar route">{item.dimension_name.charAt(0).toUpperCase()}</span><div><b>{item.dimension_name}</b><small>{compactNumber(item.input_tokens + item.output_tokens)} Token</small></div></div></td><td><b className="table-primary">{compactNumber(item.request_count)}</b><small>{formatPercent(item.success_rate)} 成功</small></td><td><b>{formatDashboardAmount(item.billed_amount)}</b><small>{formatDashboardAmount(item.supplier_cost_amount)} 成本</small></td><td><b className={isNegativeAmount(item.gross_margin_amount) ? "analytics-negative" : "analytics-positive"}>{formatDashboardAmount(item.gross_margin_amount)}</b><small>{formatPercent(item.gross_margin_rate)}</small></td><td>{formatDashboardLatency(item.average_latency_ms)}</td><td>{item.attempt_count ? <><b>{formatPercent(item.attempt_success_rate)}</b><small>{item.attempt_supplier_failure_count} 次责任失败</small></> : <span>—</span>}</td></tr>)}</tbody></table></div>;
}

function chartAmount(value: string): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function isNegativeAmount(value: string): boolean {
  return value.startsWith("-") && !/^-[0.]+$/.test(value);
}

function formatPercent(value: number): string {
  return `${value.toLocaleString("zh-CN", { maximumFractionDigits: 3 })}%`;
}

function formatChartTime(value?: string): string {
  if (!value) return "";
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit" }).format(new Date(value));
}

function errorLabel(category: string): string {
  const labels: Record<string, string> = {
    upstream_5xx: "上游 5xx", upstream_429: "上游限流", timeout: "上游超时",
    network: "网络异常", upstream_rejected: "上游拒绝", platform_failure: "平台异常",
  };
  return labels[category] ?? category.replaceAll("_", " ");
}
