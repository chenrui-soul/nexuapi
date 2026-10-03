"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import Link from "next/link";
import gsap from "gsap";
import { useGSAP } from "@gsap/react";
import { AnnouncementBell } from "@/components/notifications/AnnouncementBell";
import { ProtectedRoute } from "@/components/auth/ProtectedRoute";
import { useAuth } from "@/components/auth/AuthProvider";
import { BrandMark } from "@/components/brand/BrandMark";
import { DeveloperPage } from "@/components/developer/DeveloperPage";
import { SystemAccessTokenPage } from "@/components/developer/SystemAccessTokenPage";
import { AccountSecurityPage } from "@/components/security/AccountSecurityPage";
import { CreationSpacePage } from "@/components/creation/CreationSpacePage";
import { SubscriptionPage } from "@/components/subscription/SubscriptionPage";
import { Pagination } from "@/components/ui/Pagination";
import { PageFrame, PagePanel } from "@/components/ui/PageFrame";
import { readPageData } from "@/lib/page-read-cache";
import { PUBLIC_API_V1_BASE_URL } from "@/lib/api";
import { hasAdminRole } from "@/lib/auth";
import { listPublicServiceGroups, type PublicServiceGroup } from "@/lib/api-keys";
import { listModelMarket, type ModelMarketItem, type ModelMarketPage } from "@/lib/model-market";
import { getUserRequestLog, listUserRequestLogs, type UserRequestLog, type UserRequestLogPage } from "@/lib/request-logs";
import {
  getUserDashboardOverview,
  getUserGroupStatuses,
  type DashboardRankingItem,
  type UserDashboardOverview,
  type UserGroupStatusItem,
  type UserGroupStatusResponse,
} from "@/lib/user-analytics";
import {
  createRechargeOrder,
  getWalletBalance,
  listWalletLedger,
  mockPayRechargeOrder,
  type WalletBalance,
  type WalletLedgerItem,
} from "@/lib/wallet";

if (typeof window !== "undefined") gsap.registerPlugin(useGSAP);

type View = "overview" | "market" | "create" | "subscription" | "wallet" | "security" | "developer" | "systemTokens" | "guide" | "logs" | "status";
type ThemeChoice = "system" | "light" | "dark";
function formatCredits(value: string | null | undefined): string {
  if (value == null) return "—";
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN", { maximumFractionDigits: 3 }) : value;
}
const nav: { section: string; items: { id: View; label: string; icon: string }[] }[] = [
  { section: "概览与创作", items: [
    { id: "overview", label: "仪表盘", icon: "grid" },
    { id: "market", label: "模型市场", icon: "layers" },
    { id: "create", label: "创作空间", icon: "spark" },
  ]},
  { section: "账户与计费", items: [
    { id: "subscription", label: "订阅计划", icon: "check" },
    { id: "wallet", label: "钱包", icon: "wallet" },
    { id: "security", label: "账户安全", icon: "shield" },
  ]},
  { section: "开发者", items: [
    { id: "developer", label: "API 令牌", icon: "code" },
    { id: "systemTokens", label: "系统访问令牌", icon: "shield" },
    { id: "guide", label: "接入指南", icon: "layers" },
    { id: "logs", label: "调用日志", icon: "request" },
    { id: "status", label: "分组状态", icon: "pulse" },
  ]},
];
const titles: Record<View, { title: string; sub: string }> = {
  overview: { title: "仪表盘", sub: "API 调用、算力消耗与业务趋势总览" },
  market: { title: "模型市场", sub: "比较模型能力、服务价格和可用状态，找到合适的模型" },
  create: { title: "创作空间", sub: "无需配置 API 令牌，直接使用账户算力" },
  subscription: { title: "订阅计划", sub: "管理当前套餐、额度周期与升级选项" },
  wallet: { title: "钱包", sub: "充值算力积分，查看余额和资金明细" },
  security: { title: "账户安全", sub: "管理登录密码、邮箱验证状态与活动会话" },
  developer: { title: "API 令牌", sub: "创建和管理用于服务端调用的访问凭证" },
  systemTokens: { title: "系统访问令牌", sub: "为报表与外部系统创建独立的只读访问凭证" },
  guide: { title: "接入指南", sub: "按照步骤，5 分钟完成第一次 API 调用" },
  logs: { title: "调用日志", sub: "搜索、筛选和排查每一次 API 请求" },
  status: { title: "服务分组状态", sub: "查看各服务分组的可用性、延迟与倍率" },
};

function Icon({ name, size = 19 }: { name: string; size?: number }) {
  const paths: Record<string, React.ReactNode> = {
    grid: <><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/></>,
    layers: <><path d="m12 2 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5"/><path d="m3 17 9 5 9-5"/></>,
    cube: <><path d="m12 2 8 4.5v9L12 20l-8-4.5v-9L12 2Z"/><path d="m4.4 6.7 7.6 4.2 7.6-4.2M12 20v-9.1"/></>,
    spark: <><path d="m12 3-1.9 5.1L5 10l5.1 1.9L12 17l1.9-5.1L19 10l-5.1-1.9L12 3Z"/><path d="m5 3-.7 1.8L2.5 5.5l1.8.7L5 8l.7-1.8 1.8-.7-1.8-.7L5 3Z"/></>,
    code: <><path d="m8 9-3 3 3 3"/><path d="m16 9 3 3-3 3"/><path d="m14 5-4 14"/></>,
    wallet: <><path d="M20 7V5a2 2 0 0 0-2-2H5a3 3 0 0 0 0 6h15v10a2 2 0 0 1-2 2H5a3 3 0 0 1-3-3V6"/><path d="M16 13h.01"/></>, infinity: <><path d="M6.5 7.5c-2.4 0-3.5 2-3.5 4.5s1.1 4.5 3.5 4.5c3.2 0 4.8-9 8-9 2.4 0 3.5 2 3.5 4.5s-1.1 4.5-3.5 4.5c-3.2 0-4.8-9-8-9Z"/></>,
    pulse: <path d="M3 12h4l2-7 4 14 2-7h6"/>, bolt: <path d="m13 2-8 12h7l-1 8 8-12h-7l1-8Z"/>, search: <><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></>,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/></>,
    arrow: <><path d="M5 12h14"/><path d="m13 6 6 6-6 6"/></>, up: <path d="m18 15-6-6-6 6"/>,
    coin: <><circle cx="12" cy="12" r="9"/><path d="M9 9.5c0-1 1.3-1.8 3-1.8s3 .8 3 1.8-1.3 1.8-3 1.8-3 .8-3 1.8 1.3 1.8 3 1.8 3-.8 3-1.8"/><path d="M12 5.5v13"/></>,
    request: <><path d="M4 18V6a2 2 0 0 1 2-2h12"/><path d="m14 2 4 2-2 4"/><path d="M20 6v12a2 2 0 0 1-2 2H6"/><path d="m10 22-4-2 2-4"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>, check: <path d="m5 12 4 4L19 6"/>,
    image: <><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="m21 15-5-5L5 21"/></>,
    video: <><rect x="3" y="5" width="14" height="14" rx="3"/><path d="m17 10 4-2v8l-4-2"/></>, chat: <path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z"/>,
    copy: <><rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/></>,
    more: <><circle cx="5" cy="12" r="1" fill="currentColor"/><circle cx="12" cy="12" r="1" fill="currentColor"/><circle cx="19" cy="12" r="1" fill="currentColor"/></>,
    edit: <><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/></>,
    trash: <><path d="M4 7h16"/><path d="M10 11v6M14 11v6"/><path d="m6 7 1 14h10l1-14M9 7V4h6v3"/></>,
    menu: <path d="M4 7h16M4 12h16M4 17h16"/>, close: <path d="m6 6 12 12M18 6 6 18"/>, plus: <path d="M12 5v14M5 12h14"/>,
    sun: <><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.42-1.42M17.66 6.34l1.41-1.41"/></>,
    moon: <path d="M20.5 14.2A8.2 8.2 0 0 1 9.8 3.5 8.5 8.5 0 1 0 20.5 14.2Z"/>,
    monitor: <><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></>,
    shield: <><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name] ?? paths.grid}</svg>;
}

function Toast({message,onClose}:{message:string;onClose:()=>void}){
  useEffect(()=>{if(!message)return;const timer=setTimeout(onClose,1800);return()=>clearTimeout(timer)},[message,onClose]);
  return message?<div className="token-toast" role="status"><Icon name="check" size={14}/>{message}</div>:null;
}

function DemoDialog({title,eyebrow="操作确认",children,onClose,actions}:{title:string;eyebrow?:string;children:React.ReactNode;onClose:()=>void;actions?:React.ReactNode}){
  useEffect(()=>{const close=(event:KeyboardEvent)=>event.key==="Escape"&&onClose();window.addEventListener("keydown",close);return()=>window.removeEventListener("keydown",close)},[onClose]);
  return <div className="token-modal-backdrop" onMouseDown={event=>event.target===event.currentTarget&&onClose()}><section className="token-modal compact-dialog" role="dialog" aria-modal="true" aria-label={title}><button className="token-modal-close" aria-label="关闭弹窗" onClick={onClose}><Icon name="close"/></button><span className="eyebrow">{eyebrow}</span><h3>{title}</h3><div className="dialog-body">{children}</div>{actions&&<div className="token-modal-actions">{actions}</div>}</section></div>;
}

function SelectMenu({value,options,onChange,label,className="",disabled=false,trigger,getOptionLabel}:{value:string;options:readonly string[];onChange:(value:string)=>void;label:string;className?:string;disabled?:boolean;trigger?:React.ReactNode;getOptionLabel?:(value:string)=>string}){
  const [open,setOpen]=useState(false);
  const [highlighted,setHighlighted]=useState(Math.max(0,options.indexOf(value)));
  const rootRef=useRef<HTMLDivElement>(null);
  const listId=useId();
  useEffect(()=>{const close=(event:PointerEvent)=>{if(!rootRef.current?.contains(event.target as Node))setOpen(false)};document.addEventListener("pointerdown",close);return()=>document.removeEventListener("pointerdown",close)},[]);
  const choose=(next:string)=>{onChange(next);setHighlighted(Math.max(0,options.indexOf(next)));setOpen(false)};
  const onKeyDown=(event:React.KeyboardEvent)=>{if(disabled)return;if(event.key==="Escape"){setOpen(false);return}if(event.key==="ArrowDown"||event.key==="ArrowUp"){event.preventDefault();const step=event.key==="ArrowDown"?1:-1;setOpen(true);setHighlighted(index=>(index+step+options.length)%options.length)}if(event.key==="Enter"||event.key===" "){event.preventDefault();if(open)choose(options[highlighted]);else setOpen(true)}};
  return <div className={`ui-select ${open?"open":""} ${disabled?"disabled":""} ${className}`} ref={rootRef} onKeyDown={onKeyDown}><button type="button" className="ui-select-trigger" role="combobox" aria-label={label} aria-expanded={open} aria-controls={listId} disabled={disabled} onClick={()=>setOpen(!open)}><span className="ui-select-value">{trigger??(getOptionLabel?.(value)??value)}</span><span className="ui-select-chevron"><Icon name="up" size={14}/></span></button>{open&&<div className="ui-select-menu" role="listbox" id={listId} aria-label={label}>{options.map((option,index)=><button type="button" role="option" aria-selected={option===value} className={`ui-select-option ${option===value?"selected":""} ${index===highlighted?"highlighted":""}`} onMouseEnter={()=>setHighlighted(index)} onClick={()=>choose(option)} key={option}><span>{getOptionLabel?.(option)??option}</span>{option===value&&<Icon name="check" size={14}/>}</button>)}</div>}</div>;
}

type DashboardRange = "今日" | "近 7 天" | "近 30 天" | "自定义";
const dashboardPresets = { "今日": "today", "近 7 天": "7d", "近 30 天": "30d" } as const;
const rankColors = ["blue", "orange", "cyan", "green", "purple", "pink"];
const capabilityLabels: Record<string,string> = { text:"文本模型", multimodal:"多模态", image:"图像生成", video:"视频生成", audio:"音频", embedding:"向量", other:"其他能力" };
const dashboardCapabilityPalette = ["#315bea", "#5d7fd3", "#87a1d1", "#afbdd3"];

