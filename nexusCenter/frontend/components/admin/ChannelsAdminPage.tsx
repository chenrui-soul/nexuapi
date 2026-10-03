"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { adminErrorMessage, listChannelOperations, listChannels, listSuppliers, saveChannel, toManualChannelStatus, type Channel, type ChannelInput, type ChannelOperation, type Supplier } from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, ConfirmDialog, Drawer, EmptyState, ErrorState, Field, IconButton, LoadingState, Pagination, PrimaryButton, SearchBox, SecondaryButton, SelectFilter, StatusActionButton, StatusPill, Toast } from "./AdminUi";

const emptyChannel: ChannelInput = {
  supplier_id: "", name: "", provider_type: "openai", operation_code: "chat_completions", endpoint_type: "text",
  base_url: "", request_method: "POST", health_probe_path: "/models",
  proxy_url: null, status: "active", timeout_ms: 120000, concurrency_limit: 100,
  priority: 100, weight: 100, version: null,
};

function toInput(item: Channel): ChannelInput {
  return {
    supplier_id: item.supplier_id, name: item.name, provider_type: item.provider_type,
    operation_code: item.operation_code, endpoint_type: item.endpoint_type,
    request_method: item.request_method ?? "POST", base_url: item.base_url, health_probe_path: item.health_probe_path,
    proxy_url: item.proxy_url, status: toManualChannelStatus(item.status),
    timeout_ms: item.timeout_ms, concurrency_limit: item.concurrency_limit,
    priority: item.priority, weight: item.weight, version: item.version,
  };
}

/**
 * 上游接口维护页：只管理供应商连接信息，上游 APIKey 统一在服务分组中配置。
 */
