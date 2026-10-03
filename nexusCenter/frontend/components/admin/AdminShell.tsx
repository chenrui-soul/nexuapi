"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import gsap from "gsap";
import { useGSAP } from "@gsap/react";
import { ProtectedRoute } from "@/components/auth/ProtectedRoute";
import { useAuth } from "@/components/auth/AuthProvider";
import { hasAdminRole } from "@/lib/auth";
import { AdminIcon, type AdminIconName } from "./AdminIcon";
import { BrandMark } from "@/components/brand/BrandMark";
import { AnnouncementBell } from "@/components/notifications/AnnouncementBell";

if (typeof window !== "undefined") gsap.registerPlugin(useGSAP);

const navigation: { section: string; items: { href: string; label: string; icon: AdminIconName; enabled: boolean }[] }[] = [
  { section: "运营中心", items: [
    { href: "/admin/announcements", label: "公告管理", icon: "bell", enabled: true },
    { href: "/admin", label: "运营总览", icon: "overview", enabled: true },
    { href: "/admin/analytics", label: "经营分析", icon: "analytics", enabled: true },
    { href: "/admin/suppliers", label: "供应商管理", icon: "supplier", enabled: true },
    { href: "/admin/adapters", label: "协议适配器", icon: "settings", enabled: true },
    { href: "/admin/models", label: "模型维护", icon: "model", enabled: true },
    { href: "/admin/routing", label: "服务分组", icon: "route", enabled: true },
    { href: "/admin/health", label: "健康与告警", icon: "pulse", enabled: true },
    { href: "/admin/protocols", label: "接口文档", icon: "settings", enabled: true },
  ]},
    { section: "业务治理", items: [
    { href: "/admin/settings", label: "系统设置", icon: "settings", enabled: true },
    { href: "/admin/device-keys", label: "设备密钥中心", icon: "shield", enabled: true },
    { href: "/admin/billing", label: "计费设置", icon: "wallet", enabled: true },
    { href: "/admin/subscription-plans", label: "订阅套餐", icon: "server", enabled: true },
    { href: "/admin/users", label: "用户与权限", icon: "users", enabled: true },
    { href: "/admin/logs", label: "审计日志", icon: "logs", enabled: true },
    { href: "/admin/request-logs", label: "调用日志", icon: "pulse", enabled: true },
  ]},
];

function isActive(pathname: string, href: string): boolean {
  return href === "/admin" ? pathname === href : pathname.startsWith(href);
}

/** 未持有管理员角色时显示 403，防止普通用户误入管理界面。 */
function AdminAccessGuard({ children }: { children: React.ReactNode }) {
  const { session } = useAuth();
  if (!hasAdminRole(session?.user.roles ?? [])) {
    return (
      <main className="admin-forbidden">
        <span><AdminIcon name="shield" size={26}/></span>
        <p>403 · ACCESS CONTROL</p>
        <h1>当前账号没有管理员权限</h1>
        <small>前端权限检查只用于界面隔离，服务端仍会对每个管理接口执行 ROLE_ADMIN 校验。</small>
        <Link href="/">返回用户控制台 <AdminIcon name="arrow" size={15}/></Link>
      </main>
    );
  }
  return children;
}

/**
 * 管理端独立框架：负责侧栏、顶部导航、响应式菜单和页面入场动效。
 * 它只做前端体验层隔离，真正的权限裁决仍由后端 ROLE_ADMIN 完成。
 */
