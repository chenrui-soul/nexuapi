"use client";

import { useEffect, useState } from "react";
import { activateMockSubscription, cancelCurrentSubscription, getSubscriptionOverview, type SubscriptionOverview, type SubscriptionPlan } from "@/lib/subscriptions";

const number = (value: string) => Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 3 });
const usageNumber = (value: string) => {
  const numeric = Number(value);
  return numeric > 0 && numeric < 0.001 ? "<0.001" : number(value);
};
const period = (value: string) => value === "yearly" ? "年" : value === "quarterly" ? "季度" : value === "one_time" ? "长期" : "月";

export function SubscriptionPage() {
  const [data, setData] = useState<SubscriptionOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [chosen, setChosen] = useState<SubscriptionPlan | null>(null);
  const [saving, setSaving] = useState(false);
  const [cancelConfirm, setCancelConfirm] = useState(false);

  useEffect(() => {
    let active = true;
    getSubscriptionOverview().then((result) => { if (active) setData(result); })
      .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "订阅数据加载失败"); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const activate = async () => {
    if (!chosen || saving) return;
    setSaving(true); setError("");
    try { setData(await activateMockSubscription(chosen.id)); setChosen(null); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "套餐开通失败"); }
    finally { setSaving(false); }
  };

  const cancel = async () => {
    if (!current || saving) return;
    setSaving(true); setError("");
    try {
      await cancelCurrentSubscription(current.version);
      setCancelConfirm(false);
      setData(await getSubscriptionOverview());
    } catch (reason) { setError(reason instanceof Error ? reason.message : "订阅取消失败"); }
    finally { setSaving(false); }
  };

  if (loading && !data) return <section className="subscription-loading loading-state" role="status"><span className="loading-orbit" aria-hidden="true"><i/><i/></span><b>正在读取真实订阅数据</b><small>同步套餐、额度与周期状态</small><em>LIVE SYNC</em></section>;
  const current = data?.current ?? null;
  const progress = current && Number(current.includedCredits) > 0 ? Math.min(100, Number(current.usedCredits) / Number(current.includedCredits) * 100) : 0;
  return <div className="subscription-page subscription-live-page">
    {current ? <section className="current-plan"><div className="plan-mark">{current.status === "active" ? "✓" : "↗"}</div><div className="current-copy"><span>当前订阅</span><h2>{current.planName} <em>{period(current.billingCycle)}付</em></h2><p>当前周期：{new Date(current.startsAt).toLocaleDateString("zh-CN")}—{new Date(current.expiresAt).toLocaleDateString("zh-CN")}</p></div><div className="quota-block"><div><span>本周期用量</span><b>{usageNumber(current.usedCredits)} / {number(current.includedCredits)}</b></div><div className="quota-track"><i style={{ width: `${progress}%` }}/></div><small>剩余 {number(current.remainingCredits)} 积分</small></div><span className="subscription-live-state">{current.status === "active" ? "真实订阅" : current.status === "refund_pending" ? "退款处理中" : "已退款"}</span>{current.status === "active" && <button className="ghost subscription-cancel-button" onClick={() => setCancelConfirm(true)}>取消订阅</button>}</section> : <section className="current-plan subscription-empty-current"><div className="plan-mark">＋</div><div className="current-copy"><span>当前订阅</span><h2>尚未开通套餐</h2><p>可先比较下方权益；未订阅时仍可使用钱包积分调用模型。</p></div></section>}
    <div className="subscription-note"><span>订阅计划、周期用量和剩余额度均来自后端真实数据；充值积分仍单独保存在钱包中。</span></div>
    {error && <div className="subscription-error">{error}</div>}
    <div className="plans subscription-plans">{data?.plans.map((plan) => <article className={`plan ${plan.featured ? "hot" : ""}`} key={plan.id}>{plan.featured && <span className="popular">推荐</span>}<h4>{plan.name}</h4><strong>¥{number(plan.price)} / {period(plan.billingCycle)}</strong><p>{plan.description}</p><div>{plan.features.map((feature) => <span key={feature}>✓ {feature}</span>)}</div><button className={plan.featured ? "primary" : "ghost"} disabled={current?.planId === plan.id} onClick={() => setChosen(plan)}>{current?.planId === plan.id ? "当前套餐" : data.mockPurchaseEnabled ? `开通${plan.name}` : "查看开通方式"}</button></article>)}</div>
    <section className="panel subscription-detail"><div className="panel-head"><div><h3>订阅说明</h3><p>当前版本不接入正式支付渠道，生产环境不会自动扣款。</p></div></div><div className="benefit-grid"><div><span>额度发放</span><b>开通后进入限时积分</b></div><div><span>调用链路</span><b>与钱包统一结算</b></div><div><span>创作空间</span><b>支持文本、图片、视频</b></div><div><span>安全边界</span><b>不影响 API 令牌配置</b></div></div></section>
    {chosen && <div className="token-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setChosen(null); }}><section className="token-confirm subscription-confirm"><span>¥</span><h3>{data?.mockPurchaseEnabled ? `确认测试开通${chosen.name}` : `${chosen.name}开通方式`}</h3><p>{data?.mockPurchaseEnabled ? `将发放 ${number(chosen.includedCredits)} 限时积分，并创建真实订阅周期。` : "正式支付渠道尚未接入，当前环境不允许直接开通；管理员仍可按业务流程分配套餐。"}</p><div><button className="ghost" onClick={() => setChosen(null)}>关闭</button>{data?.mockPurchaseEnabled && <button className="primary" disabled={saving} onClick={() => void activate()}>{saving ? "开通中…" : "确认开通"}</button>}</div></section></div>}
    {cancelConfirm && current && <div className="token-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setCancelConfirm(false); }}><section className="token-confirm subscription-confirm"><span>!</span><h3>确认取消订阅？</h3><p>取消后套餐权限立即停止，未使用的订阅积分失效；在途请求完成后按比例退款，永久钱包余额不受影响。</p><div><button className="ghost" onClick={() => setCancelConfirm(false)}>暂不取消</button><button className="primary" disabled={saving} onClick={() => void cancel()}>{saving ? "处理中…" : "确认取消"}</button></div></section></div>}
  </div>;
}
