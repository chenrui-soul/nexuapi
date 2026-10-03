import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const fixture = JSON.parse(await readFile(resolve(root, "references/admin-model-status-single-click.json"), "utf8"));
const page = await readFile(resolve(root, "components/admin/ModelsAdminPage.tsx"), "utf8");
const ui = await readFile(resolve(root, "components/admin/AdminUi.tsx"), "utf8");

const checks = {
  clicks_to_start_request: page.includes("onClick={() => setConfirmTarget(item)}") && page.includes("onConfirm={() => void toggleStatus()}") ? 2 : 1,
  confirmation_dialog_required: page.includes('title={confirmTarget?.status === "active" ? "停用这个模型？"') ,
  optimistic_card_update: page.includes("item.id === target.id ? { ...item, status: nextStatus } : item"),
  duplicate_clicks_blocked_while_saving: page.includes("statusSavingId) return") && page.includes("disabled={Boolean(statusSavingId)}") && page.includes("busy={Boolean(statusSavingId)}"),
  failed_request_restores_previous_model: !page.includes("item.id === target.id ? { ...item, status: nextStatus } : item"),
  successful_response_replaces_stale_model_version: page.includes("item.id === updated.id ? updated : item") && ui.includes("aria-busy={busy}"),
};

const expected = fixture.ground_truth;
const failures = Object.entries(expected).filter(([key, value]) => checks[key] !== value);
const result = { feature: fixture.feature, expected, actual: checks, passed: failures.length === 0, failures };
console.log(JSON.stringify(result, null, 2));
if (failures.length) process.exitCode = 1;
