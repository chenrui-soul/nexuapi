import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const [groundTruth, source, css] = await Promise.all([
  readFile(new URL("../references/time-pricing-ui-v51-ground-truth.json", import.meta.url), "utf8").then(JSON.parse),
  readFile(new URL("../components/admin/TimePricingAdminPage.tsx", import.meta.url), "utf8"),
  readFile(new URL("../app/admin/admin.css", import.meta.url), "utf8"),
]);

assert.equal(groundTruth.page, "/admin/billing");
assert.match(source, /className="time-pricing-list admin-animate"/);
assert.match(source, /className="time-rule-card"/);
assert.match(source, /SelectFilter label="模型能力筛选"/);
assert.match(source, /当前显示 \{modelOptions\.length\} \/ \{modelTotal\}/);
assert.doesNotMatch(source, /ai_models\.id/);
assert.match(css, /\.time-pricing-summary\{[\s\S]*?grid-template-columns:repeat\(4,minmax\(0,1fr\)\)/);
assert.match(css, /\.time-rule-card dl\{[\s\S]*?grid-template-columns:1\.05fr \.9fr 1\.35fr \.7fr/);
assert.match(css, /\.time-model-picker\{[\s\S]*?max-height:286px;overflow:auto/);
assert.doesNotMatch(css, /\.time-pricing-table\{/);

console.log(`time-pricing-ui-v51: ${groundTruth.requirements.length}/${groundTruth.requirements.length} requirements verified`);
