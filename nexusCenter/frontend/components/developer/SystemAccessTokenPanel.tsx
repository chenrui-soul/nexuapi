"use client";

import { useEffect, useState } from "react";
import {
  createSystemAccessToken,
  listSystemAccessScopes,
  listSystemAccessTokens,
  revokeSystemAccessToken,
  setSystemAccessTokenStatus,
  type SystemAccessScope,
  type SystemAccessToken,
} from "@/lib/system-access-tokens";

function date(value: string | null): string {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "长期有效";
}

export function SystemAccessTokenPanel() {
  const [tokens, setTokens] = useState<SystemAccessToken[]>([]);
  const [scopes, setScopes] = useState<SystemAccessScope[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [selectedScopes, setSelectedScopes] = useState<string[]>([]);
  const [ipAllowlist, setIpAllowlist] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [saving, setSaving] = useState(false);
  const [secret, setSecret] = useState("");

  const refresh = async () => {
    setLoading(true);
    setError("");
    try {
      const [nextTokens, nextScopes] = await Promise.all([listSystemAccessTokens(), listSystemAccessScopes()]);
      setTokens(nextTokens);
      setScopes(nextScopes);
      setSelectedScopes((current) => current.length === 0 ? nextScopes.map((scope) => scope.code) : current);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "系统访问令牌加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let active = true;
    Promise.all([listSystemAccessTokens(), listSystemAccessScopes()]).then(([nextTokens, nextScopes]) => {
      if (!active) return;
      setTokens(nextTokens); setScopes(nextScopes); setSelectedScopes(nextScopes.map((scope) => scope.code));
    }).catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "系统访问令牌加载失败"); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const create = async () => {
    if (!name.trim() || selectedScopes.length === 0 || saving) return;
    setSaving(true);
    setError("");
    try {
      const created = await createSystemAccessToken({
        name: name.trim(), scopes: selectedScopes,
        ipAllowlist: ipAllowlist.split(/[\n,]+/).map((value) => value.trim()).filter(Boolean),
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null,
      });
      setTokens((current) => [created.token, ...current]);
      setSecret(created.secret);
      setOpen(false); setName(""); setIpAllowlist(""); setExpiresAt("");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "系统访问令牌创建失败");
    } finally { setSaving(false); }
  };

  const changeStatus = async (token: SystemAccessToken) => {
    const target = token.status === "active" ? "disabled" : "active";
    if (!window.confirm(`确认${target === "active" ? "启用" : "停用"}系统访问令牌“${token.name}”吗？`)) return;
    try {
      const updated = await setSystemAccessTokenStatus(token, target);
      setTokens((current) => current.map((item) => item.id === updated.id ? updated : item));
    } catch (reason) { setError(reason instanceof Error ? reason.message : "状态修改失败"); }
  };

  const revoke = async (token: SystemAccessToken) => {
    if (!window.confirm(`撤销后无法恢复。确认撤销“${token.name}”吗？`)) return;
    try {
      await revokeSystemAccessToken(token.id);
      setTokens((current) => current.map((item) => item.id === token.id ? { ...item, status: "revoked" } : item));
    } catch (reason) { setError(reason instanceof Error ? reason.message : "撤销失败"); }
  };

  return <>
    <section className="system-token-card system-token-live">
      <div className="system-token-icon">S</div>
      <div className="system-token-copy"><div><h3>系统访问令牌</h3><span className="on"><i/> 独立只读凭证</span></div><p>用于外部系统读取当前账户的仪表盘、钱包和调用日志，不可调用模型，也无法读取供应商或上游 APIKey。</p><small>Bearer 前缀为 nx-sys，与用户 API 令牌和上游 APIKey 完全隔离。</small></div>
      <div className="system-token-actions"><span>{tokens.filter((token) => token.status === "active").length} 枚启用</span><button className="primary" onClick={() => setOpen(true)}>新建系统令牌</button></div>
    </section>
    <section className="system-token-list">
      <div className="system-token-list-head"><div><h3>系统令牌列表</h3><p>完整令牌仅在创建后展示一次，列表只保留脱敏标识。</p></div><button onClick={() => void refresh()} disabled={loading}>{loading ? "刷新中" : "刷新"}</button></div>
      {error && <div className="system-token-error">{error}</div>}
      {loading && <div className="system-token-empty loading-state" role="status"><span className="loading-orbit" aria-hidden="true"><i/><i/></span><b>正在同步系统访问令牌</b><small>读取令牌状态与权限范围</small></div>}
      {!loading && tokens.length === 0 && <div className="system-token-empty">还没有系统访问令牌</div>}
      {tokens.map((token) => <article className="system-token-row" key={token.id}>
        <div><b>{token.name}</b><code>{token.maskedToken}</code></div>
        <div className="system-token-scopes">{token.scopes.map((scope) => <span key={scope}>{scope}</span>)}</div>
        <div><span className={`token-status ${token.status}`}>{token.status === "active" ? "启用" : token.status === "disabled" ? "停用" : token.status === "expired" ? "已过期" : "已撤销"}</span><small>有效期：{date(token.expiresAt)}</small></div>
        <div className="system-token-row-actions">{(token.status === "active" || token.status === "disabled") && <button onClick={() => void changeStatus(token)}>{token.status === "active" ? "停用" : "启用"}</button>}{token.status !== "revoked" && <button className="danger" onClick={() => void revoke(token)}>撤销</button>}</div>
      </article>)}
    </section>
    {open && <div className="token-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setOpen(false); }}><section className="token-modal system-token-modal" role="dialog" aria-modal="true" aria-label="新建系统访问令牌"><button className="token-modal-close" onClick={() => setOpen(false)}>×</button><h3>新建系统访问令牌</h3><p>选择外部系统真正需要的最小只读权限。</p><label>令牌名称<b>*</b><input value={name} maxLength={120} onChange={(event) => setName(event.target.value)} placeholder="例如：数据报表同步"/></label><div className="system-scope-picker">{scopes.map((scope) => <label key={scope.code}><input type="checkbox" checked={selectedScopes.includes(scope.code)} onChange={() => setSelectedScopes((current) => current.includes(scope.code) ? current.filter((value) => value !== scope.code) : [...current, scope.code])}/><span><b>{scope.label}</b><small>{scope.code}</small></span></label>)}</div><label>IP 白名单 <small>可选，每行一个 CIDR</small><textarea value={ipAllowlist} onChange={(event) => setIpAllowlist(event.target.value)} placeholder="203.0.113.8/32"/></label><label>到期时间 <small>留空表示长期有效</small><input type="datetime-local" value={expiresAt} onChange={(event) => setExpiresAt(event.target.value)}/></label><div className="token-modal-actions"><button className="ghost" onClick={() => setOpen(false)}>取消</button><button className="primary" disabled={!name.trim() || selectedScopes.length === 0 || saving} onClick={() => void create()}>{saving ? "创建中…" : "确认创建"}</button></div></section></div>}
    {secret && <div className="token-modal-backdrop"><section className="token-secret-modal" role="dialog" aria-modal="true"><div className="secret-success">✓</div><h3>系统访问令牌创建成功</h3><p>请立即复制并安全保存，关闭后无法再次查看。</p><code>{secret}</code><button className="primary" onClick={async () => { await navigator.clipboard.writeText(secret); setSecret(""); }}>复制并关闭</button></section></div>}
  </>;
}
