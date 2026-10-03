import { ApiError, apiDataRequest } from "./api.ts";

export type AdminPage<T> = {
  items: T[];
  total: number;
  page: number;
  page_size: number;
};

export type AdminListQuery = {
  page?: number;
  pageSize?: number;
  query?: string;
  status?: string;
  capabilityType?: string;
  provider?: string;
  serviceGroup?: string;
  healthStatus?: string;
  role?: string;
  resourceType?: string;
  actorType?: string;
  targetType?: string;
  supplierId?: string;
};

/** 接口字段定义；description 会同时用于管理员提示和用户接口文档。 */
export type ProtocolSchemaField = {
  name: string;
  path?: string | null;
  type: "string" | "integer" | "number" | "boolean" | "array" | "object" | "file" | "null" | string;
  required: boolean;
  description?: string | null;
  default_value?: unknown;
  example?: unknown;
  enum_values?: string[] | null;
  minimum?: number | null;
  maximum?: number | null;
  deprecated: boolean;
  sensitive: boolean;
  children: ProtocolSchemaField[];
};

export type ApiInterfaceDefinition = {
  id: string;
  interface_code: string;
  interface_name: string;
  interface_version: string;
  capability_type: string;
  transport_mode: "sync" | "stream" | "async_poll" | string;
  http_method: string;
  public_path: string;
  request_content_type: string;
  description: string | null;
  status: string;
  request_fields: ProtocolSchemaField[];
  response_fields: ProtocolSchemaField[];
  created_at: string;
  updated_at: string;
  version: number;
};

export type ApiInterfaceInput = Omit<ApiInterfaceDefinition,
  "id" | "created_at" | "updated_at" | "version"
> & { version: number | null };

export type Adapter = {
  id: string;
  adapter_key: string;
  display_name: string;
  capability_type: "text" | "image" | "video" | string;
  implementation_key: string;
  implementation_label: string;
  description: string | null;
  status: "active" | "disabled" | string;
  built_in: boolean;
  created_at: string;
  updated_at: string;
  version: number;
};

export type AdapterInput = Omit<Adapter,
  "id" | "implementation_label" | "built_in" | "created_at" | "updated_at" | "version"
> & { version: number | null };

export type Supplier = {
  id: string;
  code: string;
  name: string;
  supplier_type: string;
  status: string;
  health_status: string;
  billing_mode: string;
  settlement_currency: string;
  disabled_reason: string | null;
  disabled_at: string | null;
  last_health_checked_at: string | null;
  metadata: Record<string, unknown>;
  created_at: string;
  updated_at: string;
  version: number;
};

export type SupplierInput = {
  code: string;
  name: string;
  supplier_type: string;
  status: string;
  billing_mode: string;
  settlement_currency: string;
  disabled_reason: string | null;
  metadata: Record<string, unknown>;
  version: number | null;
};

export type SupplierDetail = {
  supplier: Supplier;
  period: { from: string; to: string; days: number };
  summary: {
    channel_count: number;
    available_channel_count: number;
    request_count: number;
    success_count: number;
    success_rate: number;
    billed_amount: string;
    supplier_cost_amount: string;
    gross_margin_amount: string;
    attempt_count: number;
    attempt_success_count: number;
    attempt_success_rate: number;
    attempt_supplier_failure_count: number;
  };
  channels: {
    id: string;
    name: string;
    provider_type: string;
    base_url_origin: string;
    status: string;
    last_attempt_outcome: string | null;
    last_error_category: string | null;
    last_attempt_at: string | null;
  }[];
};

export type Model = {
  id: string;
  public_name: string;
  display_name: string;
  provider: string;
  capability_type: string;
  adapter_key: string;
  input_modalities: string[];
  output_modalities: string[];
  context_window: number | null;
  max_output_tokens: number | null;
  supports_streaming: boolean;
  supports_tools: boolean;
  supports_structured_output: boolean;
  input_price: number;
  output_price: number;
  cached_input_price: number;
  price_unit: string;
  billing_type: BillingType;
  unit_price: number;
  display_original_price: number;
  input_token_ratio: number;
  output_token_ratio: number;
  audio_input_token_ratio?: number;
  audio_output_token_ratio?: number;
  cached_input_token_ratio: number;
  cache_write_5m_token_ratio?: number;
  cache_write_1h_token_ratio?: number;
  charge_desc: string | null;
  active_pricing_version_id: string | null;
  public_visible: boolean;
  status: string;
  sync_source: string | null;
  source_model_key: string | null;
  source_managed: boolean;
  source_last_seen_at: string | null;
  source_synced_at: string | null;
  service_groups: {
    id: string;
    code: string;
    name: string;
    source_status: "active" | "stale";
    upstream_last_status: number | null;
    upstream_success_rate: number | null;
  }[];
  interfaces: {
    id: string;
    interface_code: string;
    interface_name: string;
    http_method: string;
    public_path: string;
    status: string;
  }[];
  created_at: string;
  updated_at: string;
  version: number;
};

/** 计费类型与模型能力类型相互独立；这里与后端稳定枚举保持一致。 */
export type BillingType = 1 | 2 | 3 | 4 | 5 | 6;

export type ModelPricingRule = {
  id: string;
  priority: number;
  name: string;
  match_conditions: Record<string, string>;
  billing_type: BillingType | null;
  unit_price: string | number | null;
  price_multiplier: string | number;
};

export type ModelContextTier = {
  id: string;
  priority: number;
  min_input_tokens: number;
  max_input_tokens: number | null;
  input_ratio: number;
  output_ratio: number;
  cached_input_ratio: number;
  cache_write_5m_ratio: number;
  cache_write_1h_ratio: number;
};

