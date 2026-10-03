import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const fixture = JSON.parse(await readFile(resolve(root, "references/admin-table-row-hover.json"), "utf8"));
const css = await readFile(resolve(root, "app/admin/admin.css"), "utf8");
const marker = "/* Admin table interaction: sticky action cells and normal cells share one row hover state. */";
const interaction = css.slice(css.indexOf(marker));

const checks = {
  scope_is_admin_only: interaction.includes(".admin-app .admin-table"),
  all_row_cells_change_together: interaction.includes(".admin-app .admin-table tbody tr:hover>td"),
  sticky_action_cell_matches_row: interaction.includes("tr:hover>td:last-child") && interaction.includes("background:var(--admin-panel-hover)"),
  keyboard_focus_matches_hover: interaction.includes("tr:focus-within>td"),
  transition_is_subtle: interaction.includes("transition:background-color .16s ease"),
};

const expected = fixture.ground_truth;
const failures = Object.entries(expected).filter(([key, value]) => checks[key] !== value);
const result = { feature: fixture.feature, expected, actual: checks, passed: failures.length === 0, failures };
console.log(JSON.stringify(result, null, 2));
if (failures.length) process.exitCode = 1;
