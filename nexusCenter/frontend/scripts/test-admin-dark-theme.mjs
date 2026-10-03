import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const fixture = JSON.parse(await readFile(resolve(root, "references/admin-dark-theme.json"), "utf8"));
const css = await readFile(resolve(root, "app/admin/admin.css"), "utf8");
const marker = "/* Admin dark theme: every management surface uses the same cool charcoal hierarchy. */";
const dark = css.slice(css.indexOf(marker));

const checks = {
  scope_is_admin_only: dark.includes('html[data-theme="dark"] .admin-app'),
  uses_layered_charcoal_surfaces: css.includes("--admin-bg:#11161e") && css.includes("--admin-panel:#181f29") && css.includes("--admin-panel-hover:#253142"),
  headings_use_high_contrast_text: dark.includes(":where(h1,h2,h3,h4){color:var(--admin-ink)!important}"),
  table_header_is_dark: dark.includes(".admin-table th,html[data-theme=\"dark\"] .admin-app .admin-table thead th:last-child"),
  form_controls_are_dark: dark.includes(".admin-field input,.admin-field select,.admin-field textarea"),
  semantic_badges_are_dark_tinted: dark.includes(".admin-status.positive{border-color:#316957!important;background:#173229!important;color:var(--admin-green)!important}") && dark.includes(".admin-status.warning{border-color:#6a4d2d!important;background:#31251a!important;color:var(--admin-orange)!important}"),
  routing_actions_are_dark_tinted: dark.includes(".routing-configure-button{border-color:#3c5684;background:#1a2942"),
  analytics_cards_are_dark: dark.includes(".analytics-presets button.active{background:#243047!important") && dark.includes(".analytics-rank.rank-1{background:#342a18"),
  model_cards_have_no_light_mode_leaks: dark.includes(".model-admin-card .admin-resource-card-head .resource-avatar{") && dark.includes(".model-card-resources .admin-card-capabilities em.on{"),
  tab_counters_are_dark: dark.includes(".admin-tabs button.active span{background:#314266!important;color:#dbe4ff!important}"),
};

const expected = fixture.ground_truth;
const failures = Object.entries(expected).filter(([key, value]) => checks[key] !== value);
const result = { feature: fixture.feature, expected, actual: checks, passed: failures.length === 0, failures };
console.log(JSON.stringify(result, null, 2));
if (failures.length) process.exitCode = 1;