export type ModelPricingVersion = {
  id: string;
  version_no: number;
  billing_type: BillingType;
  billing_unit: string;
  unit_price: string | number;
  display_original_price: string | number;
  input_token_ratio: number;
  output_token_ratio: number;
  audio_input_token_ratio: number;
  audio_output_token_ratio: number;
  cached_input_token_ratio: number;
  cache_write_5m_token_ratio: number;
  cache_write_1h_token_ratio: number;
  charge_desc: string | null;
  context_tier_mode: 0 | 1 | 2;
  unmatched_behavior: "base" | "reject";
  source_type: string;
  source_hash: string | null;
  source_observed_at: string | null;
  change_note: string | null;
  created_by: string | null;
  created_at: string;
  active: boolean;
  rules: ModelPricingRule[];
  context_tiers: ModelContextTier[];
};

export type ModelPricing = {
  model_id: string;
  active_pricing_version_id: string | null;
  pricing_mode: "manual" | "follow_upstream";
  pricing_source_hash: string | null;
  pricing_source_synced_at: string | null;
  model_version: number;
  active: ModelPricingVersion | null;
  versions: ModelPricingVersion[];
};

export type ModelPricingInput = {
  billing_type: BillingType;
  unit_price: string;
  display_original_price: string;
  input_token_ratio: number;
  output_token_ratio: number;
  audio_input_token_ratio: number;
  audio_output_token_ratio: number;
  cached_input_token_ratio: number;
  cache_write_5m_token_ratio: number;
  cache_write_1h_token_ratio: number;
  charge_desc: string | null;
  context_tier_mode: 0 | 1 | 2;
  unmatched_behavior: "base" | "reject";
  rules: {
    priority: number;
    name: string;
    match_conditions: Record<string, string>;
    billing_type: BillingType | null;
    unit_price: string | null;
    price_multiplier: string;
  }[];
  context_tiers: {
    priority: number;
    min_input_tokens: number;
    max_input_tokens: number | null;
    input_ratio: number;
    output_ratio: number;
    cached_input_ratio: number;
    cache_write_5m_ratio: number;
    cache_write_1h_ratio: number;
  }[];
  change_note: string | null;
  model_version: number;
};

/** 特定模型循环时段加价规则；星期使用 ISO 1（周一）至 7（周日）。 */
export type TimePricingRule = {
  id: string;
  name: string;
  multiplier: string | number;
  days_of_week: number[];
  start_time: string;
  end_time: string;
  enabled: boolean;
  models: {
    id: string;
    public_name: string;
    display_name: string;
    capability_type: string;
  }[];
  created_at: string;
  updated_at: string;
  version: number;
};

export type TimePricingRuleInput = {
  name: string;
  multiplier: string;
  days_of_week: number[];
  start_time: string;
  end_time: string;
  enabled: boolean;
  model_ids: string[];
  version: number;
};

/** 单次模型市场同步的脱敏结果，不包含上游 URL、响应正文或任何凭证。 */
export type ModelSyncRun = {
  id: string;
  trigger_type: "manual" | "scheduled";
  status: "running" | "succeeded" | "failed";
  upstream_total: number | null;
  fetched_count: number;
  inserted_count: number;
  updated_count: number;
  unchanged_count: number;
  skipped_count: number;
  group_total: number;
  group_inserted_count: number;
  group_updated_count: number;
  group_unchanged_count: number;
  group_stale_count: number;
  error_code: string | null;
  error_summary: string | null;
  started_at: string;
  completed_at: string | null;
};

export type ModelSyncStatus = {
  enabled: boolean;
  interval_minutes: number;
  next_run_at: string | null;
  running: boolean;
  version: number;
  last_run: ModelSyncRun | null;
};

export type ModelSyncSettingsInput = {
  enabled: boolean;
  interval_minutes: number;
  version: number;
};

export type ModelInput = Omit<Model,
  "id" | "sync_source" | "source_model_key" | "source_managed" |
  "source_last_seen_at" | "source_synced_at" | "service_groups" | "interfaces" |
  "billing_type" | "unit_price" | "display_original_price" |
  "input_token_ratio" | "output_token_ratio" | "cached_input_token_ratio" |
  "audio_input_token_ratio" | "audio_output_token_ratio" |
  "cache_write_5m_token_ratio" | "cache_write_1h_token_ratio" |
  "charge_desc" | "active_pricing_version_id" |
  "created_at" | "updated_at" | "version"
> & {
  interface_ids: string[];
  version: number | null;
};

export type Channel = {
  id: string;
  supplier_id: string;
  supplier_code: string;
  supplier_name: string;
  name: string;
  provider_type: string;
  operation_code: ChannelOperationCode;
  endpoint_type: "text" | "image" | "video" | "audio" | "embedding" | "multimodal";
  request_method: "GET" | "POST";
  base_url: string;
  health_probe_path: string;
  credential_configured: boolean;
  credential_fingerprint: string | null;
  credential_updated_at: string | null;
  proxy_url: string | null;
  status: string;
  timeout_ms: number;
  concurrency_limit: number | null;
  priority: number;
  weight: number;
  last_error_summary: string | null;
  created_at: string;
  updated_at: string;
  version: number;
};

export type ChannelOperationCode =
  | "chat_completions"
  | "responses"
  | "image_generations"
  | "image_tasks"
  | "video_create"
  | "video_list"
  | "video_detail"
  | "audio_speech"
  | "audio_transcriptions"
  | "embeddings";

export type ChannelOperation = {
  operation_code: ChannelOperationCode;
  display_name: string;
  capability_type: Channel["endpoint_type"];
  request_method: Channel["request_method"];
  public_path: string;
};

