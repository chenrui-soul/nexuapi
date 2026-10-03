import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { projectRoot } from "./runtime-env.mjs";

const workerPath = resolve(projectRoot, "dist/server/index.js");
const hostingPath = resolve(projectRoot, "dist/.openai/hosting.json");

JSON.parse(await readFile(hostingPath, "utf8"));
const workerUrl = pathToFileURL(workerPath);
workerUrl.searchParams.set("sites-validation", `${Date.now()}`);
const worker = await import(workerUrl.href);
if (!worker.default || typeof worker.default.fetch !== "function") {
  throw new Error("dist/server/index.js must expose default.fetch(request, env, ctx)");
}

console.log("Validated Sites artifact: worker and hosting manifest are present.");
