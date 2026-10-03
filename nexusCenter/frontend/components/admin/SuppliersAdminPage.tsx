"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { adminErrorMessage, formatAdminTime, listSuppliers, saveSupplier, type Supplier, type SupplierInput } from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, ConfirmDialog, Drawer, EmptyState, ErrorState, Field, LoadingState, Pagination, PrimaryButton, SearchBox, SecondaryButton, SelectFilter, StatusPill, Toast } from "./AdminUi";

const emptySupplier: SupplierInput = {
  code: "", name: "", supplier_type: "direct", status: "active", billing_mode: "prepaid",
  settlement_currency: "USD", disabled_reason: null, metadata: {}, version: null,
};

function toInput(item: Supplier): SupplierInput {
  return {
    code: item.code, name: item.name, supplier_type: item.supplier_type, status: item.status,
    billing_mode: item.billing_mode, settlement_currency: item.settlement_currency,
    disabled_reason: item.disabled_reason, metadata: { ...item.metadata }, version: item.version,
  };
}

/** 卡片只展示稳定中文业务术语，底层枚举仍原样提交给后端。 */
function supplierTypeLabel(value: string): string {
  return ({ direct: "官方直连", reseller: "代理商", aggregator: "聚合平台", other: "其他" } as Record<string, string>)[value] ?? value;
}

function billingModeLabel(value: string): string {
  return ({ prepaid: "预付费", postpaid: "后付费", monthly_settlement: "月结", other: "其他" } as Record<string, string>)[value] ?? value;
}

