import { invalidatePageReads, readPageData } from "./page-read-cache.ts";

const CONFIGURED_API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

/**
 * Browser requests in production must stay same-origin. This deliberately
 * overrides a stale build-time localhost value when the UI is opened from a
 * real hostname, so a release cannot accidentally call the user's own
 * 127.0.0.1:8080. Local development keeps using the configured backend URL.
 */
function resolveRuntimeApiBaseUrl(): string {
  if (typeof window !== "undefined") {
    const hostname = window.location.hostname;
    const isLocalBrowser = hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]";
    if (!isLocalBrowser) return "";
  }
  return CONFIGURED_API_BASE_URL;
}

const API_BASE_URL = resolveRuntimeApiBaseUrl();

/** 用户接入文档使用的公开 v1 地址；与前端实际请求后端的公开地址保持一致。 */
export function resolvePublicApiV1BaseUrl(apiBaseUrl: string | undefined): string {
  const normalized = (apiBaseUrl ?? "").trim().replace(/\/+$/, "");
  if (!normalized) return "/v1";
  return normalized.endsWith("/v1") ? normalized : `${normalized}/v1`;
}

export const PUBLIC_API_V1_BASE_URL = resolvePublicApiV1BaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL);

export type ApiEnvelope<T> = {
  success: boolean;
  data?: T;
  error?: {
    code?: string;
    message?: string;
    details?: unknown;
  };
  request_id?: string;
};

export class ApiError extends Error {
  status: number;
  payload: unknown;
  code?: string;
  requestId?: string;

  constructor(status: number, payload: unknown) {
    const envelope = payload as ApiEnvelope<unknown> | null;
    super(envelope?.error?.message ?? `API request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.payload = payload;
    this.code = envelope?.error?.code;
    this.requestId = envelope?.request_id;
  }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");

  const isWrite = !["GET", "HEAD", "OPTIONS"].includes((init.method ?? "GET").toUpperCase());
  if (isWrite) invalidatePageReads();
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    credentials: "include",
    headers,
  }).finally(() => { if (isWrite) invalidatePageReads(); });
  const payload = response.status === 204 ? null : await response.json().catch(() => null);

  if (response.status === 401) invalidatePageReads();
  if (!response.ok) throw new ApiError(response.status, payload);
  return payload as T;
}

export async function apiDataRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const envelope = await apiRequest<ApiEnvelope<T>>(path, init);
  if (!envelope?.success || envelope.data === undefined) {
    throw new ApiError(200, envelope);
  }
  return envelope.data;
}

/** Only explicitly selected page reads use memory caching; tokens/wallet/security do not. */
export function pageDataRequest<T>(path: string, ttl: number, force = false): Promise<T> {
  return readPageData(path, () => apiDataRequest<T>(path, { method: "GET", cache: "no-store" }), ttl, force);
}
