import { pageDataRequest } from "./api.ts";

export type DashboardPreset = "today" | "7d" | "30d";

export type UserDashboardSummary = {
  request_count: number;
  success_count: number;
  failure_count: number;
  success_rate: number;
  input_tokens: number;
  output_tokens: number;
  cached_tokens: number;
  cache_hit_rate: number;
  billed_amount: string;
  average_billed_amount: string;
  average_latency_ms: number;
  latency_p50_ms: number | null;
  latency_p95_ms: number | null;
};

export type DashboardTrendPoint = {
  bucket_start: string;
  request_count: number;
  success_count: number;
  failure_count: number;
  billed_amount: string;
  input_tokens: number;
  output_tokens: number;
  cached_tokens: number;
};

export type DashboardRankingItem = {
  dimension_id: string;
  dimension_name: string;
  request_count: number;
  billed_amount: string;
  percentage: number;
};

export type DashboardRecentRequest = {
  request_id: string;
  started_at: string;
  public_model: string;
  api_key_name: string | null;
  service_group_name: string | null;
  status_code: number | null;
  status: "success" | "failed";
  input_tokens: number;
  output_tokens: number;
  cached_tokens: number;
  billed_amount: string;
  duration_ms: number | null;
  streaming: boolean;
  retry_count: number;
  failure_reason: string | null;
};

export type UserDashboardOverview = {
  preset: DashboardPreset | "custom";
  from: string;
  to: string;
  bucket_size: "hour" | "day";
  updated_at: string | null;
  summary: UserDashboardSummary;
  comparison: {
    request_change_rate: number | null;
    billed_change_rate: number | null;
  };
  trend: DashboardTrendPoint[];
  capability_distribution: Array<{
    capability_type: string;
    request_count: number;
    billed_amount: string;
    percentage: number;
  }>;
  rankings: {
    models: DashboardRankingItem[];
    api_keys: DashboardRankingItem[];
    groups: DashboardRankingItem[];
  };
  recent_requests: DashboardRecentRequest[];
  activity_heatmap: Array<{
    day_of_week: number;
    hour_of_day: number;
    request_count: number;
    billed_amount: string;
  }>;
  live_metrics: {
    request_count: number;
    requests_per_second: number;
    rpm: number;
    tokens_per_minute: number;
    cached_tokens: number;
    billed_amount_per_minute: string;
  };
};

export type UserGroupHistoryItem = {
  status: "success" | "failed";
  occurred_at: string;
  duration_ms: number | null;
  status_code: number | null;
};

export type UserGroupStatusItem = {
  id: string;
  name: string;
  description: string | null;
  price_multiplier: string;
  status: "normal" | "partial" | "unavailable" | "no_data" | "unconfigured";
  availability: number;
  average_latency_ms: number;
  latency_p95_ms: number | null;
  available_model_count: number;
  total_model_count: number;
  sample_count: number;
  last_request_at: string | null;
  history: UserGroupHistoryItem[];
};

export type UserGroupStatusResponse = {
  updated_at: string | null;
  sample_limit: number;
  groups: UserGroupStatusItem[];
};

export function getUserDashboardOverview(query: {
  preset?: DashboardPreset;
  from?: string;
  to?: string;
} = {}, force = false): Promise<UserDashboardOverview> {
  const params = new URLSearchParams({ preset: query.preset ?? "today" });
  if (query.from && query.to) {
    params.set("from", query.from);
    params.set("to", query.to);
  }
  return pageDataRequest<UserDashboardOverview>(`/api/v1/dashboard/overview?${params.toString()}`, 30_000, force);
}

export function getUserGroupStatuses(force = false): Promise<UserGroupStatusResponse> {
  return pageDataRequest<UserGroupStatusResponse>("/api/v1/status/groups", 15_000, force);
}