/**
 * 与当前生产后端兼容的接口能力目录。
 *
 * 新版后端提供 /admin/channel-operations 时优先读取服务端目录；旧版后端
 * 尚未提供该只读目录接口时，使用这份稳定的前端目录，避免阻断供应商/渠道管理。
 */
const DEFAULT_CHANNEL_OPERATIONS: ChannelOperation[] = [
  { operation_code: "chat_completions", display_name: "文本对话", capability_type: "text", request_method: "POST", public_path: "/v1/chat/completions" },
  { operation_code: "responses", display_name: "Responses", capability_type: "text", request_method: "POST", public_path: "/v1/responses" },
  { operation_code: "image_generations", display_name: "图片生成", capability_type: "image", request_method: "POST", public_path: "/v1/images/generations" },
  { operation_code: "image_tasks", display_name: "图片任务查询", capability_type: "image", request_method: "GET", public_path: "/v1/images/{id}" },
  { operation_code: "video_create", display_name: "视频生成", capability_type: "video", request_method: "POST", public_path: "/v1/videos" },
  { operation_code: "video_list", display_name: "视频列表", capability_type: "video", request_method: "GET", public_path: "/v1/videos" },
  { operation_code: "video_detail", display_name: "视频详情", capability_type: "video", request_method: "GET", public_path: "/v1/videos/{id}" },
  { operation_code: "audio_speech", display_name: "语音合成", capability_type: "audio", request_method: "POST", public_path: "/v1/audio/speech" },
  { operation_code: "audio_transcriptions", display_name: "语音转写", capability_type: "audio", request_method: "POST", public_path: "/v1/audio/transcriptions" },
  { operation_code: "embeddings", display_name: "向量嵌入", capability_type: "embedding", request_method: "POST", public_path: "/v1/embeddings" },
];

/** 判断供应商能力接口能否承载指定模型；多模态接口可覆盖全部能力。 */
export function endpointSupportsCapability(
  endpointType: Channel["endpoint_type"],
  capabilityType: string,
): boolean {
  if (endpointType === "multimodal") return true;
  if (capabilityType === "multimodal") return endpointType === "text";
  return endpointType === capabilityType;
}

/** 将模型能力转换为管理员可直接处理的接口名称。 */
export function capabilityEndpointLabel(capabilityType: string): string {
  return ({
    text: "文本", image: "图片", video: "视频", audio: "音频",
    embedding: "向量", multimodal: "多模态",
  } as Record<string, string>)[capabilityType] ?? "对应能力";
}

export type ChannelInput = {
  supplier_id: string;
  name: string;
  provider_type: string;
  operation_code: ChannelOperationCode;
  endpoint_type: "text" | "image" | "video" | "audio" | "embedding" | "multimodal";
  request_method: "GET" | "POST";
  base_url: string;
  health_probe_path: string;
  proxy_url: string | null;
  status: string;
  timeout_ms: number;
  concurrency_limit: number | null;
  priority: number;
  weight: number;
  version: number | null;
};

/** 将健康运行态归一化为管理员可写入的业务状态，避免编辑波动渠道时提交非法枚举。 */
export function toManualChannelStatus(status: string): "active" | "disabled" {
  return status === "disabled" ? "disabled" : "active";
}

export type RoutingGroup = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  price_multiplier: number;
  audience: "all" | "assigned" | "internal";
  status: "active" | "disabled" | "degraded";
  source_supplier_id: string | null;
  source_supplier_name: string | null;
  source_group_id: string | null;
  source_group_name: string | null;
  sync_source: string | null;
  source_status: "active" | "stale" | null;
  source_rate: number | null;
  source_billing_type: number | null;
  source_managed: boolean;
  source_last_seen_at: string | null;
  source_synced_at: string | null;
  source_model_count: number;
  healthy_model_count: number;
  stale_model_count: number;
  authorized_user_count: number;
  /** 仅表示分组是否存在启用且已安全保存的上游 API Key，不返回任何密钥内容。 */
  credential_configured: boolean;
  created_at: string;
  updated_at: string;
  version: number;
};

export type RoutingGroupInput = {
  code: string;
  name: string;
  description: string | null;
  price_multiplier: number;
  audience: "all" | "assigned" | "internal";
  status: "active" | "disabled";
  version: number | null;
};

export type SubscriptionPlanServiceGroup = {
  id: string;
  code: string;
  name: string;
};

export type SubscriptionPlanModel = {
  id: string;
  public_name: string;
  display_name: string;
  provider: string;
  capability_type: string;
};

/** 管理员维护的套餐定义；已售订阅使用独立快照，不随此对象更新。 */
export type AdminSubscriptionPlan = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  billing_cycle: "monthly" | "quarterly" | "yearly" | "one_time";
  price: string | number;
  included_credits: string | number;
  concurrency_limit: number | null;
  features: string[];
  status: "draft" | "active" | "archived";
  display_order: number;
  featured: boolean;
  service_groups: SubscriptionPlanServiceGroup[];
  models: SubscriptionPlanModel[];
  created_at: string;
  updated_at: string;
  version: number;
};

export type AdminSubscriptionPlanInput = {
  code: string;
  name: string;
  description: string | null;
  billing_cycle: AdminSubscriptionPlan["billing_cycle"];
  price: string;
  included_credits: string;
  concurrency_limit: number | null;
  features: string[];
  status: AdminSubscriptionPlan["status"];
  display_order: number;
  featured: boolean;
  service_group_ids: string[];
  model_ids: string[];
  version: number | null;
};

export type AdminSubscriptionPlanOptions = {
  service_groups: Array<SubscriptionPlanServiceGroup & {
    audience: "all" | "assigned";
    price_multiplier: string | number;
  }>;
  models: SubscriptionPlanModel[];
};

