import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const fixture = JSON.parse(await readFile(resolve(root, "references/admin-coordinated-design.json"), "utf8"));
const css = await readFile(resolve(root, "app/admin/admin.css"), "utf8");
const marker = "/* Coordinated admin refinement: redesign, UX rules and visual-taste review share one contract. */";
const coordinated = css.slice(css.indexOf(marker));
const primaryRules = [...css.matchAll(/(?:html\[data-theme="dark"\] )?\.admin-app \.admin-button\.primary\{([^}]*)\}/g)].map(match => match[1]);

const checks = {
  scope_is_admin_only: coordinated.includes(".admin-app"),
  single_primary_accent: css.includes("--admin-primary:#315bea") && css.includes("--admin-primary:#708ce4"),
  primary_button_has_no_generic_gradient: primaryRules.some(rule => rule.includes("background:var(--admin-primary)")) && primaryRules.every(rule => !rule.includes("linear-gradient")),
  redundant_page_eyebrow_is_removed: coordinated.includes(".admin-page-header>div:first-child>span{display:none!important}"),
  dense_admin_hover_stays_stable: coordinated.includes(":hover:not(:disabled){transform:none}") && coordinated.includes(":where(.admin-resource-card,.protocol-card):hover{transform:none}"),
  focus_state_is_visible: coordinated.includes("box-shadow:0 0 0 3px color-mix(in srgb,var(--admin-blue) 30%,transparent)"),
  reduced_motion_is_supported: coordinated.includes("@media(prefers-reduced-motion:reduce)"),
  light_secondary_text_uses_semantic_contrast: css.includes("--admin-muted:#626873") && coordinated.includes('html:not([data-theme="dark"]) .admin-app :where(') && coordinated.includes(".admin-table th,") && coordinated.includes("color:var(--admin-muted)!important"),
};

const expected = fixture.ground_truth;
const failures = Object.entries(expected).filter(([key, value]) => checks[key] !== value);
const result = { feature: fixture.feature, expected, actual: checks, passed: failures.length === 0, failures };
console.log(JSON.stringify(result, null, 2));
if (failures.length) process.exitCode = 1;
