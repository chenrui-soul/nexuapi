import { apiDataRequest } from "./api.ts";

export type UserRequestLog = {
  id: string;
  request_id: string;
  started_at: string;
  completed_at: string | null;
  duration_ms: number | null;
  public_model: string;
  api_key_name: string | null;
  service_group_name: string | null;
  status_code: number | null;
  status: "success" | "failed";
  input_tokens: number;
  output_tokens: number;
  cached_tokens: number;
  billed_amount: string;
  streaming: boolean;
  retry_count: number;
  failure_reason: string | null;
  request_summary?: string | null;
  response_summary?: string | null;
  request_payload_size?: number;
  response_payload_size?: number;
};

export type UserRequestLogPage = {
  items: UserRequestLog[];
  total: number;
  page: number;
  pageSize: number;
};

export type UserRequestLogQuery = {
  page?: number;
  pageSize?: number;
  query?: string;
  status?: "success" | "failed";
  model?: string;
  period?: "today" | "24h" | "7d" | "30d" | "custom";
  from?: string;
  to?: string;
};

type BackendPage = Omit<UserRequestLogPage, "pageSize"> & { page_size: number };

export async function listUserRequestLogs(query: UserRequestLogQuery = {}): Promise<UserRequestLogPage> {
  const params = new URLSearchParams({
    page: String(query.page ?? 1),
    page_size: String(query.pageSize ?? 20),
    period: query.period ?? "24h",
  });
  if (query.query?.trim()) params.set("query", query.query.trim());
  if (query.status) params.set("status", query.status);
  if (query.model) params.set("model", query.model);
  if (query.from && query.to) {
    params.set("from", query.from);
    params.set("to", query.to);
  }
  const page = await apiDataRequest<BackendPage>(`/api/v1/request-logs?${params.toString()}`, {
    method: "GET",
    cache: "no-store",
  });
  return { ...page, pageSize: page.page_size };
}

export function getUserRequestLog(requestId: string): Promise<UserRequestLog> {
  return apiDataRequest<UserRequestLog>(`/api/v1/request-logs/${encodeURIComponent(requestId)}`, {
    method: "GET",
    cache: "no-store",
  });
}
