/**
 * API 令牌请求层：隔离后端 snake_case 契约、统一附加 CSRF，并避免组件接触底层响应格式。
 */
import { apiDataRequest } from "./api.ts";
import { listModelMarket } from "./model-market.ts";

export type ApiKeyStatus = "active" | "disabled" | "expired" | "revoked";

export type ApiKeyItem = {
  id: string;
  name: string;
  maskedKey: string;
  status: ApiKeyStatus;
  serviceGroupId: string | null;
  serviceGroupName: string | null;
  defaultGroupId: string | null;
  allowedModelIds: string[];
  allowedGroupIds: string[];
  ipAllowlist: string[];
  rpmLimit: number | null;
  tpmLimit: number | null;
  concurrencyLimit: number | null;
  creditLimit: number | null;
  usedCredits: string;
  reservedCredits: string;
  remainingCredits: string | null;
  expiresAt: string | null;
  lastUsedAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
};

export type ApiKeyInput = {
  name: string;
  serviceGroupId: string;
  allowedModelIds: string[];
  ipAllowlist: string[];
  rpmLimit: number | null;
  tpmLimit: number | null;
  concurrencyLimit: number | null;
  creditLimit: number | null;
  expiresAt: string | null;
};

export type ApiKeyCreated = {
  id: string;
  name: string;
  secret: string;
  maskedKey: string;
  status: ApiKeyStatus;
  createdAt: string;
  version: number;
};

export type AvailableModel = {
  id: string;
  publicName: string;
  displayName: string;
};

export type PublicServiceGroup = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  priceMultiplier: number;
  modelCount: number;
};

type BackendApiKey = {
  id: string;
  name: string;
  masked_key: string;
  status: ApiKeyStatus;
  service_group_id: string | null;
  service_group_name: string | null;
  default_group_id: string | null;
  allowed_model_ids: string[];
  allowed_group_ids: string[];
  ip_allowlist: string[];
  rpm_limit: number | null;
  tpm_limit: number | null;
  concurrency_limit: number | null;
  credit_limit: number | null;
  used_credits?: string | number;
  reserved_credits?: string | number;
  remaining_credits?: string | number | null;
  expires_at: string | null;
  last_used_at: string | null;
  created_at: string;
  updated_at: string;
  version: number;
};

type BackendCreatedApiKey = {
  id: string;
  name: string;
  secret: string;
  masked_key: string;
  status: ApiKeyStatus;
  created_at: string;
  version: number;
};

type BackendPage<T> = {
  items: T[];
  total: number;
  page: number;
  page_size: number;
};

type BackendModel = {
  id: string;
  public_name: string;
  display_name: string;
};

type BackendServiceGroup = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  price_multiplier: number;
  model_count: number;
};

type CsrfResponse = {
  header: string;
  token: string;
};

function mapApiKey(item: BackendApiKey): ApiKeyItem {
  return {
    id: item.id,
    name: item.name,
    maskedKey: item.masked_key,
    status: item.status,
    serviceGroupId: item.service_group_id,
    serviceGroupName: item.service_group_name,
    defaultGroupId: item.default_group_id,
    allowedModelIds: [...item.allowed_model_ids],
    allowedGroupIds: [...item.allowed_group_ids],
    ipAllowlist: [...item.ip_allowlist],
    // 部分后端响应会省略未配置限制；用户端统一归一化为 null，避免显示 undefined。
    rpmLimit: item.rpm_limit ?? null,
    tpmLimit: item.tpm_limit ?? null,
    concurrencyLimit: item.concurrency_limit ?? null,
    creditLimit: item.credit_limit ?? null,
    // 计费数字保留后端字符串精度；兼容发布过渡期尚未返回新字段的旧节点。
    usedCredits: String(item.used_credits ?? "0"),
    reservedCredits: String(item.reserved_credits ?? "0"),
    remainingCredits: item.remaining_credits === null || item.remaining_credits === undefined
      ? null
      : String(item.remaining_credits),
    expiresAt: item.expires_at ?? null,
    lastUsedAt: item.last_used_at ?? null,
    createdAt: item.created_at,
    updatedAt: item.updated_at,
    version: item.version,
  };
}

function mutationBody(input: ApiKeyInput) {
  // 后端 PATCH 使用完整配置快照，因此这里必须保留界面暂未展示的限制字段。
  return {
    name: input.name.trim(),
    service_group_id: input.serviceGroupId,
    // 兼容字段保持与唯一服务分组一致，旧版后端或灰度节点不会发生分组漂移。
    default_group_id: input.serviceGroupId,
    allowed_model_ids: input.allowedModelIds,
    allowed_group_ids: [input.serviceGroupId],
    ip_allowlist: input.ipAllowlist,
    rpm_limit: input.rpmLimit,
    tpm_limit: input.tpmLimit,
    concurrency_limit: input.concurrencyLimit,
    credit_limit: input.creditLimit,
    expires_at: input.expiresAt,
  };
}

