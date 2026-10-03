"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import {
  adminErrorMessage, listProtocols, saveProtocol,
  type ApiInterfaceDefinition, type ApiInterfaceInput, type ProtocolSchemaField,
} from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, Drawer, EmptyState, ErrorState, Field, LoadingState, Pagination, PrimaryButton, SearchBox, SecondaryButton, SelectFilter, StatusPill, Toast } from "./AdminUi";

const emptyField = (): ProtocolSchemaField => ({
  name: "", path: "", type: "string", required: false, description: "", default_value: undefined,
  example: undefined, enum_values: [], minimum: null, maximum: null, deprecated: false,
  sensitive: false, children: [],
});

const emptyProtocol: ApiInterfaceInput = {
  interface_code: "", interface_name: "", interface_version: "v1", capability_type: "text",
  transport_mode: "sync", http_method: "POST", public_path: "/v1/", request_content_type: "application/json",
  description: "", status: "active", request_fields: [emptyField()], response_fields: [emptyField()], version: null,
};

const capabilityLabels: Record<string, string> = { text: "文本", image: "图像", audio: "音频", video: "视频", embedding: "向量", multimodal: "多模态", file: "文件" };
const transportLabels: Record<string, string> = { sync: "同步响应", stream: "SSE 流式", async_poll: "异步任务 / 轮询" };

function fieldFromValue(value: ProtocolSchemaField): ProtocolSchemaField {
  return { ...emptyField(), ...value, children: (value.children ?? []).map(fieldFromValue), enum_values: value.enum_values ?? [] };
}

function toInput(item: ApiInterfaceDefinition): ApiInterfaceInput {
  return {
    interface_code: item.interface_code, interface_name: item.interface_name, interface_version: item.interface_version,
    capability_type: item.capability_type, transport_mode: item.transport_mode, http_method: item.http_method,
    public_path: item.public_path, request_content_type: item.request_content_type,
    description: item.description ?? "", status: item.status, request_fields: item.request_fields.map(fieldFromValue),
    response_fields: item.response_fields.map(fieldFromValue), version: item.version,
  };
}

function ProtocolFieldRow({ field, index, depth, onChange, onRemove }: { field: ProtocolSchemaField; index: number; depth: number; onChange: (field: ProtocolSchemaField) => void; onRemove: () => void }) {
  const update = (patch: Partial<ProtocolSchemaField>) => onChange({ ...field, ...patch });
  const updateChild = (childIndex: number, child: ProtocolSchemaField) => update({ children: field.children.map((current, indexValue) => indexValue === childIndex ? child : current) });
  const removeChild = (childIndex: number) => update({ children: field.children.filter((_, indexValue) => indexValue !== childIndex) });
  const canNest = field.type === "object" || field.type === "array";
  return <article className={`protocol-field-card depth-${Math.min(depth, 3)}`}>
    <div className="protocol-field-index">{String(index + 1).padStart(2, "0")}</div>
    <div className="protocol-field-content">
      <div className="protocol-field-grid">
        <Field label="字段名称" required><input value={field.name} onChange={event => update({ name: event.target.value, path: field.path || event.target.value })} placeholder="例如 model" /></Field>
        <Field label="类型"><select value={field.type} onChange={event => update({ type: event.target.value, children: ["object", "array"].includes(event.target.value) ? field.children : [] })}><option value="string">string</option><option value="string|array">string | array</option><option value="integer">integer</option><option value="number">number</option><option value="boolean">boolean</option><option value="array">array</option><option value="object">object</option><option value="object|array|string">object | array | string</option><option value="file">File</option></select></Field>
        <label className="protocol-required"><input type="checkbox" checked={field.required} onChange={event => update({ required: event.target.checked })}/><span>必填字段</span></label>
        <button type="button" className="protocol-remove-field" onClick={onRemove} aria-label={`删除字段 ${field.name || index + 1}`}><AdminIcon name="close" size={14}/></button>
        <Field label="字段路径"><input value={field.path ?? ""} onChange={event => update({ path: event.target.value })} placeholder="例如 choices[].message.content" /></Field>
        <Field label="中文说明" wide><textarea rows={2} value={field.description ?? ""} onChange={event => update({ description: event.target.value })} placeholder="说明这个参数的用途、取值含义和使用限制" /></Field>
        <Field label="默认值"><input value={field.default_value == null ? "" : String(field.default_value)} onChange={event => update({ default_value: event.target.value || undefined })} placeholder="可选" /></Field>
        <Field label="示例值"><input value={field.example == null ? "" : String(field.example)} onChange={event => update({ example: event.target.value || undefined })} placeholder="可选" /></Field>
        <Field label="枚举值"><input value={(field.enum_values ?? []).join(", ")} onChange={event => update({ enum_values: event.target.value.split(",").map(value => value.trim()).filter(Boolean) })} placeholder="用逗号分隔" /></Field>
      </div>
      {/* key 不能包含可编辑字段名，否则每输入一个字符都会重建组件并丢失焦点。 */}
      {canNest && <section className="protocol-children"><header><span>{field.type === "array" ? "数组元素字段" : "对象子字段"}</span><button type="button" onClick={() => update({ children: [...field.children, emptyField()] })}><AdminIcon name="plus" size={12}/> 添加子字段</button></header>{field.children.map((child, childIndex) => <ProtocolFieldRow key={childIndex} field={child} index={childIndex} depth={depth + 1} onChange={value => updateChild(childIndex, value)} onRemove={() => removeChild(childIndex)}/>)}</section>}
    </div>
  </article>;
}