export function ChannelsAdminPage({ supplierId }: { supplierId?: string }) {
  const [items, setItems] = useState<Channel[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [operations, setOperations] = useState<ChannelOperation[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<Channel | null>(null);
  const [form, setForm] = useState<ChannelInput>(emptyChannel);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<Channel | null>(null);
  const [toast, setToast] = useState({ message: "", tone: "success" as "success" | "error" });

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [channels, supplierPage, operationItems] = await Promise.all([
        listChannels({ page, pageSize: 20, query, status, supplierId }),
        listSuppliers({ pageSize: 100 }),
        listChannelOperations(),
      ]);
      setItems(channels.items); setTotal(channels.total); setSuppliers(supplierPage.items); setOperations(operationItems);
    } catch (loadError) { setError(adminErrorMessage(loadError)); }
    finally { setLoading(false); }
  }, [page, query, status, supplierId]);

  useEffect(() => { const timer = window.setTimeout(() => void load(), query ? 260 : 0); return () => window.clearTimeout(timer); }, [load, query]);

  const summary = useMemo(() => ({
    callable: items.filter(item => item.status === "active" || item.status === "degraded").length,
    disabled: items.filter(item => item.status === "disabled").length,
    endpointTypes: new Set(items.map(item => item.endpoint_type)).size,
  }), [items]);

  const activeSuppliers = suppliers.filter(item => item.status === "active");
  const operationByCode = useMemo(() => new Map(operations.map(item => [item.operation_code, item])), [operations]);
  const selectOperation = (operationCode: ChannelInput["operation_code"]) => {
    const operation = operationByCode.get(operationCode);
    if (!operation) return;
    setForm(current => ({
      ...current,
      operation_code: operationCode,
      endpoint_type: operation.capability_type,
      request_method: operation.request_method,
    }));
  };
  const openCreate = () => { setEditing(null); setForm({ ...emptyChannel, supplier_id: supplierId ?? activeSuppliers[0]?.id ?? "" }); setDrawerOpen(true); };
  const openEdit = (item: Channel) => { setEditing(item); setForm(toInput(item)); setDrawerOpen(true); };
  const closeDrawer = () => { if (!saving) { setDrawerOpen(false); setEditing(null); setForm(emptyChannel); } };

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true);
    try {
      const payload = {
        ...form,
        name: form.name.trim(), provider_type: form.provider_type.trim().toLowerCase(), base_url: form.base_url.trim(),
        health_probe_path: form.health_probe_path.trim(),
        proxy_url: form.proxy_url?.trim() || null,
      };
      await saveChannel(payload, editing?.id);
      setToast({ message: editing ? "渠道配置已更新" : "渠道已创建", tone: "success" }); closeDrawer(); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  const toggleStatus = async () => {
    if (!confirmTarget) return;
    setSaving(true); const nextStatus = confirmTarget.status === "active" ? "disabled" : "active";
    try { await saveChannel({ ...toInput(confirmTarget), status: nextStatus }, confirmTarget.id); setConfirmTarget(null); setToast({ message: nextStatus === "active" ? "渠道已启用" : "渠道已停用", tone: "success" }); await load(); }
    catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  return <div>
    <AdminPageHeader eyebrow="UPSTREAM / ENDPOINTS" title="上游接口" description="在供应商下维护文本、图像、视频、音频等接口地址和协议；上游 APIKey 统一在服务分组中配置。" action={<PrimaryButton onClick={openCreate} disabled={!activeSuppliers.length}><AdminIcon name="plus" size={16}/>新增上游接口</PrimaryButton>}/>
    <section className="admin-summary-strip admin-animate"><div><span>接口总数</span><b>{total}</b></div><div><span>本页可调用</span><b className="good">{summary.callable}</b></div><div><span>人工停用</span><b className={summary.disabled ? "bad" : ""}>{summary.disabled}</b></div><div><span>能力类型</span><b>{summary.endpointTypes}</b></div><p><AdminIcon name="key" size={15}/>上游 APIKey 属于服务分组资源，不再保存在供应商接口中。</p></section>
    {!activeSuppliers.length && !loading && <div className="admin-inline-warning admin-animate"><AdminIcon name="warning" size={17}/><div><b>请先创建并启用供应商</b><span>渠道必须归属于一个可用供应商。</span></div></div>}
    <section className="admin-list-panel admin-animate">
      <div className="admin-list-toolbar"><SearchBox value={query} onChange={value => { setPage(1); setQuery(value); }} placeholder="搜索接口、供应商或 operation_code"/><div><SelectFilter label="状态筛选" value={status} onChange={value => { setPage(1); setStatus(value); }}><option value="all">全部状态</option><option value="active">启用</option><option value="disabled">停用</option><option value="degraded">波动</option></SelectFilter><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/></SecondaryButton></div></div>
      {loading && !items.length ? <LoadingState/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="没有匹配的接口" description="调整筛选条件，或在供应商下创建第一个上游接口。"/> : <div className="admin-table-wrap"><table className="admin-table channel-table"><thead><tr><th>接口</th><th>供应商</th><th>接口用途</th><th>上游地址</th><th>路由参数</th><th>最近错误</th><th>状态</th><th aria-label="操作"/></tr></thead><tbody>{items.map(item => { const operation = operationByCode.get(item.operation_code); return <tr key={item.id}><td><div className="resource-name"><span className="resource-avatar channel">{item.name.charAt(0).toUpperCase()}</span><div><b>{item.name}</b><code>{item.operation_code}</code></div></div></td><td><b className="table-primary">{item.supplier_name}</b><small>{item.supplier_code}</small></td><td><span className="admin-tag">{operation?.display_name ?? item.operation_code}</span><small>{item.request_method} · {operation?.public_path ?? item.endpoint_type}</small></td><td><code className="url-cell" title={item.base_url}>{item.base_url}</code><small>{item.timeout_ms / 1000}s timeout · {item.concurrency_limit ?? "∞"} 并发</small></td><td><b className="table-primary">P{item.priority}</b><small>权重 {item.weight}</small></td><td><code className="health-summary-code">{item.last_error_summary ?? "未记录"}</code></td><td><StatusPill value={item.status}/></td><td><div className="row-actions"><IconButton icon="edit" label="编辑接口" onClick={() => openEdit(item)}/><StatusActionButton active={item.status === "active"} activeLabel="停用接口" inactiveLabel="启用接口" onClick={() => setConfirmTarget(item)}/></div></td></tr>; })}</tbody></table></div>}
      <Pagination page={page} pageSize={20} total={total} onChange={setPage}/>
    </section>

    <Drawer open={drawerOpen} title={editing ? "编辑上游接口" : "新增上游接口"} description="这里只维护接口地址、协议和连接参数；上游 APIKey 请在服务分组中配置。" onClose={closeDrawer} footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="channel-form" disabled={saving}>{saving ? "保存中…" : "保存上游接口"}</PrimaryButton></>}>
      <form id="channel-form" className="admin-form-grid" onSubmit={submit} autoComplete="off">
        <Field label="所属供应商" required><select autoFocus required disabled={Boolean(supplierId)} value={form.supplier_id} onChange={event => setForm(current => ({ ...current, supplier_id: event.target.value }))}><option value="" disabled>请选择供应商</option>{suppliers.map(supplier => <option value={supplier.id} disabled={supplier.status !== "active" && supplier.id !== editing?.supplier_id} key={supplier.id}>{supplier.name} · {supplier.code}{supplier.status !== "active" ? "（已停用）" : ""}</option>)}</select></Field>
        <Field label="接口名称" required><input required maxLength={120} value={form.name} onChange={event => setForm(current => ({ ...current, name: event.target.value }))} placeholder="文本 API · 主线路"/></Field>
        <Field label="协议类型" required><select value={form.provider_type} onChange={event => setForm(current => ({ ...current, provider_type: event.target.value }))}><option value="openai">OpenAI Compatible</option><option value="anthropic">Anthropic</option><option value="azure_openai">Azure OpenAI</option><option value="google">Google Gemini</option><option value="custom">自定义协议</option></select></Field>
        <Field label="接口用途" required hint="平台按 operation_code 精确选择供应商接口"><select value={form.operation_code} onChange={event => selectOperation(event.target.value as ChannelInput["operation_code"])}>{operations.map(operation => <option value={operation.operation_code} key={operation.operation_code}>{operation.display_name} · {operation.request_method} {operation.public_path}</option>)}</select></Field>
        <Field label="能力编码"><div className="admin-readonly-field"><code>{form.operation_code}</code><small>{form.request_method} · {form.endpoint_type}</small></div></Field>
        <Field label="业务状态" required><select value={form.status} onChange={event => setForm(current => ({ ...current, status: event.target.value }))}><option value="active">启用</option><option value="disabled">停用</option></select></Field>
        <Field wide label="完整上游接口地址" required hint={form.operation_code === "image_task_detail" ? "异步任务详情支持 /v1/images/tasks/{id} 路径占位符" : "真实调用按 operation_code 选择此地址，不再通过 URL 路径猜测用途"}><input type={form.operation_code === "image_task_detail" ? "text" : "url"} required maxLength={2048} value={form.base_url} onChange={event => setForm(current => ({ ...current, base_url: event.target.value }))} placeholder={form.operation_code === "image_task_detail" ? "https://api.example.com/v1/images/tasks/{id}" : "https://api.openai.com/v1/chat/completions"}/></Field>
        <Field wide label="健康探测路径" required hint="相对于同一上游 API 根路径解析；不支持查询参数、片段或路径穿越，不兼容时自动回退 /models"><input required maxLength={256} pattern="/.*" value={form.health_probe_path} onChange={event => setForm(current => ({ ...current, health_probe_path: event.target.value }))} placeholder="/models"/></Field>
        <Field wide label="代理地址（可选）"><input type="url" maxLength={2048} value={form.proxy_url ?? ""} onChange={event => setForm(current => ({ ...current, proxy_url: event.target.value }))} placeholder="https://proxy.internal"/></Field>
        <Field label="超时时间（毫秒）" required><input type="number" min={100} max={600000} required value={form.timeout_ms} onChange={event => setForm(current => ({ ...current, timeout_ms: Number(event.target.value) }))}/></Field>
        <Field label="并发上限" required><input type="number" min={1} max={100000} required value={form.concurrency_limit ?? ""} onChange={event => setForm(current => ({ ...current, concurrency_limit: Number(event.target.value) }))}/></Field>
        <Field label="优先级" required hint="数值越小越优先"><input type="number" min={0} max={1000000} required value={form.priority} onChange={event => setForm(current => ({ ...current, priority: Number(event.target.value) }))}/></Field>
        <Field label="路由权重" required hint="同优先级内按权重分流"><input type="number" min={1} max={1000000} required value={form.weight} onChange={event => setForm(current => ({ ...current, weight: Number(event.target.value) }))}/></Field>
        <div className="admin-security-note wide"><AdminIcon name="shield" size={18}/><div><b>职责边界</b><p>供应商接口通过 operation_code 声明“提供哪项能力以及从哪里调用”；服务分组负责用户权限与计价。</p></div></div>
      </form>
    </Drawer>
    <ConfirmDialog open={Boolean(confirmTarget)} title={confirmTarget?.status === "active" ? "停用这个渠道？" : "重新启用这个渠道？"} description={confirmTarget?.status === "active" ? `停用 ${confirmTarget?.name} 后，路由器将立即跳过该上游连接。` : `重新启用 ${confirmTarget?.name} 后，路由器会按 operation_code、模型能力和运行状态决定是否调用。`} confirmLabel={confirmTarget?.status === "active" ? "确认停用" : "确认启用"} danger={confirmTarget?.status === "active"} busy={saving} onCancel={() => setConfirmTarget(null)} onConfirm={() => void toggleStatus()}/>
    <Toast message={toast.message} tone={toast.tone} onClose={() => setToast(current => ({ ...current, message: "" }))}/>
  </div>;
}
