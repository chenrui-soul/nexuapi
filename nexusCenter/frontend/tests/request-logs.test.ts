import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { ApiError } from "../lib/api.ts";
import { getUserRequestLog, listUserRequestLogs } from "../lib/request-logs.ts";

const validation = JSON.parse(readFileSync(new URL("../references/request-logs-validation.json", import.meta.url), "utf8")) as {
  list_path: string;
  custom_list_path: string;
  detail_path: string;
  safe_failure_reason: string;
  forbidden_fields: string[];
};

const item = {
  id: "log-1",
  request_id: "req-user-1",
  started_at: "2026-08-21T06:00:00Z",
  completed_at: "2026-08-21T06:00:01Z",
  duration_ms: 812,
  public_model: "gpt-5.6-sol",
  api_key_name: "Production",
  service_group_name: "高质量",
  status_code: 200,
  status: "success" as const,
  input_tokens: 100,
  output_tokens: 20,
  cached_tokens: 10,
  billed_amount: "0.25000000",
  streaming: true,
  retry_count: 1,
  failure_reason: null,
};

test("listUserRequestLogs sends pagination and user-visible filters", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    assert.equal(String(input), validation.list_path);
    assert.equal(init?.method, "GET");
    return Response.json({ success: true, data: { items: [item], total: 21, page: 2, page_size: 20 } });
  };
  try {
    const page = await listUserRequestLogs({ page: 2, query: " req-user ", status: "success", model: "gpt-5.6-sol", period: "7d" });
    assert.equal(page.pageSize, 20);
    assert.equal(page.items[0].billed_amount, "0.25000000");
    assert.equal(page.items[0].service_group_name, "高质量");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listUserRequestLogs sends a precise custom time range as ISO boundaries", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input) => {
    assert.equal(String(input), validation.custom_list_path);
    return Response.json({ success: true, data: { items: [], total: 0, page: 1, page_size: 20 } });
  };
  try {
    await listUserRequestLogs({
      period: "custom",
      from: "2026-09-01T16:00:00.000Z",
      to: "2026-09-07T16:00:00.000Z",
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("request log UI exposes today and a consistently styled precise time panel", () => {
  const source = readFileSync(new URL("../app/page.tsx", import.meta.url), "utf8");
  const styles = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");
  assert.match(source, /options=\{\["今日","最近 24 小时","最近 7 天","最近 30 天","自定义时间"\]\}/);
  assert.match(source, /className="log-custom-range"/);
  assert.match(source, /type="datetime-local"/);
  assert.match(source, /step=\{1\}/);
  assert.match(source, /精确到秒/);
  assert.match(styles, /\.logs-page \.log-custom-range/);
  assert.match(styles, /var\(--control-border\)/);
});

test("getUserRequestLog encodes request id and preserves safe failure message", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input) => {
    assert.equal(String(input), validation.detail_path);
    return Response.json({ success: true, data: { ...item, status: "failed", status_code: 504, failure_reason: validation.safe_failure_reason } });
  };
  try {
    const detail = await getUserRequestLog("req/unsafe id");
    assert.equal(detail.failure_reason, validation.safe_failure_reason);
    for (const field of validation.forbidden_fields) assert.equal(field in detail, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("request log API errors are propagated", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({ success: false, error: { code: "REQUEST_LOG_NOT_FOUND", message: "调用日志不存在" } }, { status: 404 });
  try {
    await assert.rejects(() => getUserRequestLog("missing"), (error: unknown) => error instanceof ApiError && error.status === 404);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
