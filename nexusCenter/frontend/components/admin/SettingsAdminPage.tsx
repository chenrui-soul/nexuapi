"use client";

import { useEffect, useState } from "react";
import { getPlatformSettings, savePlatformSettings, type PlatformSettings } from "@/lib/platform";
import { adminErrorMessage } from "@/lib/admin";
import { AdminPageHeader, ErrorState, LoadingState, PrimaryButton, ToggleField } from "./AdminUi";

export function SettingsAdminPage() {
  const [settings, setSettings] = useState<PlatformSettings | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [recharge, setRecharge] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  async function load() {
    setError("");
    try { const next = await getPlatformSettings(); setSettings(next); setEnabled(next.registration_enabled); setRecharge(Boolean(next.manual_recharge_enabled)); }
    catch (cause) { setError(adminErrorMessage(cause)); }
  }
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, []);
  async function save() {
    if (!settings || busy) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const next = await savePlatformSettings({ registration_enabled: enabled, manual_recharge_enabled: recharge, version: settings.version });
      setSettings(next); setEnabled(next.registration_enabled); setRecharge(Boolean(next.manual_recharge_enabled));
      setNotice("设置已保存，即时生效。");
    } catch (cause) { setError(adminErrorMessage(cause)); }
    finally { setBusy(false); }
  }
  return <div className="platform-page platform-ui">
    <AdminPageHeader eyebrow="PLATFORM SETTINGS" title="系统设置" description="管理平台开放策略，保存后立即生效。" />
    {!settings ? error ? <ErrorState message={error} onRetry={() => void load()}/> : <LoadingState/> :
      <section className="admin-panel platform-settings-panel">
        <div className="p-settings-heading"><span className="p-eyebrow">访问与账户</span><h2>平台开放设置</h2></div>

        <fieldset disabled={busy}>
          <ToggleField label="允许新用户注册" description="关闭后隐藏注册入口，并阻止所有新的注册请求。已有账号不受影响。" checked={enabled} onChange={value => { setEnabled(value); setNotice(""); }}/>
          <ToggleField label="允许管理员手动充值" description="开启后，在用户与权限页面显示充值按钮。充值积分永久有效，每笔操作保留账本和审计记录。" checked={recharge} onChange={value => { setRecharge(value); setNotice(""); }}/>
        </fieldset>
        {error && <div role="alert" className="platform-error">{error} <button type="button" onClick={() => void load()}>重新读取设置</button></div>}
        {notice && <p role="status">{notice}</p>}
        <PrimaryButton disabled={busy || (enabled === settings.registration_enabled && recharge === Boolean(settings.manual_recharge_enabled))} onClick={() => void save()}>{busy ? "保存中…" : "保存设置"}</PrimaryButton>
      </section>}
  </div>;
}