/** 分组按供应商隔离的上游凭证状态；完整凭证永远不会从服务端返回。 */
export type GroupSupplierCredential = {
  supplier_id: string;
  supplier_name: string;
  priority: number;
  weight: number;
  credential_configured: boolean;
  credential_active: boolean;
  credential_fingerprint: string | null;
  credential_updated_at: string | null;
};

/**
 * 生成服务分组打开配置页时应默认勾选的供应商。
 * 已有关联和同步分组的来源供应商都会入选；凭证状态不参与判断，停用供应商则统一排除。
 */
export function resolveDefaultGroupSupplierIds<T extends { supplier_id: string }>(
  suppliers: ReadonlyArray<Pick<Supplier, "id" | "status">>,
  associations: readonly T[],
  sourceSupplierId: string | null,
): string[] {
  const activeSupplierIds = new Set(
    suppliers.filter(supplier => supplier.status === "active").map(supplier => supplier.id),
  );
  const selectedIds = associations
    .map(item => item.supplier_id)
    .filter(supplierId => activeSupplierIds.has(supplierId));
  if (sourceSupplierId && activeSupplierIds.has(sourceSupplierId)) selectedIds.push(sourceSupplierId);
  return Array.from(new Set(selectedIds));
}

export type GroupConfiguration = {
  group: RoutingGroup;
  /** 同步分组来自上游开放关系；人工分组来自管理员当前勾选的模型。 */
  default_model_ids: string[];
  supplier_credentials: GroupSupplierCredential[];
};

export type GroupConfigurationInput = {
  group_version: number;
  /** 只提交模型集合，后端运行时按模型能力匹配供应商接口。 */
  model_ids: string[];
  supplier_credentials: {
    supplier_id: string;
    priority: number;
    weight: number;
    /** 仅首次配置或轮换时赋值；空值表示保留已有分组上游 APIKey。 */
    credential: string | null;
  }[];
};

export type AdminUser = {
  id: string;
  display_name: string;
  masked_email: string;
  status: "active" | "pending" | "suspended" | "locked";
  roles: ("user" | "operator" | "admin")[];
  email_verified: boolean;
  last_login_at: string | null;
  created_at: string;
  updated_at: string;
  version: number;
};

export type AdminUserInput = {
  display_name: string;
  status: AdminUser["status"];
  roles: AdminUser["roles"];
  version: number;
};

/** 特殊服务分组授权用户只包含脱敏身份信息，不暴露邮箱明文或认证字段。 */
export type GroupUserGrant = {
  user_id: string;
  display_name: string;
  masked_email: string;
  user_status: string;
  expires_at: string | null;
  granted_at: string;
  updated_at: string;
};

export type AuditLog = {
  id: number;
  actor_user_id: string | null;
  actor_display_name: string | null;
  actor_type: "user" | "admin" | "system";
  action: string;
  resource_type: string;
  resource_id: string | null;
  before_data: Record<string, unknown>;
  after_data: Record<string, unknown>;
  ip_address: string | null;
  user_agent_hash: string | null;
  request_id: string | null;
  created_at: string;
};

export type AdminRequestLog = {
  id: string;
  request_id: string;
  user_id: string | null;
  user_display_name: string | null;
  api_key_name: string | null;
  service_group_name: string | null;
  supplier_code: string | null;
  supplier_name: string | null;
  started_at: string;
  completed_at: string | null;
  duration_ms: number | null;
  public_model: string;
  status_code: number | null;
  status: "success" | "failed";
  input_tokens: number;
  output_tokens: number;
  cached_tokens: number;
  billed_amount: string | number | null;
  streaming: boolean;
  retry_count: number;
  platform_error_code: string | null;
  request_summary: Record<string, unknown>;
  response_summary: Record<string, unknown>;
  request_detail: Record<string, unknown>;
  response_detail: Record<string, unknown>;
  request_payload_size: number;
  response_payload_size: number;
};

export type AdminRequestLogQuery = {
  page?: number;
  pageSize?: number;
  query?: string;
  status?: "success" | "failed";
  model?: string;
  period?: "24h" | "7d" | "30d";
};

export type ChannelHealth = {
  channel_id: string;
  channel_name: string;
  supplier_name: string;
  channel_status: string;
  health_probe_path: string;
  latest_check_status: string | null;
  latest_latency_ms: number | null;
  latest_error_summary: string | null;
  latest_checked_at: string | null;
};

export type GroupHealth = {
  group_id: string;
  group_code: string;
  group_name: string;
  configuration_status: string;
  health_status: string;
  configured_route_count: number;
  available_route_count: number;
  latest_checked_at: string | null;
};

export type HealthCheck = {
  id: number;
  target_type: "channel" | "group";
  target_id: string;
  target_name: string | null;
  status: string;
  latency_ms: number | null;
  error_summary: string | null;
  checked_at: string;
};

export type HealthAlert = {
  id: number;
  group_id: string;
  group_code: string;
  group_name: string;
  alert_type: string;
  status: "open" | "resolved";
  severity: string;
  title: string;
  summary: string;
  occurrence_count: number;
  notification_count: number;
  suppressed_count: number;
  opened_at: string;
  last_seen_at: string;
  last_notified_at: string | null;
  resolved_at: string | null;
  updated_at: string;
};

export type ManualProbeResult = {
  channel_id: string;
  outcome: "healthy" | "failure" | "unconfigured";
  category: string | null;
  latency_ms: number;
  channel_status: string;
};

