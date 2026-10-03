import assert from "node:assert/strict";
import test from "node:test";
import {
  createSystemAccessToken,
  listSystemAccessScopes,
  listSystemAccessTokens,
  revokeSystemAccessToken,
  setSystemAccessTokenStatus,
} from "../lib/system-access-tokens.ts";

function ok<T>(data: T, status = 200): Response {
  return Response.json({ success: true, data, request_id: "req-system-token" }, { status });
}

const backendToken = {
  id: "token-1", name: "报表系统", masked_token: "nx-sys-v1_abc...xyz",
  scopes: ["dashboard:read"], ip_allowlist: [], status: "active" as const,
  expires_at: null, last_used_at: null, created_at: "2026-08-25T00:00:00Z",
  updated_at: "2026-08-25T00:00:00Z", version: 0,
};

test("system access tokens use their own management endpoints and preserve one-time secret", async () => {
  const originalFetch = globalThis.fetch;
  let call = 0;
  globalThis.fetch = async (request, init) => {
    call += 1;
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-value" });
    assert.equal(path, "/api/v1/system-access-tokens");
    assert.equal(init?.method, "POST");
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-value");
    assert.deepEqual(JSON.parse(String(init?.body)), {
      name: "报表系统", scopes: ["dashboard:read"], ip_allowlist: [], expires_at: null,
    });
    return ok({ token: backendToken, secret: "nx-sys-v1_once-only" }, 201);
  };
  try {
    const created = await createSystemAccessToken({
      name: "报表系统", scopes: ["dashboard:read"], ipAllowlist: [], expiresAt: null,
    });
    assert.equal(created.secret, "nx-sys-v1_once-only");
    assert.equal(created.token.maskedToken, backendToken.masked_token);
    assert.equal(call, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("system token list, scopes, status and revoke never use API key or supplier endpoints", async () => {
  const originalFetch = globalThis.fetch;
  const paths: string[] = [];
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    paths.push(path);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-value" });
    if (path.endsWith("/scopes")) return ok([{ code: "dashboard:read", label: "仪表盘只读" }]);
    if (path.endsWith("/status")) {
      assert.equal(init?.method, "PUT");
      assert.deepEqual(JSON.parse(String(init?.body)), { status: "disabled", version: 0 });
      return ok({ ...backendToken, status: "disabled", version: 1 });
    }
    if (init?.method === "DELETE") return ok({ revoked: true });
    return ok([backendToken]);
  };
  try {
    assert.equal((await listSystemAccessTokens())[0].id, "token-1");
    assert.equal((await listSystemAccessScopes())[0].code, "dashboard:read");
    await setSystemAccessTokenStatus({
      id: "token-1", name: "报表系统", maskedToken: backendToken.masked_token,
      scopes: ["dashboard:read"], ipAllowlist: [], status: "active", expiresAt: null,
      lastUsedAt: null, createdAt: backendToken.created_at, updatedAt: backendToken.updated_at, version: 0,
    }, "disabled");
    await revokeSystemAccessToken("token-1");
    assert.equal(paths.some((path) => path.includes("api-keys") || path.includes("suppliers")), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