function formatInteger(value:number){return value.toLocaleString("zh-CN")}
function formatShortDate(value:string){return new Date(value).toLocaleDateString("zh-CN",{month:"2-digit",day:"2-digit"})}
function formatRate(value:number|null){return value==null?"—":`${value>0?"+":""}${value.toLocaleString("zh-CN",{maximumFractionDigits:3})}%`}
function formatPercent(value:number|null){return value==null?"—":`${value.toLocaleString("zh-CN",{maximumFractionDigits:3})}%`}
function formatDuration(value:number|null|undefined){if(value==null)return"—";return value>=1000?`${(value/1000).toFixed(value>=10000?1:2)}s`:`${Math.round(value)}ms`}
function formatDateTime(value:string|null|undefined){return value?new Date(value).toLocaleString("zh-CN",{hour12:false}):"暂无请求"}
function formatTime(value:string){return new Date(value).toLocaleTimeString("zh-CN",{hour12:false,hour:"2-digit",minute:"2-digit",second:"2-digit"})}
function formatMultiplier(value:string){const parsed=Number(value);return Number.isFinite(parsed)?`${parsed.toLocaleString("zh-CN",{maximumFractionDigits:3})}×`:`${value}×`}
function dateTimeInputValue(offsetDays=0,startOfDay=false){
  const date=new Date(Date.now()+offsetDays*86400000);
  const parts=new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Shanghai",year:"numeric",month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit",second:"2-digit",hourCycle:"h23"}).formatToParts(date);
  const values=Object.fromEntries(parts.filter(part=>part.type!=="literal").map(part=>[part.type,part.value]));
  return `${values.year}-${values.month}-${values.day}T${startOfDay?"00:00:00":`${values.hour}:${values.minute}:${values.second}`}`;
}
function customDateTimeIso(value:string){
  const normalized=value.length===16?`${value}:00`:value;
  const instant=new Date(`${normalized}+08:00`);
  if(Number.isNaN(instant.getTime())) throw new Error("无效的时间格式");
  return instant.toISOString();
}
function trendLabel(value:string,bucketSize:"hour"|"day"){return new Date(value).toLocaleString("zh-CN",bucketSize==="hour"?{hour12:false,hour:"2-digit",minute:"2-digit"}:{month:"2-digit",day:"2-digit"})}
function DashboardKpi({label,value,sub,tone,icon,variant="blue",onClick}:{label:string;value:string;sub:string;tone?:string;icon:string;variant?:"blue"|"cyan"|"green"|"violet";onClick?:()=>void}){
  const spark=[24,38,31,48,42,61,53,72,63,82,69,88];
  return <button className={`dash-kpi ${variant}`} onClick={onClick}>
    <span className="dash-kpi-head"><span>{label}</span><i><Icon name={icon} size={19}/></i></span>
    <strong>{value}</strong>
    <small className={tone||""}>{sub}</small>
    <span className="kpi-spark" aria-hidden="true">{spark.map((height,index)=><i style={{height:`${height}%`}} key={index}/>)}</span>
  </button>
}
function Overview({onNavigate,balance}:{onNavigate:(view:View)=>void;balance:WalletBalance|null}) {
  const dashboardRef=useRef<HTMLDivElement>(null);
  const rangeReady=useRef(false);
  const [range,setRange]=useState<DashboardRange>("今日");
  const [dimension,setDimension]=useState("按模型");
  const [alert,setAlert]=useState(true);
  const [balanceOpen,setBalanceOpen]=useState(false);
  const [customOpen,setCustomOpen]=useState(false);
  const [customFrom,setCustomFrom]=useState(dateTimeInputValue(-6,true));
  const [customTo,setCustomTo]=useState(dateTimeInputValue());
  const [appliedCustom,setAppliedCustom]=useState<{from:string;to:string}|null>(null);
  const [refreshing,setRefreshing]=useState(false);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState("");
  const [overview,setOverview]=useState<UserDashboardOverview|null>(null);
  const [groupStatus,setGroupStatus]=useState<UserGroupStatusResponse|null>(null);
  const [toast,setToast]=useState("");
  const loadVersion=useRef(0);
  const load=useCallback(async(showRefresh=false)=>{
    const version=++loadVersion.current;
    if(showRefresh)setRefreshing(true);else setLoading(true);
    setError("");
    try{
      const query=range==="自定义"&&appliedCustom
        ?{from:appliedCustom.from,to:appliedCustom.to}
        :{preset:dashboardPresets[range as keyof typeof dashboardPresets]??"today"};
      const [nextOverview,nextGroups]=await Promise.all([getUserDashboardOverview(query,showRefresh),getUserGroupStatuses(showRefresh)]);
      if(version!==loadVersion.current)return;
      setOverview(nextOverview);setGroupStatus(nextGroups);
      if(showRefresh)setToast("仪表盘真实数据已刷新");
    }catch(reason){if(version===loadVersion.current)setError(reason instanceof Error?reason.message:"仪表盘数据加载失败")}finally{if(version===loadVersion.current){setLoading(false);setRefreshing(false)}}
  },[appliedCustom,range]);
  useEffect(()=>{let active=true;queueMicrotask(()=>{if(active)void load()});return()=>{active=false;loadVersion.current++}},[load]);
  const refresh=()=>void load(true);
  const applyCustom=()=>{
    if(!customFrom||!customTo||customFrom>=customTo){setError("开始时间必须早于结束时间");return}
    const from=customDateTimeIso(customFrom);const to=customDateTimeIso(customTo);
    if(new Date(to).getTime()>Date.now()){setError("结束时间不能晚于当前时间");return}
    setAppliedCustom({from,to});setRange("自定义");setCustomOpen(false);setToast("已应用自定义时间范围");
  };
  useGSAP(()=>{
    const motion=gsap.matchMedia();
    motion.add({desktop:"(min-width: 801px)",mobile:"(max-width: 800px)",reduceMotion:"(prefers-reduced-motion: reduce)"},context=>{
      const {desktop,reduceMotion}=context.conditions as {desktop:boolean;mobile:boolean;reduceMotion:boolean};
      if(reduceMotion)return;
      const timeline=gsap.timeline({defaults:{duration:.48,ease:"power3.out"}});
      timeline
        .addLabel("system")
        .from(".dash-toolbar",{autoAlpha:0,y:-8,duration:.34},"system")
        .from(".ops-alert",{autoAlpha:0,y:10,duration:.38},"system+=.08")
        .from(".dash-kpi-row",{autoAlpha:0,y:desktop?18:10,scale:.995},"system+=.12")
        .from(".dash-kpi",{autoAlpha:0,y:desktop?20:12,scale:.975,stagger:.055},"system+=.19")
        .from(".dash-grid > .dash-panel",{autoAlpha:0,y:desktop?24:14,scale:.992,stagger:.055},"system+=.34")
        .fromTo(".cost-chart button i",{scaleY:.08},{scaleY:1,transformOrigin:"bottom",duration:.62,stagger:.014,ease:"power4.out"},"system+=.46")
        .fromTo(".kpi-spark i",{scaleY:.12},{scaleY:1,transformOrigin:"bottom",duration:.46,stagger:.018,ease:"power3.out"},"system+=.42");
      timeline.timeScale(1.7);
    },dashboardRef);
    return()=>motion.revert();
  },{scope:dashboardRef});
  useGSAP(()=>{
    if(!rangeReady.current){rangeReady.current=true;return;}
    const motion=gsap.matchMedia();
    motion.add("(prefers-reduced-motion: no-preference)",()=>{
      gsap.fromTo(".cost-chart button i",{scaleY:.18},{scaleY:1,transformOrigin:"bottom",duration:.52,stagger:.014,ease:"power3.out",overwrite:"auto"});
      gsap.fromTo(".chart-summary strong, .trend-insights b",{autoAlpha:.35,y:5},{autoAlpha:1,y:0,duration:.34,stagger:.035,ease:"power2.out",overwrite:"auto"});
    },dashboardRef);
    return()=>motion.revert();
  },{dependencies:[range,overview?.from],scope:dashboardRef,revertOnUpdate:true});
  if(loading&&!overview)return <section className="analytics-state loading-state"><span className="eyebrow"><i/> 实时数据</span><h3>正在读取仪表盘数据</h3><p>统计当前账户的真实调用、积分和延迟。</p></section>;
  if(error&&!overview)return <section className="analytics-state error"><Icon name="pulse" size={24}/><h3>仪表盘加载失败</h3><p>{error}</p><button onClick={refresh}>重新加载</button></section>;
  if(!overview||!groupStatus)return null;
  const summary=overview.summary;
  const maxTrend=Math.max(...overview.trend.map(item=>Number(item.billed_amount)),0);
  const trend=overview.trend.map(item=>({...item,height:maxTrend>0?Math.max(12,Number(item.billed_amount)/maxTrend*100):12}));
  const peakTrend=overview.trend.reduce<(typeof overview.trend)[number]|null>((peak,item)=>!peak||Number(item.billed_amount)>Number(peak.billed_amount)?item:peak,null);
  const capabilities=overview.capability_distribution.slice(0,4);
  const capabilityGradient=capabilities.map((item,index)=>{const start=capabilities.slice(0,index).reduce((sum,current)=>sum+current.percentage,0);const end=Math.min(100,start+item.percentage);return `${dashboardCapabilityPalette[index%dashboardCapabilityPalette.length]} ${start}% ${end}%`}).join(",");
  const rankItems:DashboardRankingItem[]=dimension==="按令牌"?overview.rankings.api_keys:dimension==="按分组"?overview.rankings.groups:overview.rankings.models;
  const groupShares=new Map(overview.rankings.groups.map(item=>[item.dimension_id,item]));
  const groupsByUsage=[...groupStatus.groups].sort((left,right)=>{
    const percentageDifference=(groupShares.get(right.id)?.percentage??0)-(groupShares.get(left.id)?.percentage??0);
    if(percentageDifference!==0)return percentageDifference;
    return right.sample_count-left.sample_count;
  });
  const attentionGroup=groupStatus.groups.find(group=>group.status==="unavailable"||group.status==="partial");
  const normalGroups=groupStatus.groups.filter(group=>group.status==="normal").length;
  const configuredGroups=groupStatus.groups.filter(group=>group.status!=="unconfigured");
  const sampledGroups=configuredGroups.filter(group=>group.sample_count>0);
  const aggregateAvailability=sampledGroups.length?sampledGroups.reduce((sum,group)=>sum+group.availability,0)/sampledGroups.length:null;
  const averageP95=sampledGroups.filter(group=>group.latency_p95_ms!=null);
  const p95Value=averageP95.length?averageP95.reduce((sum,group)=>sum+(group.latency_p95_ms??0),0)/averageP95.length:null;
  const activityMap=new Map(overview.activity_heatmap.map(item=>[`${item.day_of_week}-${item.hour_of_day}`,item]));
  const maxActivityCost=Math.max(...overview.activity_heatmap.map(item=>Number(item.billed_amount)),0);
  const peakActivity=overview.activity_heatmap.reduce<(typeof overview.activity_heatmap)[number]|null>((peak,item)=>!peak||Number(item.billed_amount)>Number(peak.billed_amount)?item:peak,null);
  const billedActivityHours=overview.activity_heatmap.filter(item=>Number(item.billed_amount)>0).length;
  const updatedAt=overview.updated_at??groupStatus.updated_at;
  return <div className="command-dashboard" ref={dashboardRef}>
    <div className="dash-toolbar"><div className="range-tabs" aria-label="仪表盘时间范围">{(["今日","近 7 天","近 30 天"] as const).map(x=><button className={range===x&&!customOpen?"active":""} onClick={()=>{setRange(x);setAppliedCustom(null);setCustomOpen(false)}} key={x}>{x}</button>)}<button className={range==="自定义"||customOpen?"active":""} onClick={()=>setCustomOpen(!customOpen)} aria-expanded={customOpen}>自定义</button></div>{customOpen&&<div className="custom-range" role="dialog" aria-label="自定义仪表盘时间范围"><div><label><span>开始时间</span><input type="datetime-local" step={1} value={customFrom} max={customTo} onChange={event=>setCustomFrom(event.target.value)} aria-label="开始时间"/></label><i>至</i><label><span>结束时间</span><input type="datetime-local" step={1} value={customTo} min={customFrom} max={dateTimeInputValue()} onChange={event=>setCustomTo(event.target.value)} aria-label="结束时间"/></label><small>Asia/Shanghai · 精确到秒</small></div><button onClick={applyCustom}>应用</button></div>}<span>数据更新于 {formatDateTime(updatedAt)}</span><button className={`refresh-button ${refreshing?"loading":""}`} aria-label="刷新数据" onClick={refresh} disabled={refreshing}><Icon name="request" size={15}/>{refreshing?"刷新中":"刷新"}</button></div>
    {error&&<section className="analytics-inline-error">{error}</section>}
    {alert&&attentionGroup&&<section className="ops-alert"><span className="alert-icon"><Icon name="pulse" size={17}/></span><div><b>{attentionGroup.name}需要关注</b><p>最近 {attentionGroup.sample_count} 次真实请求可用率 {formatPercent(attentionGroup.availability)}，P95 {formatDuration(attentionGroup.latency_p95_ms)}。</p></div><button onClick={()=>onNavigate("status")}>查看状态 <Icon name="arrow" size={14}/></button><button className="alert-close" aria-label="关闭提醒" onClick={()=>setAlert(false)}><Icon name="close" size={16}/></button></section>}
    <section className="dash-kpi-row"><DashboardKpi label="请求总数" icon="layers" value={formatInteger(summary.request_count)} sub={`${formatRate(overview.comparison.request_change_rate)} 较上期`} tone={(overview.comparison.request_change_rate??0)>=0?"good":""} onClick={()=>onNavigate("logs")}/><DashboardKpi label="消耗积分" icon="coin" variant="cyan" value={formatCredits(summary.billed_amount)} sub={`平均 ${formatCredits(summary.average_billed_amount)} / 次`} onClick={()=>onNavigate("logs")}/><DashboardKpi label="成功率" icon="shield" variant="green" value={formatPercent(summary.success_rate)} sub={`${formatInteger(summary.failure_count)} 次失败`} tone={summary.failure_count?"warn":"good"} onClick={()=>onNavigate("logs")}/><DashboardKpi label="响应延迟" icon="bolt" variant="violet" value={formatDuration(summary.latency_p50_ms)} sub={`P95 ${formatDuration(summary.latency_p95_ms)}`} onClick={()=>onNavigate("logs")}/><DashboardKpi label="缓存命中率" icon="pulse" variant="cyan" value={formatPercent(summary.cache_hit_rate)} sub={`${formatInteger(summary.cached_tokens)} 缓存 Token`} tone="good" onClick={()=>onNavigate("logs")}/></section>
    <div className="dash-grid">
      <section className="dash-panel cost-trend-panel span-8"><div className="dash-panel-head"><div><span><Icon name="pulse" size={12}/>数据趋势</span><h3>积分消耗趋势</h3></div><div className="trend-legend"><span className="route-status"><i/>{summary.failure_count?"存在失败请求":"当前区间全部成功"}</span><span><i className="violet"/>积分</span></div></div><div className="trend-overview"><div className="chart-summary"><strong>{formatCredits(summary.billed_amount)}<small> 积分</small></strong><span>{formatRate(overview.comparison.billed_change_rate)}<small> 对比上期</small></span></div><div className="trend-insights"><div><span>本期总计</span><b>{formatCredits(summary.billed_amount)}</b><small>积分</small></div><div><span>峰值时间</span><b>{peakTrend?trendLabel(peakTrend.bucket_start,overview.bucket_size):"—"}</b><small>{peakTrend?`${formatCredits(peakTrend.billed_amount)} 积分`:"暂无数据"}</small></div><div><span>平均每桶</span><b>{overview.trend.length?formatCredits(String(Number(summary.billed_amount)/overview.trend.length)):"0"}</b><small>积分</small></div></div></div>{trend.length?<><div className="cost-chart">{trend.map(item=><button key={item.bucket_start} style={{height:`${item.height}%`}} aria-label={`${trendLabel(item.bucket_start,overview.bucket_size)}，${formatCredits(item.billed_amount)} 积分`} onClick={()=>setToast(`${trendLabel(item.bucket_start,overview.bucket_size)} 消耗 ${formatCredits(item.billed_amount)} 积分`)}><i/><em>{trendLabel(item.bucket_start,overview.bucket_size)}<br/>{formatCredits(item.billed_amount)} 积分</em></button>)}</div><div className="chart-axis"><span>{trendLabel(trend[0].bucket_start,overview.bucket_size)}</span><span>{trendLabel(trend[Math.floor((trend.length-1)/2)].bucket_start,overview.bucket_size)}</span><span>{trendLabel(trend[trend.length-1].bucket_start,overview.bucket_size)}</span></div></>:<div className="analytics-panel-empty">当前区间暂无积分消耗记录</div>}</section>
      <section className="dash-panel balance-health span-4"><div className="balance-visual" aria-hidden="true"><span><Icon name="cube" size={38}/></span><i/><i/></div><div className="dash-panel-head"><div><span><Icon name="wallet" size={12}/>账户余额</span><h3>积分余额</h3></div><button className="detail-dot" onClick={()=>setBalanceOpen(!balanceOpen)} aria-expanded={balanceOpen} aria-label="查看积分来源"><Icon name="more"/></button></div><strong className="big-balance">{formatCredits(balance?.available_credits)}<small> 积分</small></strong><div className="health-line"><span>{balance?"实时钱包余额":"正在读取钱包"}</span><b>{balance?"正常":"加载中"}</b></div><div className="health-track"><i/></div><div className="balance-sources"><button onClick={()=>setBalanceOpen(!balanceOpen)}><span>永久积分</span><b>{formatCredits(balance?.permanent_credits)}</b></button><button onClick={()=>setBalanceOpen(!balanceOpen)}><span>限时积分</span><b>{formatCredits(balance?.expiring_credits)}</b></button><button onClick={()=>onNavigate("wallet")}><span>冻结积分</span><b>{formatCredits(balance?.frozen_credits)}</b><small>查看资金明细</small></button></div>{balanceOpen&&<div className="balance-popover"><b>积分扣除顺序</b><p>系统优先使用最早到期的限时积分，永久积分最后使用。</p><div><span>钱包版本</span><b>{balance?.version??"—"}</b></div><div><span>更新时间</span><b>{balance?.updated_at?new Date(balance.updated_at).toLocaleString("zh-CN"):"—"}</b></div></div>}<div className="balance-cta"><button className="primary" onClick={()=>onNavigate("wallet")}>充值积分</button><button className="ghost" onClick={()=>onNavigate("subscription")}>查看订阅</button></div></section>
      <section className="dash-panel category-panel span-5"><div className="dash-panel-head"><div><span><Icon name="layers" size={12}/>能力分布</span><h3>类型分布</h3></div><span className="panel-filter">按积分消耗</span></div>{capabilities.length?<><div className="category-content"><div className="donut" style={{background:`radial-gradient(circle,var(--theme-panel) 0 55%,transparent 57%),conic-gradient(${capabilityGradient})`}}><div><strong>{formatCredits(summary.billed_amount)}</strong><small>总积分</small></div></div><div className="category-breakdown">{capabilities.map((item,index)=>{const width=`${Math.min(100,item.percentage)}%`;const color=dashboardCapabilityPalette[index%dashboardCapabilityPalette.length];return <div key={item.capability_type}><span><i style={{background:color}}/><b>{capabilityLabels[item.capability_type]??item.capability_type}</b><small>{formatCredits(item.billed_amount)} 积分</small></span><em><i style={{width,background:color}}/></em><strong>{formatPercent(item.percentage)}</strong></div>})}</div></div><div className="category-note"><span>{capabilityLabels[capabilities[0].capability_type]??capabilities[0].capability_type}是当前主要消耗来源</span><b>{formatPercent(capabilities[0].percentage)}</b></div></>:<div className="analytics-panel-empty">当前区间暂无能力消耗数据</div>}</section>
      <section className="dash-panel group-panel span-7"><div className="dash-panel-head"><div><span><Icon name="pulse" size={12}/>服务状态</span><h3>分组运行状态</h3></div><span className="panel-filter">按使用占比</span></div><div className="group-overview"><div><span>运行正常</span><b>{normalGroups} / {groupStatus.groups.length}</b></div><div><span>综合可用率</span><b>{formatPercent(aggregateAvailability)}</b></div><div><span>平均 P95</span><b>{formatDuration(p95Value)}</b></div><div className="warning"><span>需要关注</span><b>{groupStatus.groups.filter(group=>group.status==="partial"||group.status==="unavailable").length} 个</b></div></div><div className="group-table-head"><span>分组 / 倍率</span><span>使用占比</span><span>可用率</span><span>P95 延迟</span><span>状态</span></div><div className="group-list">{groupsByUsage.slice(0,4).map(group=>{const share=groupShares.get(group.id);const tone=group.status==="normal"?"good":"warn";const label=group.status==="normal"?"正常":group.status==="partial"?"部分异常":group.status==="unavailable"?"不可用":group.status==="no_data"?"暂无样本":"未配置";return <div className="group-row" key={group.id}><span className="group-name"><i className={tone}/><b>{group.name}</b><small>{formatMultiplier(group.price_multiplier)}</small></span><span className="group-share"><em><i style={{width:`${Math.min(100,share?.percentage??0)}%`}}/></em><b>{formatPercent(share?.percentage??0)}</b></span><span><b>{group.sample_count?formatPercent(group.availability):"—"}</b><small>{group.sample_count} 次样本</small></span><span><b>{formatDuration(group.latency_p95_ms)}</b><small>P95</small></span><span className={`group-state ${tone}`}>{label}</span></div>})}</div></section>
      <section className="dash-panel cost-rank span-12"><div className="dash-panel-head"><div><span><Icon name="coin" size={12}/>消耗排行</span><h3>积分消耗排行</h3></div><div className="mini-tabs">{["按模型","按令牌","按分组"].map(x=><button className={dimension===x?"active":""} onClick={()=>setDimension(x)} key={x}>{x}</button>)}</div></div>{rankItems.length?<div className="rank-list">{rankItems.map((item,index)=><div className="rank-row" key={`${item.dimension_id}-${index}`}><span className="rank-no">{String(index+1).padStart(2,"0")}</span><span className={`model-logo ${rankColors[index%rankColors.length]}`}>{item.dimension_name.slice(0,1).toUpperCase()}</span><span className="rank-name"><b>{item.dimension_name}</b><i><em style={{width:`${Math.min(100,item.percentage)}%`}}/></i></span><span className="rank-cost"><b>{formatCredits(item.billed_amount)}</b><small>{formatPercent(item.percentage)}</small></span></div>)}</div>:<div className="analytics-panel-empty compact">当前区间暂无排行数据</div>}</section>
      <section className="dash-panel heatmap-panel span-8"><div className="dash-panel-head"><div><span><Icon name="grid" size={12}/>积分时段分布</span><h3>积分消耗热力图</h3></div><div className="heat-legend"><span>低</span><i/><i/><i/><i/><span>高</span></div></div><div className="heatmap"><div className="heat-times"><span>00</span><span>06</span><span>12</span><span>18</span><span>24</span></div>{["周一","周二","周三","周四","周五","周六","周日"].map((day,dayIndex)=><div className="heat-row" key={day}><span>{day}</span>{Array.from({length:24}).map((_,hour)=>{const item=activityMap.get(`${dayIndex+1}-${hour}`);const billed=Number(item?.billed_amount??0);const level=billed&&maxActivityCost?Math.max(1,Math.ceil(billed/maxActivityCost*4)):0;return <i key={hour} className={`h${level}`} title={`${day} ${hour}:00 · ${item?.request_count??0} 次请求 · ${formatCredits(item?.billed_amount??"0")} 积分`}/>})}</div>)}</div><div className="heat-footer"><span>消耗高峰：{peakActivity&&Number(peakActivity.billed_amount)>0?`周${["一","二","三","四","五","六","日"][peakActivity.day_of_week-1]} ${String(peakActivity.hour_of_day).padStart(2,"0")}:00`:"暂无"}</span><span>有消耗时段 {billedActivityHours} 小时</span><span>仅统计当前用户真实结算</span></div></section>
      <section className="dash-panel live-panel span-4"><div className="dash-panel-head"><div><span><Icon name="request" size={12}/>实时流量</span><h3>最近一分钟</h3></div><span className="live-toggle on"><i/>实时</span></div><div className="live-number"><strong>{overview.live_metrics.requests_per_second.toLocaleString("zh-CN",{maximumFractionDigits:3})}</strong><span>req/s</span><i/></div><div className="live-metrics"><div><span>RPM</span><b>{formatInteger(overview.live_metrics.rpm)}</b></div><div><span>TPM</span><b>{formatInteger(overview.live_metrics.tokens_per_minute)}</b></div><div><span>积分 / 分</span><b>{formatCredits(overview.live_metrics.billed_amount_per_minute)}</b></div><div><span>缓存 Token</span><b>{formatInteger(overview.live_metrics.cached_tokens)}</b></div></div><div className="live-source-note">来源：最近一分钟真实调用日志</div></section>
      <section className="dash-panel recent-calls span-12"><div className="dash-panel-head"><div><span><Icon name="clock" size={12}/>请求记录</span><h3>最近调用</h3></div><span className="panel-filter">最近 4 条</span></div>{overview.recent_requests.length?<div className="recent-list">{overview.recent_requests.slice(0,4).map(request=><div className="recent-item" key={request.request_id}><span className="recent-item-icon"><Icon name="layers" size={13}/></span><span className="recent-item-main"><b>{request.public_model}</b><small>{formatShortDate(request.started_at)} {formatTime(request.started_at)} · {request.service_group_name??"未知分组"}</small></span><span className="recent-item-usage"><small>Token</small><b>{formatInteger(request.input_tokens+request.output_tokens)}</b></span><span className="recent-item-cost"><small>{formatDuration(request.duration_ms)}</small><b>{formatCredits(request.billed_amount)} 积分</b></span><span className={request.status==="success"?"request-ok":"request-bad"}><i/>{request.status==="success"?"成功":"失败"}</span></div>)}</div>:<div className="analytics-panel-empty compact">当前区间暂无调用记录</div>}</section>
    </div>
    <Toast message={toast} onClose={()=>setToast("")}/>
  </div>;
}

