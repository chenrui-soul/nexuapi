import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  batchUpdateModelStatus, capabilityEndpointLabel, endpointSupportsCapability, listAdminUsers, listAuditLogs, listAdminRequestLogs, getAdminRequestLog, listChannelHealth, listChannels, listGroupHealth,
  activateModelPricingVersion, compactNumber, deleteModelPricingVersion, formatDashboardAmount, formatDashboardLatency, getAdminDashboard, getAdminResourceOverview, getGroupConfiguration, getGroupUserGrants, getModelPricing, getModelSyncStatus, getSupplierDetail, listHealthAlerts, listHealthChecks, listModels, probeChannelHealth, publishModelPricing,
  listChannelOperations, listRoutingGroups, listSuppliers, saveAdminUser, saveChannel,
  resolveDefaultGroupSupplierIds, toManualChannelStatus,
  runModelSync, saveGroupConfiguration, saveGroupUserGrants, saveProtocol, saveRoutingGroup, saveSupplier, updateModelPricingSourceMode, updateModelSyncSettings, type AdminUserInput, type ChannelInput,
  deleteTimePricingRule, listProtocols, listTimePricingRules, saveTimePricingRule, updateTimePricingRuleStatus,
  archiveSubscriptionPlan, getSubscriptionPlanOptions, listSubscriptionPlans, saveSubscriptionPlan,
  type ApiInterfaceInput, type ModelPricingInput, type RoutingGroupInput, type SupplierInput, type TimePricingRuleInput,
  type AdminSubscriptionPlanInput,
} from "../lib/admin.ts";

test("service group supplier selection keeps active associations and defaults its active source supplier", () => {
  const suppliers = [
    { id: "active-with-key", status: "active" },
    { id: "active-without-key", status: "active" },
    { id: "active-unassociated", status: "active" },
    { id: "disabled-associated", status: "disabled" },
  ];
  const associations = [
    { supplier_id: "active-with-key", credential_active: true },
    { supplier_id: "active-without-key", credential_active: false },
    { supplier_id: "disabled-associated", credential_active: true },
  ];

  assert.deepEqual(
    resolveDefaultGroupSupplierIds(suppliers, associations, "active-unassociated"),
    ["active-with-key", "active-without-key", "active-unassociated"],
  );
  assert.deepEqual(
    resolveDefaultGroupSupplierIds(suppliers, associations, "disabled-associated"),
    ["active-with-key", "active-without-key"],
  );
});

function ok<T>(data: T, status = 200): Response {
  return Response.json({ success: true, data, request_id: "req-admin" }, { status });
}

test("dashboard presentation normalizes decimal exponents and missing latency", () => {
  assert.equal(formatDashboardAmount("0E-8"), "0");
  assert.equal(formatDashboardAmount("12345.67000000"), "12,345.67");
  assert.equal(formatDashboardAmount("1.25E+3"), "1,250");
  assert.equal(formatDashboardAmount("1E-3"), "0.001");
  assert.equal(formatDashboardAmount("1.2346"), "1.235");
  assert.equal(formatDashboardLatency(null), "—");
  assert.equal(formatDashboardLatency(undefined), "—");
  assert.equal(formatDashboardLatency(Number.NaN), "—");
  assert.equal(formatDashboardLatency(1234.56), "1,234.6 ms");
});

test("compact number renders omitted optional model token limits as unavailable", () => {
  assert.equal(compactNumber(null), "—");
  assert.equal(compactNumber(undefined), "—");
  assert.equal(compactNumber(128000), "128K");
});

test("protocol field selector exposes the string or array union type", () => {
  const source = readFileSync(new URL("../components/admin/ProtocolsAdminPage.tsx", import.meta.url), "utf8");
  assert.match(source, /<option value="string\|array">string \| array<\/option>/);
});

test("service group route support follows model capability instead of manual channel mappings", () => {
  assert.equal(endpointSupportsCapability("text", "text"), true);
  assert.equal(endpointSupportsCapability("multimodal", "video"), true);
  assert.equal(endpointSupportsCapability("text", "multimodal"), true);
  assert.equal(endpointSupportsCapability("text", "image"), false);
  assert.equal(capabilityEndpointLabel("image"), "图片");
  assert.equal(capabilityEndpointLabel("embedding"), "向量");
});

