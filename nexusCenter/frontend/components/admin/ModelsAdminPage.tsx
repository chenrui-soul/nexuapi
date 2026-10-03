"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  adminErrorMessage, batchUpdateModelStatus, formatAdminTime, getModelSyncStatus, listAdapters, listModels,
  listProtocols, runModelSync, saveModel, updateModelSyncSettings, type ApiInterfaceDefinition,
  type Adapter, type Model, type ModelInput, type ModelSyncStatus,
} from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, ConfirmDialog, Drawer, EmptyState, ErrorState, Field, IconButton, LoadingState, Pagination, PrimaryButton, SearchBox, SecondaryButton, SelectFilter, StatusActionButton, StatusPill, Toast, ToggleField } from "./AdminUi";
import { ModelPricingDrawer } from "./ModelPricingDrawer";

const emptyModel: ModelInput = {
  public_name: "", display_name: "", provider: "", capability_type: "text",
  input_modalities: ["text"], output_modalities: ["text"], context_window: null,
  max_output_tokens: null, supports_streaming: true, supports_tools: true,
  supports_structured_output: true, input_price: 0, output_price: 0, cached_input_price: 0,
  price_unit: "million_tokens", interface_ids: [], adapter_key: "openai_compatible_text", public_visible: true, status: "active", version: null,
};

function toInput(item: Model): ModelInput {
  return {
    public_name: item.public_name, display_name: item.display_name, provider: item.provider,
    capability_type: item.capability_type, adapter_key: item.adapter_key, input_modalities: [...item.input_modalities],
    output_modalities: [...item.output_modalities], context_window: item.context_window,
    max_output_tokens: item.max_output_tokens, supports_streaming: item.supports_streaming,
    supports_tools: item.supports_tools, supports_structured_output: item.supports_structured_output,
    input_price: item.input_price, output_price: item.output_price,
    cached_input_price: item.cached_input_price, price_unit: item.price_unit,
    interface_ids: item.interfaces.map(value => value.id),
    public_visible: item.public_visible, status: item.status, version: item.version,
  };
}

function splitModalities(value: string): string[] {
  return value.split(",").map(item => item.trim().toLowerCase()).filter(Boolean).slice(0, 8);
}

/** Token 限制留空表示上游未提供明确数值，避免将空字符串误转成 0。 */
function optionalTokenLimit(value: string): number | null {
  return value === "" ? null : Number(value);
}

const syncIntervals = [15, 60, 360, 720, 1440, 10080];
const modelPageSize = 6;

function syncIntervalLabel(minutes: number): string {
  if (minutes === 15) return "每 15 分钟";
  if (minutes < 1440) return `每 ${minutes / 60} 小时`;
  return minutes === 1440 ? "每天" : "每 7 天";
}

function syncStatusLabel(status: ModelSyncStatus | null): string {
  if (status?.running) return "同步中";
  if (!status?.last_run) return "尚未执行";
  if (status.last_run.status === "succeeded") return "同步成功";
  if (status.last_run.status === "failed") return "同步失败";
  return "同步中";
}

function adapterSupports(adapter: Adapter, capabilityType: string): boolean {
  if (adapter.capability_type === "video") return capabilityType === "video";
  if (adapter.capability_type === "image") return capabilityType === "image";
  return ["text", "multimodal", "audio", "embedding"].includes(capabilityType);
}

function adaptersFor(adapters: Adapter[], capabilityType: string): Adapter[] {
  return adapters.filter(adapter => adapter.status === "active" && adapterSupports(adapter, capabilityType));
}

function defaultAdapterFor(adapters: Adapter[], capabilityType: string): string {
  return adaptersFor(adapters, capabilityType)[0]?.adapter_key ?? "";
}

function adapterLabel(adapters: Adapter[], value: string): string {
  return adapters.find(option => option.adapter_key === value)?.display_name ?? value;
}

function capabilityLabel(value: string): string {
  return ({ text: "文本", multimodal: "多模态", embedding: "向量", image: "图像", video: "视频", audio: "音频" } as Record<string, string>)[value] ?? value;
}

function priceUnitLabel(value: string): string {
  return ({ million_tokens: "/ 1M Token", request: "/ 次", image: "/ 张", second: "/ 秒", character: "/ 字符" } as Record<string, string>)[value] ?? value;
}

