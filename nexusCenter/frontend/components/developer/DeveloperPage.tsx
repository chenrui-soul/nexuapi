"use client";

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import {
  createApiKey,
  revealApiKey,
  listApiKeys,
  listAvailableModels,
  listPublicServiceGroups,
  revokeApiKey,
  setApiKeyStatus,
  updateApiKey,
  type ApiKeyInput,
  type ApiKeyItem,
  type AvailableModel,
  type PublicServiceGroup,
} from "@/lib/api-keys";
import { ApiError } from "@/lib/api";
import { modelWhitelistText, resolveModelWhitelist } from "@/lib/api-key-whitelist";

type ApiKeyFormState = {
  name: string;
  serviceGroupId: string;
  quota: string;
  never: boolean;
  expires: string;
  allowedModelIds: string[];
};

export type ApiKeyPreset = {
  serviceGroupId: string;
  modelId: string;
};

const EMPTY_FORM: ApiKeyFormState = {
  name: "",
  serviceGroupId: "",
  quota: "0",
  never: true,
  expires: "",
  allowedModelIds: [],
};

const DATE_FORMATTER = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

function Icon({ name, size = 16 }: { name: string; size?: number }) {
  const paths: Record<string, ReactNode> = {
    code: <><path d="m8 9-3 3 3 3"/><path d="m16 9 3 3-3 3"/><path d="m14 5-4 14"/></>,
    search: <><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></>,
    request: <><path d="M4 18V6a2 2 0 0 1 2-2h12"/><path d="m14 2 4 2-2 4"/><path d="M20 6v12a2 2 0 0 1-2 2H6"/><path d="m10 22-4-2 2-4"/></>,
    plus: <path d="M12 5v14M5 12h14"/>,
    copy: <><rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/></>,
    edit: <><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/></>,
    trash: <><path d="M4 7h16"/><path d="M10 11v6M14 11v6"/><path d="m6 7 1 14h10l1-14M9 7V4h6v3"/></>,
    check: <path d="m5 12 4 4L19 6"/>,
    close: <path d="m6 6 12 12M18 6 6 18"/>,
  };
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name] ?? paths.code}
    </svg>
  );
}

function Toast({ message, onClose }: { message: string; onClose: () => void }) {
  useEffect(() => {
    if (!message) return;
    const timer = window.setTimeout(onClose, 2200);
    return () => window.clearTimeout(timer);
  }, [message, onClose]);
  return message ? (
    <div className="token-toast" role="status">
      <Icon name="check" size={14}/>
      {message}
    </div>
  ) : null;
}

function formatDate(value: string | null) {
  if (!value) return "永久";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "未知" : DATE_FORMATTER.format(date);
}

function toDateInput(value: string | null) {
  return value ? value.slice(0, 10) : "";
}

function minimumExpiryDate() {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return year + "-" + month + "-" + day;
}

function expirationInstant(value: string) {
  return new Date(value + "T23:59:59.999").toISOString();
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "操作失败，请稍后重试";
}

function statusLabel(status: ApiKeyItem["status"]) {
  if (status === "active") return "启用";
  if (status === "disabled") return "禁用";
  if (status === "expired") return "已过期";
  return "已撤销";
}

const CREDIT_SOURCE_SCALE = 12;
const CREDIT_DISPLAY_SCALE = 3;

/** 将后端 NUMERIC(30, 12) 转成定点整数，累加时不经过 JavaScript 浮点数。 */
function scaledCredits(value: string | number | null) {
  let normalized = String(value ?? 0).trim();
  if (!/^\d+(?:\.\d+)?$/.test(normalized)) {
    const fallback = Number(normalized);
    normalized = Number.isFinite(fallback) ? fallback.toFixed(CREDIT_SOURCE_SCALE) : "0";
  }
  const [whole, fraction = ""] = normalized.split(".");
  const paddedFraction = fraction.padEnd(CREDIT_SOURCE_SCALE, "0").slice(0, CREDIT_SOURCE_SCALE);
  const sourceFactor = 10n ** BigInt(CREDIT_SOURCE_SCALE);
  return BigInt(whole) * sourceFactor + BigInt(paddedFraction || "0");
}

/** 页面统一将积分四舍五入展示到小数点后三位。 */
function formatScaledCredits(value: bigint) {
  const divisor = 10n ** BigInt(CREDIT_SOURCE_SCALE - CREDIT_DISPLAY_SCALE);
  const rounded = (value + divisor / 2n) / divisor;
  const displayFactor = 10n ** BigInt(CREDIT_DISPLAY_SCALE);
  return `${rounded / displayFactor}.${String(rounded % displayFactor).padStart(CREDIT_DISPLAY_SCALE, "0")}`;
}

