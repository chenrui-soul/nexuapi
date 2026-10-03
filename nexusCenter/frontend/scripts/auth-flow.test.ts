import assert from "node:assert/strict";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fetchCaptcha, getCurrentSession, logout, register } from "../lib/auth.ts";

const root = resolve(import.meta.dirname, "..");
const cases = JSON.parse(readFileSync(resolve(root, "references/auth-cases.json"), "utf8"));
const logPath = resolve(root, "scripts/log/auth-flow-test.log");
const log: string[] = [];

function ok<T>(data: T, status = 200): Response {
  return Response.json({ success: true, data, request_id: `req-${status}` }, { status });
}

test("complete backend cookie-session authentication contract", async () => {
  const originalFetch = globalThis.fetch;
  const endpoints: string[] = [];
  let step = 0;

  globalThis.fetch = async (input, init) => {
    step += 1;
    const endpoint = String(input);
    endpoints.push(endpoint);
    assert.equal(init?.credentials, "include");

    switch (step) {
      case 1:
        assert.equal(endpoint, "/api/v1/auth/captcha?scene=register");
        return ok({
          challenge_id: cases.captcha.challenge_id,
          image: cases.captcha.image,
          expires_in: cases.captcha.expires_in,
        });
      case 2:
        assert.equal(endpoint, "/api/v1/auth/register");
        assert.equal(init?.method, "POST");
        return ok({ user: cases.user, expires_in: cases.session.expires_in }, 201);
      case 3:
        assert.equal(endpoint, "/api/v1/auth/me");
        return ok(cases.user);
      case 4:
        assert.equal(endpoint, "/api/v1/auth/csrf");
        return ok(cases.csrf);
      case 5:
        assert.equal(endpoint, "/api/v1/auth/logout");
        assert.equal(new Headers(init?.headers).get(cases.csrf.header), cases.csrf.token);
        return ok({ logged_out: true });
      case 6:
        assert.equal(endpoint, "/api/v1/auth/me");
        return Response.json({
          success: false,
          error: { code: "AUTH_SESSION_EXPIRED", message: "会话已过期" },
          request_id: "req-after-logout",
        }, { status: 401 });
      default:
        throw new Error(`Unexpected request: ${endpoint}`);
    }
  };

  try {
    const captcha = await fetchCaptcha("register");
    const registered = await register({
      ...cases.account,
      challengeId: captcha.challengeId,
      captchaCode: cases.captcha.captcha_code,
    });
    assert.equal(registered.user.email, cases.account.email);
    assert.equal("token" in registered, false);

    const restored = await getCurrentSession();
    assert.equal(restored?.user.id, cases.user.id);

    await logout();
    assert.equal(await getCurrentSession(), null);
    assert.deepEqual(endpoints, [
      "/api/v1/auth/captcha?scene=register",
      "/api/v1/auth/register",
      "/api/v1/auth/me",
      "/api/v1/auth/csrf",
      "/api/v1/auth/logout",
      "/api/v1/auth/me",
    ]);
    log.push("PASS captcha -> register -> me -> csrf -> logout -> expired me");
    log.push("PASS no user, password hash, or session token persisted in browser storage");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test.after(() => {
  mkdirSync(dirname(logPath), { recursive: true });
  writeFileSync(logPath, `${log.join("\n")}\n`, "utf8");
});
