// Disposable localhost-only UI fixture; no production requests or persistent data.
import http from "node:http";
import { randomUUID } from "node:crypto";
let signedIn = true;
let settings = { registration_enabled: true, manual_recharge_enabled: true, version: 0 };
const now = new Date().toISOString();
const users = ["本地验收账号", "林间工作室", "陈小白", "开发者账户", "运营同事"].map((name,i) => ({ id: i ? randomUUID() : "preview-admin", display_name:name, masked_email: `de***${i}@example.test`, status: i === 3 ? "suspended" : "active", roles: i === 0 ? ["user","admin"] : i === 4 ? ["user","operator"] : ["user"], email_verified:true, created_at:now, last_login_at:now, version:0 }));
const balances = new Map(users.map(u => [u.id, 1200]));
const receipts = new Map();
let items = [
 {title:"平台服务升级：让每一次调用更稳定",content:"我们已完成新一轮服务升级。\n\n本次更新优化了数据加载和页面切换体验，同时新增了平台公告通知，重要消息将直接展示在右下角。\n\n感谢你的支持与陪伴。",status:"published"},
 {title:"国庆假期服务安排",content:"假期期间 API 服务正常运行。如需帮助，请通过控制台联系我们。",status:"published",read_at:now},
 {title:"十月更新计划",content:"我们正在准备新的模型接入和使用体验优化，敬请期待。",status:"draft"},
 {title:"历史维护通知",content:"本次维护已完成，平台服务恢复正常。",status:"withdrawn"}
].map(x => ({...x,id:randomUUID(),version:0,created_at:now,published_at:x.status === "draft" ? null : now}));
http.createServer(async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1:3018");
  res.setHeader("Access-Control-Allow-Credentials", "true");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, X-CSRF-TOKEN");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
  res.setHeader("Content-Type", "application/json");
  if (req.method === "OPTIONS") return res.end();
  const url = new URL(req.url, "http://localhost");
  const path = url.pathname;
  const chunks = []; for await (const chunk of req) chunks.push(chunk);
  const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : {};
  const ok = data => res.end(JSON.stringify({ success: true, data }));
  if (path === "/api/v1/auth/me") {
    if (!signedIn) { res.statusCode = 401; return res.end('{}'); }
    return ok({ id: "preview-admin", name: "本地验收账号", email: "preview@example.test", status: "active", roles: ["admin"], created_at: new Date().toISOString() });
  }
  if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "local-fixture" });
  if (path === "/api/v1/auth/logout") { signedIn = false; return ok({ logged_out: true }); }
  if (path === "/api/v1/auth/captcha") return ok({ challenge_id: "preview", image: "", expires_in: 60 });
  if (path === "/api/v1/system/registration") return ok(settings);
  if (path === "/api/v1/admin/settings") { if (req.method === "PUT") settings = { ...body, version: settings.version + 1 }; return ok(settings); }
  const page = (list) => ({ items: list, total: list.length, page: 1, page_size: 10 });
  if (path === "/api/v1/admin/users") return ok(page(users.filter(u => !url.searchParams.get("q") || u.display_name.includes(url.searchParams.get("q")))));
  const wallet = path.match(/^\/api\/v1\/admin\/users\/([^/]+)\/(wallet|recharge)$/);
  if (wallet) {
    if(wallet[2] === "wallet") return ok({available_credits:String(balances.get(wallet[1])), permanent_credits:String(balances.get(wallet[1])),version:0});
    if(!settings.manual_recharge_enabled) {res.statusCode=403;return res.end(JSON.stringify({success:false,error:{code:"ADMIN_RECHARGE_DISABLED",message:"手动充值已关闭"}}));}
    if(receipts.has(body.request_id)) return ok({...receipts.get(body.request_id),replayed:true});
    const balance = balances.get(wallet[1]) + Number(body.credits); balances.set(wallet[1],balance);
    const receipt = {...body,ledger_id:randomUUID(),available_credits:String(balance),replayed:false};receipts.set(body.request_id,receipt);return ok(receipt);
  }
  if (path === "/api/v1/admin/announcements") {
    if (req.method === "POST") { const item = { ...body, id: randomUUID(), status: "draft", version: 0, created_at: new Date().toISOString() }; items.unshift(item); return ok(item); }
    return ok(page(items));
  }
  const match = path.match(/^\/api\/v1\/admin\/announcements\/([^/]+)(?:\/(publish|withdraw))?$/);
  if (match) {
    const item = items.find(item => item.id === match[1]);
    if (match[2]) { item.status = match[2] === "publish" ? "published" : "withdrawn"; item.published_at = new Date().toISOString(); }
    else Object.assign(item, body);
    item.version++; return ok(item);
  }
  if (path === "/api/v1/announcements") { const published = items.filter(item => item.status === "published"); return ok({ announcements: page(published), unread_count: published.filter(item => !item.read_at).length, next_unread: published.find(item => !item.read_at) ?? null }); }
  const read = path.match(/^\/api\/v1\/announcements\/([^/]+)\/read$/);
  if (read) { items.find(item => item.id === read[1]).read_at = new Date().toISOString(); return ok({ read: true }); }
  res.statusCode = 404; res.end('{}');
}).listen(8080, "127.0.0.1", () => console.log("Local platform UI fixture: http://127.0.0.1:8080"));
