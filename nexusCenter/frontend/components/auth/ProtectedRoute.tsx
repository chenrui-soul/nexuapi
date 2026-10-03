"use client";

import { useEffect } from "react";
import { useAuth } from "./AuthProvider";
import { BrandMark } from "@/components/brand/BrandMark";

export function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { ready, session } = useAuth();

  useEffect(() => {
    if (!ready || session) return;
    const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    window.location.replace(`/login?return_to=${encodeURIComponent(returnTo)}`);
  }, [ready, session]);

  if (!ready || !session) {
    return (
      <main className="auth-loading" aria-live="polite">
        <span className="auth-loading-mark"><BrandMark /></span>
        <p>{ready ? "正在前往登录页面…" : "正在检查登录状态…"}</p>
      </main>
    );
  }

  return children;
}