function formatCredits(value: string | number | null) {
  return formatScaledCredits(scaledCredits(value));
}

async function loadDeveloperData() {
  // 先取得用户可见分组，再按默认分组加载分组模型目录，白名单不接触管理员路由细节。
  const [keys, serviceGroups] = await Promise.all([
    listApiKeys(),
    listPublicServiceGroups(),
  ]);
  const models = serviceGroups[0] ? await listAvailableModels(serviceGroups[0].id) : [];
  return {
    keys: keys.filter((key) => key.status !== "revoked"),
    models,
    serviceGroups,
  };
}

export function DeveloperPage({
  preset = null,
  onPresetConsumed,
}: {
  preset?: ApiKeyPreset | null;
  onPresetConsumed?: () => void;
}) {
  const [tokens, setTokens] = useState<ApiKeyItem[]>([]);
  const [models, setModels] = useState<AvailableModel[]>([]);
  const [serviceGroups, setServiceGroups] = useState<PublicServiceGroup[]>([]);
  const [query, setQuery] = useState("");
  const [modal, setModal] = useState<"create" | "edit" | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<ApiKeyFormState>(EMPTY_FORM);
  // 用户维护公开 API 模型名，保存时再安全映射为后端现有的模型 ID。
  const [modelWhitelist, setModelWhitelist] = useState("");
  const [formError, setFormError] = useState("");
  const [toast, setToast] = useState("");

  // 完整 Secret 仅保存在组件内存中，关闭创建成功弹窗后立即清空，不落浏览器持久化存储。
  const [createdSecret, setCreatedSecret] = useState("");
  const [revokeId, setRevokeId] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isModelsLoading, setIsModelsLoading] = useState(false);
  const [loadError, setLoadError] = useState("");
  const minExpiry = useMemo(() => minimumExpiryDate(), []);

  useEffect(() => {
    // 组件卸载后禁止异步请求继续写 state，避免路由切换时出现竞态和 React 警告。
    let active = true;
    void loadDeveloperData()
      .then((data) => {
        if (!active) return;
        setTokens(data.keys);
        setModels(data.models);
        setServiceGroups(data.serviceGroups);
        setLoadError("");
      })
      .catch((error: unknown) => {
        if (active) setLoadError(errorMessage(error));
      })
      .finally(() => {
        if (active) setIsLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return tokens;
    return tokens.filter((token) => (
      token.name + token.maskedKey + token.status + (token.serviceGroupName ?? "")
    ).toLowerCase().includes(normalized));
  }, [query, tokens]);

  const totalUsedCredits = useMemo(
    () => tokens.reduce((total, token) => total + scaledCredits(token.usedCredits), 0n),
    [tokens],
  );

  const showToast = (message: string) => setToast(message);

  const loadModelsForGroup = useCallback(async (serviceGroupId: string) => {
    setIsModelsLoading(true);
    try {
      const items = serviceGroupId ? await listAvailableModels(serviceGroupId) : [];
      setModels(items);
      return items;
    } finally {
      setIsModelsLoading(false);
    }
  }, []);

  const copyText = async (value: string, message: string) => {
    try {
      if (!navigator.clipboard) throw new Error("Clipboard API unavailable");
      await navigator.clipboard.writeText(value);
      showToast(message);
      return true;
    } catch {
      showToast("复制失败，请手动复制");
      return false;
    }
  };

  const refresh = async (notify = true) => {
    setIsRefreshing(true);
    try {
      const data = await loadDeveloperData();
      setTokens(data.keys);
      setModels(data.models);
      setServiceGroups(data.serviceGroups);
      setLoadError("");
      if (notify) showToast("API 令牌数据已更新");
    } catch (error) {
      setLoadError(errorMessage(error));
      if (notify) showToast(errorMessage(error));
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  };

  const closeModal = () => {
    if (isSaving) return;
    setModal(null);
    setEditingId(null);
    setModelWhitelist("");
    setFormError("");
  };

  const openCreate = () => {
    const serviceGroupId = serviceGroups[0]?.id ?? "";
    setEditingId(null);
    setForm({ ...EMPTY_FORM, serviceGroupId });
    setModelWhitelist("");
    setFormError("");
    setModal("create");
    void loadModelsForGroup(serviceGroupId).catch((error) => setFormError(errorMessage(error)));
  };

  const openEdit = (token: ApiKeyItem) => {
    setEditingId(token.id);
    setForm({
      name: token.name,
      serviceGroupId: token.serviceGroupId ?? "",
      quota: token.creditLimit === null ? "0" : String(token.creditLimit),
      never: token.expiresAt === null,
      expires: toDateInput(token.expiresAt),
      allowedModelIds: [...token.allowedModelIds],
    });
    setModelWhitelist("");
    setFormError("");
    setModal("edit");
    void loadModelsForGroup(token.serviceGroupId ?? "")
      .then((items) => {
        const whitelist = modelWhitelistText(token.allowedModelIds, items);
        setModelWhitelist(whitelist.value);
        if (whitelist.unknownIds.length > 0) {
          setFormError("部分已保存模型已不在当前分组目录中，请重新检查白名单。");
        }
      })
      .catch((error) => setFormError(errorMessage(error)));
  };

  const handleSave = async () => {
    setFormError("");
    const name = form.name.trim();
    if (!name) {
      setFormError("请输入 API 令牌名称。");
      return;
    }
    if (!form.serviceGroupId) {
      setFormError("请选择这枚 API 令牌唯一绑定的服务分组。");
      return;
    }
    const creditLimit = Number(form.quota);
    if (!Number.isFinite(creditLimit) || creditLimit < 0) {
      setFormError("配额上限必须是大于或等于 0 的数字。");
      return;
    }
    if (!form.never && !form.expires) {
      setFormError("请选择有效期，或勾选“永不过期”。");
      return;
    }
    const current = editingId ? tokens.find((token) => token.id === editingId) : null;
    if (editingId && !current) {
      setFormError("API 令牌已变化，请刷新列表后重试。");
      return;
    }

    const whitelist = resolveModelWhitelist(modelWhitelist, models);
    if (whitelist.unknownNames.length > 0) {
      setFormError(`模型白名单中存在未识别模型：${whitelist.unknownNames.join("、")}。请使用当前服务分组中的公开模型名。`);
      return;
    }

    const input: ApiKeyInput = {
      name,
      serviceGroupId: form.serviceGroupId,
      allowedModelIds: whitelist.modelIds,
      ipAllowlist: current?.ipAllowlist ?? [],
      rpmLimit: current?.rpmLimit ?? null,
      tpmLimit: current?.tpmLimit ?? null,
      concurrencyLimit: current?.concurrencyLimit ?? null,
      creditLimit: creditLimit === 0 ? null : creditLimit,
      expiresAt: form.never ? null : expirationInstant(form.expires),
    };

    setIsSaving(true);
    try {
      if (current) {
        const updated = await updateApiKey(current.id, input, current.version);
        setTokens((items) => items.map((item) => item.id === updated.id ? updated : item));
        setModal(null);
        setEditingId(null);
        setModelWhitelist("");
        setFormError("");
        showToast("API 令牌配置已保存");
      } else {
        const created = await createApiKey(input);
        setCreatedSecret(created.secret);
        setModal(null);
        setEditingId(null);
        setModelWhitelist("");
        void listApiKeys()
          .then((items) => setTokens(items.filter((item) => item.status !== "revoked")))
          .catch(() => showToast("API 令牌已创建，列表刷新失败，请稍后刷新"));
      }
    } catch (error) {
      setFormError(errorMessage(error));
      if (error instanceof ApiError && error.code === "API_KEY_VERSION_CONFLICT") {
        // 发生乐观锁冲突时刷新服务器最新版本，避免用户继续基于旧数据提交。
        void refresh(false);
      }
    } finally {
      setIsSaving(false);
    }
  };

  const changeStatus = async (token: ApiKeyItem) => {
    if (token.status === "expired") {
      showToast("请先编辑有效期，保存后即可重新启用");
      return;
    }
    const nextStatus = token.status === "active" ? "disabled" : "active";
    setPendingId("status:" + token.id);
    try {
      const updated = await setApiKeyStatus(token.id, nextStatus, token.version);
      setTokens((items) => items.map((item) => item.id === updated.id ? updated : item));
      showToast(token.name + " 已" + (nextStatus === "active" ? "启用" : "禁用"));
    } catch (error) {
      showToast(errorMessage(error));
      if (error instanceof ApiError && error.code === "API_KEY_VERSION_CONFLICT") {
        void refresh(false);
      }
    } finally {
      setPendingId(null);
    }
  };

  const confirmRevoke = async () => {
    if (!revokeId) return;
    setPendingId("revoke:" + revokeId);
    try {
      // 撤销成功后从当前列表移除；撤销是后端不可逆状态，不提供本地“恢复”操作。
      await revokeApiKey(revokeId);
      setTokens((items) => items.filter((item) => item.id !== revokeId));
      setRevokeId(null);
      showToast("API 令牌已撤销");
    } catch (error) {
      showToast(errorMessage(error));
    } finally {
      setPendingId(null);
    }
  };

  useEffect(() => {
    if (!preset || serviceGroups.length === 0) return;
    const timer = window.setTimeout(() => {
      const group = serviceGroups.find((item) => item.id === preset.serviceGroupId);
      if (!group) {
        showToast("该服务分组当前不可公开选择");
        onPresetConsumed?.();
        return;
      }
      setEditingId(null);
      setForm({ ...EMPTY_FORM, serviceGroupId: group.id, allowedModelIds: [preset.modelId] });
      setModelWhitelist("");
      setFormError("");
      setModal("create");
      void loadModelsForGroup(group.id)
        .then((items) => {
          if (!items.some((item) => item.id === preset.modelId)) {
            setForm((current) => ({ ...current, allowedModelIds: [] }));
            setFormError("该模型在当前服务分组中暂不可调用。");
            return;
          }
          const presetModel = items.find((item) => item.id === preset.modelId);
          if (presetModel) setModelWhitelist(presetModel.publicName);
        })
        .catch((error) => setFormError(errorMessage(error)))
        .finally(() => onPresetConsumed?.());
    }, 0);
    return () => window.clearTimeout(timer);
  }, [preset, serviceGroups, onPresetConsumed, loadModelsForGroup]);

  return (
    <div className="token-page">
      <section className="token-summary">
        {[
          ["令牌总数", String(tokens.length), "未撤销的 API 令牌", "neutral"],
          ["启用中", String(tokens.filter((token) => token.status === "active").length), "当前可用数量", "green"],
          ["累计消耗", formatScaledCredits(totalUsedCredits), "已结算积分，不含冻结中金额", "orange"],
          ["已设配额", String(tokens.filter((token) => token.creditLimit !== null).length), "设置独立积分上限的令牌", "purple"],
        ].map((item) => (
          <article key={item[0]} className={item[3]}>
            <span>{item[0]}</span>
            <strong>{item[1]}</strong>
            <small>{item[2]}</small>
          </article>
        ))}
      </section>

      <section className="token-manager">
        <div className="token-toolbar">
          <label>
            <Icon name="search" size={16}/>
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              aria-label="搜索 API 令牌"
              placeholder="按名称、脱敏标识或状态筛选"
            />
          </label>
          <span>共 {tokens.length} 条 · 显示 {filtered.length}</span>
          <button
            className={"token-refresh " + (isRefreshing ? "loading" : "")}
            onClick={() => void refresh()}
            aria-label="刷新 API 令牌列表"
            disabled={isRefreshing}
          >
            <Icon name="request" size={15}/>
          </button>
          <button className="primary" onClick={openCreate} disabled={serviceGroups.length === 0}>
            <Icon name="plus" size={15}/> 新建 API 令牌
          </button>
        </div>

        <div className="token-table-wrap">
          <div className="token-table">
            <div className="token-row token-head">
              <span>名称</span><span>令牌标识</span><span>价格分组</span><span>用量 / 配额</span>
              <span>状态</span><span>过期时间</span><span>创建</span><span>操作</span>
            </div>
            {filtered.map((token) => {
              const isPending = pendingId?.endsWith(token.id) ?? false;
              return (
                <div className="token-row" key={token.id}>
                  <span className="token-name">
                    <b>{token.name}</b>
                    <small>{token.allowedModelIds.length > 0 ? "已限制模型" : "全部模型"}</small>
                  </span>
                  <span className="token-key">
                    <code>{token.maskedKey}</code>
                    <button
                      aria-label={"复制 " + token.name + " 的脱敏标识"}
                      onClick={() => void copyText(token.maskedKey, "已复制脱敏标识（不可用于调用）")}
                    >
                      <Icon name="copy" size={14}/>
                    </button>
                    <button aria-label={"复制 " + token.name + " 的完整 Key"} title="复制完整 Key" onClick={async () => { try { const secret = await revealApiKey(token.id); await copyText(secret, "完整 API 令牌已复制"); } catch { setToast("该 Key 为历史令牌，请重新生成"); } }}>
                      <Icon name="code" size={14}/>
                    </button>
                  </span>
                  <span><em className="group-chip">{token.serviceGroupName ?? "未绑定分组"}</em></span>
                  <span className="token-quota">
                    <b>
                      {token.creditLimit === null
                        ? `已用 ${formatCredits(token.usedCredits)} · 未设置上限`
                        : `已用 ${formatCredits(token.usedCredits)} / 上限 ${formatCredits(token.creditLimit)}`}
                    </b>
                    <small>
                      {scaledCredits(token.reservedCredits) > 0n
                        ? `冻结中 ${formatCredits(token.reservedCredits)} · ${token.remainingCredits === null ? "无独立额度上限" : `剩余 ${formatCredits(token.remainingCredits)}`}`
                        : token.remainingCredits === null
                          ? "当前无独立额度上限"
                          : `剩余额度 ${formatCredits(token.remainingCredits)}`}
                    </small>
                  </span>
                  <span>
                    <em className={"token-state " + (token.status === "active" ? "on" : "off")}>
                      <i/>{statusLabel(token.status)}
                    </em>
                  </span>
                  <span>{formatDate(token.expiresAt)}</span>
                  <span>{formatDate(token.createdAt)}</span>
                  <span className="token-actions">
                    <button
                      aria-label={(token.status === "active" ? "禁用 " : "启用 ") + token.name}
                      onClick={() => void changeStatus(token)}
                      disabled={isPending || token.status === "expired"}
                      title={token.status === "expired" ? "请先编辑并延长有效期" : undefined}
                    >
                      {token.status === "active" ? "停" : "启"}
                    </button>
                    <button
                      aria-label={"编辑 " + token.name}
                      onClick={() => openEdit(token)}
                      disabled={isPending}
                    >
                      <Icon name="edit" size={14}/>
                    </button>
                    <button
                      className="delete"
                      aria-label={"撤销 " + token.name}
                      onClick={() => setRevokeId(token.id)}
                      disabled={isPending}
                    >
                      <Icon name="trash" size={14}/>
                    </button>
                  </span>
                </div>
              );
            })}
          </div>
        </div>

        {isLoading && (
          <div className="token-empty loading-state">
            <span className="loading-orbit" aria-hidden="true"><i/><i/></span>
            <b>正在加载 API 令牌</b>
            <span>正在从安全会话中读取令牌配置。</span>
          </div>
        )}
        {!isLoading && loadError && (
          <div className="token-empty">
            <Icon name="request" size={24}/>
            <b>API 令牌加载失败</b>
            <span>{loadError}</span>
            <button onClick={() => void refresh(false)}>重新加载</button>
          </div>
        )}
        {!isLoading && !loadError && filtered.length === 0 && (
          <div className="token-empty">
            <Icon name="search" size={24}/>
            <b>{tokens.length === 0 ? "还没有 API 令牌" : "没有找到匹配的 API 令牌"}</b>
            <span>{tokens.length === 0 ? "创建后，完整令牌只会展示一次。" : "换一个名称、标识或状态试试。"}</span>
            <button onClick={tokens.length === 0 ? openCreate : () => setQuery("")}>
              {tokens.length === 0 ? "创建第一个 API 令牌" : "清除搜索"}
            </button>
          </div>
        )}
      </section>

      {modal && (
        <div className="token-modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && closeModal()}>
          <section className="token-modal" role="dialog" aria-modal="true" aria-label={modal === "create" ? "新建 API 令牌" : "编辑 API 令牌"}>
            <button className="token-modal-close" aria-label="关闭 API 令牌弹窗" onClick={closeModal}>
              <Icon name="close"/>
            </button>
            <span className="eyebrow">{modal === "create" ? "创建访问凭证" : "修改访问凭证"}</span>
            <h3>{modal === "create" ? "新建 API 令牌" : "编辑 API 令牌"}</h3>
            <p>完整令牌仅在创建成功后显示一次；每枚令牌只能调用所选服务分组，不能在请求中切换。</p>
            <label>
              名称 <b>*</b>
              <input
                autoFocus
                maxLength={100}
                value={form.name}
                onChange={(event) => setForm({ ...form, name: event.target.value })}
                placeholder="例如：生产环境 / 内部脚本 / 主力令牌"
              />
            </label>
            <div className="token-form-grid">
              <label className="token-group-field">
                服务分组 <b>*</b>
                <select className="token-service-group-select"
                  value={form.serviceGroupId}
                  onChange={(event) => {
                    const serviceGroupId = event.target.value;
                    setForm({ ...form, serviceGroupId, allowedModelIds: [] });
                    setModelWhitelist("");
                    setFormError("");
                    void loadModelsForGroup(serviceGroupId).catch((error) => setFormError(errorMessage(error)));
                  }}
                  disabled={serviceGroups.length === 0}
                >
                  <option value="">请选择服务分组</option>
                  {serviceGroups.map((group) => (
                    <option value={group.id} key={group.id}>
                      {group.name} · {group.modelCount} 个模型 · {group.priceMultiplier}×
                    </option>
                  ))}
                </select>
                <small>{serviceGroups.length > 0 ? "创建后可编辑切换，服务端会强制单分组隔离。" : "暂无可用服务分组，请稍后重试。"}</small>
              </label>
              <label>
                关联套餐
                <input value="不绑定套餐" readOnly disabled/>
              </label>
            </div>
            <label>
              配额上限（积分）<small>0 = 不设置独立上限</small>
              <input
                type="number"
                min="0"
                step="0.00000001"
                value={form.quota}
                onChange={(event) => setForm({ ...form, quota: event.target.value })}
              />
            </label>
            <label className="expiry-label">
              有效期
              <span>
                <input
                  type="checkbox"
                  checked={form.never}
                  onChange={(event) => setForm({ ...form, never: event.target.checked })}
                />
                永不过期
              </span>
              {!form.never && (
                <input
                  type="date"
                  min={minExpiry}
                  value={form.expires}
                  onChange={(event) => setForm({ ...form, expires: event.target.value })}
                />
              )}
            </label>
            <fieldset className="token-model-picker">
              <legend>模型白名单 <small>留空 = 可调用该服务分组内全部模型</small></legend>
              {isModelsLoading && <p className="token-model-state">正在读取当前分组可调用模型…</p>}
              {!isModelsLoading && <textarea
                className="token-model-whitelist-input"
                value={modelWhitelist}
                onChange={(event) => setModelWhitelist(event.target.value)}
                placeholder={models.length > 0
                  ? "输入公开模型名，例如：gpt-4.1, claude-sonnet-4\n支持英文逗号、中文逗号或换行分隔"
                  : "当前分组暂时没有可调用模型"
                }
                rows={4}
                aria-label="模型白名单"
                disabled={models.length === 0}
              />}
              <small>输入公开模型名，不是 UUID；服务端会再次校验模型与分组关系。</small>
            </fieldset>
            {formError && <p className="form-error" role="alert">{formError}</p>}
            <div className="token-modal-actions">
              <button className="ghost" onClick={closeModal} disabled={isSaving}>取消</button>
              <button className="primary" onClick={() => void handleSave()} disabled={isSaving || isModelsLoading || !form.name.trim() || !form.serviceGroupId}>
                <Icon name="check" size={14}/>
                {isSaving ? "提交中…" : modal === "create" ? "创建" : "保存"}
              </button>
            </div>
          </section>
        </div>
      )}

      {createdSecret && (
        <div className="token-modal-backdrop">
          <section className="token-secret-modal" role="dialog" aria-modal="true" aria-label="API 令牌创建成功">
            <span className="secret-success"><Icon name="check" size={22}/></span>
            <h3>API 令牌创建成功</h3>
            <p>完整令牌仅显示这一次。请立即复制到服务端凭证管理或环境变量中，不要放入浏览器代码。</p>
            <code>{createdSecret}</code>
            <button
              className="primary"
              onClick={async () => {
                if (await copyText(createdSecret, "完整 API 令牌已复制")) setCreatedSecret("");
              }}
            >
              <Icon name="copy" size={15}/> 复制并完成
            </button>
            <button className="ghost" onClick={() => setCreatedSecret("")}>我已手动安全保存，关闭</button>
          </section>
        </div>
      )}

      {revokeId && (
        <div className="token-modal-backdrop">
          <section className="token-confirm" role="dialog" aria-modal="true" aria-label="确认撤销 API 令牌">
            <span><Icon name="trash" size={20}/></span>
            <h3>撤销这个 API 令牌？</h3>
            <p>撤销不可恢复，使用该令牌的应用会立即无法发起新的模型请求。</p>
            <div>
              <button className="ghost" onClick={() => setRevokeId(null)} disabled={pendingId !== null}>取消</button>
              <button className="danger" onClick={() => void confirmRevoke()} disabled={pendingId !== null}>
                {pendingId ? "撤销中…" : "确认撤销"}
              </button>
            </div>
          </section>
        </div>
      )}

      <Toast message={toast} onClose={() => setToast("")}/>
    </div>
  );
}