const capabilityOptions = [
  ["all", "全部"], ["text", "文本"], ["multimodal", "多模态"],
  ["image", "图像"], ["audio", "音频"], ["video", "视频"], ["embedding", "向量"],
] as const;
const capabilityNames = Object.fromEntries(capabilityOptions) as Record<string,string>;
const marketSorts = { "名称排序": "name", "价格从低到高": "price_asc", "价格从高到低": "price_desc" } as const;
 function compactTokens(value:number|null|undefined):string|null{if(value==null)return null;if(value>=1_000_000)return`${value/1_000_000}M`;if(value>=1_000)return`${Math.round(value/1_000)}K`;return String(value)}
function priceText(value:string){const number=Number(value);return Number.isFinite(number)?number.toLocaleString("zh-CN",{maximumFractionDigits:3}):value}
const billingUnitNames:Record<string,string>={request:"次",quantity:"张 / 个",second:"秒",million_tokens:"1M Token",million_characters:"1M 字符"};
/** 用整数缩放计算 Token 倍率展示价，避免积分小数经过浮点运算后出现尾差。 */
function plainDecimalText(value:string){const normalized=value.trim().toLowerCase();if(/^\d+(?:\.\d+)?$/.test(normalized))return normalized;const match=normalized.match(/^(\d+)(?:\.(\d+))?e([+-]?\d+)$/);if(!match)return null;const integer=match[1];const fraction=match[2]??"";const exponent=Number(match[3]);const digits=integer+fraction;const point=integer.length+exponent;if(point<=0)return`0.${"0".repeat(-point)}${digits}`;if(point>=digits.length)return digits+"0".repeat(point-digits.length);return`${digits.slice(0,point)}.${digits.slice(point)}`}
function multiplyRatioText(value:string,ratio:number){const normalized=plainDecimalText(value);if(!normalized)return value;const [integer,fraction=""]=normalized.split(".");const scaled=BigInt(integer+fraction)*BigInt(ratio);const resultScale=fraction.length+4;const digits=scaled.toString().padStart(resultScale+1,"0");const whole=digits.slice(0,-resultScale)||"0";const decimal=digits.slice(-resultScale).replace(/0+$/,"");return decimal?`${whole}.${decimal}`:whole}
 function marketPriceRows(model:ModelMarketItem, recommended=false){
   const input=recommended&&model.recommendedEffectiveInputPrice?model.recommendedEffectiveInputPrice:model.effectiveInputPrice;
   const output=recommended&&model.recommendedEffectiveOutputPrice?model.recommendedEffectiveOutputPrice:model.effectiveOutputPrice;
   const unit=recommended&&model.recommendedEffectiveUnitPrice?model.recommendedEffectiveUnitPrice:model.effectiveUnitPrice;
   if(model.billingType===4){
     if(!model.pricingVersionId)return[{label:"输入",value:input,unit:"1M Token"},{label:"输出",value:output,unit:"1M Token"}];
     return[{label:"输入",value:multiplyRatioText(unit,model.inputTokenRatio),unit:"1M Token"},{label:"输出",value:multiplyRatioText(unit,model.outputTokenRatio),unit:"1M Token"}]
   }
   return[{label:"平台售价",value:unit,unit:billingUnitNames[model.billingUnit]??model.billingUnit}]
 }
