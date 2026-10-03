import { readFile, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";

const root = process.cwd();
const read = (relativePath) => readFile(path.join(root, relativePath), "utf8");
const groundTruth = JSON.parse(await read("references/ui-fix-v48-ground-truth.json"));
const [page, developer, tokenPage, globals, admin] = await Promise.all([
  read("app/page.tsx"),
  read("components/developer/DeveloperPage.tsx"),
  read("components/developer/SystemAccessTokenPage.tsx"),
  read("app/globals.css"),
  read("app/admin/admin.css"),
]);

const checks = [
  ["API 令牌拥有独立导航", page.includes(`label: "${groundTruth.navigation.apiTokenLabel}"`)],
  ["系统访问令牌拥有独立导航", page.includes(`label: "${groundTruth.navigation.systemTokenLabel}"`)],
  ["API 令牌页不再嵌入系统令牌", !developer.includes(groundTruth.developerPage.forbiddenComponent)],
  ["系统令牌使用独立页面", tokenPage.includes("SystemAccessTokenPanel") && page.includes("<SystemAccessTokenPage/>")],
  ["积分统一保留三位小数", developer.includes("CREDIT_DISPLAY_SCALE = 3")],
  ["页面动效使用 GSAP", page.includes('from "gsap"') && tokenPage.includes('from "gsap"')],
  ["动效尊重减少动态效果", page.includes("prefers-reduced-motion") && globals.includes("prefers-reduced-motion")],
  ["仪表盘不再持续循环", !page.includes("repeat:-1")],
  ["用户头像使用固定网格", admin.includes("grid-template-columns:36px minmax(0,1fr)")],
  ["用户头像固定 36 像素", admin.includes("width:36px!important") && admin.includes("height:36px!important")],
  ["亮暗主题均有语义样式", globals.includes('html[data-theme="light"]') && globals.includes('html[data-theme="dark"]')],
];

const failed = checks.filter(([, passed]) => !passed);
const lines = [
  `UI fix V48 verification: ${failed.length === 0 ? "PASS" : "FAIL"}`,
  ...checks.map(([name, passed]) => `${passed ? "PASS" : "FAIL"} ${name}`),
];
const logDirectory = path.join(root, "scripts/log");
await mkdir(logDirectory, { recursive: true });
const stamp = new Date().toISOString().replaceAll(":", "-").replaceAll(".", "-");
const logPath = path.join(logDirectory, `ui-fix-v48-${stamp}.log`);
await writeFile(logPath, `${lines.join("\n")}\n`, "utf8");
console.log(lines.join("\n"));
console.log(`Log: ${logPath}`);
if (failed.length > 0) process.exitCode = 1;
