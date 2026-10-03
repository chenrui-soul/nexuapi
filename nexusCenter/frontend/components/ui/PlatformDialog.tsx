"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { AdminIcon } from "@/components/admin/AdminIcon";

/** Shared, focus-contained dialog; portaled so animated page containers cannot clip it. */
export function PlatformDialog({ title, description, children, footer, onClose, busy = false, wide = false }: {
  title: string; description?: string; children: ReactNode; footer?: ReactNode; onClose: () => void; busy?: boolean; wide?: boolean;
}) {
  const panel = useRef<HTMLElement>(null);
  const close = useRef(onClose);
  const locked = useRef(busy);
  useEffect(() => { close.current = onClose; locked.current = busy; }, [onClose, busy]);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    panel.current?.focus();
    const keys = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopImmediatePropagation(); if (!locked.current) close.current(); }
      if (event.key !== "Tab") return;
      const nodes = [...(panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]') ?? [])].filter(node => node.getClientRects().length > 0);
      if (!nodes.length) { event.preventDefault(); return; }
      const first = nodes[0], last = nodes[nodes.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.current)) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", keys, true);
    return () => { document.removeEventListener("keydown", keys, true); document.body.style.overflow = overflow; if (previous?.isConnected) previous.focus(); };
  }, []);
  return createPortal(<div className="platform-ui platform-overlay" onMouseDown={event => { if (event.target === event.currentTarget && !busy) onClose(); }}>
    <section ref={panel} tabIndex={-1} className={`platform-dialog${wide ? " is-wide" : ""}`} role="dialog" aria-modal="true" aria-label={title}>
      <header className="platform-dialog-header"><div><h2>{title}</h2>{description && <p>{description}</p>}</div><button className="p-icon" type="button" aria-label="关闭窗口" disabled={busy} onClick={onClose}><AdminIcon name="close" size={18}/></button></header>
      <div className="platform-dialog-body">{children}</div>
      {footer && <footer className="platform-dialog-footer">{footer}</footer>}
    </section>
  </div>, document.body);
}
