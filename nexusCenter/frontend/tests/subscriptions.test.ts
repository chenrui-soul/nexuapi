import assert from "node:assert/strict";
import test from "node:test";
import { activateMockSubscription, cancelCurrentSubscription, getSubscriptionOverview } from "../lib/subscriptions.ts";

function ok<T>(data: T): Response {
  return Response.json({ success: true, data, request_id: "req-subscription" });
}

const backendOverview = {
  plans: [{
    id: "plan-1", code: "starter-monthly", name: "基础版", description: "个人使用",
    billing_cycle: "monthly", price: "59.00", included_credits: "8000.000000000000",
    concurrency_limit: 5, features: ["每月 8,000 积分"], featured: false,
  }],
  current_subscription: null,
  mock_purchase_enabled: true,
};

test("subscription overview maps the real backend catalog", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (request, init) => {
    assert.equal(String(request), "/api/v1/subscriptions");
    assert.equal(init?.credentials, "include");
    return ok(backendOverview);
  };
  try {
    const overview = await getSubscriptionOverview();
    assert.equal(overview.plans[0].billingCycle, "monthly");
    assert.equal(overview.plans[0].includedCredits, "8000.000000000000");
    assert.equal(overview.current, null);
    assert.equal(overview.mockPurchaseEnabled, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("mock activation is CSRF protected and only targets the selected plan", async () => {
  const originalFetch = globalThis.fetch;
  let call = 0;
  globalThis.fetch = async (request, init) => {
    call += 1;
    if (call === 1) return ok({ header: "X-CSRF-TOKEN", token: "csrf-value" });
    assert.equal(String(request), "/api/v1/subscriptions/plans/plan%2F1/mock-activate");
    assert.equal(init?.method, "POST");
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-value");
    return ok(backendOverview);
  };
  try {
    await activateMockSubscription("plan/1");
    assert.equal(call, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("subscription cancellation sends version with CSRF and maps refund state", async () => {
  const originalFetch = globalThis.fetch;
  let call = 0;
  globalThis.fetch = async (request, init) => {
    call += 1;
    if (call === 1) return ok({ header: "X-CSRF-TOKEN", token: "csrf-cancel" });
    assert.equal(String(request), "/api/v1/subscriptions/current/cancel");
    assert.equal(init?.method, "POST");
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-cancel");
    assert.deepEqual(JSON.parse(String(init?.body)), { version: 3 });
    return ok({
      subscription_id: "subscription-1", status: "refund_pending", refund_amount: "0.00",
      refundable_credits: "0", frozen_credits: "2", refund_pending: true, completed_at: null,
    });
  };
  try {
    const result = await cancelCurrentSubscription(3);
    assert.equal(result.refundPending, true);
    assert.equal(result.frozenCredits, "2");
    assert.equal(call, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
