"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  activateModelPricingVersion, adminErrorMessage, deleteModelPricingVersion, getModelPricing, publishModelPricing,
  updateModelPricingSourceMode,
  type BillingType, type Model, type ModelPricing, type ModelPricingInput,
  type ModelPricingVersion,
} from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import { ConfirmDialog, Drawer, Field, PrimaryButton, SecondaryButton } from "./AdminUi";

type RuleForm = {
  key: string;
  priority: number;
  name: string;
  conditions: string;
  billingType: BillingType | "";
  unitPrice: string;
  priceMultiplier: string;
};

type TierForm = {
  key: string;
  priority: number;
  minInputTokens: number;
  maxInputTokens: string;
  inputRatio: number;
  outputRatio: number;
  cachedInputRatio: number;
  cacheWrite5mRatio: number;
  cacheWrite1hRatio: number;
};

type PricingForm = {
  billingType: BillingType;
  unitPrice: string;
  displayOriginalPrice: string;
  inputTokenRatio: number;
  outputTokenRatio: number;
  audioInputTokenRatio: number;
  audioOutputTokenRatio: number;
  cachedInputTokenRatio: number;
  cacheWrite5mTokenRatio: number;
  cacheWrite1hTokenRatio: number;
  chargeDesc: string;
  contextTierMode: 0 | 1 | 2;
  unmatchedBehavior: "base" | "reject";
  changeNote: string;
  rules: RuleForm[];
  tiers: TierForm[];
};

const billingLabels: Record<BillingType, string> = {
  1: "按次",
  2: "按数量 / 图片张数",
  3: "按视频秒",
  4: "按 Token（每 1M）",
  5: "按字符（每 1M）",
  6: "按音频秒",
};

const unitLabels: Record<string, string> = {
  request: "次",
  quantity: "张 / 个",
  second: "秒（历史兼容）",
  video_second: "视频秒",
  audio_second: "音频秒",
  million_tokens: "1M Token",
  million_characters: "1M 字符",
};

const billingUnits: Record<BillingType, string> = {
  1: "request",
  2: "quantity",
  3: "video_second",
  4: "million_tokens",
  5: "million_characters",
  6: "audio_second",
};

function allowedBillingTypes(capabilityType: string, currentType?: BillingType): BillingType[] {
  if (capabilityType === "audio") return currentType === 3 ? [6, 5, 3] : [6, 5];
  if (capabilityType === "image") return [1, 2];
  if (capabilityType === "video") return [1, 3];
  return [1, 4];
}

/** 管理界面以易读的业务倍数编辑，API 仍使用万分位整数保证计算精度。 */
function ratioToDisplay(value: number | null | undefined, fallback = 1): number {
  return (value ?? fallback * 10000) / 10000;
}

function ratioToBasisPoints(value: number): number {
  return Math.round(Math.max(0, value) * 10000);
}

function pricePreview(base: string, ratio: number, groupMultiplier: number): string {
  const value = Number(base) * ratio * groupMultiplier;
  return Number.isFinite(value) ? value.toLocaleString("zh-CN", { maximumFractionDigits: 3 }) : "—";
}

type TokenPreviewRow = {
  key: keyof Pick<PricingForm, "inputTokenRatio" | "outputTokenRatio" | "cachedInputTokenRatio" | "cacheWrite5mTokenRatio" | "cacheWrite1hTokenRatio" | "audioInputTokenRatio" | "audioOutputTokenRatio">;
  label: string;
  hint: string;
  fallbackRatio?: (form: PricingForm) => number;
};

