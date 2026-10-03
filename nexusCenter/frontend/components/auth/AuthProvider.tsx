"use client";

import { createContext, Fragment, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { invalidatePageReads, setPageReadScope } from "@/lib/page-read-cache";
import {
  type AuthSession,
  type LoginInput,
  type RegisterInput,
  getCurrentSession,
  login as loginRequest,
  logout as logoutRequest,
  register as registerRequest,
} from "@/lib/auth";

type AuthContextValue = {
  ready: boolean;
  session: AuthSession | null;
  login: (input: LoginInput) => Promise<AuthSession>;
  register: (input: RegisterInput) => Promise<AuthSession>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false);
  const [session, setSession] = useState<AuthSession | null>(null);
  const authVersion = useRef(0);
  const authChanging = useRef(false);

  const acceptSession = useCallback((next: AuthSession | null) => {
    setPageReadScope(next ? JSON.stringify([next.user.id, next.user.roles]) : null);
    setSession(next);
  }, []);

  useEffect(() => {
    let active = true;
    const sync = async () => {
      if (authChanging.current) return;
      const version = ++authVersion.current;
      try {
        const next = await getCurrentSession();
        if (active && version === authVersion.current) acceptSession(next);
      } catch {
        if (active && version === authVersion.current) acceptSession(null);
      } finally {
        if (active) setReady(true);
      }
    };
    void sync();
    window.addEventListener("focus", sync);
    return () => {
      active = false;
      authVersion.current++;
      setPageReadScope(null);
      window.removeEventListener("focus", sync);
    };
  }, [acceptSession]);

  const value = useMemo<AuthContextValue>(() => ({
    ready,
    session,
    login: async input => {
      authChanging.current = true; authVersion.current++; invalidatePageReads();
      try {
        const next = await loginRequest(input);
        acceptSession(next);
        return next;
      } finally { authChanging.current = false; }
    },
    register: async input => {
      authChanging.current = true; authVersion.current++; invalidatePageReads();
      try {
        const next = await registerRequest(input);
        acceptSession(next);
        return next;
      } finally { authChanging.current = false; }
    },
    logout: async () => {
      authChanging.current = true; authVersion.current++; setPageReadScope(null);
      try {
        await logoutRequest();
      } finally {
        acceptSession(null);
        authChanging.current = false;
      }
    },
  }), [ready, session, acceptSession]);

  return <AuthContext.Provider value={value}><Fragment key={session?.user.id ?? "anonymous"}>{children}</Fragment></AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used within AuthProvider");
  return value;
}
