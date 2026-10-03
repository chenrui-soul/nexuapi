"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  adminErrorMessage,
  deleteTimePricingRule,
  listModels,
  listTimePricingRules,
  saveTimePricingRule,
  updateTimePricingRuleStatus,
  type Model,
  type TimePricingRule,
  type TimePricingRuleInput,
} from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import {
  AdminPageHeader,
  ConfirmDialog,
  Drawer,
  EmptyState,
  ErrorState,
  Field,
  IconButton,
  LoadingState,
  PrimaryButton,
  SearchBox,
  SecondaryButton,
  SelectFilter,
  StatusActionButton,
  StatusPill,
  Toast,
  ToggleField,
} from "./AdminUi";

const weekdayOptions = [
  { value: 1, short: "一", label: "周一" },
  { value: 2, short: "二", label: "周二" },
  { value: 3, short: "三", label: "周三" },
  { value: 4, short: "四", label: "周四" },
  { value: 5, short: "五", label: "周五" },
  { value: 6, short: "六", label: "周六" },
  { value: 7, short: "日", label: "周日" },
] as const;

type RuleForm = {
  name: string;
  multiplier: string;
  daysOfWeek: number[];
  startTime: string;
  endTime: string;
  enabled: boolean;
  modelIds: string[];
  version: number;
};

function emptyForm(): RuleForm {
  return {
    name: "",
    multiplier: "1.2",
    daysOfWeek: [1, 2, 3, 4, 5],
    startTime: "18:00",
    endTime: "22:00",
    enabled: true,
    modelIds: [],
    version: 0,
  };
}

function formFromRule(rule: TimePricingRule): RuleForm {
  return {
    name: rule.name,
    multiplier: String(rule.multiplier),
    daysOfWeek: [...rule.days_of_week],
    startTime: rule.start_time.slice(0, 5),
    endTime: rule.end_time.slice(0, 5),
    enabled: rule.enabled,
    modelIds: rule.models.map(model => model.id),
    version: rule.version,
  };
}

function weekdayText(days: number[]): string {
  const sorted = [...days].sort((left, right) => left - right);
  if (sorted.join(",") === "1,2,3,4,5") return "工作日";
  if (sorted.join(",") === "6,7") return "周末";
  if (sorted.length === 7) return "每天";
  return sorted.map(day => weekdayOptions.find(option => option.value === day)?.label ?? day).join("、");
}

function multiplierText(value: string | number): string {
  const number = Number(value);
  return Number.isFinite(number)
    ? `${number.toLocaleString("zh-CN", { maximumFractionDigits: 3 })}x`
    : "1x";
}

function timeRangeText(rule: Pick<TimePricingRule, "start_time" | "end_time">): string {
  const start = rule.start_time.slice(0, 5);
  const end = rule.end_time.slice(0, 5);
  return `${start} 至 ${end}${start > end ? " · 跨天" : ""}`;
}

function capabilityLabel(value: string): string {
  return ({ text: "文本", multimodal: "多模态", embedding: "向量", image: "图像", video: "视频", audio: "音频" } as Record<string, string>)[value] ?? value;
}

