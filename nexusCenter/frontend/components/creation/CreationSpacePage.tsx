"use client";
/* eslint-disable @next/next/no-img-element -- 上游可返回 data URL 或临时外链，不能交给固定域名图片优化器。 */

import { useEffect, useMemo, useState } from "react";
import { listPublicServiceGroups, type PublicServiceGroup } from "@/lib/api-keys";
import { listModelMarket, type ModelMarketItem } from "@/lib/model-market";
import {
  createChat,
  createImage,
  createVideo,
  extractAssistantText,
  extractMediaUrls,
  extractVideoReference,
} from "@/lib/creation-space";

type Capability = "text" | "image" | "video";
type ChatMessage = { role: "user" | "assistant"; content: string };

const tabs: { capability: Capability; label: string }[] = [
  { capability: "text", label: "智能对话" },
  { capability: "image", label: "图像生成" },
  { capability: "video", label: "视频生成" },
];

export function CreationSpacePage() {
  const [capability, setCapability] = useState<Capability>("image");
  const [groups, setGroups] = useState<PublicServiceGroup[]>([]);
  const [groupId, setGroupId] = useState("");
  const [models, setModels] = useState<ModelMarketItem[]>([]);
  const [model, setModel] = useState("");
  const [prompt, setPrompt] = useState("一座漂浮在云层之上的未来城市，清晨金色阳光，电影感构图");
  const [ratio, setRatio] = useState("1:1");
  const [quantity, setQuantity] = useState(1);
  const [duration, setDuration] = useState(5);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");
  const [mediaUrls, setMediaUrls] = useState<string[]>([]);
  const [videoReference, setVideoReference] = useState<string | null>(null);
  const [rawResult, setRawResult] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);

  useEffect(() => {
    listPublicServiceGroups().then((items) => {
      setGroups(items);
      setGroupId((current) => current || items[0]?.id || "");
    }).catch((reason) => setError(reason instanceof Error ? reason.message : "服务分组加载失败"));
  }, []);

  useEffect(() => {
    if (!groupId) return;
    let active = true;
    listModelMarket({ serviceGroupId: groupId, capabilityType: capability, pageSize: 100, sort: "name" })
      .then((result) => {
        if (!active) return;
        setModels(result.items);
        setModel((current) => result.items.some((item) => item.publicName === current)
          ? current : result.items[0]?.publicName || "");
      })
      .catch((reason) => { if (active) { setModels([]); setModel(""); setError(reason instanceof Error ? reason.message : "模型加载失败"); } });
    return () => { active = false; };
  }, [groupId, capability]);

  const selectedModel = useMemo(() => models.find((item) => item.publicName === model) ?? null, [models, model]);
  const estimated = useMemo(() => {
    if (!selectedModel) return "—";
    const unit = Number(selectedModel.effectiveUnitPrice || 0);
    if (capability === "image") return (unit * quantity).toLocaleString("zh-CN", { maximumFractionDigits: 3 });
    if (capability === "video") return (unit * duration).toLocaleString("zh-CN", { maximumFractionDigits: 3 });
    return "按实际 Token 结算";
  }, [selectedModel, capability, quantity, duration]);

  const run = async () => {
    if (!groupId || !model || !prompt.trim() || creating) return;
    setCreating(true); setError(""); setMediaUrls([]); setVideoReference(null); setRawResult("");
    try {
      if (capability === "text") {
        const userMessage: ChatMessage = { role: "user", content: prompt.trim() };
        const payload = await createChat({ serviceGroupId: groupId, model, messages: [...messages, userMessage] });
        const assistantMessage: ChatMessage = { role: "assistant", content: extractAssistantText(payload) };
        setMessages((current) => [...current, userMessage, assistantMessage]);
        setPrompt("");
      } else if (capability === "image") {
        const payload = await createImage({ serviceGroupId: groupId, model, prompt: prompt.trim(), quantity, aspectRatio: ratio, quality: "medium", resolution: "1k" });
        const urls = extractMediaUrls(payload);
        setMediaUrls(urls);
        if (urls.length === 0) setRawResult(JSON.stringify(payload, null, 2));
      } else {
        const payload = await createVideo({ serviceGroupId: groupId, model, prompt: prompt.trim(), duration, aspectRatio: ratio, resolution: "480p" });
        setVideoReference(extractVideoReference(payload));
        setRawResult(JSON.stringify(payload, null, 2));
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "创作请求失败");
    } finally { setCreating(false); }
  };

  return <div className="create-page creation-live-page">
    <div className="studio-tabs">{tabs.map((tab) => <button key={tab.capability} className={capability === tab.capability ? "active" : ""} onClick={() => { setCapability(tab.capability); setMediaUrls([]); setRawResult(""); setVideoReference(null); }}>{tab.label}</button>)}</div>
    <section className="creation-context-bar">
      <label><span>服务分组</span><select value={groupId} onChange={(event) => { setError(""); setModels([]); setModel(""); setGroupId(event.target.value); }}><option value="">选择服务分组</option>{groups.map((group) => <option key={group.id} value={group.id}>{group.name} · {Number(group.priceMultiplier).toFixed(3)}×</option>)}</select></label>
      <label><span>调用模型</span><select value={model} onChange={(event) => setModel(event.target.value)} disabled={models.length === 0}><option value="">{models.length === 0 ? "当前分组暂无该能力模型" : "选择模型"}</option>{models.map((item) => <option key={item.id} value={item.publicName}>{item.displayName} · {item.publicName}</option>)}</select></label>
      <div><span>预计消耗</span><b>{estimated}{estimated !== "—" && capability !== "text" ? " 积分" : ""}</b><small>最终以调用日志实扣为准</small></div>
    </section>
    <div className="studio-grid">
      <section className="control-panel">
        <div className="field"><div className="label-line"><label>{capability === "text" ? "输入消息" : "描述你的创意"}</label><span>{prompt.length}/800</span></div><textarea value={prompt} maxLength={800} onChange={(event) => setPrompt(event.target.value)} placeholder={capability === "text" ? "向模型提问…" : "描述希望生成的画面…"}/></div>
        {capability !== "text" && <div className="double-field"><div className="field"><label>画面比例</label><div className="ratio-row">{["1:1", "16:9", "9:16"].map((value) => <button key={value} className={ratio === value ? "active" : ""} onClick={() => setRatio(value)}>{value}</button>)}</div></div><div className="field"><label>{capability === "image" ? "生成数量" : "视频时长"}</label><select className="creation-native-select" value={capability === "image" ? quantity : duration} onChange={(event) => capability === "image" ? setQuantity(Number(event.target.value)) : setDuration(Number(event.target.value))}>{(capability === "image" ? [1, 2, 4] : [5, 8, 10]).map((value) => <option value={value} key={value}>{value}{capability === "image" ? " 张" : " 秒"}</option>)}</select></div></div>}
        {error && <div className="creation-error">{error}</div>}
        <button className="generate" onClick={() => void run()} disabled={creating || !groupId || !model || !prompt.trim()}>{creating ? "正在请求上游…" : capability === "text" ? "发送消息" : "开始生成"}</button>
      </section>
      <section className="result-panel">
        <div className="result-head"><div><h3>{capability === "text" ? "对话结果" : "创作画布"}</h3><span>真实 Gateway · 实时计费 · 自动写入调用日志</span></div>{capability === "text" && messages.length > 0 && <button className="text-btn" onClick={() => setMessages([])}>新对话</button>}</div>
        {capability === "text" ? <div className="creation-conversation">{messages.length === 0 && <div className="creation-placeholder"><b>开始一次真实对话</b><span>请求会使用上方选择的服务分组与模型。</span></div>}{messages.map((message, index) => <div className={message.role === "user" ? "user-message" : "ai-message"} key={`${message.role}-${index}`}><p>{message.content}</p></div>)}</div> : mediaUrls.length > 0 ? <div className="creation-media-grid">{mediaUrls.map((url, index) => <a href={url} target="_blank" rel="noreferrer" key={`${url}-${index}`}><img src={url} alt={`生成结果 ${index + 1}`}/><span>打开原图</span></a>)}</div> : <div className="creation-placeholder"><b>{creating ? "正在等待上游返回" : videoReference ? "视频任务已创建" : "还没有生成结果"}</b><span>{videoReference ?? "填写参数后开始生成，结果会显示在这里。"}</span>{rawResult && <pre>{rawResult}</pre>}</div>}
      </section>
    </div>
  </div>;
}
