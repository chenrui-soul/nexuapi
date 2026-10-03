import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { projectRoot, runtimeEnv } from "./runtime-env.mjs";

const child = spawn(process.execPath, [resolve(projectRoot, "node_modules/vite/bin/vite.js"), "--host", "0.0.0.0", ...process.argv.slice(2)], {
  cwd: projectRoot,
  env: runtimeEnv(),
  stdio: "inherit",
});

for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => child.kill(signal));
child.on("exit", code => process.exit(code ?? 1));
