import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { projectRoot, runtimeEnv } from "./runtime-env.mjs";

const importFromProject = relativePath => import(pathToFileURL(resolve(projectRoot, relativePath)).href);

// Vinext's Windows path.relative() result uses backslashes. The production
// static-file cache stores those paths as lookup keys, while browser URLs
// always use forward slashes, causing every /assets/*.js request to return
// 404 and leaving the login page stuck in its loading state. Normalize the
// cache keys in-process so production behavior is identical on Windows and
// POSIX hosts without modifying node_modules on disk.
const { StaticFileCache } = await importFromProject("node_modules/vinext/dist/server/static-file-cache.js");
const originalCreate = StaticFileCache.create.bind(StaticFileCache);
StaticFileCache.create = async clientDir => {
  const cache = await originalCreate(clientDir);
  if (process.platform === "win32" && cache?.entries instanceof Map) {
    cache.entries = new Map([...cache.entries].map(([key, value]) => [key.replaceAll("\\", "/"), value]));
  }
  return cache;
};

const { loadDotenv } = await importFromProject("node_modules/vinext/dist/config/dotenv.js");
loadDotenv({ root: projectRoot, mode: "production" });
const { startProdServer } = await importFromProject("node_modules/vinext/dist/server/prod-server.js");
const args = process.argv.slice(2);
const readArg = name => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
};
const port = Number(readArg("--port") ?? process.env.PORT ?? 3000);
const host = readArg("--host") ?? "0.0.0.0";
process.env.NODE_ENV = "production";
Object.assign(process.env, runtimeEnv());
await startProdServer({ port, host, outDir: resolve(projectRoot, "dist") });
