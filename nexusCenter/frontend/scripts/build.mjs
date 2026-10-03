import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { projectRoot, runtimeEnv } from "./runtime-env.mjs";

const build = spawnSync(process.execPath, [resolve(projectRoot, "node_modules/vinext/dist/cli.js"), "build"], {
  cwd: projectRoot,
  env: runtimeEnv(),
  stdio: "inherit",
});
if (build.status !== 0) process.exit(build.status ?? 1);

await import("./validate-artifact.mjs");
