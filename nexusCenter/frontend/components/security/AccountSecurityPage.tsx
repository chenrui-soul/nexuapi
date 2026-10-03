"use client";

import { useEffect, useState } from "react";
import { AuthError, changePassword, getAccountSecurity, revokeOtherSessions, type AccountSecurity, validatePassword } from "@/lib/auth";

function SecurityIcon({ name }: { name: "shield" | "mail" | "clock" | "devices" | "lock" | "check" | "warning" }) {
  const paths = {
    shield: <><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/></>,
    mail: <><path d="M4 6h16v12H4z"/><path d="m4 7 8 6 8-6"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    devices: <><rect x="3" y="4" width="14" height="11" rx="2"/><path d="M8 19h4M10 15v4"/><rect x="17" y="9" width="4" height="10" rx="1"/></>,
    lock: <><rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></>,
    check: <path d="m5 12 4 4L19 6"/>,
    warning: <><path d="M12 3 2.8 20h18.4L12 3Z"/><path d="M12 9v4M12 17h.01"/></>,
  };
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "暂无记录";
}

export function AccountSecurityPage() {
  const [security, setSecurity] = useState<AccountSecurity | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const [confirmRevoke, setConfirmRevoke] = useState(false);

  const load = async () => {
    setLoadError("");
    try { setSecurity(await getAccountSecurity()); }
    catch (error) { setLoadError(error instanceof AuthError ? error.message : "账户安全信息加载失败"); }
    finally { setLoading(false); }
  };

  // 初次挂载需要调用同一个可复用刷新函数；实际状态写入发生在异步请求完成后。
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load(); }, []);
  useEffect(() => {
    if (!confirmRevoke) return;
    const close = (event: KeyboardEvent) => { if (event.key === "Escape" && !busy) setConfirmRevoke(false); };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, [busy, confirmRevoke]);

  const submitPassword = async (event: React.FormEvent) => {
    event.preventDefault(); setFeedback(null);
    const passwordError = validatePassword(newPassword);
    if (!currentPassword) return setFeedback({ kind: "error", text: "请输入当前密码" });
    if (passwordError) return setFeedback({ kind: "error", text: passwordError });
    if (newPassword !== confirmPassword) return setFeedback({ kind: "error", text: "两次输入的新密码不一致" });
    setBusy(true);
    try {
      const revoked = await changePassword(currentPassword, newPassword);
      setCurrentPassword(""); setNewPassword(""); setConfirmPassword("");
      setFeedback({ kind: "success", text: revoked ? `密码已更新，并退出了 ${revoked} 个其他会话` : "密码已更新" });
      await load();
    } catch (error) { setFeedback({ kind: "error", text: error instanceof AuthError ? error.message : "密码修改失败" }); }
    finally { setBusy(false); }
  };

  const revoke = async () => {
    setBusy(true); setFeedback(null);
    try {
      const count = await revokeOtherSessions(); setConfirmRevoke(false);
      setFeedback({ kind: "success", text: count ? `已退出 ${count} 个其他会话` : "当前没有其他活动会话" });
      await load();
    } catch (error) { setFeedback({ kind: "error", text: error instanceof AuthError ? error.message : "退出其他设备失败" }); }
    finally { setBusy(false); }
  };

  if (loading) return <section className="security-state"><span/><h3>正在读取账户安全状态</h3><p>检查密码、邮箱和活动会话信息。</p></section>;
  if (loadError && !security) return <section className="security-state error"><SecurityIcon name="warning"/><h3>安全信息加载失败</h3><p>{loadError}</p><button onClick={() => { setLoading(true); void load(); }}>重新加载</button></section>;

  return <div className="security-page">
    <section className="security-hero"><div className="security-hero-icon"><SecurityIcon name="shield"/></div><div><span>ACCOUNT SECURITY</span><h2>账户保护状态正常</h2><p>管理登录密码与控制台会话。敏感操作均需要当前登录态和 CSRF 校验。</p></div><em><i/>已保护</em></section>
    <section className="security-overview" aria-label="安全概览">
      <article><span><SecurityIcon name="mail"/></span><div><small>登录邮箱</small><b>{security?.email ?? "—"}</b><em className={security?.emailVerified ? "verified" : "pending"}>{security?.emailVerified ? "已验证" : "待验证"}</em></div></article>
      <article><span><SecurityIcon name="clock"/></span><div><small>最近登录</small><b>{formatTime(security?.lastLoginAt ?? null)}</b><em>成功登录时间</em></div></article>
      <article><span><SecurityIcon name="lock"/></span><div><small>密码更新时间</small><b>{formatTime(security?.passwordChangedAt ?? null)}</b><em>Argon2id 安全摘要</em></div></article>
      <article><span><SecurityIcon name="devices"/></span><div><small>活动会话</small><b>{security?.activeSessionCount ?? 0} 个设备</b><em>包含当前设备</em></div></article>
    </section>
    {feedback && <div className={`security-feedback ${feedback.kind}`} role="status"><SecurityIcon name={feedback.kind === "success" ? "check" : "warning"}/>{feedback.text}</div>}
    <div className="security-layout">
      <section className="security-panel"><header><span>修改密码</span><h3>更新登录密码</h3><p>新密码至少 8 位，并同时包含字母和数字。</p></header><form onSubmit={submitPassword}><label><span>当前密码</span><input type="password" value={currentPassword} onChange={event => setCurrentPassword(event.target.value)} autoComplete="current-password" maxLength={128} placeholder="输入当前登录密码"/></label><div className="security-password-grid"><label><span>新密码</span><input type="password" value={newPassword} onChange={event => setNewPassword(event.target.value)} autoComplete="new-password" maxLength={128} placeholder="设置新的登录密码"/></label><label><span>确认新密码</span><input type="password" value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} autoComplete="new-password" maxLength={128} placeholder="再次输入新密码"/></label></div><div className="security-form-actions"><small>修改后将保留当前设备，并自动退出其他设备。</small><button disabled={busy} type="submit">{busy ? "处理中…" : "更新密码"}</button></div></form></section>
      <section className="security-panel session-panel"><header><span>会话管理</span><h3>登录设备</h3><p>发现异常登录时，可以让除当前浏览器外的所有设备立即退出。</p></header><div className="current-session"><span><SecurityIcon name="devices"/></span><div><b>当前浏览器</b><small>当前会话会继续保持登录</small></div><em><i/>在线</em></div><button className="revoke-button" onClick={() => setConfirmRevoke(true)} disabled={busy || (security?.activeSessionCount ?? 0) <= 1}>退出其他设备</button><small className="session-note">系统不会向页面展示 Session ID、IP 地址或完整设备指纹。</small></section>
    </div>
    {confirmRevoke && <div className="security-modal-backdrop" onMouseDown={event => event.target === event.currentTarget && setConfirmRevoke(false)}><section className="security-modal" role="alertdialog" aria-modal="true" aria-labelledby="revoke-title"><span><SecurityIcon name="warning"/></span><h3 id="revoke-title">确认退出其他设备？</h3><p>其他浏览器和设备上的控制台会话会立即失效，当前设备不受影响。</p><div><button onClick={() => setConfirmRevoke(false)} disabled={busy}>取消</button><button className="danger" onClick={() => void revoke()} disabled={busy}>{busy ? "处理中…" : "确认退出"}</button></div></section></div>}
  </div>;
}
