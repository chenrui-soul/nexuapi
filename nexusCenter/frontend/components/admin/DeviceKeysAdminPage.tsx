"use client";

import { useCallback, useEffect, useState } from "react";
import { createDeviceKey, deleteDeviceKey, listDeviceKeys, setDeviceKeyStatus, type DeviceKey } from "@/lib/device-keys";
import { AdminIcon } from "./AdminIcon";
import { AdminPageHeader, ConfirmDialog, Drawer, EmptyState, ErrorState, Field, LoadingState, Pagination, SearchBox, SecondaryButton, StatusPill } from "./AdminUi";

const remaining = (value: string | null) => {
  if (!value) return "长期有效";
  const ms = new Date(value).getTime() - Date.now();
  if (ms <= 0) return "已过期";
  const hours = Math.floor(ms / 3600000);
  const days = Math.floor(hours / 24);
  return days ? `${days} 天 ${hours % 24} 小时` : `${Math.max(1, hours)} 小时`;
};

const validityOptions = [
  ["permanent", "长期有效", null],
  ["7d", "7 天", 7],
  ["30d", "30 天", 30],
  ["90d", "90 天", 90],
  ["180d", "180 天", 180],
  ["365d", "1 年", 365],
] as const;

function expiryFromChoice(choice: string): string | null {
  const option = validityOptions.find(([value]) => value === choice);
  if (!option || option[2] === null) return null;
  return new Date(Date.now() + option[2] * 24 * 60 * 60 * 1000).toISOString();
}