const tokenPreviewRows: TokenPreviewRow[] = [
  { key: "inputTokenRatio", label: "普通输入 Token", hint: "总输入扣除缓存与音频后的部分" },
  { key: "outputTokenRatio", label: "普通输出 Token", hint: "总输出扣除音频输出后的部分" },
  { key: "cachedInputTokenRatio", label: "缓存命中输入", hint: "缓存命中的输入 Token" },
  { key: "cacheWrite5mTokenRatio", label: "5 分钟缓存写入", hint: "倍率为 0 时按输入 × 1.25", fallbackRatio: form => form.inputTokenRatio * 1.25 },
  { key: "cacheWrite1hTokenRatio", label: "1 小时缓存写入", hint: "倍率为 0 时按输入 × 2", fallbackRatio: form => form.inputTokenRatio * 2 },
  { key: "audioInputTokenRatio", label: "音频输入 Token", hint: "音频输入产生的 Token" },
  { key: "audioOutputTokenRatio", label: "音频输出 Token", hint: "音频输出产生的 Token" },
];

function effectiveTokenRatio(form: PricingForm, row: TokenPreviewRow): number {
  const configured = form[row.key] as number;
  return configured > 0 ? configured : row.fallbackRatio?.(form) ?? 0;
}

function decimalText(value: string | number | null | undefined, fallback = "0"): string {
  return value === null || value === undefined ? fallback : String(value);
}

function ruleFromVersion(version: ModelPricingVersion): RuleForm[] {
  return version.rules.map(rule => ({
    key: rule.id,
    priority: rule.priority,
    name: rule.name,
    conditions: Object.entries(rule.match_conditions).map(([key, value]) => `${key}=${value}`).join(", "),
    billingType: rule.billing_type ?? "",
    unitPrice: decimalText(rule.unit_price, ""),
    priceMultiplier: decimalText(rule.price_multiplier, "1"),
  }));
}

function tierFromVersion(version: ModelPricingVersion): TierForm[] {
  return version.context_tiers.map(tier => ({
    key: tier.id,
    priority: tier.priority,
    minInputTokens: tier.min_input_tokens,
    maxInputTokens: tier.max_input_tokens === null ? "" : String(tier.max_input_tokens),
    inputRatio: ratioToDisplay(tier.input_ratio),
    outputRatio: ratioToDisplay(tier.output_ratio),
    cachedInputRatio: ratioToDisplay(tier.cached_input_ratio),
    cacheWrite5mRatio: ratioToDisplay(tier.cache_write_5m_ratio, 0),
    cacheWrite1hRatio: ratioToDisplay(tier.cache_write_1h_ratio, 0),
  }));
}

/** 未发布 V2 版本时从模型当前兼容价格生成首版草稿，不会自动写入数据库。 */
function createForm(model: Model, pricing: ModelPricing): PricingForm {
  const active = pricing.active;
  return {
    billingType: active?.billing_type ?? model.billing_type,
    unitPrice: decimalText(active?.unit_price ?? model.unit_price ?? model.input_price),
    displayOriginalPrice: decimalText(active?.display_original_price ?? model.display_original_price),
    inputTokenRatio: ratioToDisplay(active?.input_token_ratio ?? model.input_token_ratio),
    outputTokenRatio: ratioToDisplay(active?.output_token_ratio ?? model.output_token_ratio),
    audioInputTokenRatio: ratioToDisplay(active?.audio_input_token_ratio ?? model.audio_input_token_ratio),
    audioOutputTokenRatio: ratioToDisplay(active?.audio_output_token_ratio ?? model.audio_output_token_ratio),
    cachedInputTokenRatio: ratioToDisplay(active?.cached_input_token_ratio ?? model.cached_input_token_ratio),
    cacheWrite5mTokenRatio: ratioToDisplay(active?.cache_write_5m_token_ratio ?? model.cache_write_5m_token_ratio, 0),
    cacheWrite1hTokenRatio: ratioToDisplay(active?.cache_write_1h_token_ratio ?? model.cache_write_1h_token_ratio, 0),
    chargeDesc: active?.charge_desc ?? model.charge_desc ?? "",
    contextTierMode: active?.context_tier_mode ?? 0,
    unmatchedBehavior: active?.unmatched_behavior ?? "base",
    changeNote: "",
    rules: active ? ruleFromVersion(active) : [],
    tiers: active ? tierFromVersion(active) : [],
  };
}

