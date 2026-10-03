"use client";
import { useEffect, useState } from "react";
import { adminErrorMessage, listAdminUsers, type AdminUser } from "@/lib/admin";
import { getAdminWallet, rechargeUser, type RechargeInput, type RechargeReceipt } from "@/lib/platform";
import { ApiError } from "@/lib/api";
import { PlatformDialog } from "@/components/ui/PlatformDialog";
import { AdminIcon } from "./AdminIcon";
import { Field, PrimaryButton, SecondaryButton } from "./AdminUi";
type Pending = { user: AdminUser; input: RechargeInput };
export function RechargeDialog({ initial, actor, onClose }: { initial: AdminUser | null; actor: string; onClose: () => void }) {
  const storageKey = `nexus.admin.recharge.pending.${actor}`;
  const [target, setTarget] = useState<AdminUser | null>(initial);
  const [query, setQuery] = useState("");
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [searching, setSearching] = useState(false);
  const [credits, setCredits] = useState("");
  const [reason, setReason] = useState("");
  const [balance, setBalance] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState<RechargeReceipt | null>(null);
  useEffect(() => {
    const timer = setTimeout(() => {
      try { const saved = sessionStorage.getItem(storageKey); if (saved) { const next: Pending = JSON.parse(saved); setPending(next); setTarget(next.user); setCredits(next.input.credits); setReason(next.input.reason); setConfirm(true); } }
      catch { setError("无法恢复上一笔操作，请核对积分账本后再充值。"); }
      setReady(true);
    }, 0);
    return () => clearTimeout(timer);
  }, [storageKey]);
  useEffect(() => {
    if (target) return;
    let active = true;
    const timer = setTimeout(async () => { setSearching(true); try { const page = await listAdminUsers({ page:1, pageSize:20, query }); if (active) setUsers(page.items); } catch (cause) { if (active) setError(adminErrorMessage(cause)); } finally { if (active) setSearching(false); } }, 260);
    return () => { active = false; clearTimeout(timer); };
  }, [query, target]);
  useEffect(() => {
    if (!target) return;
    let active = true;
    getAdminWallet(target.id).then(wallet => { if (active) setBalance(wallet.available_credits); }).catch(cause => { if (active) setError(adminErrorMessage(cause)); });
    return () => { active = false; };
  }, [target]);
  const valid = /^\d+(\.\d{1,2})?$/.test(credits) && Number(credits) >= 0.01 && Number(credits) <= 1000000000 && reason.trim().length > 0;
  async function submit() {
    if (!target || busy || !ready) return;
    const request = pending ?? { user: target, input: { request_id: crypto.randomUUID(), credits, reason: reason.trim() } };
    setBusy(true); setError("");
    try {
      // Keep the same request across a failed response, dialog close, or page reload.
      sessionStorage.setItem(storageKey, JSON.stringify(request)); setPending(request);
      const result = await rechargeUser(request.user.id, request.input);
      setReceipt(result); setBalance(result.available_credits); sessionStorage.removeItem(storageKey); setPending(null);
    } catch (cause) {
      setError(adminErrorMessage(cause));
      // These application rejections happen before any wallet mutation. Ambiguous failures keep the request.
      if (!pending && cause instanceof ApiError && ["ADMIN_RECHARGE_DISABLED", "USER_NOT_FOUND", "VALIDATION_ERROR"].includes(cause.code ?? "")) {
        sessionStorage.removeItem(storageKey); setPending(null); setConfirm(false);
      }
    }
    finally { setBusy(false); }
  }
  return <PlatformDialog title={receipt ? "充值成功" : confirm ? "确认充值" : "手动充值"} description="为用户增加永久积分，每笔充值都将记录到账本。" onClose={onClose} busy={busy} footer={receipt ? <PrimaryButton onClick={onClose}>完成</PrimaryButton> : <><SecondaryButton onClick={onClose} disabled={busy}>关闭</SecondaryButton>{confirm && !pending && <SecondaryButton onClick={() => setConfirm(false)} disabled={busy}>返回修改</SecondaryButton>}{target && <PrimaryButton disabled={busy || !ready || !valid || (!confirm && balance === null)} onClick={() => { if (confirm) void submit(); else { setConfirm(true); setError(""); } }}>{busy ? "正在充值…" : confirm ? pending ? "重试同一笔充值" : "确认充值积分" : "下一步"}</PrimaryButton>}</>}>
    <div className="p-form">
      {!target ? <><Field label="选择充值用户" hint="支持名称、用户 ID 或完整邮箱"><input value={query} placeholder="搜索用户…" onChange={e => setQuery(e.target.value)}/></Field><div className="p-user-picker">{searching ? <p className="p-muted">正在查找用户…</p> : users.length ? users.map(user => <button key={user.id} onClick={() => { setTarget(user); setError(""); }}><span className="p-avatar">{user.display_name.charAt(0)}</span><span><b>{user.display_name}</b><small>{user.masked_email}</small></span><AdminIcon name="chevron" size={16}/></button>) : <p className="p-empty">没有匹配的用户</p>}</div></> : <>
        <div className="p-recharge-user"><span className="p-avatar">{target.display_name.charAt(0)}</span><div><b>{target.display_name}</b><small>{target.masked_email}</small><small className="p-user-id">{target.id}</small></div>{!confirm && !receipt && <button className="p-link" onClick={() => { setTarget(null); setBalance(null); }}>更换</button>}</div>
        {receipt ? <div className="p-recharge-result"><span className="p-symbol"><AdminIcon name="check" size={24}/></span><span>已到账积分</span><strong>+{Number(receipt.credits).toLocaleString("zh-CN", {maximumFractionDigits:2})}</strong><p>当前可用积分 {Number(receipt.available_credits).toLocaleString("zh-CN", {maximumFractionDigits:2})}</p><small>流水编号</small><code>{receipt.ledger_id}</code></div> : confirm ? <><div className="p-credit-confirm"><span>本次充值积分</span><strong>+{Number(credits).toLocaleString("zh-CN", {maximumFractionDigits:2})}</strong><p>{reason}</p></div><p className="p-muted">请核对用户和金额。确认后积分立即到账。</p>{pending && <p className="p-info">这笔充值曾提交过。重试只核实或完成同一笔充值，不会重复入账。</p>}</> : <>
          <div className="p-balance-line"><span>当前可用积分</span><b>{balance === null ? "读取中…" : Number(balance).toLocaleString("zh-CN", {maximumFractionDigits:2})}</b></div>
          <Field label="充值积分" required hint="最小 0.01 积分，最多保留两位小数"><div className="p-amount-input"><input inputMode="decimal" placeholder="0.00" value={credits} onChange={e => setCredits(e.target.value)} maxLength={14}/><span>积分</span></div></Field>
          <Field label="充值备注" required hint={`${reason.length} / 200`}><textarea rows={3} maxLength={200} placeholder="例如：线下付款充值、活动奖励…" value={reason} onChange={e => setReason(e.target.value)}/></Field>
        </>}
      </>}
      {error && <p className="p-error" role="alert">{error}</p>}
    </div>
  </PlatformDialog>;
}
