/** 用户模型市场请求层：默认读取公开目录，传入服务分组后读取分组模型和倍率价格。 */
import { apiDataRequest, pageDataRequest } from "./api.ts";

export type ModelMarketItem = {
  id: string;
  publicName: string;
  displayName: string;
  provider: string;
  capabilityType: string;
  contextWindow: number | null;
  maxOutputTokens: number | null;
  supportsStreaming: boolean;
  supportsTools: boolean;
  supportsStructuredOutput: boolean;
  serviceGroupId: string | null;
  serviceGroupName: string | null;
  priceMultiplier: string | null;
  baseInputPrice: string;
  baseOutputPrice: string;
  effectiveInputPrice: string;
  effectiveOutputPrice: string;
  effectiveCachedInputPrice: string;
  priceUnit: "million_tokens" | string;
  billingType: 1 | 2 | 3 | 4 | 5 | 6;
  billingUnit: string;
  baseUnitPrice: string;
  effectiveUnitPrice: string;
  displayOriginalPrice: string;
  inputTokenRatio: number;
  outputTokenRatio: number;
  audioInputTokenRatio: number;
  audioOutputTokenRatio: number;
  cachedInputTokenRatio: number;
  cacheWrite5mTokenRatio: number;
  cacheWrite1hTokenRatio: number;
  chargeDesc: string | null;
  pricingVersionId: string | null;
  availability: "catalog" | "in_group";
  recommendedServiceGroupId: string | null;
  recommendedServiceGroupName: string | null;
  recommendedPriceMultiplier: string | null;
  recommendedEffectiveInputPrice: string | null;
  recommendedEffectiveOutputPrice: string | null;
  recommendedEffectiveCachedInputPrice: string | null;
  recommendedEffectiveUnitPrice: string | null;
};

export type ModelMarketPage = {
  items: ModelMarketItem[];
  total: number;
  page: number;
  pageSize: number;
  serviceGroupId: string | null;
  serviceGroupName: string | null;
  priceMultiplier: string | null;
};

export type ModelInterfaceField = {
  name: string;
  path: string | null;
  type: string;
  required: boolean;
  description: string | null;
  defaultValue: unknown;
  example: unknown;
  enumValues: string[];
  minimum: string | null;
  maximum: string | null;
  deprecated: boolean;
  children: ModelInterfaceField[];
};

export type ModelMarketInterface = {
  id: string;
  interfaceCode: string;
  interfaceName: string;
  interfaceVersion: string;
  capabilityType: string;
  transportMode: string;
  httpMethod: string;
  publicPath: string;
  requestContentType: string;
  description: string | null;
  requestFields: ModelInterfaceField[];
  responseFields: ModelInterfaceField[];
};

export type ModelMarketGroupPrice = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  priceMultiplier: string;
  effectiveInputPrice: string;
  effectiveOutputPrice: string;
  effectiveCachedInputPrice: string;
  effectiveUnitPrice: string;
};

export type ModelMarketDetail = {
  model: ModelMarketItem;
  interfaces: ModelMarketInterface[];
  serviceGroups: ModelMarketGroupPrice[];
};

export type ModelMarketQuery = {
  serviceGroupId?: string;
  page?: number;
  pageSize?: number;
  query?: string;
  provider?: string;
  capabilityType?: string;
  supportsStreaming?: boolean;
  supportsTools?: boolean;
  sort?: "name" | "price_asc" | "price_desc";
};