function parseConditions(value: string): Record<string, string> {
  const result: Record<string, string> = {};
  const entries = value.split(/[\n,]+/).map(entry => entry.trim()).filter(Boolean);
  if (!entries.length) throw new Error("每条条件计价规则至少需要一个匹配条件");
  for (const entry of entries) {
    const separator = entry.indexOf("=");
    if (separator <= 0 || separator === entry.length - 1) {
      throw new Error(`条件“${entry}”必须使用 key=value 格式`);
    }
    const key = entry.slice(0, separator).trim().toLowerCase();
    const conditionValue = entry.slice(separator + 1).trim();
    if (!/^[a-z][a-z0-9_.-]{0,63}$/.test(key)) throw new Error(`条件参数名“${key}”格式无效`);
    if (/authorization|token|secret|password|api.?key|credential/i.test(key)) {
      throw new Error("条件规则不能包含凭证、Token、密码或 Authorization 字段");
    }
    if (result[key] !== undefined) throw new Error(`条件参数“${key}”重复`);
    result[key] = conditionValue;
  }
  return result;
}

function toInput(form: PricingForm, modelVersion: number): ModelPricingInput {
  return {
    billing_type: form.billingType,
    unit_price: form.unitPrice,
    display_original_price: form.displayOriginalPrice,
    input_token_ratio: ratioToBasisPoints(form.inputTokenRatio),
    output_token_ratio: ratioToBasisPoints(form.outputTokenRatio),
    audio_input_token_ratio: ratioToBasisPoints(form.audioInputTokenRatio),
    audio_output_token_ratio: ratioToBasisPoints(form.audioOutputTokenRatio),
    cached_input_token_ratio: ratioToBasisPoints(form.cachedInputTokenRatio),
    cache_write_5m_token_ratio: ratioToBasisPoints(form.cacheWrite5mTokenRatio),
    cache_write_1h_token_ratio: ratioToBasisPoints(form.cacheWrite1hTokenRatio),
    charge_desc: form.chargeDesc.trim() || null,
    context_tier_mode: form.billingType === 4 ? form.contextTierMode : 0,
    unmatched_behavior: form.unmatchedBehavior,
    rules: form.rules.map(rule => ({
      priority: rule.priority,
      name: rule.name.trim(),
      match_conditions: parseConditions(rule.conditions),
      billing_type: rule.billingType || null,
      unit_price: rule.unitPrice.trim() || null,
      price_multiplier: rule.priceMultiplier,
    })),
    context_tiers: form.billingType === 4 ? form.tiers.map(tier => ({
      priority: tier.priority,
      min_input_tokens: tier.minInputTokens,
      max_input_tokens: tier.maxInputTokens === "" ? null : Number(tier.maxInputTokens),
          input_ratio: ratioToBasisPoints(tier.inputRatio),
          output_ratio: ratioToBasisPoints(tier.outputRatio),
          cached_input_ratio: ratioToBasisPoints(tier.cachedInputRatio),
          cache_write_5m_ratio: ratioToBasisPoints(tier.cacheWrite5mRatio),
          cache_write_1h_ratio: ratioToBasisPoints(tier.cacheWrite1hRatio),
    })) : [],
    change_note: form.changeNote.trim() || null,
    model_version: modelVersion,
  };
}

