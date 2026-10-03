import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

export function runtimeEnv() {
  const runtimeRoot = resolve(projectRoot, ".sites-runtime");
  const wranglerLogs = resolve(runtimeRoot, "wrangler/logs");
  const miniflareRegistry = resolve(runtimeRoot, "wrangler/registry");
  mkdirSync(wranglerLogs, { recursive: true });
  mkdirSync(miniflareRegistry, { recursive: true });
  return {
    ...process.env,
    WRANGLER_WRITE_LOGS: "false",
    WRANGLER_LOG_PATH: wranglerLogs,
    MINIFLARE_REGISTRY_PATH: miniflareRegistry,
  };
}