test("channel edit maps runtime health statuses to administrator writable statuses", () => {
  assert.equal(toManualChannelStatus("active"), "active");
  assert.equal(toManualChannelStatus("disabled"), "disabled");
  assert.equal(toManualChannelStatus("degraded"), "active");
});

test("channel operation catalog falls back when an older backend does not expose it", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({ success: false, error: { code: "NOT_FOUND" } }, { status: 404 });
  try {
    const operations = await listChannelOperations();
    assert.ok(operations.length >= 3);
    assert.equal(operations[0].operation_code, "chat_completions");
    assert.equal(operations[0].public_path, "/v1/chat/completions");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("admin lists serialize pagination and filters with the backend contract", async () => {
  const originalFetch = globalThis.fetch;
  const requests: string[] = [];
  globalThis.fetch = async request => {
    requests.push(String(request));
    return ok({ items: [], total: 0, page: 2, page_size: 20 });
  };
  try {
    await listSuppliers({ page: 2, pageSize: 20, query: "OpenAI", status: "active", healthStatus: "healthy" });
    await listModels({ page: 2, pageSize: 20, query: "gpt", status: "all", capabilityType: "image", provider: "openai", serviceGroup: "premium" });
    await listChannels({ page: 2, pageSize: 20, status: "disabled" });
    await listRoutingGroups({ page: 2, pageSize: 20, query: "premium", status: "degraded" });
    await listAdminUsers({ page: 2, pageSize: 20, query: "admin@example.com", status: "active", role: "admin" });
    await listAuditLogs({ page: 2, pageSize: 20, query: "req-1", resourceType: "user", actorType: "system" });
    await listChannelHealth({ page: 2, pageSize: 20, status: "degraded" });
    await listGroupHealth({ page: 2, pageSize: 20, healthStatus: "unavailable" });
    await listHealthChecks({ page: 2, pageSize: 20, targetType: "channel", status: "degraded" });
    await listHealthAlerts({ page: 2, pageSize: 20, status: "open" });
    await getAdminDashboard({ preset: "30d" });
    assert.deepEqual(requests, [
      "/api/v1/admin/suppliers?page=2&page_size=20&query=OpenAI&status=active&health_status=healthy",
      "/api/v1/admin/models?page=2&page_size=20&query=gpt&capability_type=image&provider=openai&service_group=premium",
      "/api/v1/admin/channels?page=2&page_size=20&status=disabled",
      "/api/v1/admin/groups?page=2&page_size=20&query=premium&status=degraded",
      "/api/v1/admin/users?page=2&page_size=20&query=admin%40example.com&status=active&role=admin",
      "/api/v1/admin/audit-logs?page=2&page_size=20&query=req-1&resource_type=user&actor_type=system",
      "/api/v1/admin/health/channels?page=2&page_size=20&status=degraded",
      "/api/v1/admin/health/groups?page=2&page_size=20&health_status=unavailable",
      "/api/v1/admin/health/checks?page=2&page_size=20&status=degraded&target_type=channel",
      "/api/v1/admin/health/alerts?page=2&page_size=20&status=open",
      "/api/v1/admin/dashboard/overview?preset=30d",
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("dashboard supplier filter is serialized without exposing credentials", async () => {
  const originalFetch = globalThis.fetch;
  const requests: string[] = [];
  globalThis.fetch = async request => {
    requests.push(String(request));
    return ok({});
  };
  try {
    await getAdminDashboard({ preset: "7d", supplierId: "supplier-uuid" });
    assert.deepEqual(requests, [
      "/api/v1/admin/dashboard/overview?preset=7d&supplier_id=supplier-uuid",
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("admin request logs keep payload detail endpoints scoped and encoded", async () => {
  const originalFetch = globalThis.fetch;
  const requests: string[] = [];
  globalThis.fetch = async request => {
    requests.push(String(request));
    return ok({ items: [], total: 0, page: 2, page_size: 20 });
  };
  try {
    await listAdminRequestLogs({ page: 2, pageSize: 20, query: "用户/请求", status: "failed", model: "gpt-5.6-sol", period: "7d" });
    await getAdminRequestLog("req/含空格");
    assert.deepEqual(requests, [
      "/api/v1/admin/request-logs?page=2&page_size=20&query=%E7%94%A8%E6%88%B7%2F%E8%AF%B7%E6%B1%82&status=failed&model=gpt-5.6-sol&period=7d",
      "/api/v1/admin/request-logs/req%2F%E5%90%AB%E7%A9%BA%E6%A0%BC",
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("admin request log table displays the resolved supplier name and code", () => {
  const source = readFileSync(new URL("../components/admin/AdminRequestLogsPage.tsx", import.meta.url), "utf8");
  assert.match(source, /<th>供应商<\/th>/);
  assert.match(source, /item\.supplier_name \|\| "未记录"/);
  assert.match(source, /item\.supplier_code \|\| "—"/);
  assert.match(source, /搜索请求 ID、用户、模型、供应商、令牌或分组/);
});

test("dashboard amount formatting tolerates numeric and missing billed amounts", () => {
  assert.equal(formatDashboardAmount(0.023), "0.023");
  assert.equal(formatDashboardAmount(null), "—");
});

test("model sync controls use admin session CSRF without exposing upstream configuration", async () => {
  const originalFetch = globalThis.fetch;
  const calls: { path: string; method?: string; body?: string }[] = [];
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-model-sync" });
    calls.push({ path, method: init?.method, body: init?.body ? String(init.body) : undefined });
    if (path.endsWith("/run")) {
      assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-model-sync");
      return ok({
        id: "run-1", trigger_type: "manual", status: "succeeded", upstream_total: 206,
        fetched_count: 206, inserted_count: 2, updated_count: 8, unchanged_count: 195,
        skipped_count: 1, group_total: 7, group_inserted_count: 2, group_updated_count: 1,
        group_unchanged_count: 4, group_stale_count: 0, error_code: null, error_summary: null,
        started_at: "2026-08-19T08:00:00Z", completed_at: "2026-08-19T08:00:02Z",
      });
    }
    if (init?.method === "PUT") {
      assert.equal(new Headers(init.headers).get("X-CSRF-TOKEN"), "csrf-model-sync");
      return ok({ enabled: true, interval_minutes: 360, next_run_at: null, running: false, version: 4, last_run: null });
    }
    return ok({ enabled: false, interval_minutes: 360, next_run_at: null, running: false, version: 3, last_run: null });
  };
  try {
    await getModelSyncStatus();
    await updateModelSyncSettings({ enabled: true, interval_minutes: 360, version: 3 });
    const run = await runModelSync();
    assert.deepEqual(calls.map(call => [call.path, call.method]), [
      ["/api/v1/admin/models/sync", undefined],
      ["/api/v1/admin/models/sync", "PUT"],
      ["/api/v1/admin/models/sync/run", "POST"],
    ]);
    assert.equal(calls[1]?.body, JSON.stringify({ enabled: true, interval_minutes: 360, version: 3 }));
    assert.equal(calls[2]?.body, "{}");
    assert.equal(run.group_inserted_count, 2);
    assert.equal(run.group_updated_count, 1);
    assert.equal(run.group_stale_count, 0);
    assert.equal(/authorization|credential|secret|api.?key|password|market-url/i.test(JSON.stringify(calls)), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("model pricing versions use admin CSRF and keep credentials out of price snapshots", async () => {
  const originalFetch = globalThis.fetch;
  const writes: { path: string; method?: string; body?: string }[] = [];
  const response = {
    model_id: "model/id",
    active_pricing_version_id: "version-2",
    pricing_mode: "manual",
    pricing_source_hash: null,
    pricing_source_synced_at: null,
    model_version: 5,
    active: null,
    versions: [],
  };
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-pricing" });
    if (!init?.method) return ok(response);
    assert.equal(new Headers(init.headers).get("X-CSRF-TOKEN"), "csrf-pricing");
    writes.push({ path, method: init.method, body: String(init.body) });
    return ok(response);
  };
  const input: ModelPricingInput = {
    billing_type: 4,
    unit_price: "2.500000000001",
    display_original_price: "5",
    input_token_ratio: 10000,
    output_token_ratio: 40000,
    audio_input_token_ratio: 30000,
    audio_output_token_ratio: 70000,
    cached_input_token_ratio: 2500,
    cache_write_5m_token_ratio: 0,
    cache_write_1h_token_ratio: 0,
    charge_desc: "按 Token 倍率计费",
    context_tier_mode: 0,
    unmatched_behavior: "base",
    rules: [{
      priority: 10,
      name: "高优先级请求",
      match_conditions: { quality: "high" },
      billing_type: null,
      unit_price: null,
      price_multiplier: "1.25",
    }],
    context_tiers: [],
    change_note: "发布 V2",
    model_version: 4,
  };
  try {
    await getModelPricing("model/id");
    await publishModelPricing("model/id", input);
    await activateModelPricingVersion("model/id", "version/id", 5);
    await deleteModelPricingVersion("model/id", "old/version", 7);
    await updateModelPricingSourceMode("model/id", true, 6);
    assert.deepEqual(writes.map(write => [write.path, write.method]), [
      ["/api/v1/admin/models/model%2Fid/pricing", "PUT"],
      ["/api/v1/admin/models/model%2Fid/pricing/versions/version%2Fid/activate", "POST"],
      ["/api/v1/admin/models/model%2Fid/pricing/versions/old%2Fversion", "DELETE"],
      ["/api/v1/admin/models/model%2Fid/pricing/source-mode", "PUT"],
    ]);
    assert.equal(writes[0]?.body, JSON.stringify(input));
    assert.equal(writes[1]?.body, JSON.stringify({ model_version: 5 }));
    assert.equal(writes[2]?.body, JSON.stringify({ model_version: 7 }));
    assert.equal(writes[3]?.body, JSON.stringify({ follow_upstream: true, model_version: 6 }));
    assert.equal(/authorization|credential|secret|api.?key|password/i.test(JSON.stringify(writes)), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("batch model status uses a fresh CSRF token and sends only selected IDs", async () => {
  const originalFetch = globalThis.fetch;
  const calls: { path: string; method?: string; body?: string }[] = [];
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-model-batch" });
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-model-batch");
    calls.push({ path, method: init?.method, body: String(init?.body) });
    return ok({ status: "disabled", requested_count: 2, updated_count: 2, unchanged_count: 0 });
  };
  try {
    await batchUpdateModelStatus(["model-1", "model-2"], "disabled");
    assert.deepEqual(calls, [{
      path: "/api/v1/admin/models/batch-status",
      method: "POST",
      body: JSON.stringify({ model_ids: ["model-1", "model-2"], status: "disabled" }),
    }]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("dashboard custom range only sends bounded time filters and no sensitive fields", async () => {
  const originalFetch = globalThis.fetch;
  let requested = "";
  globalThis.fetch = async request => {
    requested = String(request);
    return ok({
      preset: "custom", from: "2026-08-01T16:00:00Z", to: "2026-08-08T16:00:00Z",
      bucket_size: "day", last_aggregated_at: null,
      summary: {}, trend: [], rankings: { suppliers: [], models: [], channels: [], groups: [] },
      error_distribution: [],
    });
  };
  try {
    await getAdminDashboard({
      preset: "custom", from: "2026-08-01T16:00:00Z", to: "2026-08-08T16:00:00Z",
    });
    assert.equal(requested, "/api/v1/admin/dashboard/overview?preset=custom&from=2026-08-01T16%3A00%3A00Z&to=2026-08-08T16%3A00%3A00Z");
    assert.equal(/authorization|credential|secret|api.?key|password|email/i.test(requested), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("dashboard resource overview stays compatible with existing list endpoints and uses their totals", async () => {
  const originalFetch = globalThis.fetch;
  const requested: string[] = [];
  const totals = new Map([
    ["/api/v1/admin/suppliers?page=1&page_size=1", 3],
    ["/api/v1/admin/suppliers?page=1&page_size=1&status=active", 2],
    ["/api/v1/admin/models?page=1&page_size=1", 120],
    ["/api/v1/admin/models?page=1&page_size=1&status=active", 105],
    ["/api/v1/admin/channels?page=1&page_size=1", 8],
    ["/api/v1/admin/channels?page=1&page_size=1&status=active", 6],
    ["/api/v1/admin/health/alerts?page=1&page_size=1&status=open", 1],
  ]);
  globalThis.fetch = async request => {
    const path = String(request);
    requested.push(path);
    return ok({ items: [], total: totals.get(path) ?? 0, page: 1, page_size: 1 });
  };
  try {
    const result = await getAdminResourceOverview();
    assert.deepEqual(requested, [...totals.keys()]);
    assert.equal(result.models.total, 120);
    assert.equal(result.models.active, 105);
    assert.equal(result.open_alert_count, 1);
    assert.equal(requested.includes("/api/v1/admin/dashboard/resources"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("supplier detail uses the bounded admin endpoint without adding sensitive query data", async () => {
  const originalFetch = globalThis.fetch;
  let requested = "";
  globalThis.fetch = async request => {
    requested = String(request);
    return ok({ supplier: {}, period: { days: 30 }, summary: {}, channels: [], models: [] });
  };
  try {
    await getSupplierDetail("supplier id/with slash");
    assert.equal(requested, "/api/v1/admin/suppliers/supplier%20id%2Fwith%20slash/detail");
    assert.equal(/authorization|credential|secret|api.?key|password/i.test(requested), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("service group and user updates use CSRF and preserve versions", async () => {
  const originalFetch = globalThis.fetch;
  const group: RoutingGroupInput = {
    code: "premium", name: "高级组", description: null, price_multiplier: 1.2,
    audience: "assigned", status: "active", version: 3,
  };
  const user: AdminUserInput = {
    display_name: "Operator", status: "active", roles: ["user", "operator"], version: 7,
  };
  const writes: { path: string; body: Record<string, unknown> }[] = [];
  globalThis.fetch = async (request, init) => {
    if (String(request) === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-admin" });
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-admin");
    writes.push({ path: String(request), body: JSON.parse(String(init?.body)) as Record<string, unknown> });
    return ok({ id: "saved", ...(JSON.parse(String(init?.body)) as object) });
  };
  try {
    await saveRoutingGroup(group, "group-1");
    await saveAdminUser(user, "user-1");
    assert.deepEqual(writes.map(item => item.path), [
      "/api/v1/admin/groups/group-1",
      "/api/v1/admin/users/user-1",
    ]);
    assert.equal(writes[1]?.body.version, 7);
    assert.equal(JSON.stringify(writes).includes("password"), false);
    assert.equal(JSON.stringify(writes).includes("email_ciphertext"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("group configuration loads redacted state and writes supplier-scoped credentials with CSRF", async () => {
  const originalFetch = globalThis.fetch;
  const calls: { path: string; method?: string; body?: Record<string, unknown> }[] = [];
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-routing" });
    if (!init?.method || init.method === "GET") {
      calls.push({ path });
      return ok({
        group: { id: "group-1", version: 4 },
        default_model_ids: ["model-from-upstream"], supplier_credentials: [],
      });
    }
    assert.equal(new Headers(init.headers).get("X-CSRF-TOKEN"), "csrf-routing");
    calls.push({ path, method: init.method, body: JSON.parse(String(init.body)) as Record<string, unknown> });
    return ok({ group: { id: "group-1", version: 5 }, supplier_credentials: [] });
  };
  try {
    const configuration = await getGroupConfiguration("group id/1");
    await saveGroupConfiguration("group id/1", {
      group_version: 4,
      model_ids: ["model-1"],
      supplier_credentials: [{ supplier_id: "supplier-1", priority: 10, weight: 100, credential: "sk-ephemeral-only" }],
    });
    assert.equal(calls[0]?.path, "/api/v1/admin/groups/group%20id%2F1/configuration");
    assert.deepEqual(configuration.default_model_ids, ["model-from-upstream"]);
    assert.equal(calls[1]?.path, "/api/v1/admin/groups/group%20id%2F1/configuration");
    assert.equal(calls[1]?.method, "PUT");
    assert.equal(calls[1]?.body?.group_version, 4);
    assert.deepEqual(calls[1]?.body?.model_ids, ["model-1"]);
    assert.equal("routes" in (calls[1]?.body ?? {}), false);
    assert.deepEqual(calls[1]?.body?.supplier_credentials, [
      { supplier_id: "supplier-1", priority: 10, weight: 100, credential: "sk-ephemeral-only" },
    ]);
    assert.equal(JSON.stringify(calls[0]).includes("sk-ephemeral-only"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("admin writes obtain a fresh CSRF token and preserve optimistic lock versions", async () => {
  const originalFetch = globalThis.fetch;
  const supplier: SupplierInput = {
    code: "openai", name: "OpenAI", supplier_type: "direct", status: "active",
    billing_mode: "prepaid", settlement_currency: "USD", disabled_reason: null,
    metadata: {}, version: 4,
  };
  let calls = 0;
  globalThis.fetch = async (request, init) => {
    calls += 1;
    if (String(request) === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-admin" });
    assert.equal(String(request), "/api/v1/admin/suppliers/supplier-1");
    assert.equal(init?.method, "PUT");
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-admin");
    assert.equal(JSON.parse(String(init?.body)).version, 4);
    return ok({ id: "supplier-1", ...supplier });
  };
  try {
    await saveSupplier(supplier, "supplier-1");
    assert.equal(calls, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("channel write contains connection settings but never contains an upstream credential", async () => {
  const originalFetch = globalThis.fetch;
  const channel: ChannelInput = {
    supplier_id: "supplier-1", name: "Primary", provider_type: "openai",
    endpoint_type: "text", request_method: "POST",
    base_url: "https://api.example.com/v1", health_probe_path: "/health/ready",
    proxy_url: null, status: "active", timeout_ms: 120000, concurrency_limit: 100,
    priority: 10, weight: 100, version: null,
  };
  globalThis.fetch = async (request, init) => {
    if (String(request) === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-admin" });
    assert.equal(String(request), "/api/v1/admin/channels");
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-admin");
    const body = JSON.parse(String(init?.body));
    assert.equal(body.request_method, "POST");
    assert.equal(Object.prototype.hasOwnProperty.call(body, "interface_code"), false);
    assert.equal(Object.prototype.hasOwnProperty.call(body, "interface_codes"), false);
    assert.equal(Object.prototype.hasOwnProperty.call(body, "credential"), false);
    assert.equal(/secret|token|api.?key|authorization/i.test(JSON.stringify(body)), false);
    return ok({ id: "channel-1", ...channel, credential_configured: false });
  };
  try {
    await saveChannel(channel);
    assert.equal(Object.prototype.hasOwnProperty.call(globalThis, "localStorage"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("manual health probe uses CSRF and never stores upstream credentials in browser state", async () => {
  const originalFetch = globalThis.fetch;
  const requests: { path: string; body: string }[] = [];
  globalThis.fetch = async (request, init) => {
    if (String(request) === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-health" });
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-health");
    requests.push({ path: String(request), body: String(init?.body) });
    return ok({
      channel_id: "channel-1", outcome: "healthy", category: null, latency_ms: 21,
      channel_status: "active",
    });
  };
  try {
    await probeChannelHealth("channel-1");
    assert.deepEqual(requests, [{ path: "/api/v1/admin/health/channels/channel-1/probe", body: "{}" }]);
    assert.equal(/authorization|credential|secret|token|api.?key|password/i.test(JSON.stringify(requests)), false);
    assert.equal(Object.prototype.hasOwnProperty.call(globalThis, "localStorage"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("assigned group user grants use redacted reads and CSRF writes containing only user IDs", async () => {
  const originalFetch = globalThis.fetch;
  const calls: { path: string; method?: string; body?: string }[] = [];
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-grants" });
    calls.push({ path, method: init?.method, body: init?.body ? String(init.body) : undefined });
    if (!init?.method) {
      return ok([{
        user_id: "user-1", display_name: "Visible User", masked_email: "vi***er@e***.com",
        user_status: "active", expires_at: null, granted_at: "2026-08-20T00:00:00Z",
        updated_at: "2026-08-20T00:00:00Z",
      }]);
    }
    assert.equal(new Headers(init.headers).get("X-CSRF-TOKEN"), "csrf-grants");
    return ok([]);
  };
  try {
    const grants = await getGroupUserGrants("group id/assigned");
    await saveGroupUserGrants("group id/assigned", ["user-1", "user-2"]);
    assert.equal(grants[0]?.masked_email, "vi***er@e***.com");
    assert.deepEqual(calls, [
      { path: "/api/v1/admin/groups/group%20id%2Fassigned/user-grants", method: undefined, body: undefined },
      {
        path: "/api/v1/admin/groups/group%20id%2Fassigned/user-grants",
        method: "PUT",
        body: JSON.stringify({ user_ids: ["user-1", "user-2"] }),
      },
    ]);
    assert.equal(/email|password|authorization|credential|secret|token|api.?key/i.test(calls[1]?.body ?? ""), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("api interfaces serialize field descriptions without model relations", async () => {
  const originalFetch = globalThis.fetch;
  const calls: { path: string; method?: string; body?: string }[] = [];
  const input: ApiInterfaceInput = {
    interface_code: "grok_video", interface_name: "Grok 视频生成接口", interface_version: "v1",
    capability_type: "video", transport_mode: "async_poll", http_method: "POST",
    public_path: "/v1/videos/grok", request_content_type: "application/json",
    description: "异步视频生成接口", status: "active",
    request_fields: [{
      name: "input_file", path: "input_file", type: "file", required: true,
      description: "待上传文件", deprecated: false, sensitive: false, children: [],
    }],
    response_fields: [{
      name: "task_id", path: "task_id", type: "string|array", required: true,
      description: "异步任务编号", deprecated: false, sensitive: false, children: [],
    }],
    version: 2,
  };
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-protocol" });
    calls.push({ path, method: init?.method, body: init?.body ? String(init.body) : undefined });
    if (!init?.method) return ok({ items: [], total: 0, page: 1, page_size: 20 });
    assert.equal(new Headers(init.headers).get("X-CSRF-TOKEN"), "csrf-protocol");
    return ok({ id: "interface-1", created_at: "", updated_at: "", ...input });
  };
  try {
    await listProtocols({ page: 1, pageSize: 20, query: "grok", capabilityType: "video", status: "active" });
    await saveProtocol(input, "interface-1");
    assert.equal(calls[0]?.path, "/api/v1/admin/api-interfaces?page=1&page_size=20&query=grok&status=active&capability_type=video");
    assert.equal(calls[1]?.path, "/api/v1/admin/api-interfaces/interface-1");
    assert.equal(calls[1]?.method, "PUT");
    const body = JSON.parse(calls[1]?.body ?? "{}");
    assert.equal(body.request_fields[0].description, "待上传文件");
    assert.equal(body.request_fields[0].type, "file");
    assert.equal(body.response_fields[0].description, "异步任务编号");
    assert.equal(body.response_fields[0].type, "string|array");
    assert.equal("model_ids" in body, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("time pricing APIs use CSRF, encode IDs, preserve versions and omit internal routing fields", async () => {
  const originalFetch = globalThis.fetch;
  const calls: { path: string; method?: string; cache?: RequestCache; body?: string; csrf?: string | null }[] = [];
  const input: TimePricingRuleInput = {
    name: "工作日晚高峰",
    multiplier: "1.250",
    days_of_week: [1, 2, 3, 4, 5],
    start_time: "18:00",
    end_time: "22:00",
    enabled: true,
    model_ids: ["model-1", "model-2"],
    version: 7,
  };
  const response = {
    id: "rule-1",
    ...input,
    models: [{ id: "model-1", public_name: "gpt-5.5", display_name: "GPT-5.5", capability_type: "text" }],
    created_at: "2026-08-25T00:00:00Z",
    updated_at: "2026-08-25T00:00:00Z",
  };

  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") {
      return ok({ header: "X-CSRF-TOKEN", token: "csrf-time-pricing" });
    }
    calls.push({
      path,
      method: init?.method,
      cache: init?.cache,
      body: init?.body ? String(init.body) : undefined,
      csrf: new Headers(init?.headers).get("X-CSRF-TOKEN"),
    });
    return ok(path === "/api/v1/admin/billing/time-rules" && !init?.method ? [response] : response);
  };

  try {
    const rules = await listTimePricingRules();
    await saveTimePricingRule({ ...input, version: 0 });
    await saveTimePricingRule(input, "rule id/晚高峰");
    await updateTimePricingRuleStatus("rule id/晚高峰", false, 8);
    await deleteTimePricingRule("rule id/晚高峰", 9);

    assert.equal(rules[0]?.models[0]?.public_name, "gpt-5.5");
    assert.deepEqual(calls.map(call => ({ path: call.path, method: call.method })), [
      { path: "/api/v1/admin/billing/time-rules", method: undefined },
      { path: "/api/v1/admin/billing/time-rules", method: "POST" },
      { path: "/api/v1/admin/billing/time-rules/rule%20id%2F%E6%99%9A%E9%AB%98%E5%B3%B0", method: "PUT" },
      { path: "/api/v1/admin/billing/time-rules/rule%20id%2F%E6%99%9A%E9%AB%98%E5%B3%B0/status", method: "PUT" },
      { path: "/api/v1/admin/billing/time-rules/rule%20id%2F%E6%99%9A%E9%AB%98%E5%B3%B0", method: "DELETE" },
    ]);
    assert.equal(calls[0]?.cache, "no-store");
    assert.equal(calls.slice(1).every(call => call.csrf === "csrf-time-pricing"), true);
    assert.equal(JSON.parse(calls[1]?.body ?? "{}").version, 0);
    assert.equal(JSON.parse(calls[2]?.body ?? "{}").version, 7);
    assert.deepEqual(JSON.parse(calls[3]?.body ?? "{}"), { enabled: false, version: 8 });
    assert.deepEqual(JSON.parse(calls[4]?.body ?? "{}"), { version: 9 });
    assert.equal(
      /supplier|credential|secret|api.?key|authorization|route|upstream/i.test(
        JSON.stringify(calls.map(call => call.body)),
      ),
      false,
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("subscription plan APIs preserve snapshots contract and use CSRF for writes", async () => {
  const originalFetch = globalThis.fetch;
  const calls: Array<{ path: string; method?: string; body?: string; csrf?: string | null }> = [];
  const plan = {
    id: "plan-1", code: "pro-monthly", name: "专业版", description: "持续创作",
    billing_cycle: "monthly" as const, price: "199.000000000000", included_credits: "30000.000000000000",
    concurrency_limit: 20, features: ["优先请求队列"], status: "active" as const,
    display_order: 20, featured: true,
    service_groups: [{ id: "group-1", code: "premium", name: "高质量" }],
    models: [{ id: "model-1", public_name: "gpt-5.5", display_name: "GPT-5.5", provider: "openai", capability_type: "text" }],
    created_at: "2026-08-26T00:00:00Z", updated_at: "2026-08-26T00:00:00Z", version: 3,
  };
  const options = {
    service_groups: [{ id: "group-1", code: "premium", name: "高质量", audience: "all" as const, price_multiplier: "1.25" }],
    models: plan.models,
  };
  const input: AdminSubscriptionPlanInput = {
    code: plan.code, name: plan.name, description: plan.description, billing_cycle: plan.billing_cycle,
    price: String(plan.price), included_credits: String(plan.included_credits), concurrency_limit: 20,
    features: [...plan.features], status: plan.status, display_order: 20, featured: true,
    service_group_ids: ["group-1"], model_ids: ["model-1"], version: 3,
  };

  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-plan" });
    calls.push({
      path,
      method: init?.method,
      body: init?.body ? String(init.body) : undefined,
      csrf: new Headers(init?.headers).get("X-CSRF-TOKEN"),
    });
    if (path.endsWith("/options")) return ok(options);
    return ok(init?.method ? plan : [plan]);
  };

  try {
    const plans = await listSubscriptionPlans();
    const loadedOptions = await getSubscriptionPlanOptions();
    await saveSubscriptionPlan({ ...input, version: 0 });
    await saveSubscriptionPlan(input, "plan id/专业版");
    await archiveSubscriptionPlan("plan id/专业版", 4);

    assert.equal(plans[0]?.models[0]?.public_name, "gpt-5.5");
    assert.equal(loadedOptions.service_groups[0]?.name, "高质量");
    assert.deepEqual(calls.map(call => ({ path: call.path, method: call.method })), [
      { path: "/api/v1/admin/subscription-plans", method: undefined },
      { path: "/api/v1/admin/subscription-plans/options", method: undefined },
      { path: "/api/v1/admin/subscription-plans", method: "POST" },
      { path: "/api/v1/admin/subscription-plans/plan%20id%2F%E4%B8%93%E4%B8%9A%E7%89%88", method: "PUT" },
      { path: "/api/v1/admin/subscription-plans/plan%20id%2F%E4%B8%93%E4%B8%9A%E7%89%88", method: "DELETE" },
    ]);
    assert.equal(calls.slice(2).every(call => call.csrf === "csrf-plan"), true);
    assert.deepEqual(JSON.parse(calls[4]?.body ?? "{}"), { version: 4 });
    const serializedWrites = JSON.stringify(calls.slice(2).map(call => call.body));
    assert.equal(/subscription_models|subscription_service_groups|api.?key|credential|upstream|route/i.test(serializedWrites), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
