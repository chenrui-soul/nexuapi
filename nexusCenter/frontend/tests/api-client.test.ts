import assert from "node:assert/strict";
import test from "node:test";
import { ApiError, apiDataRequest, apiRequest, resolvePublicApiV1BaseUrl } from "../lib/api.ts";

test("resolvePublicApiV1BaseUrl uses the configured public backend address", () => {
  assert.equal(resolvePublicApiV1BaseUrl(undefined), "/v1");
  assert.equal(resolvePublicApiV1BaseUrl("http://localhost:8080/"), "http://localhost:8080/v1");
  assert.equal(resolvePublicApiV1BaseUrl("https://api.example.com/v1"), "https://api.example.com/v1");
});

test("apiRequest sends JSON requests with credentials", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    assert.equal(input, "/api/models");
    assert.equal(init?.credentials, "include");
    assert.equal(new Headers(init?.headers).get("Content-Type"), "application/json");
    return Response.json({ models: ["gpt-4.1"] });
  };

  try {
    assert.deepEqual(await apiRequest<{ models: string[] }>("/api/models", { method: "POST", body: "{}" }), {
      models: ["gpt-4.1"],
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiRequest exposes backend status and payload", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({ message: "余额不足" }, { status: 402 });

  try {
    await assert.rejects(apiRequest("/api/wallet"), (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 402);
      assert.deepEqual(error.payload, { message: "余额不足" });
      return true;
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiDataRequest unwraps the standard backend envelope", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({
    success: true,
    data: { id: "user-1" },
    request_id: "req-1",
  });

  try {
    assert.deepEqual(await apiDataRequest<{ id: string }>("/api/v1/auth/me"), { id: "user-1" });
  } finally {
    globalThis.fetch = originalFetch;
  }
});
