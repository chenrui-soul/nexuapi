/** 系统访问令牌请求层；与用户 API 令牌完全独立。 */
import { apiDataRequest } from "./api.ts";

export type SystemAccessTokenStatus = "active" | "disabled" | "expired" | "revoked";
export type SystemAccessToken = {
  id: string; name: string; maskedToken: string; scopes: string[]; ipAllowlist: string[];
  status: SystemAccessTokenStatus; expiresAt: string | null; lastUsedAt: string | null;
  createdAt: string; updatedAt: string; version: number;
};
export type SystemAccessScope = { code: string; label: string };

type BackendToken = {
  id: string; name: string; masked_token: string; scopes: string[]; ip_allowlist: string[];
  status: SystemAccessTokenStatus; expires_at: string | null; last_used_at: string | null;
  created_at: string; updated_at: string; version: number;
};

const map = (token: BackendToken): SystemAccessToken => ({
  id: token.id, name: token.name, maskedToken: token.masked_token, scopes: [...token.scopes],
  ipAllowlist: [...token.ip_allowlist], status: token.status, expiresAt: token.expires_at,
  lastUsedAt: token.last_used_at, createdAt: token.created_at, updatedAt: token.updated_at, version: token.version,
});

async function csrfHeaders(): Promise<Record<string, string>> {
  const csrf = await apiDataRequest<{ header: string; token: string }>("/api/v1/auth/csrf", { cache: "no-store" });
  return { [csrf.header]: csrf.token };
}

export async function listSystemAccessTokens(): Promise<SystemAccessToken[]> {
  return (await apiDataRequest<BackendToken[]>("/api/v1/system-access-tokens", { cache: "no-store" })).map(map);
}

export async function listSystemAccessScopes(): Promise<SystemAccessScope[]> {
  return apiDataRequest("/api/v1/system-access-tokens/scopes", { cache: "no-store" });
}

export async function createSystemAccessToken(input: {
  name: string; scopes: string[]; ipAllowlist: string[]; expiresAt: string | null;
}): Promise<{ token: SystemAccessToken; secret: string }> {
  const result = await apiDataRequest<{ token: BackendToken; secret: string }>("/api/v1/system-access-tokens", {
    method: "POST", headers: await csrfHeaders(), body: JSON.stringify({
      name: input.name, scopes: input.scopes, ip_allowlist: input.ipAllowlist, expires_at: input.expiresAt,
    }),
  });
  return { token: map(result.token), secret: result.secret };
}

export async function setSystemAccessTokenStatus(token: SystemAccessToken, status: "active" | "disabled") {
  return map(await apiDataRequest<BackendToken>(`/api/v1/system-access-tokens/${encodeURIComponent(token.id)}/status`, {
    method: "PUT", headers: await csrfHeaders(), body: JSON.stringify({ status, version: token.version }),
  }));
}

export async function revokeSystemAccessToken(id: string): Promise<void> {
  await apiDataRequest(`/api/v1/system-access-tokens/${encodeURIComponent(id)}`, {
    method: "DELETE", headers: await csrfHeaders(),
  });
}
