import assert from "node:assert/strict";
import test from "node:test";
import { getUserDashboardOverview, getUserGroupStatuses } from "../lib/user-analytics.ts";
import { readFileSync } from "node:fs";

test("dashboard overview sends preset and custom time range", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    assert.equal(
      String(input),
      "/api/v1/dashboard/overview?preset=7d&from=2026-08-01T00%3A00%3A00.000Z&to=2026-08-08T00%3A00%3A00.000Z",
    );
    assert.equal(init?.method, "GET");
    return Response.json({
      success: true,
      data: {
        preset: "custom",
        from: "2026-08-01T00:00:00Z",
        to: "2026-08-08T00:00:00Z",
        bucket_size: "day",
        updated_at: null,
        summary: { request_count: 0, billed_amount: "0.000000000000" },
        comparison: { request_change_rate: null, billed_change_rate: null },
        trend: [], capability_distribution: [], rankings: { models: [], api_keys: [], groups: [] },
        recent_requests: [], activity_heatmap: [], live_metrics: { request_count: 0 },
      },
    });
  };
  try {
    const result = await getUserDashboardOverview({
      preset: "7d",
      from: "2026-08-01T00:00:00.000Z",
      to: "2026-08-08T00:00:00.000Z",
    });
    assert.equal(result.preset, "custom");
    assert.equal(result.summary.billed_amount, "0.000000000000");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("dashboard custom range uses precise datetime controls", () => {
  const source = readFileSync(new URL("../app/page.tsx", import.meta.url), "utf8");
  assert.match(source, /className="custom-range" role="dialog"/);
  assert.match(source, /type="datetime-local" step=\{1\}/);
  assert.match(source, /结束时间不能晚于当前时间/);
});

test("group status preserves the latest real request history without internal fields", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    assert.equal(String(input), "/api/v1/status/groups");
    assert.equal(init?.method, "GET");
    return Response.json({
      success: true,
      data: {
        updated_at: "2026-08-24T03:00:00Z",
        sample_limit: 60,
        groups: [{
          id: "quality",
          name: "高质量",
          description: "关键业务",
          price_multiplier: "2.800000000000",
          status: "partial",
          availability: 96.67,
          average_latency_ms: 6600,
          latency_p95_ms: 8200,
          available_model_count: 238,
          total_model_count: 238,
          sample_count: 60,
          last_request_at: "2026-08-24T03:00:00Z",
          history: [
            { status: "success", occurred_at: "2026-08-24T02:59:00Z", duration_ms: 6000, status_code: 200 },
            { status: "failed", occurred_at: "2026-08-24T03:00:00Z", duration_ms: 8000, status_code: 502 },
          ],
        }],
      },
    });
  };
  try {
    const result = await getUserGroupStatuses();
    assert.equal(result.sample_limit, 60);
    assert.deepEqual(result.groups[0].history.map((item) => item.status), ["success", "failed"]);
    for (const forbidden of ["supplier", "channel", "upstream", "credential", "cost", "route"]) {
      assert.equal(forbidden in result.groups[0], false);
    }
  } finally {
    globalThis.fetch = originalFetch;
  }
});