type BackendModelMarketItem = {
  id: string;
  public_name: string;
  display_name: string;
  provider: string;
  capability_type: string;
  context_window: number | null;
  max_output_tokens: number | null;
  supports_streaming: boolean;
  supports_tools: boolean;
  supports_structured_output: boolean;
  service_group_id: string | null;
  service_group_name: string | null;
  price_multiplier: string | number | null;
  base_input_price: string | number;
  base_output_price: string | number;
  effective_input_price: string | number;
  effective_output_price: string | number;
  effective_cached_input_price: string | number;
  price_unit: string;
  billing_type: 1 | 2 | 3 | 4 | 5 | 6;
  billing_unit: string;
  base_unit_price: string | number;
  effective_unit_price: string | number;
  display_original_price: string | number;
  input_token_ratio: number;
  output_token_ratio: number;
  audio_input_token_ratio: number;
  audio_output_token_ratio: number;
  cached_input_token_ratio: number;
  cache_write_5m_token_ratio: number;
  cache_write_1h_token_ratio: number;
  charge_desc: string | null;
  pricing_version_id: string | null;
  availability: "catalog" | "in_group";
  recommended_service_group_id: string | null;
  recommended_service_group_name: string | null;
  recommended_price_multiplier: string | number | null;
  recommended_effective_input_price: string | number | null;
  recommended_effective_output_price: string | number | null;
  recommended_effective_cached_input_price: string | number | null;
  recommended_effective_unit_price: string | number | null;
};

type BackendModelMarketPage = {
  items: BackendModelMarketItem[];
  total: number;
  page: number;
  page_size: number;
  service_group_id: string | null;
  service_group_name: string | null;
  price_multiplier: string | number | null;
};

type BackendInterfaceField = {
  name: string;
  path?: string | null;
  type: string;
  required: boolean;
  description?: string | null;
  default_value?: unknown;
  example?: unknown;
  enum_values?: string[] | null;
  minimum?: string | number | null;
  maximum?: string | number | null;
  deprecated: boolean;
  children?: BackendInterfaceField[] | null;
};

type BackendModelMarketDetail = {
  model: BackendModelMarketItem;
  interfaces: Array<{
    id: string;
    interface_code: string;
    interface_name: string;
    interface_version: string;
    capability_type: string;
    transport_mode: string;
    http_method: string;
    public_path: string;
    request_content_type: string;
    description: string | null;
    request_fields: BackendInterfaceField[];
    response_fields: BackendInterfaceField[];
  }>;
  service_groups: Array<{
    id: string;
    code: string;
    name: string;
    description: string | null;
    price_multiplier: string | number;
    effective_input_price: string | number;
    effective_output_price: string | number;
    effective_cached_input_price: string | number;
    effective_unit_price: string | number;
  }>;
};

/** 后端在未选择服务分组时可能省略可选字段；省略和 null 都表示“未选择/未维护”。 */
const nullableDecimalText = (value: string | number | null | undefined) =>
  value === null || value === undefined ? null : String(value);

const decimalText = (value: string | number | null | undefined) =>
  value === null || value === undefined ? "0" : String(value);

const mapMarketItem = (item: BackendModelMarketItem): ModelMarketItem => ({
  id: item.id,
  publicName: item.public_name,
  displayName: item.display_name,
  provider: item.provider,
  capabilityType: item.capability_type,
  contextWindow: item.context_window ?? null,
  maxOutputTokens: item.max_output_tokens ?? null,
  supportsStreaming: item.supports_streaming,
  supportsTools: item.supports_tools,
  supportsStructuredOutput: item.supports_structured_output,
  serviceGroupId: item.service_group_id ?? null,
  serviceGroupName: item.service_group_name ?? null,
  priceMultiplier: nullableDecimalText(item.price_multiplier),
  baseInputPrice: decimalText(item.base_input_price),
  baseOutputPrice: decimalText(item.base_output_price),
  effectiveInputPrice: decimalText(item.effective_input_price),
  effectiveOutputPrice: decimalText(item.effective_output_price),
  effectiveCachedInputPrice: decimalText(item.effective_cached_input_price),
  priceUnit: item.price_unit,
  billingType: item.billing_type,
  billingUnit: item.billing_unit,
  baseUnitPrice: decimalText(item.base_unit_price),
  effectiveUnitPrice: decimalText(item.effective_unit_price),
  displayOriginalPrice: decimalText(item.display_original_price),
  inputTokenRatio: item.input_token_ratio,
  outputTokenRatio: item.output_token_ratio,
  audioInputTokenRatio: item.audio_input_token_ratio,
  audioOutputTokenRatio: item.audio_output_token_ratio,
  cachedInputTokenRatio: item.cached_input_token_ratio,
  cacheWrite5mTokenRatio: item.cache_write_5m_token_ratio,
  cacheWrite1hTokenRatio: item.cache_write_1h_token_ratio,
  chargeDesc: item.charge_desc,
  pricingVersionId: item.pricing_version_id,
  availability: item.availability,
  recommendedServiceGroupId: item.recommended_service_group_id ?? null,
  recommendedServiceGroupName: item.recommended_service_group_name ?? null,
  recommendedPriceMultiplier: nullableDecimalText(item.recommended_price_multiplier),
  recommendedEffectiveInputPrice: nullableDecimalText(item.recommended_effective_input_price),
  recommendedEffectiveOutputPrice: nullableDecimalText(item.recommended_effective_output_price),
  recommendedEffectiveCachedInputPrice: nullableDecimalText(item.recommended_effective_cached_input_price),
  recommendedEffectiveUnitPrice: nullableDecimalText(item.recommended_effective_unit_price),
});

