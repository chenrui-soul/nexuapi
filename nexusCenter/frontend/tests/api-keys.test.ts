import assert from "node:assert/strict";
import test from "node:test";
import {
  createApiKey,
  listApiKeys,
  listAvailableModels,
  listPublicServiceGroups,
  revokeApiKey,
  setApiKeyStatus,
  updateApiKey,
  type ApiKeyInput,
} from "../lib/api-keys.ts";

const input: ApiKeyInput = {
  name: "Production Web",
  serviceGroupId: "group-1",
  allowedModelIds: [],
  ipAllowlist: [],
  rpmLimit: null,
  tpmLimit: null,
  concurrencyLimit: null,
  creditLimit: 100,
  expiresAt: null,
};

function ok<T>(data: T, status = 200): Response {
  return Response.json({ success: true, data, request_id: "req-api-key" }, { status });
}

const backendItem = {
  id: "key-1",
  name: "Production Web",
  masked_key: "sk-nx-v1_Q8w7...c91f2a0b",
  status: "active" as const,
  service_group_id: "group-1",
  service_group_name: "高质量组",
  default_group_id: "group-1",
  allowed_model_ids: [],
  allowed_group_ids: [],
  ip_allowlist: [],
  rpm_limit: null,
  tpm_limit: null,
  concurrency_limit: null,
  credit_limit: 100,
  used_credits: "12.345600000000",
  reserved_credits: "1.250000000000",
  remaining_credits: "86.404400000000",
  expires_at: null,
  last_used_at: null,
  created_at: "2026-08-17T08:00:00Z",
  updated_at: "2026-08-17T08:00:00Z",
  version: 0,
};