function billingTypeLabel(value: number): string {
  return ({ 1: "按次", 2: "按数量", 3: "按秒", 4: "按 Token", 5: "按字符" } as Record<number, string>)[value] ?? "待配置";
}

/** 模型维护页：管理对外模型目录、协议适配器、能力开关和基础售价。 */
export function ModelsAdminPage() {
  const [items, setItems] = useState<Model[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [provider, setProvider] = useState("");
  const [serviceGroup, setServiceGroup] = useState("");
  const [capabilityType, setCapabilityType] = useState("all");
  const [status, setStatus] = useState("all");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<Model | null>(null);
  const [form, setForm] = useState<ModelInput>(emptyModel);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [statusSavingId, setStatusSavingId] = useState<string | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<Model | null>(null);
  const [pricingTarget, setPricingTarget] = useState<Model | null>(null);
  const [batchTarget, setBatchTarget] = useState<"active" | "disabled" | null>(null);
  const [toast, setToast] = useState({ message: "", tone: "success" as "success" | "error" });
  const [syncStatus, setSyncStatus] = useState<ModelSyncStatus | null>(null);
  const [syncLoading, setSyncLoading] = useState(true);
  const [syncSaving, setSyncSaving] = useState(false);
  const [syncError, setSyncError] = useState("");
  const [syncPanelOpen, setSyncPanelOpen] = useState(false);
  const [interfaceOptions, setInterfaceOptions] = useState<ApiInterfaceDefinition[]>([]);
  const [adapterCatalog, setAdapterCatalog] = useState<Adapter[]>([]);

  const load = useCallback(async () => {
    setLoading(true); setError("");
      try { const result = await listModels({ page, pageSize: modelPageSize, query, provider, serviceGroup, capabilityType, status }); setItems(result.items); setTotal(result.total); }
    catch (loadError) { setError(adminErrorMessage(loadError)); }
    finally { setLoading(false); }
  }, [page, query, provider, serviceGroup, capabilityType, status]);

  const loadSyncStatus = useCallback(async () => {
    setSyncLoading(true); setSyncError("");
    try { setSyncStatus(await getModelSyncStatus()); }
    catch (loadError) { setSyncError(adminErrorMessage(loadError)); }
    finally { setSyncLoading(false); }
  }, []);

  useEffect(() => { const timer = window.setTimeout(() => void load(), query ? 260 : 0); return () => window.clearTimeout(timer); }, [load, query]);
  useEffect(() => { const timer = window.setTimeout(() => void loadSyncStatus(), 0); return () => window.clearTimeout(timer); }, [loadSyncStatus]);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      void listProtocols({ page: 1, pageSize: 100 }).then(result => setInterfaceOptions(result.items)).catch(() => setInterfaceOptions([]));
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      void listAdapters({ page: 1, pageSize: 100 }).then(result => setAdapterCatalog(result.items)).catch(() => setAdapterCatalog([]));
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  const summary = useMemo(() => ({ visible: items.filter(item => item.public_visible).length, streaming: items.filter(item => item.supports_streaming).length }), [items]);
  const pageIds = useMemo(() => items.map(item => item.id), [items]);
  const allPageSelected = pageIds.length > 0 && pageIds.every(id => selectedIds.includes(id));
  const openCreate = () => { setEditing(null); setForm({ ...emptyModel, input_modalities: ["text"], output_modalities: ["text"] }); setDrawerOpen(true); };
  const openEdit = (item: Model) => { setEditing(item); setForm(toInput(item)); setDrawerOpen(true); };
  const closeDrawer = () => { if (!saving) { setDrawerOpen(false); setEditing(null); setForm({ ...emptyModel }); } };

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true);
    try {
      await saveModel({ ...form, public_name: form.public_name.trim(), display_name: form.display_name.trim(), provider: form.provider.trim().toLowerCase(), input_modalities: form.input_modalities.length ? form.input_modalities : ["text"], output_modalities: form.output_modalities.length ? form.output_modalities : ["text"] }, editing?.id);
      setToast({ message: editing ? "模型配置已更新" : "模型已创建", tone: "success" }); closeDrawer(); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  const toggleStatus = async () => {
    if (!confirmTarget || statusSavingId) return;
    const target = confirmTarget;
    const nextStatus = target.status === "active" ? "disabled" : "active";
    setStatusSavingId(target.id);
    try {
      const updated = await saveModel({ ...toInput(target), status: nextStatus }, target.id);
      setItems(current => current.map(item => item.id === updated.id ? updated : item));
      setToast({ message: nextStatus === "active" ? "模型已启用" : "模型已停用", tone: "success" });
      setConfirmTarget(null);
    } catch (saveError) {
      setToast({ message: adminErrorMessage(saveError), tone: "error" });
    } finally {
      setStatusSavingId(null);
    }
  };

  const togglePageSelection = () => {
    setSelectedIds(current => allPageSelected
      ? current.filter(id => !pageIds.includes(id))
      : [...new Set([...current, ...pageIds])]);
  };

  const toggleSelection = (id: string) => {
    setSelectedIds(current => current.includes(id) ? current.filter(itemId => itemId !== id) : [...current, id]);
  };

  const executeBatchStatus = async () => {
    if (!batchTarget || !selectedIds.length) return;
    setSaving(true);
    try {
      const result = await batchUpdateModelStatus(selectedIds, batchTarget);
      setBatchTarget(null); setSelectedIds([]);
      setToast({ message: `已${batchTarget === "active" ? "启用" : "停用"} ${result.updated_count} 个模型`, tone: "success" });
      await load();
    } catch (saveError) {
      setToast({ message: adminErrorMessage(saveError), tone: "error" });
    } finally { setSaving(false); }
  };

  /** 开关和周期都携带乐观锁版本，防止多个管理员同时修改时互相覆盖。 */
  const saveSyncSettings = async (enabled: boolean, intervalMinutes: number) => {
    if (!syncStatus) return;
    setSyncSaving(true); setSyncError("");
    try {
      setSyncStatus(await updateModelSyncSettings({
        enabled, interval_minutes: intervalMinutes, version: syncStatus.version,
      }));
      setToast({ message: enabled ? "模型自动同步已开启" : "模型自动同步已关闭", tone: "success" });
    } catch (saveError) {
      setSyncError(adminErrorMessage(saveError));
      setToast({ message: adminErrorMessage(saveError), tone: "error" });
      await loadSyncStatus();
    } finally { setSyncSaving(false); }
  };

  const executeSync = async () => {
    if (syncSaving || syncStatus?.running) return;
    setSyncSaving(true); setSyncError("");
    setSyncStatus(current => current ? { ...current, running: true } : current);
    try {
      const lastRun = await runModelSync();
      setToast({ message: `同步完成：新增 ${lastRun.inserted_count}，更新 ${lastRun.updated_count}`, tone: "success" });
      await Promise.all([loadSyncStatus(), load()]);
    } catch (runError) {
      setSyncError(adminErrorMessage(runError));
      setToast({ message: adminErrorMessage(runError), tone: "error" });
      await loadSyncStatus();
    } finally { setSyncSaving(false); }
  };

  return <div className="model-maintenance-page">
    <AdminPageHeader eyebrow="MODEL CATALOG" title="模型维护" description="统一维护对外模型、能力规格与平台售价；上游真实模型仍由渠道映射管理。" action={<><SecondaryButton onClick={() => setSyncPanelOpen(current => !current)}><AdminIcon name="refresh" size={15}/>{syncPanelOpen ? "收起同步设置" : "同步设置"}</SecondaryButton><PrimaryButton onClick={openCreate}><AdminIcon name="plus" size={16}/>新增模型</PrimaryButton></>}/>
    {syncPanelOpen && <section className="model-sync-panel model-sync-panel-expanded admin-animate" aria-label="模型市场同步设置">
      <div className="model-sync-heading">
        <span><AdminIcon name="refresh" size={17}/></span>
        <div><b>上游模型与服务分组同步</b><small>同步模型目录、上游服务分组及归属健康状态；不覆盖本地售价、权限、路由或上游 APIKey。</small></div>
      </div>
      <div className="model-sync-facts" aria-live="polite">
        <div><span>运行状态</span><b className={syncStatus?.last_run?.status === "failed" ? "bad" : ""}>{syncLoading ? "读取中…" : syncStatusLabel(syncStatus)}</b></div>
        <div><span>下次执行</span><b>{syncStatus?.enabled ? formatAdminTime(syncStatus.next_run_at) : "自动同步已关闭"}</b></div>
        <div><span>最近结果</span><b>{syncStatus?.last_run ? `+${syncStatus.last_run.inserted_count} / ~${syncStatus.last_run.updated_count} / 跳过 ${syncStatus.last_run.skipped_count}` : "暂无记录"}</b></div>
      </div>
      <div className="model-sync-controls">
        <label className="model-sync-switch">
          <input type="checkbox" checked={syncStatus?.enabled ?? false} disabled={!syncStatus || syncSaving} onChange={event => void saveSyncSettings(event.target.checked, syncStatus?.interval_minutes ?? 360)}/>
          <i/><span>自动同步</span>
        </label>
        <label className="model-sync-interval"><span className="sr-only">同步周期</span><select value={syncStatus?.interval_minutes ?? 360} disabled={!syncStatus || syncSaving} onChange={event => void saveSyncSettings(syncStatus?.enabled ?? false, Number(event.target.value))}>
          {!syncIntervals.includes(syncStatus?.interval_minutes ?? 360) && <option value={syncStatus?.interval_minutes}>{syncIntervalLabel(syncStatus?.interval_minutes ?? 360)}</option>}
          {syncIntervals.map(minutes => <option key={minutes} value={minutes}>{syncIntervalLabel(minutes)}</option>)}
        </select></label>
        <SecondaryButton onClick={() => void executeSync()} disabled={!syncStatus || syncSaving || syncStatus.running}><AdminIcon name="refresh" size={14}/>{syncStatus?.running || syncSaving ? "同步中…" : "立即同步"}</SecondaryButton>
      </div>
      {syncError && <p className="model-sync-error"><AdminIcon name="warning" size={13}/>{syncError}</p>}
    </section>}
    <section className="admin-list-panel admin-animate admin-card-list-panel">
      <div className="model-catalog-overview"><div><span>当前目录</span><b>{total} 个模型</b><small>本页展示 {items.length} 个，使用筛选可以快速缩小范围</small></div><dl><div><dt>本页公开</dt><dd>{summary.visible}</dd></div><div><dt>支持流式</dt><dd>{summary.streaming}</dd></div><div><dt>同步状态</dt><dd className={syncStatus?.last_run?.status === "failed" ? "bad" : "good"}>{syncLoading ? "读取中" : syncStatusLabel(syncStatus)}</dd></div></dl></div>
      <div className="admin-list-toolbar resource-card-toolbar"><div className="model-filter-searches"><SearchBox value={query} onChange={value => { setPage(1); setSelectedIds([]); setQuery(value); }} placeholder="搜索模型名称"/><SearchBox value={provider} onChange={value => { setPage(1); setSelectedIds([]); setProvider(value); }} placeholder="搜索厂商"/><SearchBox value={serviceGroup} onChange={value => { setPage(1); setSelectedIds([]); setServiceGroup(value); }} placeholder="搜索服务分组"/></div><div><SelectFilter label="能力类型筛选" value={capabilityType} onChange={value => { setPage(1); setSelectedIds([]); setCapabilityType(value); }}><option value="all">全部能力类型</option><option value="text">文本</option><option value="multimodal">多模态</option><option value="embedding">向量</option><option value="image">图像</option><option value="video">视频</option><option value="audio">音频</option></SelectFilter><SelectFilter label="状态筛选" value={status} onChange={value => { setPage(1); setSelectedIds([]); setStatus(value); }}><option value="all">全部状态</option><option value="active">启用</option><option value="disabled">停用</option><option value="maintenance">维护中</option></SelectFilter><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/></SecondaryButton></div></div>
      {items.length > 0 && <div className={`model-bulk-toolbar ${selectedIds.length ? "active" : ""}`}><label className="admin-card-select-all"><input type="checkbox" aria-label="选择当前页模型" checked={allPageSelected} onChange={togglePageSelection}/><i/><span>{selectedIds.length ? <>已选择 <b>{selectedIds.length}</b> 个模型</> : "选择本页模型"}</span></label>{selectedIds.length > 0 && <div><SecondaryButton tone="positive" onClick={() => setBatchTarget("active")} disabled={saving}><AdminIcon name="play" size={14}/>批量启用</SecondaryButton><SecondaryButton tone="danger" onClick={() => setBatchTarget("disabled")} disabled={saving}><AdminIcon name="pause" size={14}/>批量停用</SecondaryButton><SecondaryButton onClick={() => setSelectedIds([])} disabled={saving}>清除选择</SecondaryButton></div>}</div>}
      {loading && !items.length ? <LoadingState/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="没有匹配的模型" description="调整筛选条件，或创建第一个对外模型。"/> : <div className="admin-resource-grid model-admin-grid">{items.map(item => <article className={`admin-resource-card model-admin-card ${selectedIds.includes(item.id) ? "selected" : ""}`} key={item.id}>
        <header className="admin-resource-card-head">
          <label className="admin-card-select"><input type="checkbox" aria-label={`选择 ${item.display_name}`} checked={selectedIds.includes(item.id)} onChange={() => toggleSelection(item.id)}/><i/></label>
          <span className="resource-avatar model">{item.provider.charAt(0).toUpperCase()}</span>
          <div><h2>{item.display_name}</h2><code>{item.public_name}</code><small>{item.provider === "unknown" ? "厂商待补充" : item.provider}</small></div>
          <StatusPill value={item.status}/>
          <div className="row-actions"><IconButton icon="wallet" label="配置平台积分价格" onClick={() => setPricingTarget(item)}/><IconButton icon="edit" label="编辑模型" onClick={() => openEdit(item)}/><StatusActionButton active={item.status === "active"} activeLabel="停用模型" inactiveLabel="启用模型" busy={statusSavingId === item.id} disabled={Boolean(statusSavingId)} onClick={() => setConfirmTarget(item)}/></div>
        </header>
        <div className="model-card-meta"><span className="admin-tag violet">{capabilityLabel(item.capability_type)}</span><span className="admin-tag">{adapterLabel(adapterCatalog, item.adapter_key)}</span><span className={`visibility ${item.public_visible ? "public" : "private"}`}>{item.public_visible ? "公开模型" : "内部模型"}</span><span className="model-modality-flow">{item.input_modalities.join(" + ")} → {item.output_modalities.join(" + ")}</span><span className={`model-source-badge ${item.sync_source ? "synced" : "manual"}`}>{item.sync_source ? (item.source_managed ? "自动同步" : "人工保护") : "人工维护"}</span></div>
        {/* 模型可关联多个接口，卡片只展示接口数量；具体路径在编辑模型时维护，避免用第一条接口误导用户。 */}
        <dl className="model-card-metrics"><div><dt>接口文档</dt><dd>{item.interfaces.length}<small>{item.interfaces.length ? `${item.interfaces.length} 个文档已关联` : "尚未关联"}</small></dd></div><div><dt>计费方式</dt><dd>{billingTypeLabel(item.billing_type)}<small>{item.active_pricing_version_id ? "版本化计费" : "兼容价格"}</small></dd></div><div><dt>销售基准</dt><dd>{Number(item.active_pricing_version_id ? item.unit_price : item.input_price).toLocaleString("zh-CN", { maximumFractionDigits: 3 })}<small>积分 {item.active_pricing_version_id ? "" : priceUnitLabel(item.price_unit)}</small></dd></div></dl>
        <div className="model-card-resources"><div><span>服务分组</span><div className="admin-card-groups" aria-label="服务分组">{item.service_groups.length ? item.service_groups.slice(0, 3).map(group => <em className={group.source_status === "stale" ? "stale" : ""} key={group.id}>{group.name}</em>) : <small>尚未关联</small>}{item.service_groups.length > 3 && <small>+{item.service_groups.length - 3}</small>}</div></div><div><span>模型能力</span><div className="admin-card-capabilities"><em className={item.supports_streaming ? "on" : ""}>流式</em><em className={item.supports_tools ? "on" : ""}>工具</em><em className={item.supports_structured_output ? "on" : ""}>JSON</em></div></div></div>
        <footer><span>最后更新 {formatAdminTime(item.updated_at)}</span></footer>
      </article>)}</div>}
      <Pagination page={page} pageSize={modelPageSize} total={total} onChange={nextPage => { setSelectedIds([]); setPage(nextPage); }}/>
    </section>

    <Drawer open={drawerOpen} title={editing ? "编辑模型" : "新增模型"} description="这里维护平台对外目录和售价，不会修改供应商渠道中的真实模型名称。" onClose={closeDrawer} footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="model-form" disabled={saving}>{saving ? "保存中…" : "保存模型"}</PrimaryButton></>}>
      <form id="model-form" className="admin-form-grid" onSubmit={submit}>
        <Field label="对外模型名" required hint="OpenAI 兼容接口中的 model 参数"><input autoFocus required maxLength={160} value={form.public_name} onChange={event => setForm(current => ({ ...current, public_name: event.target.value }))} placeholder="gpt-5.6-sol"/></Field>
        <Field label="显示名称" required><input required maxLength={160} value={form.display_name} onChange={event => setForm(current => ({ ...current, display_name: event.target.value }))} placeholder="GPT 5.6 Sol"/></Field>
        <Field label="厂商" required><input required maxLength={80} value={form.provider} onChange={event => setForm(current => ({ ...current, provider: event.target.value }))} placeholder="OpenAI"/></Field>
        <Field label="能力类型" required><select value={form.capability_type} onChange={event => setForm(current => ({ ...current, capability_type: event.target.value, adapter_key: defaultAdapterFor(adapterCatalog, event.target.value) }))}><option value="text">文本</option><option value="multimodal">多模态</option><option value="embedding">向量</option><option value="image">图像</option><option value="video">视频</option><option value="audio">音频</option></select></Field><Field label="协议适配器" required hint="由管理员在协议适配器目录维护"><select required value={form.adapter_key} onChange={event => setForm(current => ({ ...current, adapter_key: event.target.value }))}>{adaptersFor(adapterCatalog, form.capability_type).map(adapter => <option value={adapter.adapter_key} key={adapter.adapter_key}>{adapter.display_name}</option>)}</select></Field>
        <Field label="输入模态" required hint="英文逗号分隔，最多 8 项"><input required value={form.input_modalities.join(", ")} onChange={event => setForm(current => ({ ...current, input_modalities: splitModalities(event.target.value) }))} placeholder="text, image"/></Field>
        <Field label="输出模态" required hint="英文逗号分隔，最多 8 项"><input required value={form.output_modalities.join(", ")} onChange={event => setForm(current => ({ ...current, output_modalities: splitModalities(event.target.value) }))} placeholder="text"/></Field>
        <Field label="上下文窗口" hint="可选；留空表示暂未确认"><input type="number" min={1} max={10000000000} value={form.context_window ?? ""} onChange={event => setForm(current => ({ ...current, context_window: optionalTokenLimit(event.target.value) }))}/></Field>
        <Field label="最大输出 Token" hint="可选；留空表示暂未确认"><input type="number" min={1} max={10000000000} value={form.max_output_tokens ?? ""} onChange={event => setForm(current => ({ ...current, max_output_tokens: optionalTokenLimit(event.target.value) }))}/></Field>
        <section className="protocol-model-selector wide"><header><div><span>API DOCUMENTS</span><h3>关联接口文档</h3><p>一个模型可以关联多个接口文档，仅用于向用户展示请求和返回参数，不影响真实调用。</p></div><b>{form.interface_ids.length} 已选择</b></header><div>{interfaceOptions.map(item => <label key={item.id}><input type="checkbox" checked={form.interface_ids.includes(item.id)} onChange={event => setForm(current => ({ ...current, interface_ids: event.target.checked ? [...current.interface_ids, item.id] : current.interface_ids.filter(id => id !== item.id) }))}/><span><b>{item.interface_name}</b><code>{item.http_method} {item.public_path}</code></span><em>{item.status === "active" ? "启用" : "停用"}</em></label>)}</div></section>
        {!editing?.active_pricing_version_id ? <><div className="admin-form-section wide"><span>LEGACY PRICE</span><p>这是创建模型时的兼容价格。模型保存后，请使用卡片上的“积分价格”按钮发布版本化平台售价。</p></div><Field label="兼容输入价格" required hint="仅在尚未发布价格版本时使用"><input type="number" min={0} step="0.0000000001" required value={form.input_price} onChange={event => setForm(current => ({ ...current, input_price: Number(event.target.value) }))}/></Field><Field label="兼容输出价格" required><input type="number" min={0} step="0.0000000001" required value={form.output_price} onChange={event => setForm(current => ({ ...current, output_price: Number(event.target.value) }))}/></Field><Field label="兼容缓存输入价格" required><input type="number" min={0} step="0.0000000001" required value={form.cached_input_price} onChange={event => setForm(current => ({ ...current, cached_input_price: Number(event.target.value) }))}/></Field><Field label="兼容价格单位" required><select value={form.price_unit} onChange={event => setForm(current => ({ ...current, price_unit: event.target.value }))}><option value="million_tokens">每百万 Token</option><option value="request">每次请求</option><option value="image">每张图片</option><option value="second">每秒</option><option value="character">每字符</option></select></Field></> : <div className="admin-security-note wide"><AdminIcon name="wallet" size={18}/><div><b>平台售价已版本化</b><p>模型基础资料保存不会覆盖当前价格。请关闭此抽屉后，点击模型卡片上的积分价格按钮调整计费规则。</p></div></div>}
        <div className="admin-toggle-grid wide"><ToggleField label="流式输出" description="允许 SSE 流式响应" checked={form.supports_streaming} onChange={checked => setForm(current => ({ ...current, supports_streaming: checked }))}/><ToggleField label="工具调用" description="支持 tools/function calling" checked={form.supports_tools} onChange={checked => setForm(current => ({ ...current, supports_tools: checked }))}/><ToggleField label="结构化输出" description="支持 JSON Schema" checked={form.supports_structured_output} onChange={checked => setForm(current => ({ ...current, supports_structured_output: checked }))}/><ToggleField label="公开可见" description="显示在用户模型目录" checked={form.public_visible} onChange={checked => setForm(current => ({ ...current, public_visible: checked }))}/></div>
        <Field label="业务状态" required wide><select value={form.status} onChange={event => setForm(current => ({ ...current, status: event.target.value }))}><option value="active">启用</option><option value="disabled">停用</option><option value="maintenance">维护中</option></select></Field>
      </form>
    </Drawer>
    <ModelPricingDrawer model={pricingTarget} open={Boolean(pricingTarget)} onClose={() => setPricingTarget(null)} onSuccess={message => { setToast({ message, tone: "success" }); void load(); }}/>
    <ConfirmDialog open={Boolean(confirmTarget)} title={confirmTarget?.status === "active" ? "停用这个模型？" : "启用这个模型？"} description={confirmTarget?.status === "active" ? `停用后，新请求将无法再通过 ${confirmTarget?.public_name ?? "该模型"} 调用。` : `启用后，${confirmTarget?.public_name ?? "该模型"} 将恢复对外可用状态，实际可调用性仍取决于渠道配置。`} confirmLabel={confirmTarget?.status === "active" ? "确认停用" : "确认启用"} danger={confirmTarget?.status === "active"} busy={Boolean(statusSavingId)} onCancel={() => { if (!statusSavingId) setConfirmTarget(null); }} onConfirm={() => void toggleStatus()}/>
    <ConfirmDialog open={Boolean(batchTarget)} title={batchTarget === "active" ? "批量启用选中的模型？" : "批量停用选中的模型？"} description={batchTarget === "active" ? `将启用选中的 ${selectedIds.length} 个模型；实际可调用性仍取决于渠道和路由配置。` : `将停用选中的 ${selectedIds.length} 个模型，新请求不能再通过这些对外模型名调用。`} confirmLabel={batchTarget === "active" ? "确认批量启用" : "确认批量停用"} danger={batchTarget === "disabled"} busy={saving} onCancel={() => setBatchTarget(null)} onConfirm={() => void executeBatchStatus()}/>
    <Toast message={toast.message} tone={toast.tone} onClose={() => setToast(current => ({ ...current, message: "" }))}/>
  </div>;
}