export function DeviceKeysAdminPage() {
  const [items, setItems] = useState<DeviceKey[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(11);
  const [query, setQuery] = useState("");
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState("");
  const [applicationCode, setApplicationCode] = useState("");
  const [validity, setValidity] = useState("permanent");
  const [deleteTarget, setDeleteTarget] = useState<DeviceKey | null>(null);
  const [mutationBusy, setMutationBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    const updatePageSize = () => setPageSize(window.innerWidth < 761 ? 8 : Math.max(3, Math.min(20, Math.floor((window.innerHeight - 414) / 72))));
    updatePageSize();
    window.addEventListener("resize", updatePageSize);
    return () => window.removeEventListener("resize", updatePageSize);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listDeviceKeys({ page, pageSize, query });
      setItems(result.items);
      setTotal(result.total);
      setError("");
    } catch (e) { setError(e instanceof Error ? e.message : "设备密钥加载失败"); }
    finally { setLoading(false); }
  }, [page, pageSize, query]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 260 : 0);
    return () => window.clearTimeout(timer);
  }, [load, query]);

  const copy = async (secret: string) => { await navigator.clipboard?.writeText(secret); };
  const toggle = async (key: DeviceKey) => {
    try {
      const next = await setDeviceKeyStatus(key, key.status === "active" ? "disabled" : "active");
      setItems(value => value.map(item => item.id === next.id ? next : item));
    } catch (e) { setError(e instanceof Error ? e.message : "状态更新失败"); }
  };
  const create = async () => {
    if (!name.trim() || !applicationCode.trim()) { setError("请填写名称和应用编码"); return; }
    setMutationBusy(true);
    try {
      await createDeviceKey({ name: name.trim(), applicationCode: applicationCode.trim(), expiresAt: expiryFromChoice(validity) });
      setName(""); setApplicationCode(""); setValidity("permanent"); setShowCreate(false); setPage(1); await load();
    } catch (e) { setError(e instanceof Error ? e.message : "设备密钥创建失败"); }
    finally { setMutationBusy(false); }
  };
  const confirmDelete = async () => {
    if (!deleteTarget) return;
    setMutationBusy(true);
    try { await deleteDeviceKey(deleteTarget.id); setDeleteTarget(null); await load(); }
    catch (e) { setError(e instanceof Error ? e.message : "设备密钥删除失败"); }
    finally { setMutationBusy(false); }
  };

  return <div className="device-keys-page">
    <AdminPageHeader eyebrow="SECURITY / DEVICE ACTIVATION" title="设备密钥中心" description="为应用激活提供独立设备密钥，单枚密钥仅绑定一个本地设备。"/>
    <section className="admin-list-panel admin-animate">
      <div className="admin-list-toolbar"><SearchBox value={query} onChange={value => { setPage(1); setQuery(value); }} placeholder="搜索名称、应用或密钥前后缀"/><div><SecondaryButton onClick={() => setShowCreate(true)}><AdminIcon name="plus" size={14}/>新建设备密钥</SecondaryButton><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/>刷新</SecondaryButton></div></div>
      {loading && !items.length ? <LoadingState label="正在读取设备密钥…"/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="暂无设备密钥" description="创建独立设备密钥后，它会显示在这里。"/> : <div className="admin-table-wrap"><table className="admin-table device-keys-table"><thead><tr><th>名称 / 应用</th><th>设备密钥</th><th>绑定状态</th><th>剩余时间</th><th>最后验证</th><th>状态</th><th>操作</th></tr></thead><tbody>{items.map(key => <tr key={key.id}><td><b className="table-primary">{key.name}</b><small>{key.applicationCode}</small></td><td><code className="device-key-secret">{key.secret}</code><button className="device-key-copy" onClick={() => void copy(key.secret)} aria-label="复制设备密钥" title="复制设备密钥"><AdminIcon name="key" size={14}/></button></td><td>{key.bound ? <StatusPill value="ACTIVE"/> : <span>未激活</span>}</td><td>{remaining(key.expiresAt)}</td><td>{key.lastVerifiedAt ? new Date(key.lastVerifiedAt).toLocaleString("zh-CN", { hour12: false }) : "—"}</td><td><StatusPill value={key.status.toUpperCase()}/></td><td><div className="row-actions"><SecondaryButton onClick={() => void toggle(key)}>{key.status === "active" ? "停用" : "激活"}</SecondaryButton><SecondaryButton tone="danger" onClick={() => setDeleteTarget(key)}>删除</SecondaryButton></div></td></tr>)}</tbody></table></div>}
      <Pagination page={page} pageSize={pageSize} total={total} itemLabel="枚密钥" onChange={setPage}/>
    </section>
    <Drawer open={showCreate} title="新建设备密钥" description="为指定应用生成一枚只能绑定一个设备的激活密钥。" onClose={() => !mutationBusy && setShowCreate(false)} footer={<><SecondaryButton onClick={() => setShowCreate(false)} disabled={mutationBusy}>取消</SecondaryButton><SecondaryButton onClick={() => void create()} disabled={mutationBusy}><AdminIcon name="plus" size={14}/>{mutationBusy ? "创建中…" : "创建密钥"}</SecondaryButton></>}>
      <div className="admin-form-grid"><Field label="密钥名称" required><input autoFocus required maxLength={160} placeholder="例如：桌面端正式版" value={name} onChange={e => setName(e.target.value)}/></Field><Field label="应用编码" required><input required maxLength={120} placeholder="例如：desktop-pro" value={applicationCode} onChange={e => setApplicationCode(e.target.value)}/></Field><Field label="有效期" hint="到期后密钥会自动失效，无法继续激活设备。"><select aria-label="有效期" value={validity} onChange={e => setValidity(e.target.value)}>{validityOptions.map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></Field></div>
    </Drawer>
    <ConfirmDialog open={Boolean(deleteTarget)} title={`删除密钥“${deleteTarget?.name ?? ""}”？`} description="删除后会立即禁止该密钥激活，并从列表中移除；操作审计仍会保留。" confirmLabel="确认删除" danger busy={mutationBusy} onCancel={() => !mutationBusy && setDeleteTarget(null)} onConfirm={() => void confirmDelete()}/>
  </div>;
}