function FieldEditor({ label, fields, onChange }: { label: string; fields: ProtocolSchemaField[]; onChange: (fields: ProtocolSchemaField[]) => void }) {
  const update = (index: number, field: ProtocolSchemaField) => onChange(fields.map((current, currentIndex) => currentIndex === index ? field : current));
  const remove = (index: number) => onChange(fields.filter((_, current) => current !== index));
  return <section className="protocol-schema-editor">
    <header><div><span>{label}</span><h3>字段定义与注释</h3><p>每个字段都可以填写类型、必填状态、说明、默认值和示例。</p></div><button type="button" className="protocol-add-field" onClick={() => onChange([...fields, emptyField()])}><AdminIcon name="plus" size={14}/> 添加字段</button></header>
    <div className="protocol-field-list">
      {fields.map((field, index) => <ProtocolFieldRow key={index} field={field} index={index} depth={0} onChange={value => update(index, value)} onRemove={() => remove(index)}/>)}
      {!fields.length && <div className="protocol-empty-fields">还没有字段，点击“添加字段”开始维护。</div>}
    </div>
  </section>;
}

export function ProtocolsAdminPage() {
  const [items, setItems] = useState<ApiInterfaceDefinition[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [capability, setCapability] = useState("all");
  const [status, setStatus] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<ApiInterfaceInput | null>(null);
  const [editingId, setEditingId] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState("");
  const [toastTone, setToastTone] = useState<"success" | "error">("success");

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { const result = await listProtocols({ page, pageSize: 10, query, capabilityType: capability, status }); setItems(result.items); setTotal(result.total); }
    catch (cause) { setError(adminErrorMessage(cause)); }
    finally { setLoading(false); }
  }, [page, query, capability, status]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);

  const openCreate = () => { setEditing({ ...emptyProtocol, request_fields: [emptyField()], response_fields: [emptyField()] }); setEditingId(undefined); };
  const openEdit = (item: ApiInterfaceDefinition) => { setEditing(toInput(item)); setEditingId(item.id); };
  const updateEditing = (patch: Partial<ApiInterfaceInput>) => setEditing(current => current ? { ...current, ...patch } : current);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!editing) return; setBusy(true);
    try { await saveProtocol(editing, editingId); setToast(editingId ? "接口文档已保存" : "接口文档已创建"); setToastTone("success"); setEditing(null); await load(); }
    catch (cause) { setToast(adminErrorMessage(cause)); setToastTone("error"); }
    finally { setBusy(false); }
  };
  const pageSize = 10;

  return <>
    <AdminPageHeader eyebrow="API DOCUMENTATION" title="接口文档" description="维护平台接口路径、请求字段、响应字段和中文说明；支持接口由模型维护页面选择。" action={<PrimaryButton onClick={openCreate}><AdminIcon name="plus" size={16}/> 新建接口</PrimaryButton>} />
    <section className="admin-list-panel protocol-panel admin-animate">
      <div className="admin-list-toolbar"><SearchBox value={query} onChange={value => { setPage(1); setQuery(value); }} placeholder="搜索接口编码、名称或说明"/><SelectFilter value={capability} onChange={value => { setPage(1); setCapability(value); }} label="能力类型"><option value="all">全部能力</option>{Object.entries(capabilityLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</SelectFilter><SelectFilter value={status} onChange={value => { setPage(1); setStatus(value); }} label="接口状态"><option value="all">全部状态</option><option value="active">启用</option><option value="disabled">停用</option></SelectFilter><button className="admin-icon-button" onClick={() => void load()} aria-label="刷新接口" title="刷新接口"><AdminIcon name="refresh" size={16}/></button></div>
      {loading ? <LoadingState label="正在加载接口文档…"/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="还没有接口文档" description="先创建一个接口，维护请求和响应字段说明。"/> : <div className="protocol-grid">{items.map(item => <article className="protocol-card" key={item.id}><header><div className="protocol-mark"><AdminIcon name={item.capability_type === "video" ? "pulse" : "settings"} size={19}/></div><div><span>{item.interface_code}</span><h2>{item.interface_name}</h2></div><StatusPill value={item.status}/></header><div className="protocol-card-meta"><span>{capabilityLabels[item.capability_type] ?? item.capability_type}</span><span>{transportLabels[item.transport_mode] ?? item.transport_mode}</span><code>{item.http_method} {item.public_path}</code></div><p>{item.description || "尚未填写接口说明"}</p><footer><span><b>{item.request_fields.length}</b> 个入参字段</span><span><b>{item.response_fields.length}</b> 个出参字段</span><button className="protocol-edit" onClick={() => openEdit(item)}><AdminIcon name="edit" size={14}/> 编辑文档</button></footer></article>)}</div>}
      {!loading && !error && items.length > 0 && <Pagination page={page} pageSize={pageSize} total={total} onChange={setPage}/>} 
    </section>
    <Drawer open={Boolean(editing)} title={editingId ? "编辑接口文档" : "新建接口文档"} description="这里只维护接口路径和 JSON 字段说明；模型支持关系请到模型维护页面选择。" onClose={() => !busy && setEditing(null)} className="protocol-drawer" footer={<><SecondaryButton onClick={() => setEditing(null)} disabled={busy}>取消</SecondaryButton><PrimaryButton form="protocol-form" type="submit" disabled={busy}>{busy ? "保存中…" : "保存接口"}</PrimaryButton></>}>
      {editing && <form id="protocol-form" onSubmit={submit} className="protocol-form"><div className="protocol-form-intro"><span>API INTERFACE</span><strong>{editing.interface_code || "new_interface"}</strong><small>接口文档不保存供应商、服务分组、上游 APIKey 或 Java 适配器配置。</small></div><div className="admin-form-grid"><Field label="接口编码" required><input value={editing.interface_code} onChange={event => updateEditing({ interface_code: event.target.value })} placeholder="例如 grok_video" disabled={Boolean(editingId)}/></Field><Field label="接口名称" required><input value={editing.interface_name} onChange={event => updateEditing({ interface_name: event.target.value })} placeholder="例如 Grok 视频生成接口"/></Field><Field label="接口版本"><input value={editing.interface_version} onChange={event => updateEditing({ interface_version: event.target.value })}/></Field><Field label="能力类型"><select value={editing.capability_type} onChange={event => updateEditing({ capability_type: event.target.value })}>{Object.entries(capabilityLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></Field><Field label="交互方式"><select value={editing.transport_mode} onChange={event => updateEditing({ transport_mode: event.target.value })}>{Object.entries(transportLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></Field><Field label="HTTP 方法"><select value={editing.http_method} onChange={event => updateEditing({ http_method: event.target.value })}>{["POST", "GET", "PUT", "PATCH", "DELETE"].map(method => <option key={method}>{method}</option>)}</select></Field><Field label="对外路径" wide><input value={editing.public_path} onChange={event => updateEditing({ public_path: event.target.value })} placeholder="/v1/videos/grok"/></Field><Field label="状态"><select value={editing.status} onChange={event => updateEditing({ status: event.target.value })}><option value="active">启用</option><option value="disabled">停用</option></select></Field><Field label="接口说明" wide><textarea rows={3} value={editing.description ?? ""} onChange={event => updateEditing({ description: event.target.value })} placeholder="说明接口用途、调用步骤和返回结果"/></Field></div><Field label="请求 Content-Type"><input value={editing.request_content_type} onChange={event => updateEditing({ request_content_type: event.target.value })}/></Field><FieldEditor label="REQUEST" fields={editing.request_fields} onChange={request_fields => updateEditing({ request_fields })}/><FieldEditor label="RESPONSE" fields={editing.response_fields} onChange={response_fields => updateEditing({ response_fields })}/></form>}
    </Drawer>
    <Toast message={toast} tone={toastTone} onClose={() => setToast("")}/>
  </>;
}
