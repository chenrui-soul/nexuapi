import assert from "node:assert/strict";
import test from "node:test";
import { createServer } from "vite";
import { fileURLToPath } from "node:url";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

test("analytics chart renders real data, zero amounts and negative margins without crashing", async () => {
  const server = await createServer({
    configFile: false,
    server: { middlewareMode: true },
    resolve: { alias: { "@": fileURLToPath(new URL("../", import.meta.url)) } },
    esbuild: { jsx: "automatic" },
  });
  try {
    const { TrendChart } = await server.ssrLoadModule("/components/admin/AnalyticsAdminPage.tsx");
    for (const amounts of [["0.598", "0", "0.598"], ["0", "0", "0"], ["1", "2", "-1"]]) {
      const html = renderToStaticMarkup(createElement(TrendChart, { points: [{
        bucket_start: "2026-09-15T04:00:00Z",
        billed_amount: amounts[0], supplier_cost_amount: amounts[1], gross_margin_amount: amounts[2],
      }] }));
      assert.match(html, /analytics-y-axis/);
      assert.equal((html.match(/<path /g) || []).length, 3);
      assert.doesNotMatch(html, /NaN|undefined|Infinity/);
    }
  } finally {
    await server.close();
  }
});