export type DashboardSummary = {
  request_count: number;
  success_count: number;
  failure_count: number;
  supplier_failure_count: number;
  success_rate: number;
  input_tokens: number;
  output_tokens: number;
  cached_tokens: number;
  billed_amount: string;
  supplier_cost_amount: string;
  gross_margin_amount: string;
  gross_margin_rate: number;
  average_latency_ms: number;
  latency_p95_ms: number | null;
  attempt_count: number;
  attempt_success_count: number;
  attempt_supplier_failure_count: number;
  attempt_success_rate: number;
  attempt_average_latency_ms: number;
  attempt_latency_p95_ms: number | null;
};

export type DashboardTrendPoint = {
  bucket_start: string;
  request_count: number;
  success_count: number;
  failure_count: number;
  success_rate: number;
  billed_amount: string;
  supplier_cost_amount: string;
  gross_margin_amount: string;
  gross_margin_rate: number;
  latency_p95_ms: number | null;
};

export type DashboardRankingItem = {
  dimension_id: string;
  dimension_name: string;
  request_count: number;
  success_count: number;
  failure_count: number;
  success_rate: number;
  input_tokens: number;
  output_tokens: number;
  cached_tokens: number;
  billed_amount: string;
  supplier_cost_amount: string;
  gross_margin_amount: string;
  gross_margin_rate: number;
  average_latency_ms: number;
  attempt_count: number;
  attempt_success_count: number;
  attempt_supplier_failure_count: number;
  attempt_success_rate: number;
};

export type DashboardOverview = {
  preset: "today" | "7d" | "30d" | "90d" | "custom";
  from: string;
  to: string;
  bucket_size: "hour" | "day";
  last_aggregated_at: string | null;
  settlement_currency: string | null;
  summary: DashboardSummary;
  trend: DashboardTrendPoint[];
  rankings: {
    suppliers: DashboardRankingItem[];
    models: DashboardRankingItem[];
    channels: DashboardRankingItem[];
    groups: DashboardRankingItem[];
  };
  error_distribution: { category: string; occurrence_count: number }[];
};

/** 运营总览资源统计，由后端直接聚合，数量不受管理员列表分页限制。 */
export type AdminResourceOverview = {
  suppliers: { total: number; active: number };
  models: { total: number; active: number };
  channels: { total: number; active: number };
  open_alert_count: number;
};

export type DashboardQuery = {
  preset?: "today" | "7d" | "30d" | "90d" | "custom";
  from?: string;
  to?: string;
  supplierId?: string;
};

type CsrfResponse = {
  header: string;
  token: string;
};

function toQueryString(input: AdminListQuery): string {
  const params = new URLSearchParams();
  params.set("page", String(input.page ?? 1));
  params.set("page_size", String(input.pageSize ?? 20));
  if (input.query?.trim()) params.set("query", input.query.trim());
  if (input.status && input.status !== "all") params.set("status", input.status);
  if (input.capabilityType && input.capabilityType !== "all") params.set("capability_type", input.capabilityType);
  if (input.provider?.trim()) params.set("provider", input.provider.trim());
  if (input.serviceGroup?.trim()) params.set("service_group", input.serviceGroup.trim());
  if (input.healthStatus && input.healthStatus !== "all") params.set("health_status", input.healthStatus);
  if (input.role && input.role !== "all") params.set("role", input.role);
  if (input.resourceType && input.resourceType !== "all") params.set("resource_type", input.resourceType);
  if (input.actorType && input.actorType !== "all") params.set("actor_type", input.actorType);
  if (input.targetType && input.targetType !== "all") params.set("target_type", input.targetType);
  if (input.supplierId) params.set("supplier_id", input.supplierId);
  return params.toString();
}

/**
 * 所有管理员写操作先临时获取 CSRF 令牌。令牌仅保存在本次函数调用内，
 * 不进入 localStorage，也不与渠道凭证等敏感字段一起持久化。
 */
async function adminWrite<T>(path: string, method: "POST" | "PUT" | "DELETE", body: unknown): Promise<T> {
  const csrf = await apiDataRequest<CsrfResponse>("/api/v1/auth/csrf", {
    method: "GET",
    cache: "no-store",
  });
  return apiDataRequest<T>(path, {
    method,
    headers: { [csrf.header]: csrf.token },
    body: JSON.stringify(body),
  });
}

export function listSuppliers(query: AdminListQuery = {}): Promise<AdminPage<Supplier>> {
  return apiDataRequest(`/api/v1/admin/suppliers?${toQueryString(query)}`, { cache: "no-store" });
}

/** 供应商详情仅返回脱敏地址、归一化错误分类和精确十进制金额。 */
export function getSupplierDetail(id: string): Promise<SupplierDetail> {
  return apiDataRequest(`/api/v1/admin/suppliers/${encodeURIComponent(id)}/detail`, { cache: "no-store" });
}

export function saveSupplier(input: SupplierInput, id?: string): Promise<Supplier> {
  return adminWrite(id ? `/api/v1/admin/suppliers/${id}` : "/api/v1/admin/suppliers", id ? "PUT" : "POST", input);
}

export function listModels(query: AdminListQuery = {}): Promise<AdminPage<Model>> {
  return apiDataRequest(`/api/v1/admin/models?${toQueryString(query)}`, { cache: "no-store" });
}

export function listAdapters(query: AdminListQuery = {}): Promise<AdminPage<Adapter>> {
  return apiDataRequest(`/api/v1/admin/adapters?${toQueryString(query)}`, { cache: "no-store" });
}

export function saveAdapter(input: AdapterInput, id?: string): Promise<Adapter> {
  return adminWrite(
    id ? `/api/v1/admin/adapters/${encodeURIComponent(id)}` : "/api/v1/admin/adapters",
    id ? "PUT" : "POST",
    input,
  );
}

