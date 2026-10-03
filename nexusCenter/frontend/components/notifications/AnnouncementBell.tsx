"use client";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { getAnnouncementFeed, readAnnouncement, type Announcement, type AnnouncementFeed } from "@/lib/platform";
import { acknowledgeAnnouncement } from "@/lib/announcement-state";
import { useAuth } from "@/components/auth/AuthProvider";
import { AdminIcon } from "@/components/admin/AdminIcon";
import { PlatformDialog } from "@/components/ui/PlatformDialog";
const date = (value?: string) => value ? new Date(value).toLocaleString("zh-CN", { month:"2-digit", day:"2-digit", hour:"2-digit", minute:"2-digit" }) : "";
function UserAnnouncementBell() {
  const [open, setOpen] = useState(false);
  const [feed, setFeed] = useState<AnnouncementFeed | null>(null);
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState("");
  const [detail, setDetail] = useState<Announcement | null>(null);
  const [hidden, setHidden] = useState<string[]>([]);
  const [visible, setVisible] = useState(true);
  const pending = useRef(new Set<string>());
  const acknowledged = useRef(new Set<string>());
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    let active = true, loading = false;
    async function load() {
      if (loading || document.visibilityState === "hidden") return;
      loading = true;
      try {
        let result = await getAnnouncementFeed(page);
        for (const id of acknowledged.current) result = acknowledgeAnnouncement(result, id)!;
        if (active) { setFeed(result); setError(""); }
      } catch { if (active) setError("公告加载失败，请重试。"); }
      finally { loading = false; }
    }
    void load();
    const timer = window.setInterval(() => void load(), 30000);
    const visibility = () => { setVisible(document.visibilityState !== "hidden"); if (document.visibilityState === "visible") void load(); };
    document.addEventListener("visibilitychange", visibility);
    return () => { active = false; window.clearInterval(timer); document.removeEventListener("visibilitychange", visibility); };
  }, [page, revision, open]);
  useEffect(() => {
    if (!open) return;
    const close = (event: KeyboardEvent) => { if (event.key === "Escape") { setOpen(false); trigger.current?.focus(); } };
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener("keydown", close); document.addEventListener("pointerdown", outside);
    return () => { document.removeEventListener("keydown", close); document.removeEventListener("pointerdown", outside); };
  }, [open]);
  async function markRead(item: Announcement, dismiss = false) {
    if (dismiss) setHidden(ids => [...new Set([...ids, item.id])]);
    if (item.read_at || acknowledged.current.has(item.id) || pending.current.has(item.id)) return;
    pending.current.add(item.id);
    try {
      await readAnnouncement(item.id);
      acknowledged.current.add(item.id);
      setFeed(current => acknowledgeAnnouncement(current, item.id));
      setRevision(value => value + 1);
    } catch { setError("已读状态保存失败，请重试。"); setHidden(ids => ids.filter(id => id !== item.id)); }
    finally { pending.current.delete(item.id); }
  }
  const unread = feed?.unread_count ?? 0;
  const popup = feed?.next_unread;
  const show = (item: Announcement) => { setDetail(item); setOpen(false); void markRead(item, true); };
  return <>
    <div className="announcement-bell platform-ui" ref={root}>
      <button ref={trigger} type="button" className="icon-button" aria-label={`平台公告${unread ? `，${unread} 条未读` : ""}`} aria-expanded={open} onClick={() => setOpen(value => !value)}><AdminIcon name="bell"/>{unread > 0 && <span className="announcement-badge">{unread > 99 ? "99+" : unread}</span>}</button>
      {open && <section className="announcement-inbox" aria-label="平台公告">
        <header><div><h2>消息公告 <span className="p-count">{unread} 未读</span></h2><p>平台动态与服务通知</p></div><button className="p-icon" onClick={() => setOpen(false)} aria-label="关闭公告"><AdminIcon name="close" size={16}/></button></header>
        {error && <p className="p-error" role="alert">{error} <button className="p-link" onClick={() => setRevision(n => n + 1)}>重试</button></p>}
        {!feed && !error && <p className="p-empty" role="status">正在加载公告…</p>}
        {feed && !feed.announcements.total && <div className="p-empty"><AdminIcon name="bell" size={28}/><b>暂无新公告</b><span>平台的最新消息会出现在这里</span></div>}
        <div className="announcement-inbox-items">{feed?.announcements.items.map(item => <button key={item.id} className={`p-notification-row ${item.read_at ? "" : "is-unread"}`} onClick={() => show(item)}>
          <span className="p-notification-dot"/><span className="p-notification-copy"><span className="p-row-heading"><b>{item.title}</b><time>{date(item.published_at)}</time></span><span className="p-excerpt">{item.content}</span><span className="p-link">查看详情 <AdminIcon name="arrow" size={13}/></span></span>
        </button>)}</div>
        {feed && feed.announcements.total > 0 && <footer><button className="p-icon" aria-label="上一页公告" disabled={page <= 1} onClick={() => setPage(n => n - 1)}>‹</button><span>{page} / {Math.max(1, Math.ceil(feed.announcements.total / 10))} · {feed.announcements.total} 条公告</span><button className="p-icon" aria-label="下一页公告" disabled={page * 10 >= feed.announcements.total} onClick={() => setPage(n => n + 1)}>›</button></footer>}
      </section>}
    </div>
    {popup && visible && !hidden.includes(popup.id) && !detail && createPortal(<aside className="platform-ui announcement-popup" aria-label="新公告" onClick={() => void markRead(popup, true)} onContextMenu={() => void markRead(popup, true)} onWheel={() => void markRead(popup)} onTouchMove={() => void markRead(popup)} onKeyDown={event => { if (["Enter", " ", "Escape"].includes(event.key)) { if (event.key === "Escape") event.preventDefault(); void markRead(popup, true); } }}>
      <div className="p-popup-heading"><span className="p-symbol"><AdminIcon name="bell" size={18}/></span><span>平台公告 <small>最新消息</small></span><button className="p-icon" aria-label="关闭并标记已读" onClick={() => void markRead(popup, true)}><AdminIcon name="close" size={16}/></button></div>
      <div role="status"><h3>{popup.title}</h3><p className="p-popup-content">{popup.content}</p></div>
      {error && <p className="p-error" role="alert">{error}</p>}
      <footer><time>{date(popup.published_at)}</time><button className="p-link" onClick={() => show(popup)}>查看公告 <AdminIcon name="arrow" size={14}/></button></footer>
    </aside>, document.body)}
    {detail && <PlatformDialog title={detail.title} description={`平台公告 · ${date(detail.published_at)}`} onClose={() => setDetail(null)}><div className="announcement-body">{detail.content}</div>{error && <p className="p-error" role="alert">{error}<button className="p-link" onClick={() => void markRead(detail)}>重试</button></p>}</PlatformDialog>}
  </>;
}
export function AnnouncementBell() {
  const { session } = useAuth();
  return session ? <UserAnnouncementBell key={session.user.id}/> : null;
}