export function ModelPricingDrawer({
  model,
  open,
  onClose,
  onSuccess,
}: {
  model: Model | null;
  open: boolean;
  onClose: () => void;
  onSuccess: (message: string) => void;
}) {
  const [pricing, setPricing] = useState<ModelPricing | null>(null);
  const [form, setForm] = useState<PricingForm | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [rollbackTarget, setRollbackTarget] = useState<ModelPricingVersion | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ModelPricingVersion | null>(null);
  const [sourceModeTarget, setSourceModeTarget] = useState<"manual" | "follow_upstream" | null>(null);
  const [sourceModeSaving, setSourceModeSaving] = useState(false);
  const [previewMultiplier, setPreviewMultiplier] = useState("1");

  const allowedTypes = useMemo(
    () => allowedBillingTypes(model?.capability_type ?? "text", form?.billingType),
    [model?.capability_type, form?.billingType],
  );

  const load = useCallback(async () => {
    if (!model) return;
    setLoading(true);
    setError("");
    try {
      const result = await getModelPricing(model.id);
      setPricing(result);
      setForm(createForm(model, result));
    } catch (loadError) {
      setError(adminErrorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, [model]);

  useEffect(() => {
    if (!open || !model) return;
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load, model, open]);

  const updateRule = (key: string, patch: Partial<RuleForm>) => {
    setForm(current => current ? {
      ...current,
      rules: current.rules.map(rule => rule.key === key ? { ...rule, ...patch } : rule),
    } : current);
  };

  const updateTier = (key: string, patch: Partial<TierForm>) => {
    setForm(current => current ? {
      ...current,
      tiers: current.tiers.map(tier => tier.key === key ? { ...tier, ...patch } : tier),
    } : current);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!model || !pricing || !form) return;
    setSaving(true);
    setError("");
    try {
      const updated = await publishModelPricing(model.id, toInput(form, pricing.model_version));
      setPricing(updated);
      setForm(createForm(model, updated));
      onSuccess(`价格版本 V${updated.active?.version_no ?? ""} 已发布并生效`);
    } catch (saveError) {
      setError(saveError instanceof Error && !("status" in saveError)
        ? saveError.message
        : adminErrorMessage(saveError));
    } finally {
      setSaving(false);
    }
  };

  const activateVersion = async () => {
    if (!model || !pricing || !rollbackTarget) return;
    setSaving(true);
    setError("");
    try {
      const updated = await activateModelPricingVersion(model.id, rollbackTarget.id, pricing.model_version);
      setPricing(updated);
      setForm(createForm(model, updated));
      setRollbackTarget(null);
      onSuccess(`已切换到价格版本 V${updated.active?.version_no ?? ""}`);
    } catch (activateError) {
      setError(adminErrorMessage(activateError));
    } finally {
      setSaving(false);
    }
  };

  const deleteVersion = async () => {
    if (!model || !pricing || !deleteTarget) return;
    setSaving(true);
    setError("");
    try {
      const deletedVersionNo = deleteTarget.version_no;
      const updated = await deleteModelPricingVersion(model.id, deleteTarget.id, pricing.model_version);
      setPricing(updated);
      setForm(createForm(model, updated));
      setDeleteTarget(null);
      onSuccess(`价格版本 V${deletedVersionNo} 已删除`);
    } catch (deleteError) {
      setError(adminErrorMessage(deleteError));
    } finally {
      setSaving(false);
    }
  };

  const toggleSourceMode = async () => {
    if (!model || !pricing || !sourceModeTarget) return;
    const followUpstream = sourceModeTarget === "follow_upstream";
    setSourceModeSaving(true);
    setError("");
    try {
      const updated = await updateModelPricingSourceMode(model.id, followUpstream, pricing.model_version);
      setPricing(updated);
      if (form) setForm(createForm(model, updated));
      setSourceModeTarget(null);
      onSuccess(followUpstream ? "已切换为跟随上游价格" : "已切换为人工定价，后续同步不会覆盖价格");
    } catch (modeError) {
      setError(adminErrorMessage(modeError));
    } finally {
      setSourceModeSaving(false);
    }
  };

  return <>
    <Drawer
      open={open}
      className="model-pricing-drawer"
      title={model ? `${model.display_name} · 计费设置` : "模型计费设置"}
      description="平台积分售价与上游供应商成本完全分离；保存会生成不可变版本，历史账单不受影响。"
      onClose={onClose}
      footer={<><SecondaryButton onClick={onClose} disabled={saving || sourceModeSaving}>关闭</SecondaryButton><PrimaryButton type="submit" form="model-pricing-form" disabled={saving || sourceModeSaving || loading || !form}>{saving ? "发布中…" : "发布新价格版本"}</PrimaryButton></>}
    >
      {loading && <div className="pricing-loading">正在读取价格版本…</div>}
      {error && <div className="pricing-error"><AdminIcon name="warning" size={16}/><span>{error}</span><button type="button" onClick={() => void load()}>重试</button></div>}
      {form && pricing && <form id="model-pricing-form" className="pricing-form" onSubmit={submit}>
        <section className="pricing-section">
          <header><div><span>BASE PRICE</span><h3>销售基准与计费方式</h3></div><div className="pricing-mode-control"><em className={pricing.pricing_mode === "follow_upstream" ? "upstream" : "manual"}>{pricing.pricing_mode === "follow_upstream" ? "跟随上游" : "人工定价"}</em><SecondaryButton type="button" onClick={() => setSourceModeTarget(pricing.pricing_mode === "follow_upstream" ? "manual" : "follow_upstream")} disabled={sourceModeSaving || saving}>{sourceModeSaving ? "切换中…" : pricing.pricing_mode === "follow_upstream" ? "切换人工定价" : "跟随上游"}</SecondaryButton></div></header>
          <p className="pricing-source-note">{pricing.pricing_mode === "follow_upstream" ? "同步到新的上游价格后会自动创建并激活新版本；管理员仍可随时切回人工定价。" : "当前价格由管理员维护，模型同步只更新目录信息，不会覆盖平台售价。"}{pricing.pricing_source_synced_at ? ` 最近同步：${new Date(pricing.pricing_source_synced_at).toLocaleString("zh-CN")}` : ""}{pricing.pricing_source_hash ? ` · 快照 ${pricing.pricing_source_hash.slice(0, 10)}…` : ""}</p>
          {model?.capability_type === "audio" && form.billingType === 3 && <p className="pricing-error"><AdminIcon name="warning" size={15}/><span>这是历史音频“类型 3”配置。重新发布时请改为“按音频秒（类型 6）”，类型 3 以后只代表视频秒。</span></p>}
          <div className="admin-form-grid">
            <Field label="计费类型" required><select value={form.billingType} onChange={event => { const billingType = Number(event.target.value) as BillingType; setForm(current => current ? { ...current, billingType, contextTierMode: billingType === 4 ? current.contextTierMode : 0 } : current); }}>{allowedTypes.map(type => <option key={type} value={type} disabled={model?.capability_type === "audio" && type === 3}>{model?.capability_type === "audio" && type === 3 ? "历史兼容：按秒（旧类型 3）" : billingLabels[type]}</option>)}</select></Field>
            <Field label="销售基准积分" required hint={`每 ${unitLabels[billingUnits[form.billingType]] ?? "计费单位"}`}><input type="number" min={0} step="0.000000000001" required value={form.unitPrice} onChange={event => setForm(current => current ? { ...current, unitPrice: event.target.value } : current)}/></Field>
            <Field label="展示原价" hint="仅划线展示，0 表示不展示，永不参与计费"><input type="number" min={0} step="0.000000000001" value={form.displayOriginalPrice} onChange={event => setForm(current => current ? { ...current, displayOriginalPrice: event.target.value } : current)}/></Field>
            <Field label="规则未命中" required><select value={form.unmatchedBehavior} onChange={event => setForm(current => current ? { ...current, unmatchedBehavior: event.target.value as "base" | "reject" } : current)}><option value="base">使用销售基准价</option><option value="reject">拒绝请求</option></select></Field>
            <Field label="计费说明" wide hint="展示给用户，不填写凭证、内部成本或路由信息"><textarea maxLength={1000} value={form.chargeDesc} onChange={event => setForm(current => current ? { ...current, chargeDesc: event.target.value } : current)} placeholder="例如：标准画质按张计费，高清画质以条件规则为准。"/></Field>
            <Field label="版本变更说明" wide hint="仅供管理员追溯本次价格调整"><input maxLength={500} value={form.changeNote} onChange={event => setForm(current => current ? { ...current, changeNote: event.target.value } : current)} placeholder="例如：新增 4K 图片档位"/></Field>
          </div>
        </section>

        {form.billingType === 4 && <section className="pricing-section pricing-token-section">
          <header><div><span>TOKEN PRICING</span><h3>七类 Token 计费倍率</h3></div><small>页面按业务倍数填写，提交时自动转换为万分位</small></header>
          <p className="pricing-source-note">同一 Token 只进入一个费用项：普通输入 = 总输入 − 缓存命中 − 两种缓存写入 − 音频输入；普通输出 = 总输出 − 音频输出。缓存写入倍率为 0 时，自动按输入倍率 × 1.25 / × 2 计算。</p>
          <div className="pricing-ratio-grid pricing-ratio-grid-seven">
            {tokenPreviewRows.map(row => {
              const ratio = effectiveTokenRatio(form, row);
              return <label key={row.key}>
                <span>{row.label}</span>
                <input type="number" min={0} step="0.01" value={form[row.key]} onChange={event => setForm(current => current ? { ...current, [row.key]: Number(event.target.value) } : current)}/>
                <small>{row.hint}</small>
                <em className="pricing-ratio-price">{form.unitPrice || "0"} × {ratio.toLocaleString("zh-CN", { maximumFractionDigits: 3 })} = {pricePreview(form.unitPrice, ratio, 1)} 积分 / 1M</em>
              </label>;
            })}
          </div>
          <div className="pricing-preview-bar">
            <div className="pricing-preview-controls">
              <label><span>服务分组倍率（仅预览）</span><input type="number" min={0} step="0.01" value={previewMultiplier} onChange={event => setPreviewMultiplier(event.target.value)}/></label>
              <div className="pricing-preview-base"><span>当前模型销售基准</span><b>{form.unitPrice || "0"} 积分 / 1M Token</b></div>
            </div>
            <div className="pricing-preview-content">
              <div className="pricing-preview-heading"><b>实际售价预览</b><span>模型基准 × Token 倍率 × 服务分组倍率</span></div>
              <div className="pricing-token-cost-grid">
                {tokenPreviewRows.map(row => {
                  const ratio = effectiveTokenRatio(form, row);
                  return <article key={row.key}><span>{row.label}</span><strong>{pricePreview(form.unitPrice, ratio, Number(previewMultiplier) || 0)} <small>积分 / 1M</small></strong><em>{ratio.toLocaleString("zh-CN", { maximumFractionDigits: 3 })}x · {row.hint}</em></article>;
                })}
              </div>
              <div className="pricing-total-formula"><b>单次请求总费用</b><span>普通输入 + 普通输出 + 缓存命中输入 + 5 分钟缓存写入 + 1 小时缓存写入 + 音频输入 + 音频输出</span><small>各项按实际 Token 用量分别计算后相加，避免同一批 Token 重复计费。</small></div>
            </div>
          </div>
        </section>}

        <section className="pricing-section">
          <header><div><span>CONDITIONAL RULES</span><h3>条件计价规则</h3></div><SecondaryButton type="button" onClick={() => setForm(current => current ? { ...current, rules: [...current.rules, { key: crypto.randomUUID(), priority: (current.rules.length + 1) * 10, name: "", conditions: "", billingType: "", unitPrice: "", priceMultiplier: "1" }] } : current)}><AdminIcon name="plus" size={13}/>添加规则</SecondaryButton></header>
          {!form.rules.length ? <p className="pricing-empty">没有条件规则时，所有请求使用销售基准价。</p> : <div className="pricing-rule-list">{form.rules.map(rule => <article key={rule.key}>
            <div className="pricing-rule-heading"><input aria-label="规则名称" required maxLength={120} value={rule.name} onChange={event => updateRule(rule.key, { name: event.target.value })} placeholder="高清图片"/><button type="button" aria-label="删除规则" onClick={() => setForm(current => current ? { ...current, rules: current.rules.filter(item => item.key !== rule.key) } : current)}><AdminIcon name="close" size={13}/></button></div>
            <div className="pricing-rule-grid"><label><span>优先级</span><input type="number" min={0} value={rule.priority} onChange={event => updateRule(rule.key, { priority: Number(event.target.value) })}/></label><label><span>覆盖计费类型</span><select value={rule.billingType} onChange={event => updateRule(rule.key, { billingType: event.target.value ? Number(event.target.value) as BillingType : "" })}><option value="">沿用基础类型</option>{allowedTypes.map(type => <option key={type} value={type}>{billingLabels[type]}</option>)}</select></label><label><span>覆盖单价</span><input type="number" min={0} step="0.000000000001" value={rule.unitPrice} onChange={event => updateRule(rule.key, { unitPrice: event.target.value })} placeholder="留空沿用基准"/></label><label><span>规则倍率</span><input type="number" min="0.0000000001" step="0.0000000001" required value={rule.priceMultiplier} onChange={event => updateRule(rule.key, { priceMultiplier: event.target.value })}/></label></div>
            <label className="pricing-condition"><span>匹配条件</span><input required value={rule.conditions} onChange={event => updateRule(rule.key, { conditions: event.target.value })} placeholder="size=1024x1024, quality=hd"/><small>使用英文逗号分隔 key=value；禁止凭证、Token 和 Authorization 字段。</small></label>
          </article>)}</div>}
        </section>

        {form.billingType === 4 && <section className="pricing-section">
          <header><div><span>CONTEXT TIERS</span><h3>长上下文分档</h3></div><SecondaryButton type="button" onClick={() => setForm(current => current ? { ...current, tiers: [...current.tiers, { key: crypto.randomUUID(), priority: (current.tiers.length + 1) * 10, minInputTokens: 0, maxInputTokens: "", inputRatio: 1, outputRatio: 1, cachedInputRatio: 1, cacheWrite5mRatio: 0, cacheWrite1hRatio: 0 }] } : current)}><AdminIcon name="plus" size={13}/>添加分档</SecondaryButton></header>
          <div className="pricing-tier-mode"><label><span>分档计算方式</span><select value={form.contextTierMode} onChange={event => setForm(current => current ? { ...current, contextTierMode: Number(event.target.value) as 0 | 1 | 2 } : current)}><option value={0}>整次按命中档</option><option value={1}>整次按命中档（兼容）</option><option value={2}>按区间分段累加</option></select></label></div>
          {!form.tiers.length ? <p className="pricing-empty">不配置分档时，所有 Token 使用上方倍率。</p> : <div className="pricing-tier-list">{form.tiers.map(tier => <article key={tier.key}><div className="pricing-tier-grid"><label><span>优先级</span><input type="number" min={0} value={tier.priority} onChange={event => updateTier(tier.key, { priority: Number(event.target.value) })}/></label><label><span>最小输入 Token</span><input type="number" min={0} value={tier.minInputTokens} onChange={event => updateTier(tier.key, { minInputTokens: Number(event.target.value) })}/></label><label><span>最大输入 Token</span><input type="number" min={1} value={tier.maxInputTokens} onChange={event => updateTier(tier.key, { maxInputTokens: event.target.value })} placeholder="留空表示无上限"/></label><label><span>输入倍数</span><input type="number" min={0} step="0.01" value={tier.inputRatio} onChange={event => updateTier(tier.key, { inputRatio: Number(event.target.value) })}/></label><label><span>输出倍数</span><input type="number" min={0} step="0.01" value={tier.outputRatio} onChange={event => updateTier(tier.key, { outputRatio: Number(event.target.value) })}/></label><label><span>缓存命中倍数</span><input type="number" min={0} step="0.01" value={tier.cachedInputRatio} onChange={event => updateTier(tier.key, { cachedInputRatio: Number(event.target.value) })}/></label><label><span>写入 5 分钟</span><input type="number" min={0} step="0.01" value={tier.cacheWrite5mRatio} onChange={event => updateTier(tier.key, { cacheWrite5mRatio: Number(event.target.value) })}/></label><label><span>写入 1 小时</span><input type="number" min={0} step="0.01" value={tier.cacheWrite1hRatio} onChange={event => updateTier(tier.key, { cacheWrite1hRatio: Number(event.target.value) })}/></label><button type="button" aria-label="删除分档" onClick={() => setForm(current => current ? { ...current, tiers: current.tiers.filter(item => item.key !== tier.key) } : current)}><AdminIcon name="close" size={13}/></button></div></article>)}</div>}
        </section>}

        <section className="pricing-section pricing-history">
          <header><div><span>VERSION HISTORY</span><h3>价格版本历史</h3></div><small>最多显示最近 50 个版本；已用于计费的版本不可删除</small></header>
          {!pricing.versions.length ? <p className="pricing-empty">发布首个价格版本后，可在这里查看、切换或删除未使用版本。</p> : <div>{pricing.versions.map(version => <article className={version.active ? "active" : ""} key={version.id}><div><b>V{version.version_no}</b><span>{billingLabels[version.billing_type]} · {decimalText(version.unit_price)} 积分 / {unitLabels[version.billing_unit] ?? version.billing_unit}</span><small><strong>{version.source_type === "manual" ? "管理员发布" : "上游同步"}</strong> · {version.change_note || "未填写变更说明"} · {new Date(version.source_observed_at ?? version.created_at).toLocaleString("zh-CN")}{version.source_hash ? ` · ${version.source_hash.slice(0, 10)}…` : ""}</small></div>{version.active ? <em>当前生效</em> : <div className="pricing-history-actions"><button type="button" onClick={() => setRollbackTarget(version)}>切换到此版本</button><button className="danger" type="button" onClick={() => setDeleteTarget(version)}>删除</button></div>}</article>)}</div>}
        </section>
      </form>}
    </Drawer>
    <ConfirmDialog open={Boolean(rollbackTarget)} title={`切换到价格版本 V${rollbackTarget?.version_no ?? ""}？`} description="只会切换后续请求使用的价格版本；历史请求、已结算账本和旧版本内容不会改变。" confirmLabel="确认切换" busy={saving} onCancel={() => setRollbackTarget(null)} onConfirm={() => void activateVersion()}/>
    <ConfirmDialog open={Boolean(deleteTarget)} title={`删除价格版本 V${deleteTarget?.version_no ?? ""}？`} description="仅允许删除非当前生效且未被任何请求计费明细引用的版本。删除后，该版本的条件规则和上下文分档也会一并移除，无法恢复。" confirmLabel="确认删除" danger busy={saving} onCancel={() => setDeleteTarget(null)} onConfirm={() => void deleteVersion()}/>
    <ConfirmDialog open={Boolean(sourceModeTarget)} title={sourceModeTarget === "follow_upstream" ? "恢复跟随上游价格？" : "切换为人工定价？"} description={sourceModeTarget === "follow_upstream" ? "如果已有上游同步版本，会立即切换到最近版本；没有上游版本时，将等待下一次完整同步。" : "当前价格保持不变，但后续模型同步不会再覆盖平台售价，直到重新开启跟随上游。"} confirmLabel="确认切换" busy={sourceModeSaving} onCancel={() => setSourceModeTarget(null)} onConfirm={() => void toggleSourceMode()}/>
  </>;
}
