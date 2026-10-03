"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import {
  adminErrorMessage, formatAdminTime, listAdminUsers, saveAdminUser,
  type AdminUser, type AdminUserInput,
} from "@/lib/admin";
import { getPlatformSettings } from "@/lib/platform";
import { RechargeDialog } from "./RechargeDialog";
import { PlatformDialog } from "@/components/ui/PlatformDialog";
import { AdminIcon } from "./AdminIcon";
import {
  AdminPageHeader, EmptyState, ErrorState, Field, IconButton, LoadingState,
  Pagination, PrimaryButton, SearchBox, SecondaryButton, SelectFilter, StatusPill, Toast,
} from "./AdminUi";

const roleLabels = { user: "普通用户", operator: "运营人员", admin: "管理员" } as const;

function userInput(item: AdminUser): AdminUserInput {
  return { display_name: item.display_name, status: item.status, roles: [...item.roles], version: item.version };
}

/** 用户权限页只展示脱敏账户信息，并通过 version 和管理员自保护维护角色与状态。 */
export function UsersAdminPage() {
  const { session } = useAuth();
  const [rechargeEnabled, setRechargeEnabled] = useState(false);
  const [recharging, setRecharging] = useState<{ user: AdminUser | null } | null>(null);
  useEffect(() => {
    let active = true;
    const refresh = () => { void getPlatformSettings().then(settings => { if (active) setRechargeEnabled(Boolean(settings.manual_recharge_enabled)); }).catch(() => { if (active) setRechargeEnabled(false); }); };
    refresh(); const timer = setInterval(refresh, 30000); window.addEventListener("focus", refresh);
    return () => { active = false; clearInterval(timer); window.removeEventListener("focus", refresh); };
  }, []);
  const [items, setItems] = useState<AdminUser[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [role, setRole] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<AdminUser | null>(null);
  const [form, setForm] = useState<AdminUserInput | null>(null);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState({ message: "", tone: "success" as "success" | "error" });

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const result = await listAdminUsers({ page, pageSize: 20, query, status, role });
      setItems(result.items); setTotal(result.total);
    } catch (loadError) { setError(adminErrorMessage(loadError)); }
    finally { setLoading(false); }
  }, [page, query, role, status]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 260 : 0);
    return () => window.clearTimeout(timer);
  }, [load, query]);

  const summary = useMemo(() => ({
    active: items.filter(item => item.status === "active").length,
    admins: items.filter(item => item.roles.includes("admin")).length,
    locked: items.filter(item => item.status === "locked" || item.status === "suspended").length,
  }), [items]);

  const openEdit = (item: AdminUser) => { setEditing(item); setForm(userInput(item)); };
  const closeDrawer = () => { if (!saving) { setEditing(null); setForm(null); } };
  const isSelf = editing?.id === session?.user.id;

  const toggleRole = (roleCode: AdminUser["roles"][number], checked: boolean) => {
    if (!form || roleCode === "user" || (isSelf && roleCode === "admin")) return;
    const roles = checked
      ? [...new Set([...form.roles, roleCode])]
      : form.roles.filter(item => item !== roleCode);
    setForm({ ...form, roles });
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!editing || !form) return;
    setSaving(true);
    try {
      await saveAdminUser({ ...form, display_name: form.display_name.trim() }, editing.id);
      setToast({ message: "用户资料与权限已更新", tone: "success" });
      setEditing(null); setForm(null); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  return <div className="platform-page platform-ui">
    <AdminPageHeader eyebrow="IDENTITY / ACCESS" title="用户与权限" description="管理用户账户、访问权限与积分。" action={rechargeEnabled ? <PrimaryButton onClick={() => setRecharging({ user: null })}><AdminIcon name="wallet" size={16}/>充值</PrimaryButton> : undefined}/>
    <section className="p-user-summary"><div><span>用户总数</span><b>{total}</b></div><div><span>本页活跃</span><b className="good">{summary.active}</b></div><div><span>本页管理员</span><b>{summary.admins}</b></div><div><span>受限账号</span><b className={summary.locked ? "bad" : ""}>{summary.locked}</b></div></section>
    <section className="admin-list-panel admin-animate">
      <div className="admin-list-toolbar"><SearchBox value={query} onChange={value => { setPage(1); setQuery(value); }} placeholder="搜索名称、用户 ID 或完整邮箱"/><div><SelectFilter label="状态筛选" value={status} onChange={value => { setPage(1); setStatus(value); }}><option value="all">全部状态</option><option value="active">活跃</option><option value="pending">待验证</option><option value="suspended">已暂停</option><option value="locked">已锁定</option></SelectFilter><SelectFilter label="角色筛选" value={role} onChange={value => { setPage(1); setRole(value); }}><option value="all">全部角色</option><option value="user">普通用户</option><option value="operator">运营人员</option><option value="admin">管理员</option></SelectFilter><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/>刷新</SecondaryButton></div></div>
      {loading && !items.length ? <LoadingState/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="没有匹配的用户" description="试试其他关键词，或调整角色和状态筛选。"/> : <div className="admin-table-wrap"><table className="admin-table admin-user-table"><thead><tr><th>用户</th><th>角色</th><th>邮箱状态</th><th>账号状态</th><th>最近活动</th><th aria-label="操作"/></tr></thead><tbody>{items.map(item => <tr key={item.id}><td><div className="p-user-identity"><span className="p-avatar">{item.display_name.charAt(0).toUpperCase()}</span><div><b>{item.display_name}{item.id === session?.user.id && <em className="self-badge">当前账号</em>}</b><code>{item.masked_email}</code><small className="p-user-id" title={item.id}>ID · {item.id.slice(0, 8)}</small></div></div></td><td><div className="role-stack">{item.roles.map(roleCode => <span className={`admin-role-chip ${roleCode}`} key={roleCode}>{roleLabels[roleCode]}</span>)}</div></td><td><b className="table-primary">{item.email_verified ? "已验证" : "未验证"}</b></td><td><StatusPill value={item.status}/></td><td><b className="table-primary">{formatAdminTime(item.last_login_at)}</b><small>注册 {formatAdminTime(item.created_at)}</small></td><td><div className="row-actions">{rechargeEnabled && <IconButton icon="wallet" label={`为 ${item.display_name} 充值`} onClick={() => setRecharging({ user: item })}/>}<IconButton icon="edit" label="编辑用户和权限" onClick={() => openEdit(item)}/></div></td></tr>)}</tbody></table></div>}
      <Pagination page={page} pageSize={20} total={total} onChange={setPage}/>
    </section>

    {editing && form && <PlatformDialog title="编辑用户与权限" description="管理账户资料与访问范围。" busy={saving} onClose={closeDrawer} footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="admin-user-form" disabled={saving}>{saving ? "保存中…" : "保存变更"}</PrimaryButton></>}>
      {editing && form && <form id="admin-user-form" className="admin-form-grid" onSubmit={submit} autoComplete="off"><Field wide label="用户标识"><input disabled value={editing.id}/></Field><Field label="脱敏邮箱"><input disabled value={editing.masked_email}/></Field><Field label="展示名称" required><input autoFocus required maxLength={80} value={form.display_name} onChange={event => setForm(current => current ? ({ ...current, display_name: event.target.value }) : current)}/></Field><Field label="账号状态" required hint={isSelf ? "当前管理员必须保持 active" : "状态变更在下一请求即时生效"}><select value={form.status} onChange={event => setForm(current => current ? ({ ...current, status: event.target.value as AdminUser["status"] }) : current)}><option value="active">活跃</option><option value="pending" disabled={isSelf}>待验证</option><option value="suspended" disabled={isSelf}>暂停</option><option value="locked" disabled={isSelf}>锁定</option></select></Field><div className="admin-form-section wide"><span>角色权限</span><p>选择用户可以访问的功能范围。</p></div><div className="admin-role-options wide">{(["user", "operator", "admin"] as const).map(roleCode => <label className={roleCode === "user" || (isSelf && roleCode === "admin") ? "locked" : ""} key={roleCode}><input type="checkbox" checked={form.roles.includes(roleCode)} disabled={roleCode === "user" || (isSelf && roleCode === "admin")} onChange={event => toggleRole(roleCode, event.target.checked)}/><span><b>{roleLabels[roleCode]}</b><small>{roleCode === "user" ? "基础控制台与个人资源" : roleCode === "operator" ? "运营功能预留权限" : "全部管理员页面和接口"}</small></span></label>)}</div>{isSelf && <div className="admin-inline-warning wide"><AdminIcon name="shield" size={17}/><div><b>当前管理员自保护已启用</b><span>你可以修改名称和 operator 角色，但不能停用账号或移除自身 admin 角色。</span></div></div>}</form>}
    </PlatformDialog>}
    {recharging && session && <RechargeDialog initial={recharging.user} actor={session.user.id} onClose={() => setRecharging(null)}/>}
    <Toast message={toast.message} tone={toast.tone} onClose={() => setToast(current => ({ ...current, message: "" }))}/>
  </div>;
}