test("listApiKeys maps the paginated backend contract", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (request, init) => {
    assert.equal(String(request), "/api/v1/api-keys?page=1&page_size=100");
    assert.equal(init?.credentials, "include");
    return ok({ items: [backendItem], total: 1, page: 1, page_size: 100 });
  };
  try {
    const items = await listApiKeys();
    assert.equal(items[0].maskedKey, backendItem.masked_key);
    assert.equal(items[0].creditLimit, 100);
    assert.equal(items[0].usedCredits, "12.345600000000");
    assert.equal(items[0].reservedCredits, "1.250000000000");
    assert.equal(items[0].remainingCredits, "86.404400000000");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listApiKeys normalizes omitted optional limits to null", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => ok({
    items: [{
      ...backendItem,
      credit_limit: undefined,
      expires_at: undefined,
      rpm_limit: undefined,
      tpm_limit: undefined,
      concurrency_limit: undefined,
      used_credits: undefined,
      reserved_credits: undefined,
      remaining_credits: undefined,
      last_used_at: undefined,
    }],
    total: 1,
    page: 1,
    page_size: 100,
  });
  try {
    const [item] = await listApiKeys();
    assert.equal(item.creditLimit, null);
    assert.equal(item.expiresAt, null);
    assert.equal(item.rpmLimit, null);
    assert.equal(item.lastUsedAt, null);
    assert.equal(item.usedCredits, "0");
    assert.equal(item.reservedCredits, "0");
    assert.equal(item.remainingCredits, null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("createApiKey obtains CSRF and preserves the one-time secret", async () => {
  const originalFetch = globalThis.fetch;
  let call = 0;
  globalThis.fetch = async (request, init) => {
    call += 1;
    if (call === 1) return ok({ header: "X-CSRF-TOKEN", token: "csrf-value" });
    assert.equal(String(request), "/api/v1/api-keys");
    assert.equal(init?.method, "POST");
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-value");
    assert.deepEqual(JSON.parse(String(init?.body)), {
      name: input.name,
      service_group_id: "group-1",
      default_group_id: "group-1",
      allowed_model_ids: [],
      allowed_group_ids: ["group-1"],
      ip_allowlist: [],
      rpm_limit: null,
      tpm_limit: null,
      concurrency_limit: null,
      credit_limit: 100,
      expires_at: null,
    });
    return ok({
      id: "key-1",
      name: input.name,
      secret: "sk-nx-v1_once-only",
      masked_key: backendItem.masked_key,
      status: "active",
      created_at: backendItem.created_at,
      version: 0,
    }, 201);
  };
  try {
    const created = await createApiKey(input);
    assert.equal(created.secret, "sk-nx-v1_once-only");
    assert.equal(call, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("update status and revoke all use CSRF-protected backend writes", async () => {
  const originalFetch = globalThis.fetch;
  const writes: string[] = [];
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-value" });
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-value");
    writes.push(`${init?.method} ${path}`);
    if (init?.method === "DELETE") return ok({ revoked: true });
    if (init?.method === "PATCH") {
      assert.deepEqual(JSON.parse(String(init.body)), {
        name: input.name,
        service_group_id: "group-1",
        default_group_id: "group-1",
        allowed_model_ids: [],
        allowed_group_ids: ["group-1"],
        ip_allowlist: [],
        rpm_limit: null,
        tpm_limit: null,
        concurrency_limit: null,
        credit_limit: 100,
        expires_at: null,
        version: 0,
      });
    }
    if (init?.method === "PUT") {
      assert.deepEqual(JSON.parse(String(init.body)), { status: "disabled", version: 1 });
    }
    return ok({ ...backendItem, version: backendItem.version + 1 });
  };
  try {
    await updateApiKey("key-1", input, 0);
    await setApiKeyStatus("key-1", "disabled", 1);
    await revokeApiKey("key-1");
    assert.deepEqual(writes, [
      "PATCH /api/v1/api-keys/key-1",
      "PUT /api/v1/api-keys/key-1/status",
      "DELETE /api/v1/api-keys/key-1",
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listAvailableModels maps model ids and public names used by the whitelist UI", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (request, init) => {
    assert.equal(String(request), "/api/v1/models");
    assert.equal(init?.method, "GET");
    return ok([{
      id: "model-1",
      public_name: "gpt-5.6-sol",
      display_name: "GPT 5.6 Sol",
    }]);
  };
  try {
    const models = await listAvailableModels();
    assert.deepEqual(models, [{
      id: "model-1",
      publicName: "gpt-5.6-sol",
      displayName: "GPT 5.6 Sol",
    }]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listAvailableModels filters the whitelist through the selected service group", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (request) => {
    assert.equal(String(request), "/api/v1/model-market?page=1&page_size=100&sort=name&service_group_id=group-1");
    return ok({
      items: [{
        id: "model-1",
        public_name: "gpt-5.6-sol",
        display_name: "GPT 5.6 Sol",
        provider: "OpenAI",
        capability_type: "text",
        context_window: null,
        max_output_tokens: null,
        supports_streaming: true,
        supports_tools: true,
        supports_structured_output: true,
        service_group_id: "group-1",
        service_group_name: "高质量组",
        price_multiplier: 1.5,
        base_input_price: 1,
        base_output_price: 2,
        effective_input_price: 1.5,
        effective_output_price: 3,
        effective_cached_input_price: 0,
        price_unit: "million_tokens",
        availability: "in_group",
      }],
      total: 1,
      page: 1,
      page_size: 100,
      service_group_id: "group-1",
      service_group_name: "高质量组",
      price_multiplier: 1.5,
    });
  };
  try {
    const models = await listAvailableModels("group-1");
    assert.deepEqual(models, [{ id: "model-1", publicName: "gpt-5.6-sol", displayName: "GPT 5.6 Sol" }]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listAvailableModels loads every model page in a large service group", async () => {
  const originalFetch = globalThis.fetch;
  const requested: string[] = [];
  globalThis.fetch = async (request) => {
    const path = String(request);
    requested.push(path);
    const page = Number(new URL(path, "http://local").searchParams.get("page"));
    return ok({
      items: [{
        id: `model-${page}`,
        public_name: `model-name-${page}`,
        display_name: `Model ${page}`,
        provider: "Provider",
        capability_type: "text",
        context_window: null,
        max_output_tokens: null,
        supports_streaming: true,
        supports_tools: true,
        supports_structured_output: true,
        service_group_id: "group-1",
        service_group_name: "高质量组",
        price_multiplier: 1,
        base_input_price: 1,
        base_output_price: 2,
        effective_input_price: 1,
        effective_output_price: 2,
        effective_cached_input_price: 0,
        price_unit: "million_tokens",
        availability: "in_group",
      }],
      total: 201,
      page,
      page_size: 100,
      service_group_id: "group-1",
      service_group_name: "高质量组",
      price_multiplier: 1,
    });
  };
  try {
    const models = await listAvailableModels("group-1");
    assert.deepEqual(requested, [
      "/api/v1/model-market?page=1&page_size=100&sort=name&service_group_id=group-1",
      "/api/v1/model-market?page=2&page_size=100&sort=name&service_group_id=group-1",
      "/api/v1/model-market?page=3&page_size=100&sort=name&service_group_id=group-1",
    ]);
    assert.deepEqual(models.map((model) => model.id), ["model-1", "model-2", "model-3"]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listPublicServiceGroups exposes only the safe public catalog fields", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (request, init) => {
    assert.equal(String(request), "/api/v1/service-groups");
    assert.equal(init?.method, "GET");
    return ok([{
      id: "group-1",
      code: "premium",
      name: "高质量组",
      description: "稳定高质量线路",
      price_multiplier: 1.5,
      model_count: 8,
    }]);
  };
  try {
    const groups = await listPublicServiceGroups();
    assert.deepEqual(groups, [{
      id: "group-1",
      code: "premium",
      name: "高质量组",
      description: "稳定高质量线路",
      priceMultiplier: 1.5,
      modelCount: 8,
    }]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
