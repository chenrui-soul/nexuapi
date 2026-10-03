"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  adminErrorMessage,
  archiveSubscriptionPlan,
  formatAdminTime,
  formatDashboardAmount,
  getSubscriptionPlanOptions,
  listSubscriptionPlans,
  saveSubscriptionPlan,
  type AdminSubscriptionPlan,
  type AdminSubscriptionPlanInput,
  type AdminSubscriptionPlanOptions,
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
  StatusPill,
  Toast,
  ToggleField,
} from "./AdminUi";

type PlanForm = Omit<AdminSubscriptionPlanInput, "features"> & { featuresText: string };

const emptyForm: PlanForm = {
  code: "",
  name: "",
  description: null,
  billing_cycle: "monthly",
  price: "0",
  included_credits: "0",
  concurrency_limit: null,
  featuresText: "",
  status: "draft",
  display_order: 10,
  featured: false,
  service_group_ids: [],
  model_ids: [],
  version: 0,
};

function toForm(plan: AdminSubscriptionPlan): PlanForm {
  return {
    code: plan.code,
    name: plan.name,
    description: plan.description,
    billing_cycle: plan.billing_cycle,
    price: String(plan.price),
    included_credits: String(plan.included_credits),
    concurrency_limit: plan.concurrency_limit,
    featuresText: plan.features.join("\n"),
    status: plan.status,
    display_order: plan.display_order,
    featured: plan.featured,
    service_group_ids: plan.service_groups.map(group => group.id),
    model_ids: plan.models.map(model => model.id),
    version: plan.version,
  };
}

function cycleLabel(value: AdminSubscriptionPlan["billing_cycle"]): string {
  return ({ monthly: "月付", quarterly: "季付", yearly: "年付", one_time: "一次性" })[value];
}

function capabilityLabel(value: string): string {
  return ({ text: "文本", multimodal: "多模态", embedding: "向量", image: "图像", video: "视频", audio: "音频" } as Record<string, string>)[value] ?? value;
}