type ModelBrandMark = { label: string; tone: string };
const MARKET_PAGE_SIZE = 12;
function modelBrandMark(displayName: string, publicName: string, capabilityType: string): ModelBrandMark {
  const value = `${displayName} ${publicName}`.toLowerCase();
  if (/gpt[-\s]?\d|openai/.test(value)) return { label: "GPT", tone: "gpt" };
  if (/claude|anthropic/.test(value)) return { label: "C", tone: "claude" };
  if (/gemini|gemma|google/.test(value)) return { label: "G", tone: "gemini" };
  if (/grok|xai/.test(value)) return { label: "X", tone: "grok" };
  if (/seedance|seedream|bytedance|jimeng|即梦/.test(value)) return { label: "S", tone: "seed" };
  if (/qwen|tongyi|通义/.test(value)) return { label: "Q", tone: "qwen" };
  if (/deepseek/.test(value)) return { label: "DS", tone: "deepseek" };
  if (capabilityType === "image") return { label: "IMG", tone: "image" };
  if (capabilityType === "video") return { label: "VID", tone: "video" };
  if (capabilityType === "audio") return { label: "AUD", tone: "audio" };
  return { label: "AI", tone: "default" };
}
function MarketLoadingSkeleton() {
  return <section className="model-grid model-grid-loading" aria-label="正在加载模型列表" aria-busy="true">{Array.from({ length: MARKET_PAGE_SIZE }, (_, index) => <article className="model-card skeleton-card" key={`model-skeleton-${index}`}><div className="skeleton-card-head"><span className="skeleton-block skeleton-mark" style={{ animationDelay: `${index * 45}ms` }}/><span className="skeleton-card-copy"><i className="skeleton-block skeleton-title" style={{ animationDelay: `${index * 45 + 30}ms` }}/><i className="skeleton-block skeleton-subtitle" style={{ animationDelay: `${index * 45 + 60}ms` }}/></span></div><i className="skeleton-block skeleton-description" style={{ animationDelay: `${index * 45 + 90}ms` }}/><div className="skeleton-tags"><i className="skeleton-block"/><i className="skeleton-block"/><i className="skeleton-block"/><i className="skeleton-block"/></div><div className="skeleton-price"><i className="skeleton-block"/><i className="skeleton-block"/></div><div className="skeleton-foot"><i className="skeleton-block"/><i className="skeleton-block"/></div></article>)}</section>;
}
function Market() {
  const [groups,setGroups]=useState<PublicServiceGroup[]>([]); const [groupId,setGroupId]=useState("");
  const [query,setQuery]=useState(""); const [provider,setProvider]=useState(""); const [capability,setCapability]=useState("all");
  const [sort,setSort]=useState<keyof typeof marketSorts>("名称排序"); const [page,setPage]=useState(1);
  const [result,setResult]=useState<ModelMarketPage|null>(null);
  const [loading,setLoading]=useState(true); const [error,setError]=useState(""); const [requestVersion,setRequestVersion]=useState(0); const lastRequestVersion=useRef(0); const gridRef=useRef<HTMLElement>(null);
  useEffect(()=>{let active=true;void readPageData("public-service-groups",listPublicServiceGroups,60_000).then(items=>{if(active)setGroups(items)}).catch(reason=>{if(active)setError(reason instanceof Error?reason.message:"服务分组加载失败")});return()=>{active=false}},[]);
  useEffect(()=>{
    let active=true;
    const load=()=>{
      if(!active)return;
      setLoading(true);setError("");
      const force=requestVersion!==lastRequestVersion.current;lastRequestVersion.current=requestVersion;
      void listModelMarket({serviceGroupId:groupId||undefined,page,pageSize:MARKET_PAGE_SIZE,query,provider,capabilityType:capability==="all"?undefined:capability,sort:marketSorts[sort]},force)
        .then(data=>{if(active)setResult(data)})
        .catch(reason=>{if(active)setError(reason instanceof Error?reason.message:"模型市场加载失败")})
        .finally(()=>{if(active)setLoading(false)});
    };
    // Hide old filter results during debounce; cached responses resolve immediately.
    queueMicrotask(()=>{if(active){setLoading(true);setResult(null)}});
    const requestTimer=query||provider?window.setTimeout(load,280):undefined;
    if(requestTimer===undefined)queueMicrotask(load);
    return()=>{active=false;if(requestTimer!==undefined)window.clearTimeout(requestTimer)};
  },[groupId,page,query,provider,capability,sort,requestVersion]);
  const totalPages=Math.max(1,Math.ceil((result?.total??0)/MARKET_PAGE_SIZE));
  const goToPage=(next:number)=>{setPage(Math.min(Math.max(1,next),totalPages));requestAnimationFrame(()=>gridRef.current?.scrollIntoView({behavior:"smooth",block:"start"}))};
  return <PageFrame className="market-page" aria-busy={loading}><section className="market-hero"><div><span className="eyebrow"><i/> {loading?"正在查询模型数据":`${result?.total??0} 个模型`}</span><h2>浏览平台支持的全部模型</h2><p>选择服务分组后，可查看该分组的模型开放范围和最终价格。</p></div></section>
     <section className="market-group-bar"><div className="market-range-controls"><div className="market-range-field"><span>模型范围</span><SelectMenu value={groupId} options={["",...groups.map(group=>group.id)]} onChange={value=>{setGroupId(value);setPage(1)}} getOptionLabel={value=>{if(!value)return"全部模型";const group=groups.find(item=>item.id===value);return group?`${group.name} · ${formatMultiplier(String(group.priceMultiplier))} · ${group.modelCount} 个模型`:"全部模型"}} label="模型范围" className="market-range-select"/></div><div className="search-large market-range-search"><Icon name="search"/><input aria-label="搜索模型" value={query} onChange={e=>{setQuery(e.target.value);setPage(1)}} placeholder="搜索模型名称…"/></div></div><div className="market-price-summary"><span>{groupId?"当前倍率":"默认价格分组"}</span><b>{groupId?(result?.priceMultiplier!=null?formatMultiplier(String(result.priceMultiplier)):"—"):(result?.items.find(item=>item.recommendedServiceGroupName)?.recommendedServiceGroupName??"—")}</b><small>{groupId?"最终售价 = 模型基础售价 × 分组倍率":"未选择分组时，按你可见分组中的最低价格展示"}</small></div></section>
    <div className="filter-row"><div className="chips">{capabilityOptions.map(([value,label])=><button key={value} className={capability===value?"active":""} onClick={()=>{setCapability(value);setPage(1)}}>{label}</button>)}</div><label className="market-provider-filter"><Icon name="search" size={15}/><input aria-label="按厂商筛选" value={provider} onChange={event=>{setProvider(event.target.value);setPage(1)}} placeholder="厂商筛选"/></label><SelectMenu value={sort} options={Object.keys(marketSorts)} onChange={value=>{setSort(value as keyof typeof marketSorts);setPage(1)}} label="模型排序" className="market-sort"/></div>
    {loading&&<><div className="market-loading-note"><span className="market-loading-orbit" aria-hidden="true"><i/><i/></span><span className="market-loading-copy"><b>正在查询模型数据</b><small>{groupId?"读取当前分组的模型、能力与价格":"读取模型能力、价格与可用状态"}</small></span><em>QUERY</em></div><div className="market-loading-progress" aria-hidden="true"><i/></div><MarketLoadingSkeleton/></>}
    {!loading&&error&&<div className="market-state error"><Icon name="request" size={24}/><b>模型市场加载失败</b><span>{error}</span><button onClick={()=>setRequestVersion(version=>version+1)}>重试</button></div>}
     {!loading&&!error&&<section className="model-grid" ref={gridRef}>{result?.items.map(model=>{const tags=[capabilityNames[model.capabilityType]??model.capabilityType,compactTokens(model.contextWindow),model.supportsStreaming?"流式":null,model.supportsTools?"工具调用":null,model.supportsStructuredOutput?"结构化":null].filter((value):value is string=>Boolean(value));const recommended=!groupId&&Boolean(model.recommendedServiceGroupId);const prices=marketPriceRows(model,recommended);const displayGroupName=groupId?model.serviceGroupName:model.recommendedServiceGroupName;const displayMultiplier=groupId?model.priceMultiplier:model.recommendedPriceMultiplier;const brand=modelBrandMark(model.displayName,model.publicName,model.capabilityType);return <article className="model-card" key={model.id}><div className="model-card-top"><div className={`model-brand-mark ${brand.tone}`} aria-label={`${brand.label} 模型`}><span className="model-brand-mark-core">{brand.label}</span><span className="model-brand-mark-sheen" aria-hidden="true"/></div><div><h3>{model.displayName}</h3><span>{model.publicName}</span></div></div><p>{model.chargeDesc??(displayGroupName?`当前价格按“${displayGroupName}”服务分组计算。`:"选择服务分组后，可查看该模型的最终价格。")}</p><div className="tag-row">{tags.map(tag=><span key={tag}>{tag}</span>)}</div><div className={`price-row ${prices.length===1?"single":""}`}>{prices.map(price=><div key={price.label}><span>{price.label} / {price.unit}</span><strong>{priceText(price.value)} 积分</strong></div>)}</div><div className="model-foot"><span><i/> {displayGroupName?`${displayGroupName} · ${formatMultiplier(String(displayMultiplier??"1"))}`:"暂无可见服务分组"}</span><Link href={`/models/${encodeURIComponent(model.id)}${groupId?`?service_group_id=${encodeURIComponent(groupId)}`:""}`}>查看详情 <Icon name="arrow" size={14}/></Link></div></article>})}</section>}
    {!loading&&!error&&result?.items.length===0&&<div className="empty"><div><Icon name="search" size={26}/></div><h3>当前条件下没有匹配模型</h3><p>请切换模型范围，或清除模型、厂商和能力筛选。</p><button onClick={()=>{setQuery("");setProvider("");setCapability("all");setPage(1)}}>清除筛选</button></div>}
    {!loading&&!error&&(result?.total??0)>MARKET_PAGE_SIZE&&<Pagination page={page} pageSize={MARKET_PAGE_SIZE} total={result?.total??0} onChange={goToPage} itemLabel="个模型" ariaLabel="模型列表分页"/>}
    </PageFrame>;
}

