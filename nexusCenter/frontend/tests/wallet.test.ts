import assert from "node:assert/strict";
import test from "node:test";
import {
  createRechargeOrder,
  getWalletBalance,
  listWalletLedger,
  mockPayRechargeOrder,
} from "../lib/wallet.ts";

test("wallet reads real balance and ledger endpoints", async () => {
  const originalFetch = globalThis.fetch;
  const paths: string[] = [];
  globalThis.fetch = async (input) => {
    paths.push(String(input));
    if (String(input).includes("/ledger")) {
      return Response.json({ success: true, data: { items: [], total: 0, page: 1, page_size: 20 } });
    }
    return Response.json({ success: true, data: { available_credits: "12.5" } });
  };
  try {
    assert.equal((await getWalletBalance()).available_credits, "12.5");
    assert.equal((await listWalletLedger()).total, 0);
    assert.deepEqual(paths, ["/api/v1/wallet", "/api/v1/wallet/ledger?page=1&page_size=20"]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("mock recharge uses CSRF and an idempotency key", async () => {
  const originalFetch = globalThis.fetch;
  const writes: RequestInit[] = [];
  globalThis.fetch = async (input, init) => {
    if (String(input) === "/api/v1/auth/csrf") {
      return Response.json({ success: true, data: { header: "X-CSRF-TOKEN", token: "csrf-value" } });
    }
    writes.push(init ?? {});
    return Response.json({ success: true, data: { id: "order-1", status: "pending" } });
  };
  try {
    await createRechargeOrder("10", "idem-1");
    await mockPayRechargeOrder("order-1");
    assert.equal(new Headers(writes[0].headers).get("X-CSRF-TOKEN"), "csrf-value");
    assert.equal(new Headers(writes[0].headers).get("Idempotency-Key"), "idem-1");
    assert.equal(new Headers(writes[1].headers).get("X-CSRF-TOKEN"), "csrf-value");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
