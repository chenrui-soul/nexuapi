"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  adminErrorMessage, capabilityEndpointLabel, endpointSupportsCapability, formatAdminTime, getGroupConfiguration, getGroupUserGrants, getModelSyncStatus,
  listAdminUsers, listChannels, listModels, listRoutingGroups, listSuppliers, runModelSync,
  saveGroupConfiguration, saveGroupUserGrants, saveRoutingGroup, updateModelSyncSettings,
  resolveDefaultGroupSupplierIds,
  type AdminPage, type AdminUser, type Channel, type GroupConfiguration, type ModelSyncStatus,
  type GroupUserGrant, type Model, type RoutingGroup, type RoutingGroupInput, type Supplier,
} from "@/lib/admin";
import { AdminIcon } from "./AdminIcon";
import {
  AdminPageHeader, ConfirmDialog, Drawer, EmptyState, ErrorState, Field, IconButton,
  LoadingState, Pagination, PrimaryButton, SearchBox, SecondaryButton, SelectFilter,
  StatusActionButton, StatusPill, Toast,
} from "./AdminUi";

const emptyGroup: RoutingGroupInput = {
  code: "", name: "", description: null, price_multiplier: 1,
  audience: "all", status: "active", version: null,
};

type CredentialDraft = {
  credential: string;
  priority: number;
  weight: number;
};

function groupInput(item: RoutingGroup): RoutingGroupInput {
  return {
    code: item.code, name: item.name, description: item.description,
    price_multiplier: Number(item.price_multiplier), audience: item.audience,
    status: item.status === "disabled" ? "disabled" : "active", version: item.version,
  };
}

const audienceLabels: Record<RoutingGroup["audience"], string> = {
  all: "所有用户", assigned: "需授权使用", internal: "仅管理员",
};

function upstreamRateLabel(rate: number | null): string {
  if (rate === null) return "未同步";
  return `${rate} 原始值`;
}

const syncIntervals = [15, 60, 360, 720, 1440, 10080];

function syncIntervalLabel(minutes: number): string {
  if (minutes === 15) return "每 15 分钟";
  if (minutes < 1440) return `每 ${minutes / 60} 小时`;
  return minutes === 1440 ? "每天" : "每 7 天";
}

function syncStatusLabel(status: ModelSyncStatus | null): string {
  if (status?.running) return "同步中";
  if (!status?.last_run) return "尚未执行";
  if (status.last_run.status === "succeeded") return "同步成功";
  if (status.last_run.status === "failed") return "同步失败";
  return "同步中";
}

/** 管理配置必须加载完整资源目录，避免 100 条分页上限造成模型漏选或路由漏建。 */
async function listAllAdminItems<T>(loader: (page: number) => Promise<AdminPage<T>>): Promise<T[]> {
  const first = await loader(1);
  const pageCount = Math.ceil(first.total / first.page_size);
  if (pageCount <= 1) return first.items;
  const rest = await Promise.all(Array.from({ length: pageCount - 1 }, (_, index) => loader(index + 2)));
  return [first, ...rest].flatMap(result => result.items);
}