function CreateSpace(){return <CreationSpacePage/>;}

function Subscription(){return <SubscriptionPage/>;}

function Wallet({balance,onWalletChange}:{balance:WalletBalance|null;onWalletChange:()=>Promise<void>}){
  const [amount,setAmount]=useState("200"); const [custom,setCustom]=useState("100"); const [confirm,setConfirm]=useState(false); const [order,setOrder]=useState<{id:string;credited_points:string}|null>(null); const [auto,setAuto]=useState(true); const [threshold,setThreshold]=useState("10"); const [autoAmount,setAutoAmount]=useState("10"); const [toast,setToast]=useState(""); const [creatingOrder,setCreatingOrder]=useState(false); const [payingOrder,setPayingOrder]=useState(false); const actualAmount=amount==="custom"?custom:amount;
  const submitRecharge=async()=>{if(creatingOrder)return;setCreatingOrder(true);try{const created=await createRechargeOrder(actualAmount,`web-${crypto.randomUUID()}`);setConfirm(false);setOrder({id:created.id,credited_points:created.credited_points});setToast("订单已创建，请确认模拟支付")}catch(error){setToast(error instanceof Error?error.message:"订单创建失败")}finally{setCreatingOrder(false)}};
  const confirmRecharge=async()=>{if(!order||payingOrder)return;setPayingOrder(true);try{await mockPayRechargeOrder(order.id);await onWalletChange();setOrder(null);setToast("模拟支付成功，积分已入账")}catch(error){setToast(error instanceof Error?error.message:"支付失败")}finally{setPayingOrder(false)}};
  return <PageFrame className="wallet-page">
    <PagePanel className="wallet-overview"><div className="wallet-main"><span>钱包余额</span><strong>{formatCredits(balance?.available_credits)} <small>算力积分</small></strong><p>实时读取账户钱包，可用于 API 调用和创作空间</p><em><Icon name="spark" size={13}/> NEXUS API · 让 AI 创造更大的价值</em></div><div className="wallet-hero-art" aria-hidden="true"><i/><i/><span><Icon name="bolt" size={30}/></span><b>NEXUS API</b></div><div className="wallet-stats"><div><span><Icon name="infinity" size={14}/> 永久积分</span><b>{formatCredits(balance?.permanent_credits)}</b><small>长期有效，可永久使用</small></div><div><span><Icon name="clock" size={14}/> 限时积分</span><b>{formatCredits(balance?.expiring_credits)}</b><small>有效期内及时使用</small></div><div><span><Icon name="shield" size={14}/> 冻结积分</span><b>{formatCredits(balance?.frozen_credits)}</b><small>暂不可用</small></div></div></PagePanel>
    <div className="wallet-grid"><PagePanel className="recharge-card"><div className="panel-head"><div><h3><Icon name="layers" size={18}/> 充值算力积分</h3><p>充值比例：¥2 = 1 算力积分，选择充值金额后立即到账</p></div><span className="safe-pay"><Icon name="check" size={14}/> 极速到账</span></div><div className="recharge-choice-layout"><div className="recharge-options"><label>选择充值金额</label><div className="amount-grid">{["50","100","200","500","1000"].map(x=><button key={x} className={amount===x?"active":""} onClick={()=>setAmount(x)}><b>¥{x}</b><span>{Number(x)/2} 积分</span></button>)}<button className={amount==="custom"?"active":""} onClick={()=>setAmount("custom")}><b>自定义金额</b><span>最低 ¥10</span></button></div>{amount==="custom"&&<label className="custom-amount">自定义金额（元）<input autoFocus type="number" min="10" value={custom} onChange={e=>setCustom(e.target.value)}/><small>预计到账 {Math.max(0,Number(custom)||0)/2} 积分</small></label>}</div><div className="recharge-benefits"><strong>更多算力<br/><em>更强创造力</em></strong><i className="benefit-bars" aria-hidden="true"><b/><b/><b/></i><span><Icon name="check" size={14}/> 安全支付 · 即时到账</span><span><Icon name="check" size={14}/> 支持多种支付方式</span><span><Icon name="check" size={14}/> 企业用户可申请发票</span></div></div><button className="primary pay-button" disabled={!actualAmount||Number(actualAmount)<10} onClick={()=>setConfirm(true)}><Icon name="bolt" size={16}/> 创建充值订单 ¥{actualAmount||"0"} <Icon name="arrow" size={16}/></button></PagePanel>
    <PagePanel className="auto-card"><div className="panel-head"><div><h3>余额保障</h3><p>余额过低时自动充值，确保服务不中断</p></div><button className={`toggle-button ${auto?"on":""}`} role="switch" aria-label="自动充值" aria-checked={auto} onClick={()=>{setAuto(!auto);setToast(auto?"自动充值已关闭":"自动充值已开启")}}><i/></button></div><div className="auto-illustration"><div><Icon name="spark" size={28}/></div><div className="auto-copy"><b>智能守护<br/>让创作永不停歇</b><p>{auto?<>当余额低于阈值时，系统将自动充值，避免因余额不足导致的服务中断。</>:<>自动充值已关闭，余额不足时调用会暂停。</>}</p></div></div><div className="auto-rule-row"><label>触发充值阈值<input type="number" min="10" value={threshold} onChange={e=>setThreshold(e.target.value)}/></label><span>积分时，自动充值</span><label className="auto-amount-field"><span>¥</span><input type="number" min="10" value={autoAmount} onChange={e=>setAutoAmount(e.target.value)}/></label></div><small>正式支付接入后才会启用自动扣款</small></PagePanel></div>
    <WalletRecords onToast={setToast} balance={balance} />
    {confirm&&<DemoDialog title="创建模拟充值订单" eyebrow="验收模式" onClose={()=>{if(!creatingOrder)setConfirm(false)}} actions={<><button className="ghost" disabled={creatingOrder} onClick={()=>setConfirm(false)}>取消</button><button className="primary" disabled={creatingOrder} onClick={submitRecharge}>{creatingOrder?<><i className="spinner"/> 创建中…</>:"创建订单"}</button></>}><dl className="dialog-kv"><div><dt>充值金额</dt><dd>¥{actualAmount}</dd></div><div><dt>预计到账</dt><dd>{Number(actualAmount)/2} 积分</dd></div><div><dt>支付渠道</dt><dd>模拟支付</dd></div></dl><p className="dialog-note">仅用于跑通订单、入账和账本流程，不代表真实付款。</p></DemoDialog>}
    {order&&<DemoDialog title="确认模拟支付" eyebrow="验收模式" onClose={()=>{if(!payingOrder)setOrder(null)}} actions={<><button className="ghost" disabled={payingOrder} onClick={()=>setOrder(null)}>稍后处理</button><button className="primary" disabled={payingOrder} onClick={confirmRecharge}>{payingOrder?<><i className="spinner"/> 入账中…</>:"确认支付"}</button></>}><div className="success-block"><Icon name="wallet" size={24}/><b>{formatCredits(order.credited_points)} 积分</b><span>确认后将写入钱包余额和不可变资金账本。</span></div></DemoDialog>}
    <Toast message={toast} onClose={()=>setToast("")}/>
  </PageFrame>
}
function WalletRecords({onToast,balance}:{onToast:(message:string)=>void;balance:WalletBalance|null}){
  const [type,setType]=useState("全部类型");
  const [page,setPage]=useState(1);
  const [reload,setReload]=useState(0);
  const [result,setResult]=useState<{items:WalletLedgerItem[];total:number;page:number;page_size:number}|null>(null);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState("");
  const pageSize=8;
  useEffect(()=>{
    let active=true;
    listWalletLedger(page,pageSize).then(next=>{if(active){setResult(next);setError("")}}).catch(error=>{if(active){setResult(null);setError(error instanceof Error?error.message:"资金明细加载失败")}}).finally(()=>{if(active)setLoading(false)});
    return()=>{active=false};
  },[balance,page,reload]);
  const shown=(result?.items??[]).filter(item=>type==="全部类型"||(type==="充值"&&item.entry_type==="recharge")||(type==="消费"&&["settle","reserve"].includes(item.entry_type))||(type==="订阅"&&item.source_type==="subscription"));
  const entryLabel=(item:WalletLedgerItem)=>item.entry_type==="recharge"?"在线充值":item.entry_type==="settle"?"模型调用":item.entry_type;
  return <PagePanel className="transaction-panel">
    <div className="panel-head"><div><h3>资金明细</h3><p>实时读取不可变资金账本</p></div><div className="record-actions"><SelectMenu value={type} options={["全部类型","充值","消费","订阅"]} onChange={value=>{setType(value);setPage(1)}} label="资金明细类型" className="record-type-select"/><button className="ghost" onClick={()=>onToast("账单导出功能将在支付模块完成后开放")}>导出账单</button></div></div>
    <div className="transaction-table-head"><span aria-hidden="true"></span><span>交易类型</span><span>请求 ID / 相关说明</span><span>积分变动</span><span>发生时间</span></div>
    <div className="transaction-list">
      {loading&&<div className="small-empty loading-state"><span className="loading-orbit" aria-hidden="true"><i/><i/></span><b>正在加载资金明细</b></div>}
      {!loading&&error&&<div className="small-empty"><Icon name="wallet"/><b>资金明细加载失败</b><span>{error}</span><button onClick={()=>setReload(value=>value+1)}>重试</button></div>}
      {!loading&&!error&&!shown.length&&<div className="small-empty"><Icon name="wallet"/><b>暂无资金流水</b><span>完成充值或 API 调用后会显示在这里。</span></div>}
      {!loading&&!error&&shown.map(item=><div className="transaction" key={item.id}><span className={Number(item.amount)>=0?"plus-t":"minus-t"}><Icon name={Number(item.amount)>=0?"plus":"arrow"}/></span><div className="transaction-kind"><b>{entryLabel(item)}</b></div><div className="transaction-reference"><small>{item.source_id??item.request_id??"资金流水"}</small></div><strong className={Number(item.amount)>=0?"positive":""}>{Number(item.amount)>=0?"+":""}{formatCredits(item.amount)}</strong><time>{new Date(item.created_at).toLocaleString("zh-CN")}</time></div>)}
    </div>
    {!loading&&!error&&<Pagination page={page} pageSize={pageSize} total={result?.total??0} onChange={setPage} itemLabel="条资金流水" ariaLabel="资金明细分页" className="transaction-pagination"/>}
  </PagePanel>
}