/** 时段倍率是跨模型配置，因此使用独立页面统一维护，不进入单模型价格抽屉。 */
export function TimePricingAdminPage() {
  const [rules, setRules] = useState<TimePricingRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<TimePricingRule | null | undefined>(undefined);
  const [form, setForm] = useState<RuleForm>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");
  const [modelQuery, setModelQuery] = useState("");
  const [modelCapability, setModelCapability] = useState("all");
  const [modelOptions, setModelOptions] = useState<Model[]>([]);
  const [modelTotal, setModelTotal] = useState(0);
  const [modelLoading, setModelLoading] = useState(false);
  const [statusTarget, setStatusTarget] = useState<TimePricingRule | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<TimePricingRule | null>(null);
  const [mutationBusy, setMutationBusy] = useState(false);
  const [toast, setToast] = useState<{ message: string; tone: "success" | "error" }>({ message: "", tone: "success" });

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setRules(await listTimePricingRules());
    } catch (loadError) {
      setError(adminErrorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    if (editing === undefined) return;
    const timer = window.setTimeout(async () => {
      setModelLoading(true);
      try {
        const result = await listModels({ page: 1, pageSize: 100, query: modelQuery, status: "active", capabilityType: modelCapability });
        setModelOptions(result.items);
        setModelTotal(result.total);
      } catch (loadError) {
        setFormError(adminErrorMessage(loadError));
      } finally {
        setModelLoading(false);
      }
    }, 220);
    return () => window.clearTimeout(timer);
  }, [editing, modelQuery, modelCapability]);

  const selectedModels = useMemo(() => {
    const byId = new Map<string, { id: string; public_name: string; display_name: string; capability_type: string }>();
    editing?.models.forEach(model => byId.set(model.id, model));
    modelOptions.forEach(model => byId.set(model.id, model));
    return form.modelIds.map(id => byId.get(id)).filter((model): model is NonNullable<typeof model> => Boolean(model));
  }, [editing, form.modelIds, modelOptions]);

  const activeCount = rules.filter(rule => rule.enabled).length;
  const coveredModelCount = new Set(rules.flatMap(rule => rule.models.map(model => model.id))).size;

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm());
    setFormError("");
    setModelQuery("");
    setModelCapability("all");
  };

  const openEdit = (rule: TimePricingRule) => {
    setEditing(rule);
    setForm(formFromRule(rule));
    setFormError("");
    setModelQuery("");
    setModelCapability("all");
  };

  const closeDrawer = () => {
    if (saving) return;
    setEditing(undefined);
    setFormError("");
  };

  const toggleDay = (day: number) => {
    setForm(current => ({
      ...current,
      daysOfWeek: current.daysOfWeek.includes(day)
        ? current.daysOfWeek.filter(value => value !== day)
        : [...current.daysOfWeek, day].sort((left, right) => left - right),
    }));
  };

  const toggleModel = (modelId: string) => {
    setForm(current => ({
      ...current,
      modelIds: current.modelIds.includes(modelId)
        ? current.modelIds.filter(id => id !== modelId)
        : [...current.modelIds, modelId],
    }));
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const multiplier = Number(form.multiplier);
    if (!form.daysOfWeek.length) return setFormError("至少选择一个适用星期");
    if (!form.modelIds.length) return setFormError("至少选择一个适用模型");
    if (!Number.isFinite(multiplier) || multiplier < 1 || multiplier > 100) return setFormError("时段倍率必须在 1 至 100 之间");
    if (form.startTime === form.endTime) return setFormError("开始时间和结束时间不能相同");
    setSaving(true);
    setFormError("");
    const input: TimePricingRuleInput = {
      name: form.name.trim(),
      multiplier: form.multiplier,
      days_of_week: [...form.daysOfWeek].sort((left, right) => left - right),
      start_time: form.startTime,
      end_time: form.endTime,
      enabled: form.enabled,
      model_ids: form.modelIds,
      version: form.version,
    };
    try {
      await saveTimePricingRule(input, editing?.id);
      setToast({ message: editing ? "时段倍率规则已更新" : "时段倍率规则已创建", tone: "success" });
      setEditing(undefined);
      await load();
    } catch (saveError) {
      setFormError(adminErrorMessage(saveError));
    } finally {
      setSaving(false);
    }
  };

  const confirmStatus = async () => {
    if (!statusTarget) return;
    setMutationBusy(true);
    try {
      await updateTimePricingRuleStatus(statusTarget.id, !statusTarget.enabled, statusTarget.version);
      setToast({ message: statusTarget.enabled ? "规则已停用" : "规则已启用", tone: "success" });
      setStatusTarget(null);
      await load();
    } catch (mutationError) {
      setToast({ message: adminErrorMessage(mutationError), tone: "error" });
    } finally {
      setMutationBusy(false);
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    setMutationBusy(true);
    try {
      await deleteTimePricingRule(deleteTarget.id, deleteTarget.version);
      setToast({ message: "时段倍率规则已删除", tone: "success" });
      setDeleteTarget(null);
      await load();
    } catch (mutationError) {
      setToast({ message: adminErrorMessage(mutationError), tone: "error" });
    } finally {
      setMutationBusy(false);
    }
  };

  return <section className="time-pricing-page">
    <AdminPageHeader
      eyebrow="TIME PRICING"
      title="计费设置"
      description="为指定模型配置循环时段倍率。新请求按模型基础用量积分、时段倍率和服务分组倍率依次计算。"
      action={<PrimaryButton onClick={openCreate}><AdminIcon name="plus" size={15}/>新增时段规则</PrimaryButton>}
    />

    <div className="time-pricing-summary admin-animate">
      <article><span>启用规则</span><b>{activeCount}</b><small>共 {rules.length} 条配置</small></article>
      <article><span>覆盖模型</span><b>{coveredModelCount}</b><small>按模型独立生效</small></article>
      <article><span>业务时区</span><b>UTC+8</b><small>Asia/Shanghai</small></article>
      <div><AdminIcon name="wallet" size={18}/><p><b>最终实扣积分</b><span>模型基础用量积分 × 时段倍率 × 服务分组倍率</span></p></div>
    </div>

    {loading ? <LoadingState label="正在读取时段计费规则…"/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !rules.length ? (
      <EmptyState title="还没有时段倍率规则" description="未配置时所有模型的时段倍率均为 1x，不改变现有计费结果。"/>
    ) : (
      <div className="time-pricing-list admin-animate">
        {rules.map(rule => <article className="time-rule-card" key={rule.id}>
          <header>
            <div className="time-rule-name"><span><AdminIcon name="wallet" size={16}/></span><div><b>{rule.name}</b><small>更新于 {new Date(rule.updated_at).toLocaleString("zh-CN")}</small></div></div>
            <div className="time-rule-card-actions"><StatusPill value={rule.enabled ? "enabled" : "disabled"}/><div className="time-rule-actions"><IconButton icon="edit" label="编辑规则" onClick={() => openEdit(rule)}/><StatusActionButton active={rule.enabled} activeLabel="停用规则" inactiveLabel="启用规则" onClick={() => setStatusTarget(rule)}/><IconButton icon="close" label="删除规则" danger onClick={() => setDeleteTarget(rule)}/></div></div>
          </header>
          <dl>
            <div><dt>循环时段</dt><dd className="time-range">{timeRangeText(rule)}</dd><small>结束时刻不计入</small></div>
            <div><dt>适用星期</dt><dd><span className="weekday-badges">{rule.days_of_week.map(day => <span key={day}>{weekdayOptions.find(option => option.value === day)?.short}</span>)}</span></dd><small>{weekdayText(rule.days_of_week)}</small></div>
            <div><dt>适用模型</dt><dd>{rule.models.length} 个模型</dd><small>{rule.models.slice(0, 3).map(model => model.display_name).join("、")}{rule.models.length > 3 ? ` 等 ${rule.models.length} 个` : ""}</small></div>
            <div className="time-rule-price"><dt>时段倍率</dt><dd>{multiplierText(rule.multiplier)}</dd><small>计入最终实扣积分</small></div>
          </dl>
        </article>)}
      </div>
    )}

    <Drawer
      open={editing !== undefined}
      className="time-pricing-drawer"
      title={editing ? `编辑 · ${editing.name}` : "新增时段倍率规则"}
      description="规则保存并启用后立即循环生效。请求开始后会固定倍率快照，不受后续修改影响。"
      onClose={closeDrawer}
      footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="time-pricing-form" disabled={saving}>{saving ? "保存中…" : editing ? "保存修改" : "创建规则"}</PrimaryButton></>}
    >
      <form id="time-pricing-form" className="time-pricing-form" onSubmit={submit}>
        {formError && <div className="pricing-error"><AdminIcon name="warning" size={16}/><span>{formError}</span></div>}
        <section className="time-pricing-form-section">
          <header><div><span>RULE</span><h3>规则与倍率</h3></div><small>页面最多显示三位小数，后端保留完整十进制精度</small></header>
          <div className="admin-form-grid">
            <Field label="规则名称" required><input required maxLength={120} value={form.name} onChange={event => setForm(current => ({ ...current, name: event.target.value }))} placeholder="例如：工作日晚高峰"/></Field>
            <Field label="时段倍率" required hint="1 表示不加价，建议按业务需要谨慎设置"><input type="number" required min="1" max="100" step="0.001" value={form.multiplier} onChange={event => setForm(current => ({ ...current, multiplier: event.target.value }))}/></Field>
          </div>
          <ToggleField label="创建后立即启用" description="停用时保留配置，但新请求不再匹配" checked={form.enabled} onChange={enabled => setForm(current => ({ ...current, enabled }))}/>
        </section>

        <section className="time-pricing-form-section">
          <header><div><span>SCHEDULE</span><h3>循环时间</h3></div><small>统一使用 Asia/Shanghai</small></header>
          <div className="weekday-selector" role="group" aria-label="适用星期">
            {weekdayOptions.map(option => <button type="button" className={form.daysOfWeek.includes(option.value) ? "selected" : ""} aria-pressed={form.daysOfWeek.includes(option.value)} onClick={() => toggleDay(option.value)} key={option.value}><b>{option.short}</b><span>{option.label}</span></button>)}
          </div>
          <div className="admin-form-grid time-range-fields">
            <Field label="开始时间" required><input type="time" required value={form.startTime} onChange={event => setForm(current => ({ ...current, startTime: event.target.value }))}/></Field>
            <Field label="结束时间" required hint={form.startTime > form.endTime ? "当前为跨天时段，结束时间属于次日" : "结束时刻不计入规则范围"}><input type="time" required value={form.endTime} onChange={event => setForm(current => ({ ...current, endTime: event.target.value }))}/></Field>
          </div>
          <div className="time-pricing-preview"><span>{weekdayText(form.daysOfWeek)}</span><b>{form.startTime || "00:00"} 至 {form.endTime || "00:00"}</b><em>{multiplierText(form.multiplier)}</em></div>
        </section>

        <section className="time-pricing-form-section">
          <header><div><span>MODELS</span><h3>适用模型</h3></div><small>已选择 {form.modelIds.length} 个</small></header>
          <div className="time-model-toolbar">
            <SearchBox value={modelQuery} onChange={setModelQuery} placeholder="按模型名称或供应商搜索"/>
            <SelectFilter label="模型能力筛选" value={modelCapability} onChange={setModelCapability}>
              <option value="all">全部能力</option><option value="text">文本</option><option value="multimodal">多模态</option><option value="image">图像</option><option value="video">视频</option><option value="audio">音频</option><option value="embedding">向量</option>
            </SelectFilter>
          </div>
          <div className="time-model-result-meta"><span>当前显示 {modelOptions.length} / {modelTotal} 个启用模型</span><small>{modelTotal > modelOptions.length ? "继续输入关键词可精确查找其他模型" : "勾选后立即加入已选模型"}</small></div>
          {selectedModels.length > 0 && <div className="selected-time-models">{selectedModels.map(model => <button type="button" onClick={() => toggleModel(model.id)} key={model.id}><span>{model.display_name}</span><small>{model.public_name}</small><AdminIcon name="close" size={12}/></button>)}</div>}
          <div className="time-model-picker" aria-busy={modelLoading}>
            {modelLoading ? <p>正在搜索模型…</p> : !modelOptions.length ? <p>没有找到符合条件的启用模型</p> : modelOptions.map(model => <label key={model.id}><input type="checkbox" checked={form.modelIds.includes(model.id)} onChange={() => toggleModel(model.id)}/><i/><div><b>{model.display_name}</b><code>{model.public_name}</code></div><span>{capabilityLabel(model.capability_type)}</span></label>)}
          </div>
        </section>
      </form>
    </Drawer>

    <ConfirmDialog open={Boolean(statusTarget)} title={`${statusTarget?.enabled ? "停用" : "启用"}规则“${statusTarget?.name ?? ""}”？`} description={statusTarget?.enabled ? "停用后，新请求立即不再匹配该规则；已经进入平台的请求仍按原倍率结算。" : "启用前后端会再次检查所选模型是否存在重叠时段；冲突时不会保存。"} confirmLabel={statusTarget?.enabled ? "确认停用" : "确认启用"} busy={mutationBusy} onCancel={() => setStatusTarget(null)} onConfirm={() => void confirmStatus()}/>
    <ConfirmDialog open={Boolean(deleteTarget)} title={`删除规则“${deleteTarget?.name ?? ""}”？`} description="删除后配置无法恢复。历史请求仍保留规则名称、倍率和计价时间快照，不会改变已结算金额。" confirmLabel="确认删除" danger busy={mutationBusy} onCancel={() => setDeleteTarget(null)} onConfirm={() => void confirmDelete()}/>
    <Toast message={toast.message} tone={toast.tone} onClose={() => setToast(current => ({ ...current, message: "" }))}/>
  </section>;
}
