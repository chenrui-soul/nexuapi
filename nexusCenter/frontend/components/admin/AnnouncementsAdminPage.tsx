"use client";

import { useCallback, useEffect, useState } from "react";
import { listAnnouncements, saveAnnouncement, transitionAnnouncement, type Announcement } from "@/lib/platform";
import { adminErrorMessage, formatAdminTime } from "@/lib/admin";
import { AdminPageHeader, ConfirmDialog, EmptyState, ErrorState, Field, LoadingState, Pagination, PrimaryButton, SecondaryButton } from "./AdminUi";

import { PlatformDialog } from "@/components/ui/PlatformDialog";
import { AdminIcon } from "./AdminIcon";

const statusLabels = { draft: "草稿", published: "已发布", withdrawn: "已撤回" };
export function AnnouncementsAdminPage() {
  const [preview, setPreview] = useState<Announcement | null>(null);
  const [items, setItems] = useState<Announcement[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<{ id?: string; title: string; content: string; version?: number } | null>(null);
  const [confirm, setConfirm] = useState<{ item: Announcement; action: "publish" | "withdraw" } | null>(null);
  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { const result = await listAnnouncements(page); setItems(result.items); setTotal(result.total); }
    catch (cause) { setError(adminErrorMessage(cause)); }
    finally { setLoading(false); }
  }, [page]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
  async function save() {
    if (!editing || busy) return;
    setBusy(true); setActionError("");
    try {
      await saveAnnouncement({ title: editing.title.trim(), content: editing.content.trim(), version: editing.version }, editing.id);
      setEditing(null); setNotice("草稿已保存，发布后所有用户可见。"); await load();
    } catch (cause) { setActionError(adminErrorMessage(cause)); }
    finally { setBusy(false); }
  }
  async function transition() {
    if (!confirm || busy) return;
    setBusy(true); setActionError("");
    try {
      await transitionAnnouncement(confirm.item, confirm.action);
      setNotice(confirm.action === "publish" ? "公告已发布，所有用户将收到右下角提醒。" : "公告已撤回，用户端不再显示。");
      setConfirm(null); await load();
    } catch (cause) { setActionError(adminErrorMessage(cause)); setConfirm(null); }
    finally { setBusy(false); }
  }
  return <div className="platform-page platform-ui">
    <AdminPageHeader eyebrow="ANNOUNCEMENTS" title="公告管理" description="发布平台动态，让每位用户及时了解最新消息。" action={<PrimaryButton onClick={() => { setActionError(""); setEditing({ title: "", content: "" }); }}><AdminIcon name="plus" size={16}/>新建公告</PrimaryButton>}/>
    {notice && <p className="p-success" role="status">{notice}</p>}
    {actionError && !editing && <p role="alert" className="platform-error">{actionError}</p>}
    {loading ? <LoadingState/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !items.length ? <EmptyState title="还没有公告" description="新建并保存草稿，确认内容后发布给所有用户。"/> :
      <section className="admin-list-panel"><div className="p-panel-heading"><div><h2>全部公告 <span className="p-count">{total}</span></h2><p>草稿仅管理员可见，发布后将向所有用户弹出提醒。</p></div><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/>刷新</SecondaryButton></div>
      <div className="admin-table-wrap"><table className="admin-table p-announcement-table"><thead><tr><th>公告内容</th><th>状态</th><th>发布时间 / 创建时间</th><th>操作</th></tr></thead><tbody>{items.map(item => <tr key={item.id}>
        <td><button className="p-title-button" onClick={() => setPreview(item)}>{item.title}</button><p className="p-excerpt">{item.content}</p></td>
        <td><span className={`p-status ${item.status}`}>{statusLabels[item.status]}</span></td>
        <td><time>{formatAdminTime(item.published_at || item.created_at)}</time><small>{item.published_at ? "发布给所有用户" : "尚未发布"}</small></td>
        <td><div className="p-row-actions"><button className="p-link" onClick={() => setPreview(item)}>查看</button>{item.status === "draft" && <><button className="p-link" disabled={busy} onClick={() => { setActionError(""); setEditing(item); }}>编辑</button><button className="p-link" disabled={busy} onClick={() => setConfirm({ item, action: "publish" })}>发布</button></>}
        {item.status === "published" && <button className="p-link p-danger" disabled={busy} onClick={() => setConfirm({ item, action: "withdraw" })}>撤回</button>}
        {item.status === "withdrawn" && <button className="p-link" onClick={() => { setActionError(""); setEditing({ title: item.title, content: item.content }); }}>复制草稿</button>}</div></td>
      </tr>)}</tbody></table></div><Pagination page={page} pageSize={10} total={total} onChange={setPage}/></section>}
    {editing && <PlatformDialog wide title={editing.id ? "编辑公告" : "新建公告"} description="先保存草稿，确认内容后再发布。" busy={busy} onClose={() => setEditing(null)} footer={<><span className="p-footer-note">发布后，所有用户将收到右下角提醒</span><SecondaryButton disabled={busy} onClick={() => setEditing(null)}>取消</SecondaryButton><PrimaryButton type="submit" form="announcement-form" disabled={busy || !editing.title.trim() || !editing.content.trim()}>{busy ? "保存中…" : "保存草稿"}</PrimaryButton></>}>
      <div className="p-editor-layout"><form id="announcement-form" className="p-form" onSubmit={event => { event.preventDefault(); void save(); }}>
        <Field label="公告标题" required hint={`${editing.title.length} / 200`}><input placeholder="用一句话概括这条公告" required maxLength={200} value={editing.title} disabled={busy} onChange={event => setEditing({ ...editing, title: event.target.value })}/></Field>
        <Field label="公告正文" required hint={`${editing.content.length} / 10000`}><textarea placeholder="填写平台动态、更新说明或维护安排…" required rows={12} maxLength={10000} value={editing.content} disabled={busy} onChange={event => setEditing({ ...editing, content: event.target.value })}/></Field>
        {actionError && <p role="alert" className="p-error">{actionError}</p>}
      </form><aside className="p-editor-preview"><span className="p-eyebrow">用户看到的公告</span><div className="p-preview-title"><span className="p-symbol"><AdminIcon name="bell" size={18}/></span><div><b>平台公告</b><small>发布给所有用户</small></div></div><h3>{editing.title || "公告标题"}</h3><p className={`announcement-body ${!editing.content ? "p-muted" : ""}`}>{editing.content || "公告内容将在这里实时预览。保留正文中的换行，方便用户阅读。"}</p><div className="p-preview-caption"><AdminIcon name="check" size={14}/>点击、查看或关闭提醒即为已读</div></aside></div>
    </PlatformDialog>}
    {preview && <PlatformDialog title={preview.title} description={`${statusLabels[preview.status]} · ${formatAdminTime(preview.published_at || preview.created_at)}`} onClose={() => setPreview(null)}><div className="announcement-body">{preview.content}</div></PlatformDialog>}
    <ConfirmDialog open={confirm !== null} title={confirm?.action === "publish" ? "发布给所有用户？" : "撤回这条公告？"} description={confirm?.action === "publish" ? `「${confirm.item.title}」发布后，所有用户将收到右下角弹出提醒。` : "撤回后用户端不再展示该公告，历史内容保留在管理后台。"} confirmLabel={confirm?.action === "publish" ? "确认发布" : "确认撤回"} danger={confirm?.action === "withdraw"} busy={busy} onCancel={() => { if (!busy) setConfirm(null); }} onConfirm={() => void transition()}/>
  </div>;
}