function AdminFrame({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const rootRef = useRef<HTMLDivElement>(null);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [theme, setTheme] = useState<"light" | "dark">("light");
  const { session, logout } = useAuth();

  useEffect(() => {
    queueMicrotask(() => setTheme(document.documentElement.dataset.theme === "dark" ? "dark" : "light"));
  }, []);

  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    setTheme(next);
    document.documentElement.dataset.theme = next;
    document.documentElement.style.colorScheme = next;
    window.localStorage.setItem("nexus-theme", next);
  };

  useGSAP(() => {
    const motion = gsap.matchMedia();
    motion.add("(prefers-reduced-motion: no-preference)", () => {
      // 详情接口加载期间页面可能只有 LoadingState；先确认目标存在，避免 GSAP 输出无意义告警。
      const animatedElements = gsap.utils.toArray<HTMLElement>(".admin-animate");
      if (!animatedElements.length) return;
      gsap.fromTo(animatedElements, { autoAlpha: 0, y: 14 }, { autoAlpha: 1, y: 0, duration: 0.42, stagger: 0.045, ease: "power3.out", clearProps: "transform,opacity,visibility" });
    });
    return () => motion.revert();
  }, { dependencies: [pathname], scope: rootRef, revertOnUpdate: true });

  const closeNavigation = () => setMobileOpen(false);
  const avatar = session?.user.name.trim().charAt(0).toUpperCase() || "A";

  return (
        <div className="admin-app" ref={rootRef}>
          {mobileOpen && <button className="admin-mobile-scrim" aria-label="关闭导航" onClick={closeNavigation}/>} 
          <aside className={`admin-sidebar ${mobileOpen ? "open" : ""}`}>
            <div className="admin-brand"><span className="brand-mark"><BrandMark /></span><div><b>NEXUS <em>API</em></b><small>管理控制台</small></div><button aria-label="关闭导航" onClick={closeNavigation}><AdminIcon name="close"/></button></div>
            <nav aria-label="管理员导航">
              {navigation.map(group => <section key={group.section}><h2>{group.section}</h2>{group.items.map(item => item.enabled ? (
                <Link href={item.href} className={isActive(pathname, item.href) ? "active" : ""} onClick={closeNavigation} key={item.href}><AdminIcon name={item.icon}/><span>{item.label}</span>{isActive(pathname, item.href) && <i/>}</Link>
              ) : (
                <span className="disabled" title="后续 Wave 开放" key={item.href}><AdminIcon name={item.icon}/><span>{item.label}</span><em>SOON</em></span>
              ))}</section>)}
            </nav>
            <div className="admin-sidebar-foot">
              <div className="admin-environment"><i/><span><b>Production</b><small>服务运行正常</small></span><AdminIcon name="chevron" size={14}/></div>
              <div className="admin-account"><span>{avatar}</span><div><b>{session?.user.name}</b><small>{session?.user.email}</small></div><div className="admin-account-actions"><button aria-label="退出登录" title="退出登录" onClick={() => void logout()}><AdminIcon name="logout" size={17}/></button></div></div>
            </div>
          </aside>
          <main className="admin-main">
            <header className="admin-topbar">
              <button className="admin-mobile-menu" aria-label="打开导航" onClick={() => setMobileOpen(true)}><AdminIcon name="menu"/></button>
              <div className="admin-breadcrumb"><span>管理控制台</span><AdminIcon name="chevron" size={13}/><b>{navigation.flatMap(group => group.items).find(item => isActive(pathname, item.href) && item.enabled)?.label ?? "运营总览"}</b></div>
              <div className="admin-top-actions">
                <Link href="/" className="admin-console-link" aria-label="进入用户控制台" title="进入用户控制台"><AdminIcon name="external" size={16}/></Link>
                <button className="admin-theme-toggle" onClick={toggleTheme} aria-label={`切换为${theme === "dark" ? "亮色" : "暗色"}模式`} title={`切换为${theme === "dark" ? "亮色" : "暗色"}模式`}><AdminIcon name={theme === "dark" ? "moon" : "sun"} size={16}/></button>
                <AnnouncementBell/>
                <span className="admin-role"><AdminIcon name="shield" size={14}/> ADMIN</span>
              </div>
            </header>
            <div className="admin-content">{children}</div>
          </main>
        </div>
  );
}

export function AdminShell({ children }: { children: React.ReactNode }) {
  return (
    <ProtectedRoute>
      <AdminAccessGuard>
        <AdminFrame>{children}</AdminFrame>
      </AdminAccessGuard>
    </ProtectedRoute>
  );
}