function Guide({onNavigate}:{onNavigate:(view:View)=>void}){
  const [lang,setLang]=useState("Node.js"); const [copied,setCopied]=useState(false); const [section,setSection]=useState("概览"); const [toast,setToast]=useState("");
  const snippets:Record<string,string>={"Node.js":`import OpenAI from "openai";\n\nconst client = new OpenAI({\n  baseURL: "${PUBLIC_API_V1_BASE_URL}",\n  apiKey: process.env.NEXUS_API_KEY\n});\n\nconst result = await client.chat.completions.create({\n  model: "gpt-4.1",\n  messages: [{ role: "user", content: "Hello!" }]\n});`,"Python":`from openai import OpenAI\n\nclient = OpenAI(\n  base_url="${PUBLIC_API_V1_BASE_URL}",\n  api_key=os.environ["NEXUS_API_KEY"]\n)\n\nresult = client.chat.completions.create(\n  model="gpt-4.1",\n  messages=[{"role": "user", "content": "Hello!"}]\n)`,"cURL":`curl ${PUBLIC_API_V1_BASE_URL}/chat/completions \\\n  -H "Authorization: Bearer $NEXUS_API_KEY" \\\n  -H "Content-Type: application/json" \\\n  -d '{"model":"gpt-4.1","messages":[{"role":"user","content":"Hello!"}]}'`};
  return <div className="guide-page"><section className="guide-hero"><div><span className="eyebrow"><i/> OpenAI API 兼容</span><h2>5 分钟完成第一次调用</h2><p>保留现有 SDK 和业务代码，仅需更换接口地址与 API 令牌。</p></div><div className="guide-progress">{[["1","创建令牌"],["2","配置环境"],["3","发送请求"]].map((x,i)=><div className={i===0?"active":""} key={x[0]}><span>{i===0?<Icon name="check" size={14}/>:x[0]}</span><b>{x[1]}</b>{i<2&&<i/>}</div>)}</div></section>
    <div className="guide-layout"><aside className="guide-nav">{[["快速开始",["概览","认证方式","发送第一个请求"]],["核心能力",["模型选择","错误处理","流式输出"]],["资源",["API 参考","SDK 下载"]]].map(group=><div className="guide-nav-group" key={group[0] as string}><span>{group[0] as string}</span>{(group[1] as string[]).map(x=><button className={section===x?"active":""} onClick={()=>{setSection(x);if(x==="SDK 下载")setToast("SDK 下载列表已展开")}} key={x}>{x}</button>)}</div>)}</aside>
    <section className="guide-content"><div className="guide-context"><span>当前章节</span><b>{section}</b><p>{section==="概览"?"按照下面三步即可完成首次调用。":section==="认证方式"?"所有模型请求都使用 Bearer API 令牌认证。":section==="发送第一个请求"?"选择语言并复制可运行示例。":section==="模型选择"?"选择服务分组和模型后即可调用，底层资源由系统自动处理。":section==="错误处理"?"重点处理 401、402、429 与 5xx，并使用请求 ID 排查。":section==="流式输出"?"设置 stream: true 后按 SSE 接收增量内容。":section==="API 参考"?"接口与 OpenAI Chat Completions 兼容。":"支持 Node.js、Python 与通用 HTTP 客户端。"}</p></div><article className="guide-step"><span className="step-no">01</span><div><h3>创建 API 令牌</h3><p>完整令牌仅在创建时展示，请保存在服务端环境变量中，不要暴露在浏览器代码。</p><div className="fake-key"><Icon name="code" size={17}/><code>sk-live••••••••••••92F</code><button onClick={()=>onNavigate("developer")}>前往创建 <Icon name="arrow" size={14}/></button></div></div></article>
    <article className="guide-step"><span className="step-no">02</span><div><h3>配置 Base URL</h3><p>NEXUS API 与 OpenAI SDK 完全兼容。</p><div className="endpoint"><span>BASE URL</span><code>{PUBLIC_API_V1_BASE_URL}</code><button onClick={async()=>{await navigator.clipboard?.writeText(PUBLIC_API_V1_BASE_URL);setToast("Base URL 已复制")}}><Icon name="copy" size={15}/>复制</button></div></div></article>
    <article className="guide-step"><span className="step-no">03</span><div><h3>发送第一个请求</h3><p>选择你的开发语言，复制下面的示例即可运行。</p><div className="code-demo"><div className="code-tabs">{Object.keys(snippets).map(x=><button className={lang===x?"active":""} onClick={()=>setLang(x)} key={x}>{x}</button>)}<button className="copy-code" onClick={async()=>{await navigator.clipboard?.writeText(snippets[lang]);setCopied(true);setToast(`${lang} 示例已复制`);setTimeout(()=>setCopied(false),1500)}}><Icon name="copy" size={14}/>{copied?"已复制":"复制代码"}</button></div><pre>{snippets[lang]}</pre></div><div className="success-tip"><Icon name="check" size={17}/><span><b>返回 200 即接入成功</b><small>你可以在“调用日志”中查看本次请求的完整状态。</small></span><button onClick={()=>onNavigate("logs")}>查看调用日志</button></div></div></article></section></div><Toast message={toast} onClose={()=>setToast("")}/></div>
}