export function listProtocols(query: AdminListQuery = {}): Promise<AdminPage<ApiInterfaceDefinition>> {
  return apiDataRequest(`/api/v1/admin/api-interfaces?${toQueryString(query)}`, { cache: "no-store" });
}

export function saveProtocol(input: ApiInterfaceInput, id?: string): Promise<ApiInterfaceDefinition> {
  return adminWrite(
    id ? `/api/v1/admin/api-interfaces/${encodeURIComponent(id)}` : "/api/v1/admin/api-interfaces",
    id ? "PUT" : "POST",
    input,
  );
}

export function saveModel(input: ModelInput, id?: string): Promise<Model> {
  return adminWrite(id ? `/api/v1/admin/models/${id}` : "/api/v1/admin/models", id ? "PUT" : "POST", input);
}

/** 读取模型当前平台价格和最近 50 个不可变历史版本。 */
export function getModelPricing(modelId: string): Promise<ModelPricing> {
  return apiDataRequest(`/api/v1/admin/models/${encodeURIComponent(modelId)}/pricing`, { cache: "no-store" });
}

/** 发布价格会创建新版本并立即激活，不会覆盖历史价格或已结算账单。 */
export function publishModelPricing(modelId: string, input: ModelPricingInput): Promise<ModelPricing> {
  return adminWrite(`/api/v1/admin/models/${encodeURIComponent(modelId)}/pricing`, "PUT", input);
}

/** 回滚只切换模型的当前价格版本指针，并使用模型 version 防止并发覆盖。 */
export function activateModelPricingVersion(
  modelId: string,
  versionId: string,
  modelVersion: number,
): Promise<ModelPricing> {
  return adminWrite(
    `/api/v1/admin/models/${encodeURIComponent(modelId)}/pricing/versions/${encodeURIComponent(versionId)}/activate`,
    "POST",
    { model_version: modelVersion },
  );
}

/** 删除未生效且未被请求计费明细引用的历史价格版本。 */
export function deleteModelPricingVersion(
  modelId: string,
  versionId: string,
  modelVersion: number,
): Promise<ModelPricing> {
  return adminWrite(
    `/api/v1/admin/models/${encodeURIComponent(modelId)}/pricing/versions/${encodeURIComponent(versionId)}`,
    "DELETE",
    { model_version: modelVersion },
  );
}

/** 切换价格覆盖边界；恢复跟随上游时，后端会优先激活最近的上游价格版本。 */
export function updateModelPricingSourceMode(
  modelId: string,
  followUpstream: boolean,
  modelVersion: number,
): Promise<ModelPricing> {
  return adminWrite(
    `/api/v1/admin/models/${encodeURIComponent(modelId)}/pricing/source-mode`,
    "PUT",
    { follow_upstream: followUpstream, model_version: modelVersion },
  );
}

/** 读取全部模型时段加价规则，响应不包含供应商成本或内部路由信息。 */
export function listTimePricingRules(): Promise<TimePricingRule[]> {
  return apiDataRequest("/api/v1/admin/billing/time-rules", { cache: "no-store" });
}

/** 新建或编辑规则时由后端在事务内校验同一模型的启用时段不能重叠。 */
export function saveTimePricingRule(input: TimePricingRuleInput, id?: string): Promise<TimePricingRule> {
  return adminWrite(
    id ? `/api/v1/admin/billing/time-rules/${encodeURIComponent(id)}` : "/api/v1/admin/billing/time-rules",
    id ? "PUT" : "POST",
    input,
  );
}

/** 启停仅影响后续进入平台的请求，已开始请求继续使用原倍率快照。 */
export function updateTimePricingRuleStatus(id: string, enabled: boolean, version: number): Promise<TimePricingRule> {
  return adminWrite(`/api/v1/admin/billing/time-rules/${encodeURIComponent(id)}/status`, "PUT", { enabled, version });
}

export function deleteTimePricingRule(id: string, version: number): Promise<void> {
  return adminWrite(`/api/v1/admin/billing/time-rules/${encodeURIComponent(id)}`, "DELETE", { version });
}

/** 套餐配置接口只维护未来销售定义，不修改已售订阅的权益快照。 */
export function listSubscriptionPlans(): Promise<AdminSubscriptionPlan[]> {
  return apiDataRequest("/api/v1/admin/subscription-plans", { cache: "no-store" });
}

export function getSubscriptionPlanOptions(): Promise<AdminSubscriptionPlanOptions> {
  return apiDataRequest("/api/v1/admin/subscription-plans/options", { cache: "no-store" });
}

export function saveSubscriptionPlan(
  input: AdminSubscriptionPlanInput,
  id?: string,
): Promise<AdminSubscriptionPlan> {
  return adminWrite(
    id ? `/api/v1/admin/subscription-plans/${encodeURIComponent(id)}` : "/api/v1/admin/subscription-plans",
    id ? "PUT" : "POST",
    input,
  );
}

/** 删除按钮执行软归档，保留历史订阅、订单和计费关联。 */
export function archiveSubscriptionPlan(id: string, version: number): Promise<AdminSubscriptionPlan> {
  return adminWrite(`/api/v1/admin/subscription-plans/${encodeURIComponent(id)}`, "DELETE", { version });
}

/** 批量启停模型共用管理员 CSRF，服务端限制最多 100 个模型并返回实际变化数量。 */
export function batchUpdateModelStatus(modelIds: string[], status: "active" | "disabled"): Promise<{
  status: "active" | "disabled";
  requested_count: number;
  updated_count: number;
  unchanged_count: number;
}> {
  return adminWrite("/api/v1/admin/models/batch-status", "POST", { model_ids: modelIds, status });
}

