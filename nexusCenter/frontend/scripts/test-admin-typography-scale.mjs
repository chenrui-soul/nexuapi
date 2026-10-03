import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const fixture = JSON.parse(await readFile(resolve(root, "references/admin-typography-scale.json"), "utf8"));
const css = await readFile(resolve(root, "app/admin/admin.css"), "utf8");
const marker = "/* Admin typography system: business text never drops below 11.5px. */";
const canonical = css.slice(css.indexOf(marker));
const numericSizes = [...canonical.matchAll(/font-size:\s*(\d+(?:\.\d+)?)px/g)].map(match => Number(match[1]));

const checks = {
  minimum_business_font_px: css.includes("--admin-type-caption:11.5px") && numericSizes.every(size => size >= 11.5) ? 11.5 : Math.min(...numericSizes),
  body_font_px: css.includes("--admin-type-body:14px") ? 14 : 0,
  control_font_px: css.includes("--admin-type-control:13px") ? 13 : 0,
  secondary_font_px: css.includes("--admin-type-secondary:12px") ? 12 : 0,
  section_title_font_px: css.includes("--admin-type-section:16px") ? 16 : 0,
  page_title_font_px: css.includes("--admin-type-page:26px") ? 26 : 0,
  table_rows_use_control_size: canonical.includes(".admin-table td{height:68px;font-size:var(--admin-type-control)!important"),
  form_controls_use_control_size: canonical.includes(".admin-field input,.admin-field select,.admin-field textarea{font-size:var(--admin-type-control)!important}"),
  compact_model_text_is_readable: canonical.includes(".model-card-metrics dd small{font-size:var(--admin-type-caption)!important") && canonical.includes(".model-card-resources .admin-card-groups em,.model-card-resources .admin-card-capabilities em{font-size:var(--admin-type-caption)!important"),
};

const expected = fixture.ground_truth;
const failures = Object.entries(expected).filter(([key, value]) => checks[key] !== value);
const result = { feature: fixture.feature, expected, actual: checks, passed: failures.length === 0, failures };
console.log(JSON.stringify(result, null, 2));
if (failures.length) process.exitCode = 1;
