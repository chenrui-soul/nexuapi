"use client";

import { type PointerEvent, useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "./AuthProvider";
import { BrandMark } from "@/components/brand/BrandMark";
import { getRegistration } from "@/lib/platform";
import {
  AuthError,
  type CaptchaChallenge,
  type CaptchaScene,
  fetchCaptcha,
  requestPasswordReset,
  resetPassword,
  validatePassword,
} from "@/lib/auth";

type AuthMode = "login" | "register" | "forgot";

const copy: Record<AuthMode, { title: string; subtitle: string; action: string }> = {
  login: { title: "欢迎回来", subtitle: "登录以继续访问控制台", action: "登 录" },
  register: { title: "创建账号", subtitle: "注册你的 NEXUS API 账户", action: "注 册" },
  forgot: { title: "重置密码", subtitle: "验证账号并设置新的登录密码", action: "确认重置" },
};

function safeReturnTo(value: string | null): string {
  if (!value || !value.startsWith("/") || value.startsWith("//")) return "/";
  return value;
}

function InlineIcon({ name }: { name: "mail" | "lock" | "user" | "scan" | "eye" }) {
  const paths = {
    mail: <><path d="M4 6h16v12H4z"/><path d="m4 7 8 6 8-6"/></>,
    lock: <><rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></>,
    user: <><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></>,
    scan: <><path d="M8 3H4v4M16 3h4v4M8 21H4v-4M16 21h4v-4"/><circle cx="12" cy="12" r="3"/></>,
    eye: <><path d="M3 12s3.3-5 9-5 9 5 9 5-3.3 5-9 5-9-5-9-5Z"/><circle cx="12" cy="12" r="2.2"/></>,
  };
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

function ThemeIcon({ theme }: { theme: "light" | "dark" }) {
  return theme === "dark" ? (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" aria-hidden="true">
      <path d="M20.5 15.1A8.4 8.4 0 0 1 8.9 3.5 8.5 8.5 0 1 0 20.5 15.1Z" />
    </svg>
  ) : (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
    </svg>
  );
}

function CaptchaImage({ src, onLoad, onError }: { src: string; onLoad: () => void; onError: () => void }) {
  const normalized = src.trim();
  if (!normalized) return null;

  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={normalized}
      alt="图形验证码"
      loading="eager"
      decoding="async"
      referrerPolicy="no-referrer"
      onLoad={onLoad}
      onError={onError}
    />
  );
}

export function AuthPage({ mode }: { mode: AuthMode }) {
  const { ready, session, login, register } = useAuth();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [captchaInput, setCaptchaInput] = useState("");
  const [captcha, setCaptcha] = useState<CaptchaChallenge | null>(null);
  const [captchaLoading, setCaptchaLoading] = useState(true);
  const [captchaImageError, setCaptchaImageError] = useState(false);
  const [forgotStep, setForgotStep] = useState<"request" | "reset" | "done">("request");
  const [resetId, setResetId] = useState("");
  const [verificationCode, setVerificationCode] = useState("");
  const [resetExpiresIn, setResetExpiresIn] = useState(0);
  const [showPassword, setShowPassword] = useState(false);
  const [remember, setRemember] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [theme, setTheme] = useState<"light" | "dark">("dark");
  const [registrationEnabled, setRegistrationEnabled] = useState<boolean | null>(null);
  const [registrationError, setRegistrationError] = useState(false);
  const [registrationRetry, setRegistrationRetry] = useState(0);
  const returnTo = safeReturnTo("/");
  const pageCopy = copy[mode];

  useEffect(() => {
    if (mode === "forgot") return;
    let active = true;
    const load = async () => {
      if (document.visibilityState === "hidden") return;
      try {
        const config = await getRegistration();
        if (active) { setRegistrationEnabled(config.registration_enabled); setRegistrationError(false); }
      } catch { if (active) { setRegistrationEnabled(null); setRegistrationError(true); } }
    };
    void load();
    const timer = window.setInterval(() => void load(), 30000);
    window.addEventListener("focus", load);
    return () => { active = false; window.clearInterval(timer); window.removeEventListener("focus", load); };
  }, [mode, registrationRetry]);

  useEffect(() => {
    const current = document.documentElement.dataset.theme;
    if (current === "light" || current === "dark") queueMicrotask(() => setTheme(current));
  }, []);

  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    setTheme(next);
    document.documentElement.dataset.theme = next;
    document.documentElement.style.colorScheme = next;
    window.localStorage.setItem("nexus-theme", next);
  };

  const handlePointerMove = (event: PointerEvent<HTMLElement>) => {
    const target = event.currentTarget;
    target.style.setProperty("--auth-mx", `${event.clientX}px`);
    target.style.setProperty("--auth-my", `${event.clientY}px`);
  };

  const handlePointerLeave = (event: PointerEvent<HTMLElement>) => {
    const target = event.currentTarget;
    target.style.setProperty("--auth-mx", "50%");
    target.style.setProperty("--auth-my", "45%");
  };

  useEffect(() => {
    if (!ready || !session || mode === "forgot") return;
    window.location.replace(returnTo);
  }, [mode, ready, returnTo, session]);

  const captchaScene: CaptchaScene = mode === "register" ? "register" : mode === "forgot" ? "password_reset" : "login";

  const refreshCaptcha = async () => {
    setCaptchaLoading(true);
    setCaptchaImageError(false);
    setCaptchaInput("");
    try {
      setCaptcha(await fetchCaptcha(captchaScene));
    } catch (caught) {
      setCaptcha(null);
      setError(caught instanceof AuthError ? caught.message : "验证码加载失败，请稍后重试");
    } finally {
      setCaptchaLoading(false);
    }
  };

  const handleCaptchaImageLoad = () => {
    setCaptchaImageError(false);
    setError(current => current === "验证码图片加载失败，请点击刷新重试" ? "" : current);
  };

  const handleCaptchaImageError = () => {
    setCaptchaImageError(true);
    setError("验证码图片加载失败，请点击刷新重试");
  };

  useEffect(() => {
    let active = true;
    fetchCaptcha(captchaScene)
      .then(next => {
        if (active) {
          setCaptchaImageError(false);
          setCaptcha(next);
        }
      })
      .catch(caught => {
        if (!active) return;
        setCaptcha(null);
        setError(caught instanceof AuthError ? caught.message : "验证码加载失败，请稍后重试");
      })
      .finally(() => {
        if (active) setCaptchaLoading(false);
      });
    return () => {
      active = false;
    };
  }, [captchaScene, mode]);

  const validate = (): string | null => {
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) return "请输入有效的邮箱地址";
    if (mode === "register" && !name.trim()) return "请输入账号名称";
    if (mode === "forgot" && forgotStep === "request") {
      if (!captcha) return "验证码尚未加载，请刷新后重试";
      if (!/^[A-Za-z0-9]{4}$/.test(captchaInput.trim())) return "请输入 4 位验证码";
      return null;
    }
    if (mode === "forgot" && forgotStep === "reset" && !/^\d{6}$/.test(verificationCode.trim())) {
      return "请输入邮件中的 6 位验证码";
    }
    const passwordError = validatePassword(password);
    if (passwordError) return passwordError;
    if (mode !== "login" && password !== confirmPassword) return "两次输入的密码不一致";
    if (mode !== "forgot") {
      if (!captcha) return "验证码尚未加载，请刷新后重试";
      if (!/^[A-Za-z0-9]{4}$/.test(captchaInput.trim())) return "请输入 4 位验证码";
    }
    return null;
  };

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (mode === "forgot" && forgotStep === "done") return;
    setError("");
    setMessage("");
    const validationError = validate();
    if (validationError) {
      setError(validationError);
      if (validationError.includes("验证码")) void refreshCaptcha();
      return;
    }
    setBusy(true);
    try {
      if (mode === "forgot" && forgotStep === "request") {
        const challenge = await requestPasswordReset({
          email,
          challengeId: captcha!.challengeId,
          captchaCode: captchaInput,
        });
        setResetId(challenge.resetId);
        setResetExpiresIn(challenge.expiresIn);
        setForgotStep("reset");
        setMessage("如果该邮箱已注册，验证码已发送，请查收邮件");
      } else if (mode === "forgot" && forgotStep === "reset") {
        await resetPassword({ email, resetId, verificationCode, newPassword: password });
        setForgotStep("done");
        setPassword("");
        setConfirmPassword("");
        setVerificationCode("");
        setMessage("密码已重置，请使用新密码登录");
      } else if (mode === "login") {
        await login({
          email,
          password,
          remember,
          challengeId: captcha!.challengeId,
          captchaCode: captchaInput,
        });
        window.location.replace(returnTo);
      } else if (mode === "register") {
        const config = await getRegistration();
        setRegistrationEnabled(config.registration_enabled);
        if (!config.registration_enabled) throw new AuthError("AUTH_REGISTRATION_DISABLED", "当前已暂停新用户注册，请稍后再试");
        await register({
          name,
          email,
          password,
          challengeId: captcha!.challengeId,
          captchaCode: captchaInput,
        });
        window.location.replace(returnTo);
      }
    } catch (caught) {
      if (caught instanceof AuthError && caught.code === "AUTH_REGISTRATION_DISABLED") setRegistrationEnabled(false);
      setError(caught instanceof AuthError ? caught.message : "操作失败，请稍后重试");
      if (mode !== "forgot" || forgotStep === "request") void refreshCaptcha();
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className={`auth-page auth-mode-${mode}`} onPointerMove={handlePointerMove} onPointerLeave={handlePointerLeave}>
      <Link className="auth-brand" href="/" aria-label="NEXUS API 首页">
        <span className="auth-brand-mark"><BrandMark className="auth-brand-symbol" /></span><b><span>NEXUS</span><em>API</em></b>
      </Link>
      <div className="auth-top-actions">
        <span className="auth-status"><i /> 系统在线</span>
        <button className="auth-theme-toggle" type="button" onClick={toggleTheme} aria-label={`切换为${theme === "dark" ? "亮色" : "暗色"}模式`} title={`切换为${theme === "dark" ? "亮色" : "暗色"}模式`}><ThemeIcon theme={theme} /></button>
      </div>
      <div className="auth-orbit" aria-hidden="true"><i/><i/><i/></div>
      <div className="auth-caption auth-caption-left" aria-hidden="true"><span>ONE GATEWAY</span><b>EVERY MODEL</b><small>统一入口 · 清晰可控</small></div>
      <div className="auth-caption auth-caption-right" aria-hidden="true"><span>ROUTE / 01</span><b>ACCESS LAYER</b><small>身份验证 · 线路就绪</small></div>
      <div className="auth-capability auth-capability-one"><span>↗</span><b>稳定转发</b><small>智能线路调度</small></div>
      <div className="auth-capability auth-capability-two"><span>▥</span><b>用量可视</b><small>数据实时掌控</small></div>
      <div className="auth-capability auth-capability-three"><span>◇</span><b>密钥安全</b><small>企业级安全策略</small></div>

      <section className="auth-panel" aria-labelledby="auth-title">
        <div className="auth-panel-kicker"><span /> SECURE ACCESS <small>01 / 03</small></div>
        <div className="auth-panel-head">
          <h1 id="auth-title">{pageCopy.title}</h1>
          <p>{pageCopy.subtitle}</p>
        </div>
        {mode === "register" && registrationEnabled !== true ? <div className="auth-registration-status" role="status">
          <p>{registrationError ? "无法获取注册状态，请重试。" : registrationEnabled === false ? "当前已暂停新用户注册，请稍后再试。已有账号可正常登录。" : "正在获取注册状态…"}</p>
          {registrationError && <button type="button" onClick={() => setRegistrationRetry(value => value + 1)}>重试</button>}
          <Link href="/login">返回登录</Link>
        </div> : <form onSubmit={onSubmit} noValidate>
          {mode === "register" && <label className="auth-field"><span>账号名称</span><div><InlineIcon name="user"/><input value={name} onChange={event => setName(event.target.value)} placeholder="输入你的名称" autoComplete="name"/></div></label>}
          {forgotStep !== "done" && <label className="auth-field"><span>邮箱地址</span><div><InlineIcon name="mail"/><input type="email" value={email} onChange={event => setEmail(event.target.value)} placeholder="name@company.com" autoComplete="email" disabled={mode === "forgot" && forgotStep === "reset"}/></div></label>}
          {(mode !== "forgot" || forgotStep === "reset") && forgotStep !== "done" && <label className="auth-field"><span>{mode === "forgot" ? "新密码" : "密码"}</span><div><InlineIcon name="lock"/><input type={showPassword ? "text" : "password"} value={password} onChange={event => setPassword(event.target.value)} placeholder={mode === "forgot" ? "设置新的登录密码" : "输入登录密码"} autoComplete={mode === "login" ? "current-password" : "new-password"}/><button type="button" className="auth-eye" onClick={() => setShowPassword(value => !value)} aria-label={showPassword ? "隐藏密码" : "显示密码"}><InlineIcon name="eye"/></button></div></label>}
          {(mode === "register" || (mode === "forgot" && forgotStep === "reset")) && <label className="auth-field"><span>确认密码</span><div><InlineIcon name="lock"/><input type={showPassword ? "text" : "password"} value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} placeholder="再次输入密码" autoComplete="new-password"/></div></label>}
          {mode === "forgot" && forgotStep === "reset" && <label className="auth-field"><span>邮件验证码</span><div><InlineIcon name="scan"/><input value={verificationCode} onChange={event => setVerificationCode(event.target.value.replace(/\D/g, ""))} placeholder="输入 6 位验证码" inputMode="numeric" maxLength={6} autoComplete="one-time-code"/></div><small className="auth-field-hint">验证码 {Math.max(1, Math.round(resetExpiresIn / 60))} 分钟内有效，仅可使用一次</small></label>}
          {(mode !== "forgot" || forgotStep === "request") && forgotStep !== "done" && <label className="auth-field"><span>验证码</span><div className="auth-captcha-row"><div><InlineIcon name="scan"/><input value={captchaInput} onChange={event => setCaptchaInput(event.target.value.toUpperCase())} placeholder="请输入验证码" maxLength={4} autoComplete="off"/></div><button className="auth-captcha" type="button" onClick={() => void refreshCaptcha()} aria-label="刷新验证码" disabled={captchaLoading}>{captcha?.image && !captchaImageError ? <CaptchaImage key={captcha.challengeId} src={captcha.image} onLoad={handleCaptchaImageLoad} onError={handleCaptchaImageError}/> : <strong>{captchaLoading ? "…" : "重试"}</strong>}<span>↻</span></button></div>{captchaImageError && <small className="auth-field-hint" role="status">验证码图片加载失败，请点击右侧刷新按钮重试</small>}</label>}

          {mode === "login" ? <div className="auth-form-row"><label className="auth-remember"><input type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)}/>记住我</label><span className="auth-links">{registrationEnabled === true && <><Link href="/register">注册账号</Link><i/></>}<Link href="/forgot-password">忘记密码？</Link></span></div> : <div className="auth-form-row auth-form-row-end">{mode === "forgot" && forgotStep === "reset" && <button type="button" className="auth-text-button" onClick={() => { setForgotStep("request"); setResetId(""); setMessage(""); void refreshCaptcha(); }}>重新发送</button>}<Link href="/login">返回登录</Link></div>}

          {(error || message) && <div className={`auth-feedback ${error ? "error" : "success"}`} role="status">{error || message}</div>}
          {forgotStep !== "done" ? <button className="auth-submit" type="submit" disabled={busy}><span>{busy ? "处理中…" : mode === "forgot" && forgotStep === "request" ? "发送邮件验证码" : pageCopy.action}</span><b aria-hidden="true">↗</b></button> : <Link className="auth-submit auth-submit-link" href="/login"><span>返回登录</span><b aria-hidden="true">↗</b></Link>}
        </form>}
        <p className="auth-terms">{mode === "forgot" ? "邮件验证码仅用于本次密码重置，过期后自动失效" : "登录即代表你同意服务条款与隐私政策"}</p>
      </section>
      <footer className="auth-footer">端到端加密 · 安全访问 · 隐私保护</footer>
    </main>
  );
}