/** 管理服务分组、价格倍率、供应商资源和开放模型。 */
export function RoutingAdminPage() {
  const [groups, setGroups] = useState<RoutingGroup[]>([]);
  const [models, setModels] = useState<Model[]>([]);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [groupTotal, setGroupTotal] = useState(0);
  const [groupPage, setGroupPage] = useState(1);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editingGroup, setEditingGroup] = useState<RoutingGroup | null>(null);
  const [groupForm, setGroupForm] = useState<RoutingGroupInput>(emptyGroup);
  const [drawer, setDrawer] = useState<"group" | "configuration" | "user-grants" | null>(null);
  const [configuration, setConfiguration] = useState<GroupConfiguration | null>(null);
  const [configurationGroup, setConfigurationGroup] = useState<RoutingGroup | null>(null);
  const [configurationLoading, setConfigurationLoading] = useState(false);
  const [credentialDrafts, setCredentialDrafts] = useState<Record<string, CredentialDraft>>({});
  const [selectedModelIds, setSelectedModelIds] = useState<string[]>([]);
  const [modelQuery, setModelQuery] = useState("");
  const [confirmTarget, setConfirmTarget] = useState<RoutingGroup | null>(null);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState({ message: "", tone: "success" as "success" | "error" });
  const [syncStatus, setSyncStatus] = useState<ModelSyncStatus | null>(null);
  const [syncLoading, setSyncLoading] = useState(true);
  const [syncSaving, setSyncSaving] = useState(false);
  const [syncError, setSyncError] = useState("");
  const [syncPanelOpen, setSyncPanelOpen] = useState(false);
  const [grantGroup, setGrantGroup] = useState<RoutingGroup | null>(null);
  const [grantUsers, setGrantUsers] = useState<AdminUser[]>([]);
  const [groupUserGrants, setGroupUserGrants] = useState<GroupUserGrant[]>([]);
  const [selectedGrantUserIds, setSelectedGrantUserIds] = useState<string[]>([]);
  const [grantQuery, setGrantQuery] = useState("");
  const [grantLoading, setGrantLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [groupResult, modelItems, channelItems, supplierItems] = await Promise.all([
        listRoutingGroups({ page: groupPage, pageSize: 20, query, status }),
        listAllAdminItems(page => listModels({ page, pageSize: 100 })),
        listAllAdminItems(page => listChannels({ page, pageSize: 100 })),
        // 配置分组只能选择可用供应商，停用供应商不进入候选目录。
        listAllAdminItems(page => listSuppliers({ page, pageSize: 100, status: "active" })),
      ]);
      setGroups(groupResult.items); setGroupTotal(groupResult.total);
      setModels(modelItems);
      setChannels(channelItems);
      setSuppliers(supplierItems);
    } catch (loadError) { setError(adminErrorMessage(loadError)); }
    finally { setLoading(false); }
  }, [groupPage, query, status]);

  const loadSyncStatus = useCallback(async () => {
    setSyncLoading(true); setSyncError("");
    try { setSyncStatus(await getModelSyncStatus()); }
    catch (loadError) { setSyncError(adminErrorMessage(loadError)); }
    finally { setSyncLoading(false); }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 260 : 0);
    return () => window.clearTimeout(timer);
  }, [load, query]);
  useEffect(() => { const timer = window.setTimeout(() => void loadSyncStatus(), 0); return () => window.clearTimeout(timer); }, [loadSyncStatus]);

  const summary = useMemo(() => ({
    activeGroups: groups.filter(item => item.status === "active").length,
    upstreamGroups: groups.filter(item => item.source_managed).length,
    assignedGroups: groups.filter(item => item.audience === "assigned").length,
  }), [groups]);
  const selectedSupplierIds = useMemo(() => Object.keys(credentialDrafts), [credentialDrafts]);
  const automaticChannelsByModel = useMemo(() => new Map(models.map(model => [
    model.id,
    channels.filter(channel => {
      return channel.status !== "disabled"
        && selectedSupplierIds.includes(channel.supplier_id)
        && endpointSupportsCapability(channel.endpoint_type, model.capability_type);
    }),
  ])), [channels, models, selectedSupplierIds]);
  const filteredModels = useMemo(() => {
    const normalized = modelQuery.trim().toLowerCase();
    return normalized
      ? models.filter(model => `${model.public_name} ${model.display_name} ${model.provider}`.toLowerCase().includes(normalized))
      : models;
  }, [modelQuery, models]);
  const hasMissingCredential = useMemo(() => selectedSupplierIds.some(supplierId => {
    const draft = credentialDrafts[supplierId];
    if (draft?.credential.trim()) return false;
    return !configuration?.supplier_credentials.some(item => item.supplier_id === supplierId && item.credential_configured);
  }), [configuration, credentialDrafts, selectedSupplierIds]);
  const unsupportedModelIds = useMemo(() => selectedModelIds.filter(modelId => {
    const model = models.find(item => item.id === modelId);
    return !model || model.status !== "active" || !(automaticChannelsByModel.get(modelId)?.length ?? 0);
  }), [automaticChannelsByModel, models, selectedModelIds]);
  const effectiveRouteCount = useMemo(() => selectedModelIds.reduce(
    (count, modelId) => count + (automaticChannelsByModel.get(modelId)?.length ?? 0), 0,
  ), [automaticChannelsByModel, selectedModelIds]);
  const readyCredentialCount = useMemo(() => selectedSupplierIds.filter(supplierId => {
    const draft = credentialDrafts[supplierId];
    if (draft?.credential.trim()) return true;
    return configuration?.supplier_credentials.some(item => item.supplier_id === supplierId && item.credential_active) ?? false;
  }).length, [configuration, credentialDrafts, selectedSupplierIds]);
  const filteredGrantUsers = useMemo(() => {
    const normalized = grantQuery.trim().toLowerCase();
    if (!normalized) return grantUsers;
    return grantUsers.filter(user => `${user.display_name} ${user.masked_email} ${user.id}`.toLowerCase().includes(normalized));
  }, [grantQuery, grantUsers]);

  const openGroup = (item?: RoutingGroup) => {
    setEditingGroup(item ?? null); setGroupForm(item ? groupInput(item) : emptyGroup); setDrawer("group");
  };

  /** 特殊分组授权抽屉只加载有效用户和脱敏授权信息。 */
  const openUserGrants = async (group: RoutingGroup) => {
    setGrantGroup(group); setGrantUsers([]); setGroupUserGrants([]); setSelectedGrantUserIds([]);
    setGrantQuery(""); setGrantLoading(true); setDrawer("user-grants");
    try {
      const [grants, users] = await Promise.all([
        getGroupUserGrants(group.id),
        listAllAdminItems(page => listAdminUsers({ page, pageSize: 100, status: "active" })),
      ]);
      setGroupUserGrants(grants);
      setSelectedGrantUserIds(grants.map(item => item.user_id));
      setGrantUsers(users);
    } catch (loadError) {
      setToast({ message: adminErrorMessage(loadError), tone: "error" });
      setDrawer(null); setGrantGroup(null);
    } finally { setGrantLoading(false); }
  };

  /** 打开分组配置时只加载脱敏状态，上游 APIKey 输入永远从空值开始。 */
  const openConfiguration = async (group: RoutingGroup) => {
    setConfigurationGroup(group); setConfiguration(null);
    setCredentialDrafts({}); setSelectedModelIds([]); setModelQuery(""); setConfigurationLoading(true); setDrawer("configuration");
    try {
      const result = await getGroupConfiguration(group.id);
      // 运行环境可能仍是升级前的后端，先将缺失集合归一化，避免配置抽屉直接崩溃。
      const normalized: GroupConfiguration = {
        ...result,
        default_model_ids: result.default_model_ids ?? [],
        supplier_credentials: result.supplier_credentials ?? [],
      };
      setConfiguration(normalized);
      const synchronizedModelIds = models.filter(model => model.service_groups.some(item => (
        item.id === group.id && item.source_status === "active"
      ))).map(model => model.id);
      // 同步分组始终以上游当前开放关系初始化；人工分组使用分组模型目录关系。
      const defaultModelIds = result.default_model_ids ?? synchronizedModelIds;
      setSelectedModelIds(Array.from(new Set(defaultModelIds)));
      // 已有关联和同步来源供应商都默认勾选；凭证是否启用只决定密钥状态，不参与关联判断。
      const defaultSupplierIds = resolveDefaultGroupSupplierIds(
        suppliers,
        normalized.supplier_credentials,
        normalized.group.source_supplier_id,
      );
      setCredentialDrafts(Object.fromEntries(defaultSupplierIds.map(supplierId => {
        const existing = normalized.supplier_credentials.find(item => item.supplier_id === supplierId);
        return [supplierId, {
          credential: "", priority: existing?.priority ?? 100, weight: existing?.weight ?? 100,
        }];
      })));
    } catch (loadError) {
      setToast({ message: adminErrorMessage(loadError), tone: "error" });
      setDrawer(null); setConfigurationGroup(null);
    } finally { setConfigurationLoading(false); }
  };

  const closeDrawer = () => {
    if (saving) return;
    setDrawer(null); setEditingGroup(null); setConfiguration(null);
    setConfigurationGroup(null); setCredentialDrafts({}); setSelectedModelIds([]); setModelQuery("");
    setGrantGroup(null); setGrantUsers([]); setGroupUserGrants([]); setSelectedGrantUserIds([]); setGrantQuery("");
  };

  const toggleGrantUser = (userId: string, checked: boolean) => {
    setSelectedGrantUserIds(current => checked
      ? current.includes(userId) ? current : [...current, userId]
      : current.filter(id => id !== userId));
  };

  const toggleSupplier = (supplierId: string, checked: boolean) => {
    setCredentialDrafts(current => {
      if (checked) {
        return { ...current, [supplierId]: { credential: "", priority: 100, weight: 100 } };
      }
      const next = { ...current };
      delete next[supplierId];
      return next;
    });
  };

  const toggleModel = (modelId: string, checked: boolean) => {
    setSelectedModelIds(current => checked
      ? current.includes(modelId) ? current : [...current, modelId]
      : current.filter(id => id !== modelId));
  };

  const submitConfiguration = async (event: FormEvent) => {
    event.preventDefault();
    if (!configurationGroup || !configuration) return;
    if (!selectedSupplierIds.length) {
      setToast({ message: "请至少关联一个供应商", tone: "error" });
      return;
    }
    const credentialState = configuration.supplier_credentials;
    for (const supplierId of selectedSupplierIds) {
      const draft = credentialDrafts[supplierId] ?? {
        credential: "", priority: 100, weight: 100,
      };
      const existing = credentialState.find(item => item.supplier_id === supplierId);
      if (!draft.credential.trim() && !existing?.credential_configured) {
        const supplierName = suppliers.find(item => item.id === supplierId)?.name ?? "该供应商";
        setToast({ message: `首次关联 ${supplierName} 时必须填写上游 APIKey`, tone: "error" });
        return;
      }
    }
    setSaving(true);
    try {
      await saveGroupConfiguration(configurationGroup.id, {
        group_version: configuration.group.version,
        model_ids: selectedModelIds,
        supplier_credentials: selectedSupplierIds.map(supplierId => {
          const draft = credentialDrafts[supplierId] ?? {
            credential: "", priority: 100, weight: 100,
          };
          return {
            supplier_id: supplierId, priority: draft.priority, weight: draft.weight,
            credential: draft.credential.trim() || null,
          };
        }),
      });
      setToast({ message: "分组模型、供应商与上游凭证已保存", tone: "success" });
      setDrawer(null); setConfiguration(null); setConfigurationGroup(null); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally {
      // 无论成功失败都立即销毁明文输入，避免敏感信息长时间停留在浏览器内存。
      setCredentialDrafts(current => Object.fromEntries(Object.entries(current).map(([id, draft]) => [id, { ...draft, credential: "" }])));
      setSaving(false);
    }
  };

  const submitGroup = async (event: FormEvent) => {
    event.preventDefault(); setSaving(true);
    try {
      await saveRoutingGroup({
        ...groupForm,
        code: groupForm.code.trim().toLowerCase(),
        name: groupForm.name.trim(),
        description: groupForm.description?.trim() || null,
      }, editingGroup?.id);
      setToast({ message: editingGroup ? "服务分组配置已更新" : "服务分组已创建", tone: "success" });
      setDrawer(null); setEditingGroup(null); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  const submitUserGrants = async (event: FormEvent) => {
    event.preventDefault();
    if (!grantGroup) return;
    setSaving(true);
    try {
      const updated = await saveGroupUserGrants(grantGroup.id, selectedGrantUserIds);
      setGroupUserGrants(updated);
      setToast({ message: `已更新授权名单，共 ${updated.length} 位有效用户`, tone: "success" });
      setDrawer(null); setGrantGroup(null); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  const toggleStatus = async () => {
    if (!confirmTarget) return;
    setSaving(true);
    try {
      const nextStatus = confirmTarget.status === "disabled" ? "active" : "disabled";
      await saveRoutingGroup({ ...groupInput(confirmTarget), status: nextStatus }, confirmTarget.id);
      setToast({ message: confirmTarget.status === "disabled" ? "配置已启用" : "配置已停用", tone: "success" });
      setConfirmTarget(null); await load();
    } catch (saveError) { setToast({ message: adminErrorMessage(saveError), tone: "error" }); }
    finally { setSaving(false); }
  };

  /** 两个管理页面共享同一套乐观锁配置，避免模型与分组调度互相覆盖。 */
  const saveSyncSettings = async (enabled: boolean, intervalMinutes: number) => {
    if (!syncStatus) return;
    setSyncSaving(true); setSyncError("");
    try {
      setSyncStatus(await updateModelSyncSettings({
        enabled, interval_minutes: intervalMinutes, version: syncStatus.version,
      }));
      setToast({ message: "服务分组同步设置已更新", tone: "success" });
    } catch (saveError) {
      setSyncError(adminErrorMessage(saveError));
      setToast({ message: adminErrorMessage(saveError), tone: "error" });
      await loadSyncStatus();
    } finally { setSyncSaving(false); }
  };

  /** 立即同步会同时刷新模型目录、服务分组和两者归属，完成后重载当前列表。 */
  const executeSync = async () => {
    if (syncSaving || syncStatus?.running) return;
    setSyncSaving(true); setSyncError("");
    setSyncStatus(current => current ? { ...current, running: true } : current);
    try {
      const lastRun = await runModelSync();
      setToast({
        message: `服务分组同步完成：新增 ${lastRun.group_inserted_count ?? 0}，更新 ${lastRun.group_updated_count ?? 0}，过期 ${lastRun.group_stale_count ?? 0}`,
        tone: "success",
      });
      await Promise.all([loadSyncStatus(), load()]);
    } catch (runError) {
      setSyncError(adminErrorMessage(runError));
      setToast({ message: adminErrorMessage(runError), tone: "error" });
      await loadSyncStatus();
    } finally { setSyncSaving(false); }
  };

  return <div>
    <AdminPageHeader eyebrow="POLICY / GROUPS" title="服务分组配置" description="服务分组只维护可用模型、供应商资源、价格倍率和分组专用上游 APIKey；实际路由在用户请求时动态计算。" action={<><SecondaryButton onClick={() => setSyncPanelOpen(current => !current)}><AdminIcon name="refresh" size={15}/>{syncPanelOpen ? "收起同步设置" : "同步设置"}</SecondaryButton><PrimaryButton onClick={() => openGroup()}><AdminIcon name="plus" size={16}/>新增服务分组</PrimaryButton></>}/>
    {syncPanelOpen && <section className="model-sync-panel model-sync-panel-expanded admin-animate" aria-label="服务分组同步设置">
      <div className="model-sync-heading">
        <span><AdminIcon name="refresh" size={17}/></span>
        <div><b>上游服务分组同步</b><small>与模型目录共享原子任务，同步上游分组、模型及归属健康状态；不覆盖本地售价、权限、路由或上游 APIKey。</small></div>
      </div>
      <div className="model-sync-facts" aria-live="polite">
        <div><span>运行状态</span><b className={syncStatus?.last_run?.status === "failed" ? "bad" : ""}>{syncLoading ? "读取中…" : syncStatusLabel(syncStatus)}</b></div>
        <div><span>下次执行</span><b>{syncStatus?.enabled ? formatAdminTime(syncStatus.next_run_at) : "自动同步已关闭"}</b></div>
        <div><span>最近分组结果</span><b>{syncStatus?.last_run ? `+${syncStatus.last_run.group_inserted_count ?? 0} / ~${syncStatus.last_run.group_updated_count ?? 0} / 过期 ${syncStatus.last_run.group_stale_count ?? 0}` : "暂无记录"}</b></div>
      </div>
      <div className="model-sync-controls">
        <label className="model-sync-switch">
          <input type="checkbox" checked={syncStatus?.enabled ?? false} disabled={!syncStatus || syncSaving} onChange={event => void saveSyncSettings(event.target.checked, syncStatus?.interval_minutes ?? 360)}/>
          <i/><span>自动同步</span>
        </label>
        <label className="model-sync-interval"><span className="sr-only">同步周期</span><select value={syncStatus?.interval_minutes ?? 360} disabled={!syncStatus || syncSaving} onChange={event => void saveSyncSettings(syncStatus?.enabled ?? false, Number(event.target.value))}>
          {!syncIntervals.includes(syncStatus?.interval_minutes ?? 360) && <option value={syncStatus?.interval_minutes}>{syncIntervalLabel(syncStatus?.interval_minutes ?? 360)}</option>}
          {syncIntervals.map(minutes => <option key={minutes} value={minutes}>{syncIntervalLabel(minutes)}</option>)}
        </select></label>
        <SecondaryButton onClick={() => void executeSync()} disabled={!syncStatus || syncSaving || syncStatus.running}><AdminIcon name="refresh" size={14}/>{syncStatus?.running || syncSaving ? "同步中…" : "立即同步"}</SecondaryButton>
      </div>
      {syncError && <p className="model-sync-error"><AdminIcon name="warning" size={13}/>{syncError}</p>}
    </section>}
    <section className="admin-summary-strip admin-animate"><div><span>服务分组总数</span><b>{groupTotal}</b></div><div><span>本页启用分组</span><b className="good">{summary.activeGroups}</b></div><div><span>本页上游分组</span><b>{summary.upstreamGroups}</b></div><div><span>本页需授权</span><b>{summary.assignedGroups}</b></div><p><AdminIcon name="shield" size={15}/>运行路由按模型能力、接口 operation_code 和分组供应商精确匹配。</p></section>
    <section className="admin-list-panel admin-animate">
      <div className="admin-list-toolbar"><SearchBox value={query} onChange={value => { setGroupPage(1); setQuery(value); }} placeholder="搜索服务分组编码、名称或描述"/><div><SelectFilter label="状态筛选" value={status} onChange={value => { setGroupPage(1); setStatus(value); }}><option value="all">全部状态</option><option value="active">启用</option><option value="disabled">停用</option><option value="degraded">健康降级</option></SelectFilter><SecondaryButton onClick={() => void load()}><AdminIcon name="refresh" size={14}/></SecondaryButton></div></div>
      {loading && !groups.length ? <LoadingState/> : error ? <ErrorState message={error} onRetry={() => void load()}/> : !groups.length ? <EmptyState title="没有匹配的服务分组" description="创建服务分组后，可以为不同用户群设置独立价格倍率、模型开放范围和上游供应商。"/> : <div className="admin-table-wrap"><table className="admin-table routing-group-table"><thead><tr><th>服务分组</th><th>上游溯源</th><th>价格倍率</th><th>用户访问方式</th><th>状态</th><th>更新时间</th><th aria-label="操作"/></tr></thead><tbody>{groups.map(item => <tr key={item.id}><td><div className="resource-name"><span className="resource-avatar route">G</span><div><b>{item.name}</b><code>{item.code}</code><small>{item.description || "未填写服务分组说明"}</small></div></div></td><td>{item.source_group_id ? <><b className="table-primary">{item.source_supplier_name || item.sync_source}</b><small><code>{item.source_group_id}</code></small><small>{upstreamRateLabel(item.source_rate)} · 模型 {item.source_model_count} · 健康 {item.healthy_model_count}{item.stale_model_count ? ` · 过期 ${item.stale_model_count}` : ""}</small></> : <small>人工分组，未绑定上游 ID</small>}</td><td><b className="routing-multiplier">× {Number(item.price_multiplier).toLocaleString("zh-CN", { maximumFractionDigits: 3 })}</b><small>本地用户售价倍率</small></td><td><span className={`admin-tag ${item.audience === "assigned" ? "warning" : ""}`}>{audienceLabels[item.audience]}</span><small>{item.audience === "assigned" ? `已授权 ${item.authorized_user_count ?? 0} 人` : item.audience === "all" ? "用户端直接可查" : "用户端不展示"}</small></td><td><StatusPill value={item.status}/>{item.source_status === "stale" && <small>上游已不可见</small>}</td><td><b className="table-primary">{new Date(item.updated_at).toLocaleDateString("zh-CN")}</b><small>版本 {item.version}</small></td><td><div className="row-actions routing-row-actions">{item.audience === "assigned" && <button className="routing-user-grants-button" type="button" onClick={() => void openUserGrants(item)}><AdminIcon name="users" size={14}/><span>分配用户</span></button>}<button className="routing-configure-button" type="button" aria-label={`${item.name}：上游 API Key ${item.credential_configured ? "已配置" : "未配置"}，打开供应商、Key 与模型配置`} onClick={() => void openConfiguration(item)}><AdminIcon name="key" size={14}/><span>配置供应商、Key 与模型</span><em className={`routing-key-badge ${item.credential_configured ? "configured" : "missing"}`}>{item.credential_configured ? "已配置" : "未配置"}</em></button><IconButton icon="edit" label="编辑服务分组基础信息" onClick={() => openGroup(item)}/><StatusActionButton active={item.status !== "disabled"} activeLabel="停用服务分组" inactiveLabel="启用服务分组" onClick={() => setConfirmTarget(item)}/></div></td></tr>)}</tbody></table></div>}
      <Pagination page={groupPage} pageSize={20} total={groupTotal} onChange={setGroupPage}/>
    </section>

    <Drawer open={drawer === "group"} title={editingGroup ? "编辑服务分组" : "新增服务分组"} description="服务分组决定用户可访问范围、模型路由和公开售价倍率；渠道成本不会在这里修改。" onClose={closeDrawer} footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="routing-group-form" disabled={saving}>{saving ? "保存中…" : "保存服务分组"}</PrimaryButton></>}>
      <form id="routing-group-form" className="admin-form-grid" onSubmit={submitGroup} autoComplete="off"><Field label="服务分组编码" required hint="小写字母、数字、下划线或连字符"><input autoFocus={!editingGroup} required minLength={2} maxLength={64} pattern="[a-z0-9][a-z0-9_-]{1,63}" value={groupForm.code} onChange={event => setGroupForm(current => ({ ...current, code: event.target.value }))} placeholder="premium"/></Field><Field label="服务分组名称" required><input required maxLength={120} value={groupForm.name} onChange={event => setGroupForm(current => ({ ...current, name: event.target.value }))} placeholder="高级服务组"/></Field><Field label="价格倍率" required hint="最多 6 位小数"><input type="number" min={0.000001} max={999.999999} step="0.000001" required value={groupForm.price_multiplier} onChange={event => setGroupForm(current => ({ ...current, price_multiplier: Number(event.target.value) }))}/></Field><Field label="用户访问方式" required><select value={groupForm.audience} onChange={event => setGroupForm(current => ({ ...current, audience: event.target.value as RoutingGroupInput["audience"] }))}><option value="all">所有用户</option><option value="assigned">需管理员授权</option><option value="internal">仅管理员</option></select></Field><Field label="业务状态" required><select value={groupForm.status} onChange={event => setGroupForm(current => ({ ...current, status: event.target.value as RoutingGroupInput["status"] }))}><option value="active">启用</option><option value="disabled">停用</option></select></Field><Field wide label="服务分组说明" hint="最多 500 字"><textarea maxLength={500} value={groupForm.description ?? ""} onChange={event => setGroupForm(current => ({ ...current, description: event.target.value }))} placeholder="说明该服务分组的用户范围、模型质量和定价策略"/></Field><div className="admin-security-note wide"><AdminIcon name="shield" size={18}/><div><b>用户可见性边界</b><p>所有用户：访客和用户端直接可查；需管理员授权：只有授权用户可查询、创建 Key 和调用；仅管理员：用户端完全不展示。</p></div></div></form>
    </Drawer>

    <Drawer className="routing-user-grants-drawer" open={drawer === "user-grants"} title={`分配 ${grantGroup?.name ?? "特殊分组"} 的用户`} description="只有被授权的有效用户才能在用户端查询该分组、使用它创建 API 令牌并发起模型调用。" onClose={closeDrawer} footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="group-user-grants-form" disabled={saving || grantLoading}>{saving ? "保存中…" : `保存 ${selectedGrantUserIds.length} 位用户`}</PrimaryButton></>}>
      {grantLoading ? <LoadingState label="正在加载有效用户和授权名单…"/> : <form id="group-user-grants-form" className="routing-user-grants-form" onSubmit={submitUserGrants}>
        <section className="routing-grant-overview">
          <div><span>访问方式</span><b>需授权使用</b><small>未授权用户完全不可见</small></div>
          <div><span>当前选择</span><b>{selectedGrantUserIds.length}</b><small>原有有效授权 {groupUserGrants.length} 人</small></div>
          <div><span>可选用户</span><b>{grantUsers.length}</b><small>仅 active 且未删除账号</small></div>
        </section>
        <div className="routing-grant-toolbar">
          <SearchBox value={grantQuery} onChange={setGrantQuery} placeholder="搜索名称、脱敏邮箱或用户 ID"/>
          <button type="button" onClick={() => setSelectedGrantUserIds(current => filteredGrantUsers.every(user => current.includes(user.id)) ? current.filter(id => !filteredGrantUsers.some(user => user.id === id)) : Array.from(new Set([...current, ...filteredGrantUsers.map(user => user.id)])))}>{filteredGrantUsers.length > 0 && filteredGrantUsers.every(user => selectedGrantUserIds.includes(user.id)) ? "取消当前结果" : "选择当前结果"}</button>
        </div>
        <div className="routing-grant-list">
          {filteredGrantUsers.map(user => {
            const selected = selectedGrantUserIds.includes(user.id);
            return <label className={`routing-grant-user ${selected ? "selected" : ""}`} key={user.id}>
              <input type="checkbox" checked={selected} onChange={event => toggleGrantUser(user.id, event.target.checked)}/>
              <i><AdminIcon name="check" size={12}/></i>
              <span className="routing-grant-avatar">{user.display_name.slice(0, 1).toUpperCase()}</span>
              <span><b>{user.display_name}</b><small>{user.masked_email}</small><code>{user.id}</code></span>
              <StatusPill value={user.status}/>
            </label>;
          })}
          {!filteredGrantUsers.length && <EmptyState title="没有匹配的有效用户" description="请调整搜索条件，或先到用户与权限页面启用目标账号。"/>}
        </div>
        <div className="admin-security-note"><AdminIcon name="shield" size={18}/><div><b>撤权即时生效</b><p>保存后，被移除用户已有的该分组 API 令牌会在下一次请求鉴权时立即失效；请求只提交用户 ID，不上传邮箱或任何认证凭证。</p></div></div>
      </form>}
    </Drawer>

    <Drawer className="routing-configuration-drawer" open={drawer === "configuration"} title={`配置 ${configurationGroup?.name ?? "服务分组"}`} description="直接选择供应商、填写本分组专用上游 APIKey，再勾选对用户开放的模型；暂不可调用的模型也允许保存。" onClose={closeDrawer} footer={<><SecondaryButton onClick={closeDrawer} disabled={saving}>取消</SecondaryButton><PrimaryButton type="submit" form="group-configuration-form" disabled={saving || configurationLoading || !configuration || hasMissingCredential}>{saving ? "安全保存中…" : hasMissingCredential ? "请补全上游 APIKey" : "保存供应商、APIKey 与模型"}</PrimaryButton></>}> 
      {configurationLoading ? <LoadingState/> : configuration ? <form id="group-configuration-form" className="routing-configuration-form" onSubmit={submitConfiguration} autoComplete="off">
        <section className="routing-config-overview">
          <div><span>服务分组</span><b>{configuration.group.name}</b><code>{configuration.group.code}</code></div>
          <div><span>关联供应商</span><b>{selectedSupplierIds.length}</b><small>显式选择，不再从模型反推</small></div>
          <div><span>APIKey 就绪</span><b className={readyCredentialCount === selectedSupplierIds.length && selectedSupplierIds.length ? "good" : ""}>{readyCredentialCount}/{selectedSupplierIds.length}</b><small>仅使用本分组专用上游 APIKey</small></div>
          <div><span>开放模型 / 当前候选</span><b>{selectedModelIds.length} / {effectiveRouteCount}</b><small>候选随接口和模型状态动态变化</small></div>
        </section>

        <section className="routing-config-section credential-section">
          <header><div><span>01 / SUPPLIER &amp; CREDENTIAL</span><h3>选择供应商并直接配置上游 APIKey</h3><p>服务分组显式关联供应商。选中后，直接在同一卡片内填写该分组专用上游 APIKey。</p></div><em className="routing-required-badge">必填 · 至少 1 个供应商</em></header>
          <div className="routing-supplier-grid">{suppliers.map(supplier => {
            const selected = selectedSupplierIds.includes(supplier.id);
            const existing = configuration.supplier_credentials.find(item => item.supplier_id === supplier.id);
            const draft = credentialDrafts[supplier.id] ?? {
              credential: "", priority: existing?.priority ?? 100, weight: existing?.weight ?? 100,
            };
            const supplierChannels = channels.filter(channel => channel.supplier_id === supplier.id && channel.status !== "disabled");
            const supportedModelCount = models.filter(model => {
              return model.status === "active" && supplierChannels.some(channel => (
                endpointSupportsCapability(channel.endpoint_type, model.capability_type)
              ));
            }).length;
            const keyReady = Boolean(draft.credential.trim() || existing?.credential_active);
            return <article className={`routing-supplier-card ${selected ? "selected" : ""}`} key={supplier.id}>
              <label className="routing-supplier-selector">
                <input type="checkbox" checked={selected} onChange={event => toggleSupplier(supplier.id, event.target.checked)}/>
                <i><AdminIcon name="check" size={12}/></i>
                <span className="routing-supplier-icon"><AdminIcon name="supplier" size={18}/></span>
                <span><b>{supplier.name}</b><code>{supplier.code}</code><small>{supplierChannels.length} 个可用接口 · 当前可承接 {supportedModelCount} 个模型</small></span>
                <StatusPill value={supplier.status}/>
              </label>
              {selected && <div className="routing-supplier-credential">
                <div className={`routing-key-state ${keyReady ? "ready" : "missing"}`}><AdminIcon name="key" size={15}/><div><b>{keyReady ? "本分组专用上游 APIKey 已就绪" : "需要配置本分组专用上游 APIKey"}</b><small>{draft.credential.trim() ? "新上游 APIKey 将在保存后加密写入" : existing?.credential_configured ? `已安全存储 · 指纹 ${existing.credential_fingerprint ?? "—"}` : "首次关联必须填写；保存后不回显完整上游 APIKey"}</small></div></div>
                <div className="routing-credential-controls">
                  <Field wide label={existing?.credential_configured ? "轮换上游 APIKey" : "上游 APIKey"} required={!existing?.credential_configured} hint={existing?.credential_configured ? "留空保留现有上游 APIKey；填写后立即轮换" : "仅属于当前服务分组和当前供应商"}><input type="password" autoComplete="new-password" maxLength={4096} value={draft.credential} onChange={event => setCredentialDrafts(current => ({ ...current, [supplier.id]: { ...draft, credential: event.target.value } }))} placeholder={existing?.credential_configured ? "••••••••（留空不修改）" : "sk-..."}/></Field>
                  <Field label="供应商优先级" hint="数值越小越优先"><input type="number" min={0} max={1000000} value={draft.priority} onChange={event => setCredentialDrafts(current => ({ ...current, [supplier.id]: { ...draft, priority: Number(event.target.value) } }))}/></Field>
                  <Field label="供应商权重" hint="同优先级内分配流量"><input type="number" min={1} max={1000000} value={draft.weight} onChange={event => setCredentialDrafts(current => ({ ...current, [supplier.id]: { ...draft, weight: Number(event.target.value) } }))}/></Field>
                </div>
              </div>}
            </article>;
          })}</div>
          {!suppliers.length && <p className="routing-config-empty">暂无供应商，请先在供应商管理中创建并启用供应商。</p>}
          <div className="admin-security-note"><AdminIcon name="shield" size={18}/><div><b>上游 APIKey 边界清晰</b><p>上游 APIKey 只归属于“当前服务分组 × 当前供应商”，不会保存到供应商或接口资料中，也不会回退读取历史渠道凭证。</p></div></div>
        </section>

        <section className="routing-config-section">
          <header><div><span>02 / MODEL ACCESS</span><h3>勾选该分组对用户开放的模型</h3><p>保存只维护开放范围；Gateway 会按模型能力和接口 operation_code 精确选择供应商接口。</p></div><SearchBox value={modelQuery} onChange={setModelQuery} placeholder="搜索模型、厂商或能力类型"/></header>
          {!selectedSupplierIds.length && <div className="routing-step-hint"><AdminIcon name="warning" size={16}/><span>请先选择供应商，模型卡片才会显示当前可用接口候选数量。</span></div>}
          <div className="routing-model-list">
            {filteredModels.map(model => {
              const synchronizedMembership = model.service_groups.some(item => (
                item.id === configuration.group.id && item.source_status === "active"
              ));
              const availableChannels = automaticChannelsByModel.get(model.id) ?? [];
              const selected = selectedModelIds.includes(model.id);
              const supported = model.status === "active" && availableChannels.length > 0;
              const routeState = model.status !== "active"
                ? "模型已停用"
                : !selectedSupplierIds.length
                  ? "请先选择供应商"
                  : supported
                    ? `当前有 ${availableChannels.length} 个接口候选`
                    : `当前缺少匹配的${capabilityEndpointLabel(model.capability_type)}接口`;
              return <article className={`routing-model-card ${selected ? "selected" : ""} ${selected && !supported ? "unsupported" : ""}`} key={model.id}>
                <label className="routing-model-switch"><input type="checkbox" checked={selected} onChange={event => toggleModel(model.id, event.target.checked)}/><i><AdminIcon name="check" size={12}/></i><span><b>{model.display_name}</b><code>{model.public_name}</code><small>{model.provider || "未标注厂商"} · {model.capability_type || "通用能力"} · {synchronizedMembership ? "上游分组已开放" : "非上游分组模型"}</small></span><em className={supported ? "ready" : ""}>{routeState}</em></label>
              </article>;
            })}
            {!filteredModels.length && <p className="routing-config-empty">没有匹配的模型。</p>}
          </div>
          {selectedSupplierIds.length > 0 && unsupportedModelIds.length > 0 && <div className="routing-model-warning"><AdminIcon name="warning" size={16}/><div><b>有 {unsupportedModelIds.length} 个已选模型当前不可调用，但仍可保存</b><span>后续补充对应能力的供应商接口或启用模型后，Gateway 会自动形成候选。</span></div></div>}
        </section>
      </form> : <ErrorState message="分组配置加载失败" onRetry={() => configurationGroup && void openConfiguration(configurationGroup)}/>}
    </Drawer>

    <ConfirmDialog open={Boolean(confirmTarget)} title={confirmTarget?.status === "disabled" ? "重新启用服务分组？" : "停用服务分组？"} description="停用服务分组后，新请求不会再选择该服务分组。" confirmLabel={confirmTarget?.status === "disabled" ? "确认启用" : "确认停用"} danger={confirmTarget?.status !== "disabled"} busy={saving} onCancel={() => setConfirmTarget(null)} onConfirm={() => void toggleStatus()}/>
    <Toast message={toast.message} tone={toast.tone} onClose={() => setToast(current => ({ ...current, message: "" }))}/>
  </div>;
}
