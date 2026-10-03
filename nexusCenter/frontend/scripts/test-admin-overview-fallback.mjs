import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { getAdminResourceOverview } from "../lib/admin.ts";

const fixtureUrl = new URL("../references/admin-overview-list-totals.json", import.meta.url);
const fixture = JSON.parse(await readFile(fixtureUrl, "utf8"));
const requested = [];
const originalFetch = globalThis.fetch;

globalThis.fetch = async request => {
  const path = String(request);
  requested.push(path);
  if (!(path in fixture.responses)) {
    return new Response(JSON.stringify({ success: false, error: { message: `unexpected request: ${path}` } }), {
      status: 404,
      headers: { "content-type": "application/json" },
    });
  }
  return new Response(JSON.stringify({
    success: true,
    data: { items: [], total: fixture.responses[path], page: 1, page_size: 1 },
  }), { status: 200, headers: { "content-type": "application/json" } });
};

try {
  const actual = await getAdminResourceOverview();
  assert.deepEqual(actual, fixture.expected);
  assert.deepEqual(requested, Object.keys(fixture.responses));
  assert.equal(requested.some(path => path.includes("dashboard/resources")), false);
  console.log(JSON.stringify({ status: "PASS", requested, actual }, null, 2));
} finally {
  globalThis.fetch = originalFetch;
}