function Logs(){
  const [query,setQuery]=useState(""); const [selected,setSelected]=useState<UserRequestLog|null>(null); const [status,setStatus]=useState("全部状态"); const [model,setModel]=useState("全部模型"); const [period,setPeriod]=useState("最近 24 小时"); const [customOpen,setCustomOpen]=useState(false); const [customFrom,setCustomFrom]=useState(dateTimeInputValue(-6,true)); const [customTo,setCustomTo]=useState(dateTimeInputValue()); const [appliedCustom,setAppliedCustom]=useState<{from:string;to:string}|null>(null); const [page,setPage]=useState(1); const [result,setResult]=useState<UserRequestLogPage|null>(null); const [loading,setLoading]=useState(true); const [error,setError]=useState(""); const [toast,setToast]=useState("");
  const pageSize=15;
  const periodValue:"today"|"24h"|"7d"|"30d"|"custom"=period==="今日"?"today":period==="最近 7 天"?"7d":period==="最近 30 天"?"30d":period==="自定义时间"?"custom":"24h";
  const load=useCallback(()=>{setLoading(true);setError("");listUserRequestLogs({page,pageSize,query,status:status==="全部状态"?undefined:status==="成功"?"success":"failed",model:model==="全部模型"?undefined:model,period:periodValue,from:periodValue==="custom"?appliedCustom?.from:undefined,to:periodValue==="custom"?appliedCustom?.to:undefined}).then(setResult).catch(e=>setError(e instanceof Error?e.message:"调用日志加载失败")).finally(()=>setLoading(false))},[page,query,status,model,periodValue,appliedCustom]);
  useEffect(()=>{const timer=window.setTimeout(load,250);return()=>window.clearTimeout(timer)},[load]);
  const applyCustomPeriod=()=>{if(!customFrom||!customTo||customFrom>=customTo){setToast("开始时间必须早于结束时间");return}const from=customDateTimeIso(customFrom);const to=customDateTimeIso(customTo);if(new Date(to).getTime()>Date.now()){setToast("结束时间不能晚于当前时间");return}if(new Date(to).getTime()-new Date(from).getTime()>90*86400000){setToast("自定义时间范围不能超过 90 天");return}setAppliedCustom({from,to});setPeriod("自定义时间");setCustomOpen(false);setPage(1)};
  const items=result?.items??[]; const total=result?.total??0; const models=Array.from(new Set(items.map(item=>item.public_model))).sort();
  const formatTime=(value:string)=>new Date(value).toLocaleString("zh-CN",{month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit",second:"2-digit"});
  const formatRequestDate=(value:string)=>new Date(value).toLocaleDateString("zh-CN",{year:"numeric",month:"2-digit",day:"2-digit"});
  const formatRequestClock=(value:string)=>new Date(value).toLocaleTimeString("zh-CN",{hour:"2-digit",minute:"2-digit",second:"2-digit",hour12:false});
  const formatDuration=(ms:number|null)=>ms==null?"—":ms<1000?`${ms} ms`:`${(ms/1000).toFixed(2)} s`;
  const parseSummary=(value?:string|null)=>{if(!value)return null;try{return JSON.parse(value) as Record<string,unknown>}catch{return null}};
  const summaryLabel=(key:string)=>({model:"模型",stream:"流式",temperature:"温度",top_p:"Top P",max_tokens:"最大 Token",max_completion_tokens:"最大输出 Token",message_count:"消息数",input_items:"输入项",input_chars:"输入字符数",tool_count:"工具数",data_count:"数据项",choice_count:"候选数",status_code:"状态码",input_tokens:"输入 Token",output_tokens:"输出 Token",cached_tokens:"缓存 Token",operation:"操作",content_type:"内容类型",response_format:"返回格式",error_code:"错误码",redacted:"已脱敏"}[key]??key);
  const renderSummary=(value?:string|null)=>{const summary=parseSummary(value);if(!summary)return <span className="log-summary-empty">暂无摘要</span>;return <div className="log-summary-grid">{Object.entries(summary).map(([key,item])=><span key={key}><small>{summaryLabel(key)}</small><b>{typeof item==="boolean"?(item?"是":"否"):typeof item==="object"?JSON.stringify(item):String(item)}</b></span>)}</div>};
  const openDetail=(item:UserRequestLog)=>{setSelected(item);getUserRequestLog(item.request_id).then(setSelected).catch(()=>setToast("详情加载失败，请稍后重试"))};
  const durations=items.flatMap(item=>item.duration_ms==null?[]:[item.duration_ms]);
  const successRate=items.length?Math.round(items.filter(item=>item.status==="success").length/items.length*100):null;
  const averageDuration=durations.length?Math.round(durations.reduce((sum,value)=>sum+value,0)/durations.length):null;
  const pageCredits=items.reduce((sum,item)=>sum+Number(item.billed_amount||0),0);
  const maxDuration=Math.max(1,...durations);
  const statCards=[
    {label:"当前筛选总请求",value:total.toLocaleString("zh-CN"),note:period,icon:"request",tone:"violet",trend:"M2 30 C10 30 12 26 18 26 S28 28 34 22 S44 25 50 18 S60 23 66 14 S76 18 84 6"},
    {label:"调用成功率",value:successRate==null?"—":`${successRate}%`,note:"仅统计当前页",icon:"pulse",tone:"green",trend:"M2 19 C9 16 15 13 21 17 S31 31 38 24 S48 4 56 7 S66 25 73 21 S79 18 84 17"},
    {label:"平均响应时间",value:formatDuration(averageDuration),note:"平台端到端耗时",icon:"clock",tone:"purple",trend:"M2 21 C9 19 15 17 22 20 S32 32 39 26 S48 8 55 9 S66 30 73 25 S80 20 84 21"},
    {label:"当前页消耗积分",value:formatCredits(pageCredits.toString()),note:"真实结算积分",icon:"coin",tone:"blue",trend:"M2 16 C11 13 14 19 21 18 S31 31 39 27 S49 5 56 9 S65 28 73 27 S80 18 84 20"},
  ];
  return <div className="logs-page">
    <header className="log-page-heading"><div className="log-heading-copy"><span className="log-heading-kicker"><Icon name="pulse" size={14}/> API OBSERVABILITY</span><h2>调用日志</h2><p>追踪、调试和分析每一次 API 请求，助力构建更稳定的 AI 应用</p></div><span className="log-heading-slogan">更智能的 API，更无限的可能</span></header>
    <section className="log-stats" aria-label="调用日志统计">
      {statCards.map(card=><article className={`log-stat ${card.tone}`} key={card.label}><span className="log-stat-icon"><Icon name={card.icon} size={16}/></span><div><span>{card.label}</span><strong>{card.value}</strong><small>{card.note}</small></div><svg className="log-stat-trend" viewBox="0 0 86 38" preserveAspectRatio="none" aria-hidden="true"><defs><linearGradient id={`log-trend-${card.tone}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="currentColor" stopOpacity=".28"/><stop offset="1" stopColor="currentColor" stopOpacity="0"/></linearGradient></defs><path className="trend-fill" d={`${card.trend} L84 38 L2 38 Z`} fill={`url(#log-trend-${card.tone})`}/><path d={card.trend}/></svg></article>)}
    </section>
    <section className="panel log-panel">
      <div className="log-toolbar"><div className="log-search"><Icon name="search" size={17}/><input aria-label="搜索调用日志" value={query} onChange={e=>{setQuery(e.target.value);setPage(1)}} placeholder="搜索请求、模型、令牌或分组…"/></div><div className="log-filter-wrap"><Icon name="pulse" size={15}/><SelectMenu value={status} options={["全部状态","成功","失败"]} onChange={value=>{setStatus(value);setPage(1)}} label="日志状态" className="log-filter-select status-filter-select"/></div><div className="log-filter-wrap model"><Icon name="layers" size={15}/><SelectMenu value={model} options={["全部模型",...models]} onChange={value=>{setModel(value);setPage(1)}} label="日志模型" className="log-filter-select model-filter-select"/></div><div className="log-filter-wrap period"><Icon name="clock" size={15}/><SelectMenu value={customOpen?"自定义时间":period} options={["今日","最近 24 小时","最近 7 天","最近 30 天","自定义时间"]} onChange={value=>{if(value==="自定义时间"){setCustomOpen(true);return}setPeriod(value);setAppliedCustom(null);setCustomOpen(false);setPage(1)}} label="日志时间范围" className="log-filter-select period-filter-select"/>{customOpen&&<div className="log-custom-range" role="dialog" aria-label="自定义日志时间范围"><header><div><b>自定义时间</b><small>Asia/Shanghai · 精确到秒</small></div><button type="button" onClick={()=>setCustomOpen(false)} aria-label="取消自定义时间"><Icon name="close" size={15}/></button></header><div><label><span>开始时间</span><input type="datetime-local" step={1} value={customFrom} max={customTo} onChange={event=>setCustomFrom(event.target.value)}/></label><i>至</i><label><span>结束时间</span><input type="datetime-local" step={1} value={customTo} min={customFrom} max={dateTimeInputValue()} onChange={event=>setCustomTo(event.target.value)}/></label></div><button type="button" className="log-custom-apply" onClick={applyCustomPeriod}>应用时间范围</button></div>}</div></div>
      {loading&&<div className="small-empty loading-state"><span className="loading-orbit" aria-hidden="true"><i/><i/></span><b>正在加载调用日志</b><span>仅查询当前登录用户的调用记录。</span></div>}
      {!loading&&error&&<div className="small-empty"><Icon name="request"/><b>调用日志加载失败</b><span>{error}</span><button onClick={load}>重试</button></div>}
      {!loading&&!error&&<>
        <div className="log-table">
          <div className="log-row head"><span><Icon name="clock" size={14}/>请求时间</span><span><Icon name="layers" size={14}/>模型</span><span><Icon name="grid" size={14}/>服务分组</span><span><Icon name="pulse" size={14}/>请求状态</span><span><Icon name="request" size={14}/>Token 用量</span><span><Icon name="bolt" size={14}/>响应时间</span><span><Icon name="coin" size={14}/>消耗积分</span><span><Icon name="more" size={15}/><i className="sr-only">操作</i></span></div>
          {items.map(item=><button className="log-row" key={item.id} onClick={()=>openDetail(item)} aria-label={`查看 ${item.public_model} 调用详情`}>
            <span className="log-time-cell"><b>{formatRequestDate(item.started_at)}</b><time>{formatRequestClock(item.started_at)}</time></span>
            <span className="log-model-cell"><i><Icon name="layers" size={13}/></i><span><b>{item.public_model}</b><small>{item.streaming?"流式调用":"普通调用"}</small></span></span>
            <span className="log-group-cell"><b>{item.service_group_name??"默认分组"}</b></span>
            <span className={item.status==="success"?"log-ok":"log-error"}><i/>{item.status==="success"?"成功":"失败"}</span>
            <span className="log-token-cell"><b>{(item.input_tokens+item.output_tokens).toLocaleString("zh-CN")}</b><small><em>输入 {item.input_tokens.toLocaleString("zh-CN")}</em><em>输出 {item.output_tokens.toLocaleString("zh-CN")}</em>{item.cached_tokens>0&&<em>缓存 {item.cached_tokens.toLocaleString("zh-CN")}</em>}</small></span>
            <span className="log-duration-cell"><span><Icon name="bolt" size={13}/><b>{formatDuration(item.duration_ms)}</b></span><i><em style={{width:`${Math.max(8,Math.round((item.duration_ms??0)/maxDuration*100))}%`}}/></i></span>
            <span className="log-credit-cell"><Icon name="coin" size={14}/><b>{formatCredits(item.billed_amount)}</b><small>积分</small></span>
            <span className="log-action-cell"><Icon name="more" size={17}/></span>
          </button>)}
          {!items.length&&<div className="small-empty"><Icon name="search"/><b>未找到匹配的调用</b><span>请尝试其他请求 ID、模型或令牌名称。</span><button onClick={()=>{setQuery("");setStatus("全部状态");setModel("全部模型");setPage(1)}}>清除筛选</button></div>}
        </div>
      </>}
    </section>
    {!loading&&!error&&<Pagination page={page} pageSize={pageSize} total={total} onChange={setPage} ariaLabel="调用日志分页"/>}
    {selected&&<><button className="drawer-scrim" aria-label="关闭请求详情" onClick={()=>setSelected(null)}/><aside className={`log-drawer ${selected.status}`} role="dialog" aria-modal="true" aria-labelledby="request-detail-title"><header className="log-drawer-head"><div><span className="eyebrow"><i/> {selected.status==="success"?"请求成功":"请求失败"}</span><h3 id="request-detail-title">请求详情</h3></div><button className="drawer-close" onClick={()=>setSelected(null)} aria-label="关闭详情"><Icon name="close"/></button></header><div className="log-request-id"><span>REQUEST ID</span><code>{selected.request_id}</code></div><dl><div><dt>请求时间</dt><dd>{formatTime(selected.started_at)}</dd></div><div><dt>请求模型</dt><dd>{selected.public_model}</dd></div><div><dt>API Key 名称</dt><dd>{selected.api_key_name??"未命名 Key"}</dd></div><div><dt>服务分组</dt><dd>{selected.service_group_name??"默认分组"}</dd></div><div><dt>响应状态</dt><dd className={selected.status==="success"?"positive":"negative"}>{selected.status_code??"—"} {selected.status==="success"?"成功":"失败"}</dd></div><div><dt>输入 / 输出 / 缓存 Token</dt><dd>{selected.input_tokens.toLocaleString("zh-CN")} / {selected.output_tokens.toLocaleString("zh-CN")} / {selected.cached_tokens.toLocaleString("zh-CN")}</dd></div><div><dt>实扣积分</dt><dd>{formatCredits(selected.billed_amount)}</dd></div><div><dt>平台总耗时</dt><dd>{formatDuration(selected.duration_ms)}</dd></div><div><dt>流式 / 重试</dt><dd>{selected.streaming?"是":"否"} / {selected.retry_count} 次</dd></div>{selected.failure_reason&&<div><dt>失败原因</dt><dd className="negative">{selected.failure_reason}</dd></div>}</dl><section className="log-summary-section"><header><h4>请求参数摘要</h4><span>{selected.request_payload_size?`${selected.request_payload_size.toLocaleString("zh-CN")} bytes`:"安全摘要"}</span></header>{renderSummary(selected.request_summary)}</section><section className="log-summary-section"><header><h4>返回结果摘要</h4><span>{selected.response_payload_size?`${selected.response_payload_size.toLocaleString("zh-CN")} bytes`:"安全摘要"}</span></header>{renderSummary(selected.response_summary)}</section><p className="log-drawer-note"><Icon name="shield" size={15}/><span>仅展示脱敏摘要和统计信息，不保存或回显消息正文、API Key、Authorization、Cookie、密码及图片/音频 Base64。</span></p></aside></>}
    <Toast message={toast} onClose={()=>setToast("")}/>
  </div>
}


function Status(){
  const [filter,setFilter]=useState("全部");
  const [query,setQuery]=useState("");
  const [refreshing,setRefreshing]=useState(false);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState("");
  const [data,setData]=useState<UserGroupStatusResponse|null>(null);
  const [selected,setSelected]=useState<UserGroupStatusItem|null>(null);
  const statusLabel:Record<UserGroupStatusItem["status"],string>={normal:"正常",partial:"部分异常",unavailable:"不可用",no_data:"暂无样本",unconfigured:"未配置"};
  const loadVersion=useRef(0);
  const load=useCallback(async(force=true)=>{const version=++loadVersion.current;setRefreshing(true);setError("");try{const next=await getUserGroupStatuses(force);if(version!==loadVersion.current)return;setData(next);setSelected(current=>current?next.groups.find(group=>group.id===current.id)??null:null)}catch(reason){if(version===loadVersion.current)setError(reason instanceof Error?reason.message:"分组状态加载失败")}finally{if(version===loadVersion.current){setLoading(false);setRefreshing(false)}}},[]);
  useEffect(()=>{let active=true;queueMicrotask(()=>{if(active)void load(false)});return()=>{active=false;loadVersion.current++}},[load]);
  if(loading&&!data)return <section className="analytics-state loading-state"><span className="eyebrow"><i/> REAL REQUESTS</span><h3>正在统计分组状态</h3><p>读取每个可见分组最近最多 60 次真实业务请求。</p></section>;
  if(error&&!data)return <section className="analytics-state error"><Icon name="pulse" size={24}/><h3>分组状态加载失败</h3><p>{error}</p><button onClick={()=>void load()}>重新加载</button></section>;
  if(!data)return null;
  const groups=data.groups;
  const visibleGroups=groups.filter(group=>(filter==="全部"||statusLabel[group.status]===filter)&&group.name.toLowerCase().includes(query.toLowerCase()));
  const normalCount=groups.filter(group=>group.status==="normal").length;
  const attentionCount=groups.filter(group=>group.status==="partial"||group.status==="unavailable").length;
  const emptyCount=groups.filter(group=>group.status==="no_data"||group.status==="unconfigured").length;
  return <div className="group-status-page">
    <section className="group-status-hero"><div><span className="eyebrow"><i/> GROUP AVAILABILITY</span><h2>分组运行状态</h2><p>基于每个分组最近最多 {data.sample_limit} 次真实业务请求，统计可用性和平台总耗时。</p></div><button className={`status-refresh ${refreshing?"loading":""}`} onClick={()=>void load()} disabled={refreshing} aria-label="刷新分组状态"><Icon name="request" size={15}/>{refreshing?"刷新中…":"刷新"}</button></section>
    {error&&<section className="analytics-inline-error">{error}</section>}
    <section className="status-summary-grid"><div><span>全部分组</span><b>{groups.length}</b><small>当前用户可见</small></div><div className="normal"><span>运行正常</span><b>{normalCount}</b><small>最近请求状态稳定</small></div><div className="partial"><span>需要关注</span><b>{attentionCount}</b><small>存在失败请求</small></div><div className="offline"><span>暂无判断</span><b>{emptyCount}</b><small>无样本或未配置</small></div></section>
    <section className="status-controls"><div className="status-tabs" aria-label="分组状态筛选">{["全部","正常","部分异常","不可用","暂无样本","未配置"].map(x=><button className={filter===x?"active":""} onClick={()=>setFilter(x)} key={x}>{x}</button>)}</div><label className="status-search"><Icon name="search" size={15}/><input value={query} onChange={event=>setQuery(event.target.value)} placeholder="搜索分组名称" aria-label="搜索分组名称"/></label><span>更新于 {formatDateTime(data.updated_at)}</span></section>
    {visibleGroups.length?<div className="group-status-grid">{visibleGroups.map(group=><button className="group-status-card" key={group.id} onClick={()=>setSelected(group)}><div className="group-status-head"><div><span><b>{group.name}</b><em>{group.sample_count?`${group.sample_count} 次样本`:"暂无样本"}</em></span><p>{group.description??"暂无分组说明"}</p></div><strong className={group.status}>{statusLabel[group.status]}</strong></div><dl><div><dt>可用性 · 最近 {data.sample_limit} 次</dt><dd className={group.status}>{group.sample_count?formatPercent(group.availability):"暂无样本"}</dd></div><div><dt>平均延迟</dt><dd>{group.sample_count?formatDuration(group.average_latency_ms):"—"}</dd></div><div><dt>可用模型</dt><dd>{group.available_model_count}/{group.total_model_count}</dd></div><div><dt>当前倍率</dt><dd>{formatMultiplier(group.price_multiplier)}</dd></div></dl>{group.history.length?<div className="probe-bars" aria-label={`${group.name}最近${group.history.length}次真实业务请求记录`}>{group.history.map((item,index)=><i className={item.status==="success"?"ok":"issue"} title={`${formatDateTime(item.occurred_at)} · HTTP ${item.status_code??"—"} · ${formatDuration(item.duration_ms)}`} key={`${item.occurred_at}-${index}`}/>)}</div>:<div className="probe-bars empty"><span>暂无真实请求样本</span></div>}<div className="probe-axis"><span>过去</span><span>实际 {group.history.length} 次记录</span><span>现在</span></div></button>)}</div>:<section className="status-empty"><Icon name="search" size={25}/><h3>{groups.length?"没有找到匹配的分组":"当前没有可见服务分组"}</h3><p>{groups.length?"尝试更换状态筛选或搜索关键词。":"请联系管理员确认服务分组授权。"}</p>{groups.length&&<button onClick={()=>{setFilter("全部");setQuery("")}}>清除筛选</button>}</section>}
    <section className="status-legend"><div><i className="ok"/><span>真实请求成功</span></div><div><i className="issue"/><span>真实请求失败</span></div><div><i className="neutral"/><span>暂无请求样本</span></div><p>参数校验、鉴权等尚未选中服务分组的失败不会计入这里。</p></section>
    {selected&&<><button className="drawer-scrim" aria-label="关闭分组详情" onClick={()=>setSelected(null)}/><aside className="group-detail-drawer"><button aria-label="关闭分组详情" onClick={()=>setSelected(null)}><Icon name="close"/></button><span className="eyebrow"><i/> 分组详情</span><h3>{selected.name}<em>{formatMultiplier(selected.price_multiplier)}</em></h3><p>{selected.description??"暂无分组说明"}</p><div className={`drawer-health ${selected.status}`}><span>当前状态</span><b>{statusLabel[selected.status]}</b><small>{selected.sample_count?`最近 ${selected.sample_count} 次可用率 ${formatPercent(selected.availability)}`:"暂无真实请求样本，暂不判断可用率"}</small></div><dl><div><dt>平均延迟</dt><dd>{selected.sample_count?formatDuration(selected.average_latency_ms):"—"}</dd></div><div><dt>P95 延迟</dt><dd>{formatDuration(selected.latency_p95_ms)}</dd></div><div><dt>可用模型</dt><dd>{selected.available_model_count}/{selected.total_model_count}</dd></div><div><dt>计费倍率</dt><dd>{formatMultiplier(selected.price_multiplier)}</dd></div><div><dt>最近请求</dt><dd>{formatDateTime(selected.last_request_at)}</dd></div></dl><h4>统计口径</h4><div className="drawer-models"><span>真实请求 {selected.sample_count} 次</span><span>样本上限 {data.sample_limit} 次</span><span>不展示内部路由</span></div><button className="primary" onClick={()=>setSelected(null)}>完成</button></aside></>}
  </div>;
}

function ConsoleApp() {
  const { session, logout } = useAuth();
  const user = session!.user;
  const canAccessAdmin = hasAdminRole(user.roles);
  const [view,setView]=useState<View>("overview"); const [mobile,setMobile]=useState(false); const [globalQuery,setGlobalQuery]=useState(""); const [userMenu,setUserMenu]=useState(false); const [themeChoice,setThemeChoice]=useState<ThemeChoice>("system"); const [systemDark,setSystemDark]=useState(false); const [themeReady,setThemeReady]=useState(false); const [apiKeyPreset,setApiKeyPreset]=useState<{serviceGroupId:string;modelId:string}|null>(null); const [walletBalance,setWalletBalance]=useState<WalletBalance|null>(null); const searchRef=useRef<HTMLInputElement>(null); const contentRef=useRef<HTMLDivElement>(null);
  const refreshWallet=async()=>{const next=await getWalletBalance();setWalletBalance(next)};
  useEffect(()=>{let active=true;queueMicrotask(()=>{void getWalletBalance().then(next=>{if(active)setWalletBalance(next)}).catch(()=>{if(active)setWalletBalance(null)})});return()=>{active=false}},[]);
  useEffect(()=>{let active=true;const media=window.matchMedia("(prefers-color-scheme: dark)");queueMicrotask(()=>{if(!active)return;const saved=window.localStorage.getItem("nexus-theme");setThemeChoice(saved==="light"||saved==="dark"||saved==="system"?saved:"system");setSystemDark(media.matches);setThemeReady(true)});const sync=(event:MediaQueryListEvent)=>setSystemDark(event.matches);media.addEventListener("change",sync);return()=>{active=false;media.removeEventListener("change",sync)}},[]);
  useEffect(()=>{if(!themeReady)return;const resolved=themeChoice==="system"?(systemDark?"dark":"light"):themeChoice;document.documentElement.dataset.theme=resolved;document.documentElement.style.colorScheme=resolved;window.localStorage.setItem("nexus-theme",themeChoice)},[themeChoice,systemDark,themeReady]);
  useEffect(()=>{const keys=(event:KeyboardEvent)=>{if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==="k"){event.preventDefault();searchRef.current?.focus()}if(event.key==="Escape"){setGlobalQuery("");setUserMenu(false)}};window.addEventListener("keydown",keys);return()=>window.removeEventListener("keydown",keys)},[]);
  const switchView=(next:View)=>{setView(next);setMobile(false);setGlobalQuery("");window.scrollTo({top:0,behavior:"smooth"})};
  const signOut=async()=>{try{await logout()}finally{window.location.assign("/login")}};
  useEffect(()=>{queueMicrotask(()=>{const params=new URLSearchParams(window.location.search);const requested=params.get("view") as View|null;if(requested&&["overview","market","create","subscription","wallet","security","developer","systemTokens","guide","logs","status"].includes(requested))setView(requested);const serviceGroupId=params.get("service_group_id");const modelId=params.get("model_id");if(requested==="developer"&&serviceGroupId&&modelId)setApiKeyPreset({serviceGroupId,modelId})})},[]);
  const content={overview:<Overview onNavigate={switchView} balance={walletBalance}/>,market:<Market/>,create:<CreateSpace/>,subscription:<Subscription/>,wallet:<Wallet balance={walletBalance} onWalletChange={refreshWallet}/>,security:<AccountSecurityPage/>,developer:<DeveloperPage preset={apiKeyPreset} onPresetConsumed={()=>setApiKeyPreset(null)}/>,systemTokens:<SystemAccessTokenPage/>,guide:<Guide onNavigate={switchView}/>,logs:<Logs/>,status:<Status/>}[view];
  const quickResults=nav.flatMap(g=>g.items).filter(item=>`${item.label}${titles[item.id].sub}`.toLowerCase().includes(globalQuery.toLowerCase())).slice(0,5);
  const resolvedTheme=themeChoice==="system"?(systemDark?"dark":"light"):themeChoice;
  const themeIcon=resolvedTheme==="dark"?"moon":"sun";
  const nextTheme=resolvedTheme==="dark"?"light":"dark";
  useGSAP(()=>{
    const motion=gsap.matchMedia();
    motion.add("(prefers-reduced-motion: no-preference)",()=>{
      if(view==="overview"||!contentRef.current)return;
      const clean="transform,opacity,visibility";
      const timeline=gsap.timeline({defaults:{ease:"power3.out",duration:.32}});
      timeline.fromTo(contentRef.current,{autoAlpha:.55,y:8},{autoAlpha:1,y:0,duration:.28,clearProps:clean});
      switch(view){
        case "market":
          timeline.from(".market-hero > *",{autoAlpha:0,y:18,duration:.62,stagger:.1,clearProps:clean},"<.05")
            .from(".filter-row > *",{autoAlpha:0,y:10,duration:.46,stagger:.06,clearProps:clean},"<.2")
            .from(".model-card",{autoAlpha:0,y:22,scale:.975,duration:.66,stagger:.075,clearProps:clean},"<.1");
          break;
        case "create":
          timeline.from(".studio-tabs > button",{autoAlpha:0,y:-9,duration:.46,stagger:.06,clearProps:clean},"<")
            .from(".chat-shell > *, .studio-grid > *",{autoAlpha:0,y:20,scale:.99,duration:.66,stagger:.1,clearProps:clean},"<.16")
            .from(".conversation > *, .art-grid .art",{autoAlpha:0,y:12,scale:.975,duration:.5,stagger:.065,clearProps:clean},"<.14");
          break;
        case "subscription":
          timeline.from(".current-plan > *",{autoAlpha:0,y:16,duration:.58,stagger:.075,clearProps:clean},"<.04")
            .from(".subscription-note",{autoAlpha:0,y:10,duration:.44,clearProps:clean},"<.2")
            .from(".subscription-plans .plan",{autoAlpha:0,y:24,scale:.98,duration:.66,stagger:.09,clearProps:clean},"<.12")
            .from(".subscription-detail",{autoAlpha:0,y:18,duration:.58,clearProps:clean},"<.18");
          break;
        case "wallet":
          timeline.from(".wallet-overview > *",{autoAlpha:0,y:16,duration:.6,stagger:.09,clearProps:clean},"<.04")
            .from(".wallet-grid > *",{autoAlpha:0,y:22,scale:.985,duration:.66,stagger:.1,clearProps:clean},"<.14")
            .from(".transaction-panel",{autoAlpha:0,y:18,duration:.58,clearProps:clean},"<.2")
            .from(".transaction",{autoAlpha:0,x:-12,duration:.46,stagger:.06,clearProps:clean},"<.12");
          break;
        case "developer":
          timeline.from(".token-summary article",{autoAlpha:0,y:12,scale:.99,stagger:.045,clearProps:clean},"<.03")
            .from(".token-manager",{autoAlpha:0,y:14,clearProps:clean},"<.08");
          if(gsap.utils.toArray(".token-row:not(.token-head)").length>0){
            timeline.from(".token-row:not(.token-head)",{autoAlpha:0,x:-12,duration:.44,stagger:.055,clearProps:clean},"<.12");
          }
          break;
        case "security":
          timeline.from(".security-hero, .security-overview, .security-layout",{autoAlpha:0,y:12,stagger:.055,clearProps:clean},"<.04");
          break;
        case "guide":
          timeline.from(".guide-hero > *",{autoAlpha:0,y:17,duration:.6,stagger:.1,clearProps:clean},"<.04")
            .from(".guide-progress > div",{autoAlpha:0,x:-12,duration:.48,stagger:.08,clearProps:clean},"<.16")
            .from(".guide-nav",{autoAlpha:0,x:-18,duration:.6,clearProps:clean},"<.1")
            .from(".guide-context, .guide-step",{autoAlpha:0,y:22,duration:.62,stagger:.085,clearProps:clean},"<.08");
          break;
        case "logs":
          timeline.from(".log-stats article",{autoAlpha:0,y:18,scale:.98,duration:.58,stagger:.075,clearProps:clean},"<.04")
            .from(".log-panel",{autoAlpha:0,y:20,duration:.62,clearProps:clean},"<.14")
            .from(".log-toolbar > *",{autoAlpha:0,y:9,duration:.44,stagger:.05,clearProps:clean},"<.1")
            .from(".log-row:not(.head)",{autoAlpha:0,x:-12,duration:.45,stagger:.055,clearProps:clean},"<.1");
          break;
        case "status":
          timeline.from(".group-status-hero > *",{autoAlpha:0,y:17,duration:.6,stagger:.1,clearProps:clean},"<.04")
            .from(".status-summary-grid > div",{autoAlpha:0,y:18,scale:.98,duration:.58,stagger:.07,clearProps:clean},"<.15")
            .from(".status-controls > *",{autoAlpha:0,y:9,duration:.44,stagger:.055,clearProps:clean},"<.14")
            .from(".group-status-card",{autoAlpha:0,y:22,scale:.98,duration:.62,stagger:.06,clearProps:clean},"<.1")
            .from(".status-legend",{autoAlpha:0,y:14,duration:.52,clearProps:clean},"<.16");
          break;
      }
      timeline.timeScale(1.65);
    },contentRef);
    return()=>motion.revert();
  },{dependencies:[view],scope:contentRef,revertOnUpdate:true});
  return <main className="app-shell"><aside className={`sidebar ${mobile?"open":""}`}><div className="brand"><span className="brand-mark"><BrandMark /></span><b>NEXUS<em>API</em></b><button className="mobile-close" onClick={()=>setMobile(false)} aria-label="关闭菜单"><Icon name="close"/></button></div><nav>{nav.map(g=><div className="nav-group" key={g.section}><small>{g.section}</small>{g.items.map(item=><button key={item.id} className={view===item.id?"active":""} onClick={()=>switchView(item.id)}><Icon name={item.icon}/><span>{item.label}</span>{item.id==="status"&&<i className="nav-live"/>}</button>)}</div>)}</nav><div className="sidebar-foot"><div className="docs-callout"><span><Icon name="code"/></span><b>第一次接入？</b><p>5 分钟完成 API 调用</p><button onClick={()=>switchView("guide")}>阅读快速指南 <Icon name="arrow" size={14}/></button></div><div className="user"><div className="avatar">{user.name.slice(0,1).toUpperCase()}</div><span><b>{user.name}</b><small>{user.email}</small></span><button className="dot-button" aria-label="用户菜单" aria-expanded={userMenu} onClick={()=>{setUserMenu(!userMenu)}}><Icon name="more"/></button>{userMenu&&<div className="user-popover"><button onClick={()=>switchView("subscription")}>订阅与权益</button><button onClick={()=>switchView("wallet")}>钱包与账单</button><button onClick={signOut}>退出登录</button></div>}</div></div></aside>{mobile&&<button className="backdrop" onClick={()=>setMobile(false)} aria-label="关闭导航"/>}<section className="main-area"><header className={`topbar ${view==="logs"?"logs-topbar":""} ${view==="overview"?"overview-topbar":""} ${view==="market"?"market-topbar":""}`}><button className="menu-button" onClick={()=>setMobile(true)} aria-label="打开菜单"><Icon name="menu"/></button><div className="topbar-page-copy"><h1>{titles[view].title}</h1><p>{titles[view].sub}</p></div><div className="top-actions"><div className="top-search"><Icon name="search" size={17}/><input ref={searchRef} aria-label="全局搜索" placeholder="搜索页面、功能…" value={globalQuery} onChange={e=>setGlobalQuery(e.target.value)}/><kbd>⌘ K</kbd>{globalQuery&&<div className="global-results">{quickResults.length?quickResults.map(item=><button key={item.id} onClick={()=>switchView(item.id)}><Icon name={item.icon} size={15}/><span><b>{item.label}</b><small>{titles[item.id].sub}</small></span><Icon name="arrow" size={13}/></button>):<div>没有匹配的页面</div>}</div>}</div>{canAccessAdmin&&<Link href="/admin" className="admin-quick-link" aria-label="进入管理后台" title="进入管理后台"><Icon name="monitor" size={17}/></Link>}<button className="theme-toggle-button" onClick={()=>setThemeChoice(nextTheme)} aria-label={`切换为${nextTheme==="dark"?"暗色":"亮色"}模式`} title={`切换为${nextTheme==="dark"?"暗色":"亮色"}模式`}><Icon name={themeIcon} size={17}/></button><AnnouncementBell/><button className="credits" onClick={()=>switchView("wallet")}><Icon name="coin" size={17}/><span>{formatCredits(walletBalance?.available_credits)}</span><b>充值</b></button></div></header><div className={`content ${view==="logs"?"logs-content":""} ${view==="overview"?"overview-content":""} ${view==="market"?"market-content":""}`} ref={contentRef}>{content}</div></section></main>;
}

export default function Home() {
  const {session}=useAuth();
  return <ProtectedRoute><ConsoleApp key={session?.user.id}/></ProtectedRoute>;
}
