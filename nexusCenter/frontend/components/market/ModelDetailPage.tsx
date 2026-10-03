"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import {
  getModelMarketDetail,
  type ModelInterfaceField,
  type ModelMarketDetail,
  type ModelMarketGroupPrice,
  type ModelMarketItem,
} from "@/lib/model-market";
import { BrandMark } from "@/components/brand/BrandMark";

const capabilityLabels: Record<string, string> = {
  text: "文本模型", image: "图像生成", audio: "音频模型", video: "视频生成",
  embedding: "向量模型", multimodal: "多模态模型",
};
const transportLabels: Record<string, string> = { sync: "同步响应", stream: "支持流式", async_poll: "异步任务" };
const billingLabels: Record<number, string> = {
  1: "按请求计费", 2: "按数量计费", 3: "按时长计费", 4: "按 Token 计费",
  5: "按字符计费", 6: "组合计费",
};
const billingUnitNames: Record<string, string> = {
  request: "次", quantity: "张 / 个", second: "秒", million_tokens: "1M Token",
  million_characters: "1M 字符", image: "张", character: "字符",
};

function DetailIcon({ name, size = 18 }: { name: "back" | "copy" | "check" | "code" | "sun" | "moon" | "key"; size?: number }) {
  const paths = {
    back: <><path d="M15 18l-6-6 6-6"/><path d="M9 12h11"/></>,
    copy: <><rect x="8" y="8" width="11" height="11" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></>,
    check: <path d="m5 12 4 4L19 6"/>,
    code: <><path d="m8 9-3 3 3 3"/><path d="m16 9 3 3-3 3"/><path d="m14 5-4 14"/></>,
    sun: <><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></>,
    moon: <path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z"/>,
    key: <><circle cx="8" cy="15" r="4"/><path d="m11 12 8-8M15 8l2 2M17 6l2 2"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

function decimalText(value: string) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN", { maximumFractionDigits: 3 }) : value;
}

function plainDecimalText(value: string) {
  const normalized = value.trim().toLowerCase();
  if (/^\d+(?:\.\d+)?$/.test(normalized)) return normalized;
  const match = normalized.match(/^(\d+)(?:\.(\d+))?e([+-]?\d+)$/);
  if (!match) return null;
  const integer = match[1]; const fraction = match[2] ?? ""; const exponent = Number(match[3]);
  const digits = integer + fraction; const point = integer.length + exponent;
  if (point <= 0) return `0.${"0".repeat(-point)}${digits}`;
  if (point >= digits.length) return digits + "0".repeat(point - digits.length);
  return `${digits.slice(0, point)}.${digits.slice(point)}`;
}

function multiplyRatioText(value: string, ratio: number) {
  const normalized = plainDecimalText(value);
  if (!normalized) return value;
  const [integer, fraction = ""] = normalized.split(".");
  const scaled = BigInt(integer + fraction) * BigInt(ratio);
  const resultScale = fraction.length + 4;
  const digits = scaled.toString().padStart(resultScale + 1, "0");
  const whole = digits.slice(0, -resultScale) || "0";
  const decimal = digits.slice(-resultScale).replace(/0+$/, "");
  return decimal ? `${whole}.${decimal}` : whole;
}

function priceRows(model: ModelMarketItem, group?: ModelMarketGroupPrice) {
  if (model.billingType === 4) {
    if (!model.pricingVersionId) {
      return [
        { label: "输入", value: group?.effectiveInputPrice ?? model.baseInputPrice },
        { label: "输出", value: group?.effectiveOutputPrice ?? model.baseOutputPrice },
      ];
    }
    const unitPrice = group?.effectiveUnitPrice ?? model.baseUnitPrice;
    const rows = [
      { label: "输入", value: multiplyRatioText(unitPrice, model.inputTokenRatio) },
      { label: "输出", value: multiplyRatioText(unitPrice, model.outputTokenRatio) },
    ];
    if (model.cachedInputTokenRatio > 0) rows.push({ label: "缓存命中", value: multiplyRatioText(unitPrice, model.cachedInputTokenRatio) });
    return rows;
  }
  return [{ label: "平台售价", value: group?.effectiveUnitPrice ?? model.baseUnitPrice }];
}

function formatFieldValue(value: unknown) {
  if (value === null || value === undefined || value === "") return "";
  if (typeof value === "string") return value;
  return JSON.stringify(value);
}

type FlattenedSchemaField = { field: ModelInterfaceField; depth: number; key: string };

function flattenSchemaFields(fields: ModelInterfaceField[], depth = 0, prefix = ""): FlattenedSchemaField[] {
  return fields.flatMap((field, index) => {
    const key = `${prefix}${field.path || field.name}-${index}`;
    return [{ field, depth, key }, ...flattenSchemaFields(field.children, depth + 1, `${key}/`)];
  });
}

function SchemaTable({ fields, showAll, onToggle }: { fields: ModelInterfaceField[]; showAll: boolean; onToggle: () => void }) {
  const flattened = flattenSchemaFields(fields);
  const visible = showAll ? flattened : flattened.slice(0, 8);
  return <div className="model-schema-table">
    <div className="schema-table-head"><span>字段</span><span>类型</span><span>规则</span><span>说明</span></div>
    <div className="schema-table-body">{visible.map(({ field, depth, key }) => {
    const facts = [
      field.defaultValue !== undefined ? `默认 ${formatFieldValue(field.defaultValue)}` : null,
      field.example !== undefined ? `示例 ${formatFieldValue(field.example)}` : null,
      field.enumValues.length ? `可选 ${field.enumValues.join(" / ")}` : null,
      field.minimum != null || field.maximum != null ? `范围 ${field.minimum ?? "-∞"} ～ ${field.maximum ?? "+∞"}` : null,
    ].filter((value): value is string => Boolean(value));
    return <article className={`schema-table-row depth-${Math.min(depth, 3)}`} key={key}>
      <div className="schema-field-name"><code>{field.path || field.name}</code>{depth>0&&<small>嵌套字段</small>}</div>
      <div><span className="schema-type">{field.type}</span></div>
      <div className="schema-rule"><span className={field.required ? "schema-required" : "schema-optional"}>{field.required ? "必填" : "可选"}</span>{field.deprecated&&<span className="schema-deprecated">已弃用</span>}</div>
      <div className="schema-description"><p>{field.description || "暂未维护字段说明"}</p>{facts.length>0&&<details><summary>查看默认值与示例</summary><div className="schema-facts">{facts.map(fact=><span key={fact}>{fact}</span>)}</div></details>}</div>
    </article>;
  })}</div>
    {flattened.length>8&&<button className="schema-expand-button" onClick={onToggle}>{showAll?"收起字段":`展开剩余 ${flattened.length-8} 个字段`}</button>}
  </div>;
}

export function ModelDetailPage({ modelId, initialServiceGroupId }: { modelId: string; initialServiceGroupId?: string }) {
  const router = useRouter();
  const [detail, setDetail] = useState<ModelMarketDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [requestVersion, setRequestVersion] = useState(0);
  const [activeInterfaceId, setActiveInterfaceId] = useState("");
  const [schemaMode, setSchemaMode] = useState<"request" | "response">("request");
  const [showAllFields, setShowAllFields] = useState(false);
  const [selectedGroupId, setSelectedGroupId] = useState(initialServiceGroupId ?? "");
  const [copied, setCopied] = useState(false);
  const [theme, setTheme] = useState<"light" | "dark">(() =>
    typeof document !== "undefined" && document.documentElement.dataset.theme === "dark" ? "dark" : "light",
  );

  useEffect(() => {
    let active = true;
    void getModelMarketDetail(modelId).then((data) => {
      if (!active) return;
      setDetail(data); setActiveInterfaceId(data.interfaces[0]?.id ?? "");
      setSelectedGroupId(data.serviceGroups.some(group => group.id === initialServiceGroupId)
        ? initialServiceGroupId ?? ""
        : data.serviceGroups[0]?.id ?? "");
    }).catch((reason) => {
      if (active) setError(reason instanceof Error ? reason.message : "模型详情加载失败");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [modelId, requestVersion, initialServiceGroupId]);

  const activeInterface = useMemo(() => detail?.interfaces.find(item => item.id === activeInterfaceId) ?? detail?.interfaces[0] ?? null, [activeInterfaceId, detail]);
  const fields = activeInterface ? (schemaMode === "request" ? activeInterface.requestFields : activeInterface.responseFields) : [];
  const requestFieldCount = activeInterface ? flattenSchemaFields(activeInterface.requestFields).length : 0;
  const responseFieldCount = activeInterface ? flattenSchemaFields(activeInterface.responseFields).length : 0;

  const copyModelName = async () => {
    if (!detail) return;
    await navigator.clipboard?.writeText(detail.model.publicName);
    setCopied(true); window.setTimeout(() => setCopied(false), 1500);
  };

  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    setTheme(next); document.documentElement.dataset.theme = next; document.documentElement.style.colorScheme = next;
    window.localStorage.setItem("nexus-theme", next);
  };

  const goBack = () => {
    if (window.history.length > 1) router.back(); else router.push("/?view=market");
  };

  const reload = () => {
    setLoading(true);
    setError("");
    setRequestVersion(value => value + 1);
  };

  if (loading) return <main className="model-detail-state"><div className="model-detail-loader"><i/><i/><i/></div><h1>正在读取模型详情</h1><p>加载接口文档和服务分组价格。</p></main>;
  if (error || !detail) return <main className="model-detail-state error"><span>MODEL DETAIL</span><h1>模型详情加载失败</h1><p>{error || "该模型不存在或未公开"}</p><div><button onClick={goBack}>返回模型市场</button><button className="primary" onClick={reload}>重新加载</button></div></main>;

  const model = detail.model;
  const providerLabel = model.provider && model.provider.toLowerCase() !== "unknown" ? model.provider : "平台模型";
  const basePrices = priceRows(model);
  const selectedGroup = detail.serviceGroups.find(group => group.id === selectedGroupId) ?? detail.serviceGroups[0] ?? null;
  const selectedPrices = selectedGroup ? priceRows(model, selectedGroup) : basePrices;
  const abilityTags = [model.supportsStreaming ? "流式输出" : null, model.supportsTools ? "工具调用" : null, model.supportsStructuredOutput ? "结构化输出" : null].filter((value): value is string => Boolean(value));

  return <main className="model-detail-shell">
    <aside className="model-detail-sidebar">
      <Link href="/" className="model-detail-brand"><span className="brand-mark"><BrandMark /></span><b>NEXUS<em>API</em></b></Link>
      <nav aria-label="详情页导航">
        <Link href="/?view=overview">仪表盘</Link>
        <Link className="active" href="/?view=market">模型市场</Link>
        <Link href="/?view=developer">API 令牌</Link>
        <Link href="/?view=guide">接入指南</Link>
        <Link href="/?view=logs">调用日志</Link>
      </nav>
      <div className="model-detail-sidebar-note"><DetailIcon name="code"/><span><b>{model.publicName}</b><small>公开模型详情</small></span></div>
    </aside>
    <section className="model-detail-main">
      <header className="model-detail-topbar"><button onClick={goBack}><DetailIcon name="back"/>返回模型市场</button><div><span>{providerLabel}</span><b>{model.displayName}</b></div><button className="model-detail-theme" onClick={toggleTheme} aria-label="切换页面主题">{theme === "dark" ? <DetailIcon name="moon"/> : <DetailIcon name="sun"/>}</button></header>
      <div className="model-detail-scroll">
        <section className="model-detail-hero">
          <div className="model-detail-kicker"><span>模型 ID</span><code>{model.publicName}</code><button onClick={copyModelName}>{copied ? <DetailIcon name="check" size={14}/> : <DetailIcon name="copy" size={14}/>} {copied ? "已复制" : "复制"}</button></div>
          <h1>{model.displayName}</h1>
          <div className="model-detail-meta"><span>{capabilityLabels[model.capabilityType] ?? model.capabilityType}</span><i/> <span>{billingLabels[model.billingType] ?? "平台计费"}</span><i/> <span>{providerLabel}</span></div>
          <p>{model.chargeDesc || `平台公开的${capabilityLabels[model.capabilityType] ?? model.capabilityType}，可通过已授权服务分组调用。`}</p>
          <div className="model-detail-tags">{abilityTags.map(tag=><span key={tag}>{tag}</span>)}<span>{detail.interfaces.length} 个公开接口</span><span>{detail.serviceGroups.length} 个可用分组</span></div>
          <div className="model-detail-orbit" aria-hidden="true"><i/><i/><i/><i/><i/></div>
        </section>

        <section className="model-detail-summary">
          <article><span>当前售价</span><strong>{selectedPrices.map(item=>`${item.label} ${decimalText(item.value)}`).join(" / ") || "—"}</strong><small>{selectedGroup ? `${selectedGroup.name} · ${decimalText(selectedGroup.priceMultiplier)}×` : "暂无可见服务分组"}</small></article>
          <article><span>计费方式</span><strong>{billingLabels[model.billingType] ?? "平台计费"}</strong><small>计费单位：{billingUnitNames[model.billingUnit] ?? model.billingUnit}</small></article>
          <article><span>公开接口</span><strong>{detail.interfaces.length}</strong><small>由模型维护中的支持接口决定</small></article>
          <article><span>服务分组</span><strong>{detail.serviceGroups.length}</strong><small>仅展示当前用户可见分组</small></article>
        </section>

        <div className="model-detail-layout">
          <section className="model-interface-docs">
            <header className="model-detail-section-head"><div><span>接口文档</span><h2>接口参数说明</h2><p>请求字段和响应字段来自管理员维护的接口文档，不参与供应商路由。</p></div>{activeInterface&&<code>{activeInterface.httpMethod} {activeInterface.publicPath}</code>}</header>
            {detail.interfaces.length>0 ? <>
              <div className="model-interface-tabs" role="tablist">{detail.interfaces.map(item=><button role="tab" aria-selected={activeInterface?.id===item.id} className={activeInterface?.id===item.id?"active":""} onClick={()=>{setActiveInterfaceId(item.id);setSchemaMode("request");setShowAllFields(false)}} key={item.id}><span>{item.httpMethod}</span><b>{item.publicPath}</b><small>{item.interfaceName}</small></button>)}</div>
              {activeInterface&&<div className="model-interface-intro"><div><span>{transportLabels[activeInterface.transportMode] ?? activeInterface.transportMode}</span><b>{activeInterface.interfaceName}</b><p>{activeInterface.description || "暂未维护接口说明"}</p></div><code>{activeInterface.requestContentType}</code></div>}
              <div className="schema-mode-tabs"><button className={schemaMode==="request"?"active":""} onClick={()=>{setSchemaMode("request");setShowAllFields(false)}}>请求参数 <span>{requestFieldCount}</span></button><button className={schemaMode==="response"?"active":""} onClick={()=>{setSchemaMode("response");setShowAllFields(false)}}>响应参数 <span>{responseFieldCount}</span></button></div>
              {fields.length>0?<SchemaTable fields={fields} showAll={showAllFields} onToggle={()=>setShowAllFields(value=>!value)}/>:<div className="model-detail-empty"><DetailIcon name="code" size={22}/><b>当前接口尚未维护{schemaMode==="request"?"请求":"响应"}字段</b><span>管理员可在接口文档页面补充字段名称、类型、示例和中文说明。</span></div>}
            </>:<div className="model-detail-empty large"><DetailIcon name="code" size={26}/><b>该模型尚未关联公开接口文档</b><span>模型仍可按平台能力入口调用；这里的关联只决定详情页展示哪些接口参数。</span></div>}
          </section>

          <aside className="model-detail-aside">
            <section className="model-copy-card"><span>快速接入</span><h2>复制模型名称</h2><p>在请求体的 <code>model</code> 字段中使用。</p><button onClick={copyModelName}>{copied?<DetailIcon name="check"/>:<DetailIcon name="copy"/>}{copied?"已复制模型名称":"复制 model_name"}</button></section>
            <section className="model-price-panel"><header><span>价格</span><h2>{selectedGroup ? `${selectedGroup.name} · 当前售价` : "模型基础价格"}</h2><p>{selectedGroup ? `模型基础价 × ${decimalText(selectedGroup.priceMultiplier)}× 分组倍率 = 当前售价` : "当前没有可见服务分组，展示模型基础价格。"}</p></header>{detail.serviceGroups.length>0?<div className="model-group-prices">{detail.serviceGroups.map((group,index)=>{const rows=priceRows(model,group);const selected=selectedGroup?.id===group.id;return <article className={`${index===0?"recommended ":""}${selected?"selected":""}`} key={group.id}><button className="group-price-select" onClick={()=>setSelectedGroupId(group.id)} aria-pressed={selected}><div className="group-price-head"><div><b>{group.name}</b><small>{group.description || "平台服务分组"}</small></div><span>{decimalText(group.priceMultiplier)}×</span></div><div className="group-price-values">{rows.map(row=><div key={row.label}><span>{row.label}</span><b>{decimalText(row.value)}</b><small>积分 / {billingUnitNames[model.billingUnit] ?? model.billingUnit}</small></div>)}</div></button><Link href={`/?view=developer&service_group_id=${encodeURIComponent(group.id)}&model_id=${encodeURIComponent(model.id)}`}><DetailIcon name="key" size={15}/>使用该分组创建 API 令牌</Link></article>})}</div>:<div className="model-detail-empty"><b>暂无可见服务分组</b><span>请联系管理员分配可使用的服务分组。</span></div>}</section>
          </aside>
        </div>
      </div>
    </section>
  </main>;
}
