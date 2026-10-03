"use client";

import { useEffect, type ReactNode } from "react";
import { AdminIcon, type AdminIconName } from "./AdminIcon";
export { Pagination } from "@/components/ui/Pagination";

export function AdminPageHeader({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) {
  return (
    <header className="admin-page-header admin-animate">
      <div>
        <span>{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action && <div className="admin-page-actions">{action}</div>}
    </header>
  );
}

export function PrimaryButton({ children, onClick, type = "button", form, disabled = false }: { children: ReactNode; onClick?: () => void; type?: "button" | "submit"; form?: string; disabled?: boolean }) {
  return <button type={type} form={form} className="admin-button primary" onClick={onClick} disabled={disabled}>{children}</button>;
}

type AdminActionTone = "neutral" | "info" | "positive" | "warning" | "danger";

export function SecondaryButton({ children, onClick, disabled = false, tone = "neutral", type = "button", form }: { children: ReactNode; onClick?: () => void; disabled?: boolean; tone?: AdminActionTone; type?: "button" | "submit"; form?: string }) {
  return <button type={type} form={form} className={`admin-button secondary tone-${tone}`} onClick={onClick} disabled={disabled}>{children}</button>;
}

export function IconButton({ icon, label, onClick, danger = false, tone }: { icon: AdminIconName; label: string; onClick: () => void; danger?: boolean; tone?: AdminActionTone }) {
  const resolvedTone = danger ? "danger" : tone ?? (icon === "edit" || icon === "logs" ? "info" : icon === "wallet" ? "warning" : "neutral");
  return <button type="button" className={`admin-icon-button tone-${resolvedTone} icon-${icon}`} aria-label={label} title={label} onClick={onClick}><AdminIcon name={icon} size={16}/></button>;
}

export function StatusActionButton({ active, activeLabel, inactiveLabel, onClick, disabled = false, busy = false }: { active: boolean; activeLabel: string; inactiveLabel: string; onClick: () => void; disabled?: boolean; busy?: boolean }) {
  const label = active ? activeLabel : inactiveLabel;
  return <button type="button" className={`admin-icon-button status-action ${active ? "tone-danger is-stop" : "tone-positive is-start"} ${busy ? "is-busy" : ""}`} aria-label={busy ? `${label}处理中` : label} aria-busy={busy} title={busy ? "状态切换处理中" : label} disabled={disabled || busy} onClick={onClick}><AdminIcon name={busy ? "refresh" : active ? "pause" : "play"} size={15}/></button>;
}

export function StatusPill({ value }: { value: string }) {
  const normalized = value.toUpperCase();
  const tone = ["ACTIVE", "HEALTHY", "NORMAL", "AVAILABLE", "ENABLED", "ADMIN", "USER"].includes(normalized)
    ? "positive"
    : ["UNHEALTHY", "UNAVAILABLE", "TERMINATED", "LOCKED", "OPEN"].includes(normalized)
      ? "negative"
      : ["DEGRADED", "UNCONFIGURED", "WARNING", "PARTIAL", "UNKNOWN", "PENDING", "SUSPENDED"].includes(normalized)
        ? "warning"
        : "neutral";
  const labels: Record<string, string> = {
    ACTIVE: "启用", ENABLED: "启用", DISABLED: "停用", HEALTHY: "健康", DEGRADED: "波动",
    UNHEALTHY: "异常", UNKNOWN: "未探测", NORMAL: "正常", WARNING: "警告",
    MAINTENANCE: "维护中", SUSPENDED: "已暂停", TERMINATED: "已终止",
    DRAFT: "草稿", ARCHIVED: "已归档",
    PENDING: "待验证", LOCKED: "已锁定", USER: "用户", ADMIN: "管理员", SYSTEM: "系统",
    UNAVAILABLE: "不可用", UNCONFIGURED: "未配置", OPEN: "告警中", RESOLVED: "已恢复",
  };
  return <span className={`admin-status ${tone}`}><i/>{labels[normalized] ?? value}</span>;
}

export function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (value: string) => void; placeholder: string }) {
  return <label className="admin-search"><AdminIcon name="search" size={16}/><input value={value} onChange={event => onChange(event.target.value)} placeholder={placeholder}/></label>;
}