/** 同步源地址固定在服务端配置中，前端只读取运行状态和安全计数。 */
export function getModelSyncStatus(): Promise<ModelSyncStatus> {
  return apiDataRequest("/api/v1/admin/models/sync", { cache: "no-store" });
}

export function updateModelSyncSettings(input: ModelSyncSettingsInput): Promise<ModelSyncStatus> {
  return adminWrite("/api/v1/admin/models/sync", "PUT", input);
}

/** 立即同步与定时同步共用服务端分布式锁，浏览器不传上游地址或凭证。 */
export function runModelSync(): Promise<ModelSyncRun> {
  return adminWrite("/api/v1/admin/models/sync/run", "POST", {});
}

export function listChannels(query: AdminListQuery = {}): Promise<AdminPage<Channel>> {
  return apiDataRequest(`/api/v1/admin/channels?${toQueryString(query)}`, { cache: "no-store" });
}

export function listChannelOperations(): Promise<ChannelOperation[]> {
  return apiDataRequest<ChannelOperation[]>("/api/v1/admin/channel-operations", { cache: "no-store" }).catch(error => {
    // 生产后端尚未部署该只读目录接口时，不应阻断渠道管理主流程。
    if (error instanceof ApiError && error.status === 404) return DEFAULT_CHANNEL_OPERATIONS;
    throw error;
  });
}

export function saveChannel(input: ChannelInput, id?: string): Promise<Channel> {
  return adminWrite(id ? `/api/v1/admin/channels/${id}` : "/api/v1/admin/channels", id ? "PUT" : "POST", input);
}

export function listRoutingGroups(query: AdminListQuery = {}): Promise<AdminPage<RoutingGroup>> {
  return apiDataRequest(`/api/v1/admin/groups?${toQueryString(query)}`, { cache: "no-store" });
}

export function saveRoutingGroup(input: RoutingGroupInput, id?: string): Promise<RoutingGroup> {
  return adminWrite(id ? `/api/v1/admin/groups/${id}` : "/api/v1/admin/groups", id ? "PUT" : "POST", input);
}

export function getGroupConfiguration(groupId: string): Promise<GroupConfiguration> {
  return apiDataRequest(`/api/v1/admin/groups/${encodeURIComponent(groupId)}/configuration`, { cache: "no-store" });
}

/**
 * 组合保存模型勾选、路由参数和分组独立上游 APIKey。
 * credential 只存在于本次请求对象中，调用方应在请求结束后立即清空表单状态。
 */
export function saveGroupConfiguration(groupId: string, input: GroupConfigurationInput): Promise<GroupConfiguration> {
  return adminWrite(`/api/v1/admin/groups/${encodeURIComponent(groupId)}/configuration`, "PUT", input);
}

export function getGroupUserGrants(groupId: string): Promise<GroupUserGrant[]> {
  return apiDataRequest(`/api/v1/admin/groups/${encodeURIComponent(groupId)}/user-grants`, { cache: "no-store" });
}

/** 保存时只上传用户 ID，禁止把邮箱、API 令牌或上游 APIKey 混入授权请求。 */
export function saveGroupUserGrants(groupId: string, userIds: string[]): Promise<GroupUserGrant[]> {
  return adminWrite(`/api/v1/admin/groups/${encodeURIComponent(groupId)}/user-grants`, "PUT", { user_ids: userIds });
}

export function listAdminUsers(query: AdminListQuery = {}): Promise<AdminPage<AdminUser>> {
  return apiDataRequest(`/api/v1/admin/users?${toQueryString(query)}`, { cache: "no-store" });
}

export function saveAdminUser(input: AdminUserInput, id: string): Promise<AdminUser> {
  return adminWrite(`/api/v1/admin/users/${id}`, "PUT", input);
}

export function listAuditLogs(query: AdminListQuery = {}): Promise<AdminPage<AuditLog>> {
  return apiDataRequest(`/api/v1/admin/audit-logs?${toQueryString(query)}`, { cache: "no-store" });
}

function toRequestLogQuery(query: AdminRequestLogQuery = {}): string {
  const params = new URLSearchParams({ page: String(query.page ?? 1), page_size: String(query.pageSize ?? 20) });
  if (query.query?.trim()) params.set("query", query.query.trim());
  if (query.status) params.set("status", query.status);
  if (query.model?.trim()) params.set("model", query.model.trim());
  if (query.period) params.set("period", query.period);
  return params.toString();
}

export function listAdminRequestLogs(query: AdminRequestLogQuery = {}): Promise<AdminPage<AdminRequestLog>> {
  return apiDataRequest(`/api/v1/admin/request-logs?${toRequestLogQuery(query)}`, { cache: "no-store" });
}

export function getAdminRequestLog(requestId: string): Promise<AdminRequestLog> {
  return apiDataRequest(`/api/v1/admin/request-logs/${encodeURIComponent(requestId)}`, { cache: "no-store" });
}

export function listChannelHealth(query: AdminListQuery = {}): Promise<AdminPage<ChannelHealth>> {
  return apiDataRequest(`/api/v1/admin/health/channels?${toQueryString(query)}`, { cache: "no-store" });
}

export function listGroupHealth(query: AdminListQuery = {}): Promise<AdminPage<GroupHealth>> {
  return apiDataRequest(`/api/v1/admin/health/groups?${toQueryString(query)}`, { cache: "no-store" });
}

export function listHealthChecks(query: AdminListQuery = {}): Promise<AdminPage<HealthCheck>> {
  return apiDataRequest(`/api/v1/admin/health/checks?${toQueryString(query)}`, { cache: "no-store" });
}

export function listHealthAlerts(query: AdminListQuery = {}): Promise<AdminPage<HealthAlert>> {
  return apiDataRequest(`/api/v1/admin/health/alerts?${toQueryString(query)}`, { cache: "no-store" });
}