async function csrfHeaders(): Promise<Record<string, string>> {
  // Cookie 会话的状态变更请求必须携带服务端签发的 CSRF Header。
  const csrf = await apiDataRequest<CsrfResponse>("/api/v1/auth/csrf", {
    method: "GET",
    cache: "no-store",
  });
  return { [csrf.header]: csrf.token };
}

export async function listApiKeys(query = ""): Promise<ApiKeyItem[]> {
  const params = new URLSearchParams({ page: "1", page_size: "100" });
  if (query.trim()) params.set("query", query.trim());
  const page = await apiDataRequest<BackendPage<BackendApiKey>>(`/api/v1/api-keys?${params}`, {
    method: "GET",
    cache: "no-store",
  });
  return page.items.map(mapApiKey);
}

export async function createApiKey(input: ApiKeyInput): Promise<ApiKeyCreated> {
  // secret 只存在于本次创建响应；调用方不得写入 localStorage、sessionStorage 或日志。
  const created = await apiDataRequest<BackendCreatedApiKey>("/api/v1/api-keys", {
    method: "POST",
    headers: await csrfHeaders(),
    body: JSON.stringify(mutationBody(input)),
  });
  return {
    id: created.id,
    name: created.name,
    secret: created.secret,
    maskedKey: created.masked_key,
    status: created.status,
    createdAt: created.created_at,
    version: created.version,
  };
}

export async function updateApiKey(id: string, input: ApiKeyInput, version: number): Promise<ApiKeyItem> {
  // version 参与后端乐观锁校验，防止旧页面静默覆盖较新的配置。
  const item = await apiDataRequest<BackendApiKey>(`/api/v1/api-keys/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: await csrfHeaders(),
    body: JSON.stringify({ ...mutationBody(input), version }),
  });
  return mapApiKey(item);
}

export async function setApiKeyStatus(id: string, status: "active" | "disabled", version: number): Promise<ApiKeyItem> {
  const item = await apiDataRequest<BackendApiKey>(`/api/v1/api-keys/${encodeURIComponent(id)}/status`, {
    method: "PUT",
    headers: await csrfHeaders(),
    body: JSON.stringify({ status, version }),
  });
  return mapApiKey(item);
}

export async function revokeApiKey(id: string): Promise<void> {
  // 撤销不可恢复；后端实现幂等，重复提交仍可安全返回成功。
  await apiDataRequest<{ revoked: boolean }>(`/api/v1/api-keys/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: await csrfHeaders(),
  });
}

export async function revealApiKey(id: string): Promise<string> {
  const result = await apiDataRequest<{ secret: string }>(`/api/v1/api-keys/${encodeURIComponent(id)}/secret`, {
    method: "GET", cache: "no-store",
  });
  return result.secret;
}

export async function listAvailableModels(serviceGroupId?: string): Promise<AvailableModel[]> {
  if (serviceGroupId) {
    // 白名单校验必须读取分组全部模型，避免只加载第一页时误判后续模型名称无效。
    const firstPage = await listModelMarket({ serviceGroupId, page: 1, pageSize: 100 });
    const totalPages = Math.ceil(firstPage.total / firstPage.pageSize);
    const remainingPages = totalPages > 1
      ? await Promise.all(Array.from({ length: totalPages - 1 }, (_, index) => (
          listModelMarket({ serviceGroupId, page: index + 2, pageSize: 100 })
        )))
      : [];
    return [firstPage, ...remainingPages].flatMap((page) => page.items).map((model) => ({
      id: model.id,
      publicName: model.publicName,
      displayName: model.displayName,
    }));
  }
  const models = await apiDataRequest<BackendModel[]>("/api/v1/models", {
    method: "GET",
    cache: "no-store",
  });
  return models.map((model) => ({
    id: model.id,
    publicName: model.public_name,
    displayName: model.display_name,
  }));
}

export async function listPublicServiceGroups(): Promise<PublicServiceGroup[]> {
  const groups = await apiDataRequest<BackendServiceGroup[]>("/api/v1/service-groups", {
    method: "GET",
    cache: "no-store",
  });
  return groups.map((group) => ({
    id: group.id,
    code: group.code,
    name: group.name,
    description: group.description,
    priceMultiplier: group.price_multiplier,
    modelCount: group.model_count,
  }));
}
