"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { adminErrorMessage, listAdapters, saveAdapter, type Adapter, type AdapterInput } from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, Drawer, EmptyState, ErrorState, Field, IconButton, LoadingState, Pagination, PrimaryButton, SearchBox, SecondaryButton, SelectFilter, StatusPill, Toast } from "./AdminUi";

const capabilityLabels: Record<string, string> = { text: "文本", image: "图片", video: "视频" };
const implementationOptions = [
  { key: "openai_compatible_text", label: "OpenAI 兼容文本实现", capability: "text" },
  { key: "openai_compatible_image", label: "OpenAI 兼容图片实现", capability: "image" },
  { key: "openai_compatible_video", label: "OpenAI 兼容视频实现", capability: "video" },
  { key: "jimeng_video", label: "即梦视频实现", capability: "video" },
  { key: "grok_video", label: "Grok 视频实现", capability: "video" },
  { key: "minimax_h3_video", label: "MiniMax H3 视频实现", capability: "video" },
];
const emptyAdapter: AdapterInput = {
  adapter_key: "", display_name: "", capability_type: "text", implementation_key: "openai_compatible_text",
  description: "", status: "active", version: null,
};

function toInput(item: Adapter): AdapterInput {
  return { adapter_key: item.adapter_key, display_name: item.display_name, capability_type: item.capability_type, implementation_key: item.implementation_key, description: item.description ?? "", status: item.status, version: item.version };
}

export function AdaptersAdminPage() {
  const [items, setItems] = useState<Adapter[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [capability, setCapability] = useState("all");
  const [status, setStatus] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<AdapterInput | null>(null);
  const [editingId, setEditingId] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState("");
  const [toastTone, setToastTone] = useState<"success" | "error">("success");

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { const result = await listAdapters({ page, pageSize: 12, query, capabilityType: capability, status }); setItems(result.items); setTotal(result.total); }
    catch (cause) { setError(adminErrorMessage(cause)); }
    finally { setLoading(false); }
  }, [page, query, capability, status]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);

  const openCreate = () => { setEditing({ ...emptyAdapter }); setEditingId(undefined); };
  const openEdit = (item: Adapter) => { setEditing(toInput(item)); setEditingId(item.id); };
  const updateEditing = (patch: Partial<AdapterInput>) => setEditing(current => current ? { ...current, ...patch } : current);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!editing) return; setBusy(true);
    try { await saveAdapter(editing, editingId); setToast(editingId ? "适配器已保存" : "适配器已创建"); setToastTone("success"); setEditing(null); await load(); }
    catch (cause) { setToast(adminErrorMessage(cause)); setToastTone("error"); }
    finally { setBusy(false); }
  };

  return <>
    <AdminPageHeader eyebrow="ADAPTER CATALOG" title="协议适配器" description="管理员维护适配器名称、能力类型、代码实现绑定和启停状态。适配器只负责协议转换，鉴权、路由、计费和日志由业务层统一处理。" action={<PrimaryButton onClick={openCreate}><AdminIcon name="plus" size={16}/> 新建适配器</PrimaryButton>} />
    <section className="admin-list-panel protocol-panel admin-animate">
      <div className="admin-list-toolbar"><SearchBox value={query} onChange={value => { setPage(1); setQuery(value); }} placeholder="搜索适配器编码、名称或说明"/><SelectFilter value={capability} onChange={value => { setPage(1); setCapability(value); }} label="能力类型"><option value="all">全部能力</option>{Object.entries(capabilityLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</SelectFilter><SelectFilter value={status} onChange={value => { setPage(1); setStatus(value); }} label="适配器状态"><option value="all">全部状态</option><option value="active">启用</option><option value="disabled">停用</option></SelectFilter><button className="admin-icon-button" onClick={() => void load()} aria-label="刷新适配器" title="刷新适配器"><AdminIcon name="refresh" size={16}/></button></div>
      {loading ? <LoadingState label="正在加载适配器目录…"/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="还没有适配器" description="创建一个适配器目录项并绑定已注册的代码实现。"/> : <div className="protocol-grid">{items.map(item => <article className="protocol-card" key={item.id}><header><div className="protocol-mark"><AdminIcon name={item.capability_type === "video" ? "pulse" : "settings"} size={19}/></div><div><span>{item.adapter_key}</span><h2>{item.display_name}</h2></div><StatusPill value={item.status}/></header><div className="protocol-card-meta"><span>{capabilityLabels[item.capability_type] ?? item.capability_type}</span><span>{item.implementation_label}</span></div><p>{item.description || "尚未填写适配器说明"}</p><footer><code>模型维护引用 · ai_models.adapter_key</code><IconButton icon="edit" label="编辑适配器" onClick={() => openEdit(item)}/></footer></article>)}</div>}
      {!loading && !error && items.length > 0 && <Pagination page={page} pageSize={12} total={total} onChange={setPage}/>} 
    </section>
    <Drawer open={Boolean(editing)} title={editingId ? "编辑协议适配器" : "新建协议适配器"} description="目录项必须绑定后端已注册的代码实现；新建后即可在模型维护中选择。" onClose={() => !busy && setEditing(null)} footer={<><SecondaryButton onClick={() => setEditing(null)} disabled={busy}>取消</SecondaryButton><PrimaryButton form="adapter-form" type="submit" disabled={busy}>{busy ? "保存中…" : "保存适配器"}</PrimaryButton></>}>
      {editing && <form id="adapter-form" onSubmit={submit} className="admin-form-grid"><Field label="适配器编码" required hint="创建后不可修改"><input required value={editing.adapter_key} onChange={event => updateEditing({ adapter_key: event.target.value })} placeholder="例如 minimax_h3_video" disabled={Boolean(editingId)}/></Field><Field label="显示名称" required><input required value={editing.display_name} onChange={event => updateEditing({ display_name: event.target.value })} placeholder="例如 MiniMax H3 视频"/></Field><Field label="能力类型" required><select value={editing.capability_type} onChange={event => { const value = event.target.value; const first = implementationOptions.find(option => option.capability === value); updateEditing({ capability_type: value, implementation_key: first?.key ?? "" }); }}><option value="text">文本</option><option value="image">图片</option><option value="video">视频</option></select></Field><Field label="代码实现" required hint="只能选择后端已注册实现"><select required value={editing.implementation_key} onChange={event => updateEditing({ implementation_key: event.target.value })}>{implementationOptions.filter(option => option.capability === editing.capability_type).map(option => <option value={option.key} key={option.key}>{option.label}</option>)}</select></Field><Field label="状态"><select value={editing.status} onChange={event => updateEditing({ status: event.target.value })}><option value="active">启用</option><option value="disabled">停用</option></select></Field><Field label="适配器说明" wide><textarea rows={4} value={editing.description ?? ""} onChange={event => updateEditing({ description: event.target.value })} placeholder="说明上游协议差异和适用场景"/></Field></form>}
    </Drawer>
    <Toast message={toast} tone={toastTone} onClose={() => setToast("")}/>
  </>;
}