/** 管理员套餐页只维护未来销售定义，已售订阅仍读取开通时固化的权限快照。 */
export function SubscriptionPlansAdminPage() {
  const [plans, setPlans] = useState<AdminSubscriptionPlan[]>([]);
  const [options, setOptions] = useState<AdminSubscriptionPlanOptions>({ service_groups: [], models: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [editing, setEditing] = useState<AdminSubscriptionPlan | null>(null);
  const [form, setForm] = useState<PlanForm>(emptyForm);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [archiveTarget, setArchiveTarget] = useState<AdminSubscriptionPlan | null>(null);
  const [modelQuery, setModelQuery] = useState("");
  const [capability, setCapability] = useState("all");
  const [toast, setToast] = useState({ message: "", tone: "success" as "success" | "error" });

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [planData, optionData] = await Promise.all([
        listSubscriptionPlans(),
        getSubscriptionPlanOptions(),
      ]);
      setPlans(planData);
      setOptions(optionData);
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

  const filteredPlans = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return plans.filter(plan => {
      const matchesStatus = status === "all" || plan.status === status;
      const matchesQuery = !normalized || `${plan.name} ${plan.code} ${plan.description ?? ""}`.toLowerCase().includes(normalized);
      return matchesStatus && matchesQuery;
    });
  }, [plans, query, status]);

  const filteredModels = useMemo(() => {
    const normalized = modelQuery.trim().toLowerCase();
    return options.models.filter(model => {
      const matchesCapability = capability === "all" || model.capability_type === capability;
      const matchesQuery = !normalized || `${model.public_name} ${model.display_name} ${model.provider}`.toLowerCase().includes(normalized);
      return matchesCapability && matchesQuery;
    });
  }, [options.models, modelQuery, capability]);

  const summary = useMemo(() => ({
    active: plans.filter(plan => plan.status === "active").length,
    draft: plans.filter(plan => plan.status === "draft").length,
    featured: plans.filter(plan => plan.status === "active" && plan.featured).length,
  }), [plans]);

  const openCreate = () => {
    setEditing(null);
    setForm({ ...emptyForm, service_group_ids: [], model_ids: [] });
    setModelQuery("");
    setCapability("all");
    setDrawerOpen(true);
  };

  const openEdit = (plan: AdminSubscriptionPlan) => {
    setEditing(plan);
    setForm(toForm(plan));
    setModelQuery("");
    setCapability("all");
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    if (saving) return;
    setDrawerOpen(false);
    setEditing(null);
  };

  const toggleId = (field: "service_group_ids" | "model_ids", id: string) => {
    setForm(current => ({
      ...current,
      [field]: current[field].includes(id)
        ? current[field].filter(value => value !== id)
        : [...current[field], id],
    }));
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    try {
      const features = form.featuresText.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
      const saved = await saveSubscriptionPlan({
        code: form.code.trim().toLowerCase(),
        name: form.name.trim(),
        description: form.description?.trim() || null,
        billing_cycle: form.billing_cycle,
        price: form.price,
        included_credits: form.included_credits,
        concurrency_limit: form.concurrency_limit,
        features,
        status: form.status,
        display_order: form.display_order,
        featured: form.featured,
        service_group_ids: form.service_group_ids,
        model_ids: form.model_ids,
        version: form.version,
      }, editing?.id);
      setPlans(current => editing
        ? current.map(plan => plan.id === saved.id ? saved : plan)
        : [...current, saved]);
      setToast({ message: editing ? "套餐配置已更新" : "套餐已创建", tone: "success" });
      setDrawerOpen(false);
      setEditing(null);
    } catch (saveError) {
      setToast({ message: adminErrorMessage(saveError), tone: "error" });
    } finally {
      setSaving(false);
    }
  };

  const archive = async () => {
    if (!archiveTarget) return;
    setSaving(true);
    try {
      const archived = await archiveSubscriptionPlan(archiveTarget.id, archiveTarget.version);
      setPlans(current => current.map(plan => plan.id === archived.id ? archived : plan));
      setToast({ message: "套餐已归档，历史订阅未受影响", tone: "success" });
      setArchiveTarget(null);
    } catch (archiveError) {
      setToast({ message: adminErrorMessage(archiveError), tone: "error" });
    } finally {
      setSaving(false);
    }
  };

  return <div className="subscription-plan-admin-page">
    <AdminPageHeader
      eyebrow="SUBSCRIPTION CATALOG"
      title="订阅套餐"
      description="配置套餐积分、售卖状态、可用服务分组与模型；修改只作用于以后新开通的订阅。"
      action={<PrimaryButton onClick={openCreate}><AdminIcon name="plus" size={16}/>新增套餐</PrimaryButton>}
    />

    <section className="admin-summary-strip subscription-plan-summary admin-animate">
      <div><span>套餐总数</span><b>{plans.length}</b></div>
      <div><span>销售中</span><b className="good">{summary.active}</b></div>
      <div><span>草稿</span><b>{summary.draft}</b></div>
      <div><span>推荐套餐</span><b>{summary.featured}</b></div>
      <p><AdminIcon name="shield" size={15}/>套餐更新不会追溯改写已售订阅的分组和模型快照。</p>
    </section>

    <section className="admin-list-toolbar subscription-plan-toolbar admin-animate">
      <SearchBox value={query} onChange={setQuery} placeholder="搜索套餐名称、编码或说明"/>
      <SelectFilter value={status} onChange={setStatus} label="套餐状态">
        <option value="all">全部状态</option>
        <option value="active">销售中</option>
        <option value="draft">草稿</option>
        <option value="archived">已归档</option>
      </SelectFilter>
    </section>

    {loading ? <LoadingState label="正在加载套餐配置…"/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !filteredPlans.length ? (
      <EmptyState title="没有匹配的套餐" description="调整筛选条件，或创建第一个订阅套餐。"/>
    ) : <section className="subscription-plan-grid admin-animate">
      {filteredPlans.map(plan => <article className={`subscription-plan-card ${plan.featured ? "featured" : ""}`} key={plan.id}>
        <header>
          <div><span>{plan.code}</span><h2>{plan.name}</h2><p>{plan.description || "尚未填写套餐说明"}</p></div>
          <StatusPill value={plan.status}/>
        </header>
        <div className="subscription-plan-price"><b>¥{formatDashboardAmount(String(plan.price))}</b><span>/ {cycleLabel(plan.billing_cycle)}</span>{plan.featured && <em>推荐</em>}</div>
        <dl>
          <div><dt>套餐积分</dt><dd>{formatDashboardAmount(String(plan.included_credits))}</dd></div>
          <div><dt>并发上限</dt><dd>{plan.concurrency_limit ?? "不额外限制"}</dd></div>
          <div><dt>服务分组</dt><dd>{plan.service_groups.length}</dd></div>
          <div><dt>可用模型</dt><dd>{plan.models.length}</dd></div>
        </dl>
        <div className="subscription-plan-tags">
          {plan.features.slice(0, 3).map(feature => <span key={feature}>{feature}</span>)}
          {plan.features.length > 3 && <span>+{plan.features.length - 3}</span>}
        </div>
        <footer>
          <span>更新于 {formatAdminTime(plan.updated_at)} · v{plan.version}</span>
          <div><IconButton icon="edit" label={`编辑${plan.name}`} onClick={() => openEdit(plan)}/>{plan.status !== "archived" && <SecondaryButton tone="danger" onClick={() => setArchiveTarget(plan)}>归档</SecondaryButton>}</div>
        </footer>
      </article>)}
    </section>}

    <Drawer
      open={drawerOpen}
      title={editing ? `编辑 ${editing.name}` : "新增订阅套餐"}
      description="活动套餐必须同时配置至少一个服务分组和一个模型。"
      onClose={closeDrawer}
      className="subscription-plan-drawer"
      footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="subscription-plan-form" disabled={saving}>{saving ? "保存中…" : editing ? "保存修改" : "创建套餐"}</PrimaryButton></>}
    >
      <form id="subscription-plan-form" className="subscription-plan-form" onSubmit={submit}>
        <section>
          <header><span>01</span><div><h3>基础信息</h3><p>用于用户侧套餐展示、积分发放和并发限制。</p></div></header>
          <div className="admin-form-grid">
            <Field label="套餐名称" required><input required maxLength={120} value={form.name} onChange={event => setForm(current => ({ ...current, name: event.target.value }))}/></Field>
            <Field label="套餐编码" required hint="小写字母、数字和连字符"><input required maxLength={64} value={form.code} onChange={event => setForm(current => ({ ...current, code: event.target.value }))}/></Field>
            <Field label="计费周期" required><select value={form.billing_cycle} onChange={event => setForm(current => ({ ...current, billing_cycle: event.target.value as PlanForm["billing_cycle"] }))}><option value="monthly">月付</option><option value="quarterly">季付</option><option value="yearly">年付</option><option value="one_time">一次性</option></select></Field>
            <Field label="销售状态" required><select value={form.status} onChange={event => setForm(current => ({ ...current, status: event.target.value as PlanForm["status"] }))}><option value="draft">草稿</option><option value="active">销售中</option><option value="archived">已归档</option></select></Field>
            <Field label="套餐价格" required><input type="number" min="0" step="0.01" required value={form.price} onChange={event => setForm(current => ({ ...current, price: event.target.value }))}/></Field>
            <Field label="包含积分" required><input type="number" min="0" step="0.001" required value={form.included_credits} onChange={event => setForm(current => ({ ...current, included_credits: event.target.value }))}/></Field>
            <Field label="并发上限" hint="留空表示套餐不额外限制"><input type="number" min="1" value={form.concurrency_limit ?? ""} onChange={event => setForm(current => ({ ...current, concurrency_limit: event.target.value ? Number(event.target.value) : null }))}/></Field>
            <Field label="展示顺序" hint="数值越小越靠前"><input type="number" min="0" value={form.display_order} onChange={event => setForm(current => ({ ...current, display_order: Number(event.target.value) }))}/></Field>
            <Field label="套餐说明" wide><textarea rows={3} maxLength={500} value={form.description ?? ""} onChange={event => setForm(current => ({ ...current, description: event.target.value }))}/></Field>
            <Field label="权益文案" wide hint="每行一条，可一次粘贴多条"><textarea rows={4} value={form.featuresText} onChange={event => setForm(current => ({ ...current, featuresText: event.target.value }))} placeholder={"每月 30,000 积分\n优先请求队列\n完整调用分析"}/></Field>
          </div>
          <ToggleField label="推荐套餐" description="在用户侧套餐列表中突出展示" checked={form.featured} onChange={featured => setForm(current => ({ ...current, featured }))}/>
        </section>

        <section>
          <header><span>02</span><div><h3>可用服务分组</h3><p>用户使用套餐时只能选择这里配置的分组。</p></div><b>{form.service_group_ids.length} 已选</b></header>
          <div className="subscription-option-grid">
            {options.service_groups.map(group => <label className={form.service_group_ids.includes(group.id) ? "selected" : ""} key={group.id}>
              <input type="checkbox" checked={form.service_group_ids.includes(group.id)} onChange={() => toggleId("service_group_ids", group.id)}/>
              <span><b>{group.name}</b><small>{group.code} · {Number(group.price_multiplier).toFixed(3)}×</small></span><AdminIcon name="check" size={15}/>
            </label>)}
          </div>
        </section>

        <section>
          <header><span>03</span><div><h3>可用模型</h3><p>模型权限与服务分组权限同时生效，API 令牌只能继续收窄。</p></div><b>{form.model_ids.length} 已选</b></header>
          <div className="subscription-model-toolbar">
            <SearchBox value={modelQuery} onChange={setModelQuery} placeholder="搜索模型名称或厂商"/>
            <SelectFilter value={capability} onChange={setCapability} label="模型能力">
              <option value="all">全部能力</option><option value="text">文本</option><option value="multimodal">多模态</option><option value="image">图像</option><option value="video">视频</option><option value="audio">音频</option><option value="embedding">向量</option>
            </SelectFilter>
          </div>
          <div className="subscription-model-list">
            {filteredModels.map(model => <label className={form.model_ids.includes(model.id) ? "selected" : ""} key={model.id}>
              <input type="checkbox" checked={form.model_ids.includes(model.id)} onChange={() => toggleId("model_ids", model.id)}/>
              <span><b>{model.display_name}</b><small>{model.public_name} · {model.provider}</small></span><em>{capabilityLabel(model.capability_type)}</em>
            </label>)}
            {!filteredModels.length && <p>没有匹配的可售模型</p>}
          </div>
        </section>
      </form>
    </Drawer>

    <ConfirmDialog
      open={Boolean(archiveTarget)}
      title="确认归档套餐？"
      description={`归档后“${archiveTarget?.name ?? ""}”不再接受新开通；已有订阅、积分批次和权限快照保持不变。`}
      confirmLabel="确认归档"
      danger
      busy={saving}
      onCancel={() => !saving && setArchiveTarget(null)}
      onConfirm={() => void archive()}
    />
    <Toast message={toast.message} tone={toast.tone} onClose={() => setToast(current => ({ ...current, message: "" }))}/>
  </div>;
}