const mapInterfaceField = (field: BackendInterfaceField): ModelInterfaceField => ({
  name: field.name,
  path: field.path ?? null,
  type: field.type,
  required: field.required,
  description: field.description ?? null,
  defaultValue: field.default_value,
  example: field.example,
  enumValues: field.enum_values ?? [],
  minimum: nullableDecimalText(field.minimum),
  maximum: nullableDecimalText(field.maximum),
  deprecated: field.deprecated,
  children: (field.children ?? []).map(mapInterfaceField),
});

export async function listModelMarket(query: ModelMarketQuery = {}, force = false): Promise<ModelMarketPage> {
  const params = new URLSearchParams({
    page: String(query.page ?? 1),
    page_size: String(query.pageSize ?? 12),
    sort: query.sort ?? "name",
  });
  if (query.serviceGroupId) params.set("service_group_id", query.serviceGroupId);
  if (query.query?.trim()) params.set("query", query.query.trim());
  if (query.provider) params.set("provider", query.provider);
  if (query.capabilityType) params.set("capability_type", query.capabilityType);
  if (query.supportsStreaming !== undefined) params.set("supports_streaming", String(query.supportsStreaming));
  if (query.supportsTools !== undefined) params.set("supports_tools", String(query.supportsTools));
  const page = await pageDataRequest<BackendModelMarketPage>(`/api/v1/model-market?${params}`, 60_000, force);
  return {
    items: page.items.map(mapMarketItem),
    total: page.total,
    page: page.page,
    pageSize: page.page_size,
    serviceGroupId: page.service_group_id ?? null,
    serviceGroupName: page.service_group_name ?? null,
    priceMultiplier: nullableDecimalText(page.price_multiplier),
  };
}

/** 读取独立模型详情页数据；接口关系只作为文档展示，不包含内部路由信息。 */
export async function getModelMarketDetail(modelId: string): Promise<ModelMarketDetail> {
  const detail = await apiDataRequest<BackendModelMarketDetail>(
    `/api/v1/model-market/${encodeURIComponent(modelId)}`,
    { method: "GET", cache: "no-store" },
  );
  return {
    model: mapMarketItem(detail.model),
    interfaces: detail.interfaces.map((item) => ({
      id: item.id,
      interfaceCode: item.interface_code,
      interfaceName: item.interface_name,
      interfaceVersion: item.interface_version,
      capabilityType: item.capability_type,
      transportMode: item.transport_mode,
      httpMethod: item.http_method,
      publicPath: item.public_path,
      requestContentType: item.request_content_type,
      description: item.description,
      requestFields: item.request_fields.map(mapInterfaceField),
      responseFields: item.response_fields.map(mapInterfaceField),
    })),
    serviceGroups: detail.service_groups.map((group) => ({
      id: group.id,
      code: group.code,
      name: group.name,
      description: group.description,
      priceMultiplier: decimalText(group.price_multiplier),
      effectiveInputPrice: decimalText(group.effective_input_price),
      effectiveOutputPrice: decimalText(group.effective_output_price),
      effectiveCachedInputPrice: decimalText(group.effective_cached_input_price),
      effectiveUnitPrice: decimalText(group.effective_unit_price),
    })),
  };
}
