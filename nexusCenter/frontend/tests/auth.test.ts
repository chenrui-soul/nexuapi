import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import {
  AuthError,
  fetchCaptcha,
  requestPasswordReset,
  resetPassword,
  getAccountSecurity,
  changePassword,
  revokeOtherSessions,
  getCurrentSession,
  hasAdminRole,
  login,
  logout,
  normalizeEmail,
  register,
  validatePassword,
} from "../lib/auth.ts";

const cases = JSON.parse(readFileSync(resolve(import.meta.dirname, "../references/auth-cases.json"), "utf8"));

function ok<T>(data: T, status = 200): Response {
  return Response.json({ success: true, data, request_id: "req-test" }, { status });
}

test("auth helpers match backend validation ground truth", () => {
  assert.equal(normalizeEmail(" Test.User@Example.COM "), cases.account.email);
  for (const item of cases.passwords) assert.equal(validatePassword(item.value) === null, item.valid);
});

test("admin page switch is driven by normalized backend roles", () => {
  assert.equal(hasAdminRole(["user"]), false);
  assert.equal(hasAdminRole(["user", "admin"]), true);
  assert.equal(hasAdminRole(["ROLE_ADMIN"]), true);
  assert.equal(hasAdminRole([" role_admin "]), true);
});

test("captcha and login use the backend contract with cookie credentials", async () => {
  const originalFetch = globalThis.fetch;
  const calls: Array<{ input: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    calls.push({ input: String(input), init });
    if (calls.length === 1) {
      return ok({
        challenge_id: cases.captcha.challenge_id,
        image: cases.captcha.image,
        expires_in: cases.captcha.expires_in,
      });
    }
    return ok({ user: cases.user, expires_in: cases.session.expires_in });
  };

  try {
    const captcha = await fetchCaptcha(cases.captcha.scene);
    assert.equal(captcha.challengeId, cases.captcha.challenge_id);
    assert.equal(captcha.image, cases.captcha.image);

    const session = await login({
      email: ` ${cases.account.email.toUpperCase()} `,
      password: cases.account.password,
      challengeId: captcha.challengeId,
      captchaCode: cases.captcha.captcha_code.toLowerCase(),
      remember: true,
    });
    assert.equal(session.user.email, cases.account.email);
    assert.equal(session.expiresIn, cases.session.expires_in);

    assert.equal(calls[0].input, "/api/v1/auth/captcha?scene=login");
    assert.equal(calls[0].init?.credentials, "include");
    assert.equal(calls[1].input, "/api/v1/auth/login");
    assert.equal(calls[1].init?.credentials, "include");
    assert.deepEqual(JSON.parse(String(calls[1].init?.body)), {
      email: cases.account.email,
      password: cases.account.password,
      challenge_id: cases.captcha.challenge_id,
      captcha_code: cases.captcha.captcha_code,
      remember: true,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("register maps the backend session response without storing a token", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    assert.equal(String(input), "/api/v1/auth/register");
    assert.equal(init?.credentials, "include");
    assert.deepEqual(JSON.parse(String(init?.body)), {
      name: cases.account.name,
      email: cases.account.email,
      password: cases.account.password,
      challenge_id: cases.captcha.challenge_id,
      captcha_code: cases.captcha.captcha_code,
    });
    return ok({ user: cases.user, expires_in: cases.session.expires_in }, 201);
  };

  try {
    const session = await register({
      ...cases.account,
      challengeId: cases.captcha.challenge_id,
      captchaCode: cases.captcha.captcha_code,
    });
    assert.equal(session.user.id, cases.user.id);
    assert.equal("token" in session, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("current-user treats an expired Redis session as signed out", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({
    success: false,
    error: { code: "AUTH_SESSION_EXPIRED", message: "会话已过期" },
    request_id: "req-expired",
  }, { status: 401 });

  try {
    assert.equal(await getCurrentSession(), null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("logout obtains CSRF first and submits the returned header", async () => {
  const originalFetch = globalThis.fetch;
  let call = 0;
  globalThis.fetch = async (input, init) => {
    call += 1;
    if (call === 1) {
      assert.equal(String(input), "/api/v1/auth/csrf");
      assert.equal(init?.credentials, "include");
      return ok(cases.csrf);
    }
    assert.equal(String(input), "/api/v1/auth/logout");
    assert.equal(new Headers(init?.headers).get(cases.csrf.header), cases.csrf.token);
    assert.equal(init?.credentials, "include");
    return ok({ logged_out: true });
  };

  try {
    await logout();
    assert.equal(call, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("backend error code and safe message are preserved", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({
    success: false,
    error: { code: "AUTH_INVALID_CREDENTIALS", message: "邮箱或密码不正确" },
    request_id: "req-invalid",
  }, { status: 401 });

  try {
    await assert.rejects(login({
      email: cases.account.email,
      password: "Wrong2026",
      challengeId: cases.captcha.challenge_id,
      captchaCode: cases.captcha.captcha_code,
      remember: false,
    }), (error: unknown) => {
      assert.ok(error instanceof AuthError);
      assert.equal(error.code, "AUTH_INVALID_CREDENTIALS");
      assert.equal(error.message, "邮箱或密码不正确");
      assert.equal(error.requestId, "req-invalid");
      return true;
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("password recovery maps the two-step public contract without persisting secrets", async () => {
  const originalFetch = globalThis.fetch;
  const calls: Array<{ input: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    calls.push({ input: String(input), init });
    return calls.length === 1
      ? ok({ reset_id: "c43ca70c-d727-4a9f-a6bb-c264e146fa17", expires_in: 600 })
      : ok({ password_reset: true });
  };
  try {
    const challenge = await requestPasswordReset({
      email: ` ${cases.account.email.toUpperCase()} `,
      challengeId: cases.captcha.challenge_id,
      captchaCode: cases.captcha.captcha_code.toLowerCase(),
    });
    assert.equal(challenge.expiresIn, 600);
    await resetPassword({
      email: cases.account.email,
      resetId: challenge.resetId,
      verificationCode: "123456",
      newPassword: "Recovered2026",
    });
    assert.deepEqual(JSON.parse(String(calls[0].init?.body)), {
      email: cases.account.email,
      challenge_id: cases.captcha.challenge_id,
      captcha_code: cases.captcha.captcha_code,
    });
    assert.deepEqual(JSON.parse(String(calls[1].init?.body)), {
      email: cases.account.email,
      reset_id: challenge.resetId,
      verification_code: "123456",
      new_password: "Recovered2026",
    });
    assert.equal(calls.some(call => new Headers(call.init?.headers).has("Authorization")), false);
  } finally { globalThis.fetch = originalFetch; }
});

test("account security maps safe fields and sensitive actions obtain CSRF", async () => {
  const originalFetch = globalThis.fetch;
  const calls: Array<{ input: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    calls.push({ input: String(input), init });
    if (String(input) === "/api/v1/auth/security") return ok({
      email: cases.account.email,
      email_verified: true,
      last_login_at: "2026-08-25T01:00:00Z",
      password_changed_at: "2026-08-24T01:00:00Z",
      active_session_count: 2,
    });
    if (String(input) === "/api/v1/auth/csrf") return ok(cases.csrf);
    if (String(input).endsWith("/password/change")) return ok({ password_changed: true, revoked_sessions: 1 });
    return ok({ revoked_count: 0 });
  };
  try {
    const security = await getAccountSecurity();
    assert.equal(security.activeSessionCount, 2);
    assert.equal("sessionIds" in security, false);
    assert.equal(await changePassword("OldPassword2026", "NewPassword2026"), 1);
    assert.equal(await revokeOtherSessions(), 0);
    const changeCall = calls.find(call => call.input.endsWith("/password/change"));
    const revokeCall = calls.find(call => call.input.endsWith("/sessions/revoke-others"));
    assert.equal(new Headers(changeCall?.init?.headers).get(cases.csrf.header), cases.csrf.token);
    assert.equal(new Headers(revokeCall?.init?.headers).get(cases.csrf.header), cases.csrf.token);
  } finally { globalThis.fetch = originalFetch; }
});