export function SelectFilter({ value, onChange, children, label }: { value: string; onChange: (value: string) => void; children: ReactNode; label: string }) {
  return <label className="admin-select"><span className="sr-only">{label}</span><select value={value} onChange={event => onChange(event.target.value)}>{children}</select></label>;
}

export function LoadingState({ label = "正在加载管理数据…" }: { label?: string }) {
  return <div className="admin-state loading-state"><span className="admin-spinner"/><p>{label}</p></div>;
}

export function EmptyState({ title, description }: { title: string; description: string }) {
  return <div className="admin-state empty"><span><AdminIcon name="server" size={24}/></span><h3>{title}</h3><p>{description}</p></div>;
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return <div className="admin-state error"><span><AdminIcon name="warning" size={22}/></span><h3>数据加载失败</h3><p>{message}</p><SecondaryButton onClick={onRetry}>重新加载</SecondaryButton></div>;
}

export function Drawer({ open, title, description, onClose, children, footer, className = "" }: { open: boolean; title: string; description: string; onClose: () => void; children: ReactNode; footer: ReactNode; className?: string }) {
  useEffect(() => {
    if (!open) return;
    const close = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    document.addEventListener("keydown", close);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", close);
      document.body.style.overflow = "";
    };
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="admin-drawer-backdrop" onMouseDown={event => event.target === event.currentTarget && onClose()}>
      <aside className={`admin-drawer ${className}`.trim()} role="dialog" aria-modal="true" aria-label={title}>
        <header><div><span>CONFIGURATION</span><h2>{title}</h2><p>{description}</p></div><IconButton icon="close" label="关闭抽屉" onClick={onClose}/></header>
        <div className="admin-drawer-body">{children}</div>
        <footer>{footer}</footer>
      </aside>
    </div>
  );
}

export function ConfirmDialog({ open, title, description, confirmLabel, danger = false, busy = false, onCancel, onConfirm }: { open: boolean; title: string; description: string; confirmLabel: string; danger?: boolean; busy?: boolean; onCancel: () => void; onConfirm: () => void }) {
  useEffect(() => {
    if (!open) return;
    const close = (event: KeyboardEvent) => event.key === "Escape" && onCancel();
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [open, onCancel]);
  if (!open) return null;
  return (
    <div className="admin-dialog-backdrop" onMouseDown={event => event.target === event.currentTarget && onCancel()}>
      <section className="admin-confirm" role="alertdialog" aria-modal="true" aria-label={title}>
        <span className={danger ? "danger" : "safe"}><AdminIcon name={danger ? "warning" : "shield"} size={22}/></span>
        <h2>{title}</h2><p>{description}</p>
        <div><SecondaryButton onClick={onCancel} disabled={busy}>取消</SecondaryButton><button className={`admin-button ${danger ? "danger" : "primary"}`} disabled={busy} onClick={onConfirm}>{busy ? "处理中…" : confirmLabel}</button></div>
      </section>
    </div>
  );
}

export function Toast({ message, tone = "success", onClose }: { message: string; tone?: "success" | "error"; onClose: () => void }) {
  useEffect(() => {
    if (!message) return;
    const timer = window.setTimeout(onClose, 2600);
    return () => window.clearTimeout(timer);
  }, [message, onClose]);
  if (!message) return null;
  return <div className={`admin-toast ${tone}`} role="status"><AdminIcon name={tone === "success" ? "check" : "warning"} size={16}/>{message}</div>;
}

export function Field({ label, hint, required = false, children, wide = false }: { label: string; hint?: string; required?: boolean; children: ReactNode; wide?: boolean }) {
  return <label className={`admin-field ${wide ? "wide" : ""}`}><span>{label}{required && <em>*</em>}</span>{children}{hint && <small>{hint}</small>}</label>;
}

export function ToggleField({ label, description, checked, onChange }: { label: string; description: string; checked: boolean; onChange: (checked: boolean) => void }) {
  return <label className="admin-toggle-field"><div><b>{label}</b><span>{description}</span></div><input type="checkbox" checked={checked} onChange={event => onChange(event.target.checked)}/><i/></label>;
}