/** 经营接口只发送时间范围和可选供应商筛选，不上传用户标识、API 令牌、上游 APIKey、渠道凭证或请求正文。 */
export function getAdminDashboard(query: DashboardQuery = {}): Promise<DashboardOverview> {
  const params = new URLSearchParams();
  params.set("preset", query.preset ?? "7d");
  if (query.from) params.set("from", query.from);
  if (query.to) params.set("to", query.to);
  if (query.supplierId) params.set("supplier_id", query.supplierId);
  return apiDataRequest(`/api/v1/admin/dashboard/overview?${params.toString()}`, { cache: "no-store" });
}

/**
 * 获取运营总览资源真实数量。
 *
 * 使用现有列表接口返回的 total，而不是统计当前页 items；因此模型超过 100 条时仍能得到真实总数。
 * 这里不依赖较新的 dashboard/resources 聚合端点，确保前端可与尚未包含该端点的正式后端兼容。
 */
export async function getAdminResourceOverview(): Promise<AdminResourceOverview> {
  const [suppliers, activeSuppliers, models, activeModels, channels, activeChannels, openAlerts] = await Promise.all([
    listSuppliers({ page: 1, pageSize: 1 }),
    listSuppliers({ page: 1, pageSize: 1, status: "active" }),
    listModels({ page: 1, pageSize: 1 }),
    listModels({ page: 1, pageSize: 1, status: "active" }),
    listChannels({ page: 1, pageSize: 1 }),
    listChannels({ page: 1, pageSize: 1, status: "active" }),
    listHealthAlerts({ page: 1, pageSize: 1, status: "open" }),
  ]);
  return {
    suppliers: { total: suppliers.total, active: activeSuppliers.total },
    models: { total: models.total, active: activeModels.total },
    channels: { total: channels.total, active: activeChannels.total },
    open_alert_count: openAlerts.total,
  };
}

/** 手动探测使用 Cookie Session + 临时 CSRF，浏览器不保存上游凭证或 Authorization。 */
export function probeChannelHealth(channelId: string): Promise<ManualProbeResult> {
  return adminWrite(`/api/v1/admin/health/channels/${encodeURIComponent(channelId)}/probe`, "POST", {});
}

export function adminErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    if (error instanceof Error && error.message.startsWith("高级配置")) return error.message;
    return "操作失败，请稍后重试";
  }
  if (error.status === 401) return "登录状态已失效，请重新登录";
  if (error.status === 403) return "当前账号没有管理员权限";
  if (error.code === "USER_SELF_PROTECTION") return error.message;
  if (error.status === 409) return error.message || "数据已被其他管理员更新，请刷新后重试";
  return error.message || "操作失败，请稍后重试";
}

export function formatAdminTime(value: string | null): string {
  if (!value) return "尚未记录";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(value));
}

export function compactNumber(value: number | null | undefined): string {
  if (value == null) return "—";
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value >= 10_000_000 ? 0 : 1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(value >= 100_000 ? 0 : 1)}K`;
  return String(value);
}

/**
 * 金额始终按服务端的十进制字符串格式化，包括 PostgreSQL 可能返回的 0E-8。
 * 界面最多展示三位小数；这里不转成 JavaScript number，避免财务金额丢失精度。
 */
export function formatDashboardAmount(value: string | number | null | undefined): string {
  // BigDecimal values are normally serialized as strings, but some deployments
  // (and older API gateways) return JSON numbers. Normalize at the boundary so
  // a numeric billed_amount cannot crash a detail drawer during rendering.
  const raw = value == null ? "" : String(value);
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw.trim());
  if (!match) return raw || "—";

  const integerPart = match[2] ?? "0";
  const fractionPart = match[3] ?? "";
  const exponent = Number(match[4] ?? 0);
  if (!Number.isSafeInteger(exponent)) return value;

  const digits = `${integerPart}${fractionPart}`;
  const decimalIndex = integerPart.length + exponent;
  let integer: string;
  let fraction: string;
  if (decimalIndex <= 0) {
    integer = "0";
    fraction = `${"0".repeat(-decimalIndex)}${digits}`;
  } else if (decimalIndex >= digits.length) {
    integer = `${digits}${"0".repeat(decimalIndex - digits.length)}`;
    fraction = "";
  } else {
    integer = digits.slice(0, decimalIndex);
    fraction = digits.slice(decimalIndex);
  }

  integer = integer.replace(/^0+(?=\d)/, "");

  // 在字符串层完成三位小数四舍五入，保留数据库原始计算精度。
  const maxFractionDigits = 3;
  if (fraction.length > maxFractionDigits) {
    const keptFraction = fraction.slice(0, maxFractionDigits);
    const shouldRoundUp = Number(fraction[maxFractionDigits]) >= 5;
    const scaled = BigInt(`${integer}${keptFraction.padEnd(maxFractionDigits, "0")}` || "0") + (shouldRoundUp ? 1n : 0n);
    const rounded = scaled.toString().padStart(maxFractionDigits + 1, "0");
    integer = rounded.slice(0, -maxFractionDigits);
    fraction = rounded.slice(-maxFractionDigits);
  }

  fraction = fraction.slice(0, maxFractionDigits).replace(/0+$/, "");
  const isZero = /^0+$/.test(integer) && !fraction;
  const sign = match[1] && !isZero ? "-" : "";
  const groupedInteger = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return fraction ? `${sign}${groupedInteger}.${fraction}` : `${sign}${groupedInteger}`;
}

/** 空数据或非有限延迟不显示 NaN，统一降级为占位符。 */
export function formatDashboardLatency(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(value)} ms`;
}