/** 供应商维护页：负责查询、新增、乐观锁编辑以及全局启停入口。 */
export function SuppliersAdminPage() {
  const [items, setItems] = useState<Supplier[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [health, setHealth] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<Supplier | null>(null);
  const [form, setForm] = useState<SupplierInput>(emptySupplier);
  const [saving, setSaving] = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<Supplier | null>(null);
  const [toast, setToast] = useState({ message: "", tone: "success" as "success" | "error" });

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const result = await listSuppliers({ page, pageSize: 20, query, status, healthStatus: health });
      setItems(result.items); setTotal(result.total);
    } catch (loadError) { setError(adminErrorMessage(loadError)); }
    finally { setLoading(false); }
  }, [page, query, status, health]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 260 : 0);
    return () => window.clearTimeout(timer);
  }, [load, query]);

  const healthSummary = useMemo(() => ({
    healthy: items.filter(item => item.health_status === "healthy").length,
    attention: items.filter(item => ["degraded", "unavailable"].includes(item.health_status)).length,
  }), [items]);

  const openCreate = () => { setEditing(null); setForm({ ...emptySupplier, metadata: {} }); setDrawerOpen(true); };
  const openEdit = (item: Supplier) => { setEditing(item); setForm(toInput(item)); setDrawerOpen(true); };
  const closeDrawer = () => { if (!saving) { setDrawerOpen(false); setEditing(null); setForm({ ...emptySupplier, metadata: {} }); } };

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true);
    try {
      await saveSupplier({ ...form, code: form.code.trim().toLowerCase(), name: form.name.trim(), settlement_currency: form.settlement_currency.trim().toUpperCase(), disabled_reason: form.status === "disabled" ? form.disabled_reason?.trim() || "管理员手动停用" : null }, editing?.id);
      setToast({ message: editing ? "供应商配置已更新" : "供应商已创建", tone: "success" });
      closeDrawer(); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  const toggleStatus = async () => {
    if (!confirmTarget) return;
    setSaving(true);
    const nextStatus = confirmTarget.status === "active" ? "disabled" : "active";
    try {
      await saveSupplier({ ...toInput(confirmTarget), status: nextStatus, disabled_reason: nextStatus === "disabled" ? "管理员从控制台停用" : null }, confirmTarget.id);
      setConfirmTarget(null); setToast({ message: nextStatus === "active" ? "供应商已启用" : "供应商已停用", tone: "success" }); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  return <div>
    <AdminPageHeader eyebrow="UPSTREAM / SUPPLIERS" title="供应商管理" description="统一维护合作方、结算方式与全局启停状态。停用供应商后，其关联渠道将不再参与路由。" action={<PrimaryButton onClick={openCreate}><AdminIcon name="plus" size={16}/>新增供应商</PrimaryButton>}/>
    <section className="admin-summary-strip admin-animate"><div><span>总供应商</span><b>{total}</b></div><div><span>本页健康</span><b className="good">{healthSummary.healthy}</b></div><div><span>本页需关注</span><b className={healthSummary.attention ? "bad" : ""}>{healthSummary.attention}</b></div><p><AdminIcon name="shield" size={15}/>供应商层是最高级停用开关，适用于合作终止或全局风险处置。</p></section>
    <section className="admin-list-panel admin-animate admin-card-list-panel">
      <div className="admin-list-toolbar resource-card-toolbar"><SearchBox value={query} onChange={value => { setPage(1); setQuery(value); }} placeholder="搜索供应商名称或代码"/><div><SelectFilter label="状态筛选" value={status} onChange={value => { setPage(1); setStatus(value); }}><option value="all">全部状态</option><option value="active">启用</option><option value="disabled">停用</option><option value="suspended">暂停</option><option value="terminated">终止</option></SelectFilter><SelectFilter label="健康筛选" value={health} onChange={value => { setPage(1); setHealth(value); }}><option value="all">全部健康状态</option><option value="healthy">健康</option><option value="degraded">波动</option><option value="unavailable">不可用</option><option value="unconfigured">未配置</option></SelectFilter><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/></SecondaryButton></div></div>
      {loading && !items.length ? <LoadingState/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="没有匹配的供应商" description="调整筛选条件，或创建第一个供应商。"/> : <div className="admin-resource-grid supplier-admin-grid">{items.map(item => <article className={`admin-resource-card supplier-admin-card ${item.health_status}`} key={item.id}>
        <header className="admin-resource-card-head">
          <span className="resource-avatar supplier">{item.name.charAt(0).toUpperCase()}</span>
          <div><h2>{item.name}</h2><code>{item.code}</code></div>
          <StatusPill value={item.status}/>
        </header>
        <div className="admin-card-tags"><span className="admin-tag">{supplierTypeLabel(item.supplier_type)}</span><StatusPill value={item.health_status}/></div>
        <dl className="admin-card-facts supplier-card-facts"><div><dt>结算币种</dt><dd>{item.settlement_currency}</dd></div><div><dt>计费模式</dt><dd>{billingModeLabel(item.billing_mode)}</dd></div><div><dt>最近探测</dt><dd>{formatAdminTime(item.last_health_checked_at)}</dd></div><div><dt>配置版本</dt><dd>v{item.version}</dd></div></dl>
        {item.disabled_reason ? <p className="supplier-card-warning"><AdminIcon name="warning" size={14}/><span>{item.disabled_reason}</span></p> : <p className="supplier-card-note"><AdminIcon name="shield" size={14}/><span>供应商正常参与渠道路由，实际可用性由渠道健康状态共同决定。</span></p>}
        <footer>
          <Link className="admin-card-detail-link" href={`/admin/suppliers/${item.id}`}>查看资源详情 <AdminIcon name="arrow" size={14}/></Link>
          <div className="supplier-card-actions" aria-label={`${item.name} 操作`}>
            <button type="button" className="supplier-card-action edit" onClick={() => openEdit(item)}><AdminIcon name="edit" size={14}/>编辑</button>
            <button type="button" className={`supplier-card-action ${item.status === "active" ? "danger is-stop" : "positive is-start"}`} onClick={() => setConfirmTarget(item)}><AdminIcon name={item.status === "active" ? "pause" : "play"} size={14}/>{item.status === "active" ? "停用" : "启用"}</button>
          </div>
        </footer>
      </article>)}</div>}
      <Pagination page={page} pageSize={20} total={total} onChange={setPage}/>
    </section>

    <Drawer open={drawerOpen} title={editing ? "编辑供应商" : "新增供应商"} description="配置合作主体和结算属性。健康状态由探测系统维护，不能在这里手工伪造。" onClose={closeDrawer} footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="supplier-form" disabled={saving}>{saving ? "保存中…" : "保存配置"}</PrimaryButton></>}>
      <form id="supplier-form" className="admin-form-grid" onSubmit={submit}>
        <Field label="供应商代码" required hint="稳定标识，建议使用小写字母、数字和短横线"><input autoFocus required maxLength={64} value={form.code} onChange={event => setForm(current => ({ ...current, code: event.target.value }))} placeholder="openai-direct"/></Field>
        <Field label="供应商名称" required><input required maxLength={120} value={form.name} onChange={event => setForm(current => ({ ...current, name: event.target.value }))} placeholder="OpenAI 官方"/></Field>
        <Field label="供应商类型" required><select value={form.supplier_type} onChange={event => setForm(current => ({ ...current, supplier_type: event.target.value }))}><option value="direct">官方直连</option><option value="reseller">代理商</option><option value="aggregator">聚合平台</option><option value="other">其他</option></select></Field>
        <Field label="计费模式" required><select value={form.billing_mode} onChange={event => setForm(current => ({ ...current, billing_mode: event.target.value }))}><option value="prepaid">预付费</option><option value="postpaid">后付费</option><option value="monthly_settlement">月结</option><option value="other">其他</option></select></Field>
        <Field label="结算币种" required hint="使用 ISO 4217 三字母代码"><input required minLength={3} maxLength={3} value={form.settlement_currency} onChange={event => setForm(current => ({ ...current, settlement_currency: event.target.value }))} placeholder="USD"/></Field>
        <Field label="业务状态" required><select value={form.status} onChange={event => setForm(current => ({ ...current, status: event.target.value }))}><option value="active">启用</option><option value="disabled">停用</option><option value="suspended">暂停</option><option value="terminated">终止</option></select></Field>
        {form.status !== "active" && <Field wide label="状态原因" required><textarea required maxLength={500} value={form.disabled_reason ?? ""} onChange={event => setForm(current => ({ ...current, disabled_reason: event.target.value }))} placeholder="记录停止合作或风险处置原因"/></Field>}
        <Field wide label="合作备注" hint="仅保存普通运营信息，请勿填写合同账号、密钥或其他秘密"><textarea maxLength={500} value={String(form.metadata.note ?? "")} onChange={event => setForm(current => ({ ...current, metadata: { ...current.metadata, note: event.target.value } }))} placeholder="例如：商务负责人、续约月份（不含敏感信息）"/></Field>
      </form>
    </Drawer>
    <ConfirmDialog open={Boolean(confirmTarget)} title={confirmTarget?.status === "active" ? "停用这个供应商？" : "重新启用这个供应商？"} description={confirmTarget?.status === "active" ? `停用 ${confirmTarget?.name} 后，其全部关联渠道会从可用路由中移除。` : `重新启用 ${confirmTarget?.name}，但关联渠道仍需满足自身状态和健康条件。`} confirmLabel={confirmTarget?.status === "active" ? "确认停用" : "确认启用"} danger={confirmTarget?.status === "active"} busy={saving} onCancel={() => setConfirmTarget(null)} onConfirm={() => void toggleStatus()}/>
    <Toast message={toast.message} tone={toast.tone} onClose={() => setToast(current => ({ ...current, message: "" }))}/>
  </div>;
}
