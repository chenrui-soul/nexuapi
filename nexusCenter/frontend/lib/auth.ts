import { ApiError, apiDataRequest } from "./api.ts";

export type AuthUser = {
  id: string;
  name: string;
  email: string;
  status: string;
  roles: string[];
  createdAt: string;
};

export type AuthSession = {
  user: AuthUser;
  expiresIn: number | null;
};

export type CaptchaScene = "login" | "register" | "password_reset";

export type CaptchaChallenge = {
  challengeId: string;
  image: string;
  expiresIn: number;
};

export type LoginInput = {
  email: string;
  password: string;
  challengeId: string;
  captchaCode: string;
  remember: boolean;
};

export type RegisterInput = {
  name: string;
  email: string;
  password: string;
  challengeId: string;
  captchaCode: string;
};

export type PasswordForgotInput = {
  email: string;
  challengeId: string;
  captchaCode: string;
};

export type PasswordResetInput = {
  email: string;
  resetId: string;
  verificationCode: string;
  newPassword: string;
};

export type AccountSecurity = {
  email: string;
  emailVerified: boolean;
  lastLoginAt: string | null;
  passwordChangedAt: string;
  activeSessionCount: number;
};

type BackendUser = {
  id: string;
  name: string;
  email: string;
  status: string;
  roles: string[];
  created_at: string;
};

type BackendSession = {
  user: BackendUser;
  expires_in: number;
};

type BackendCaptcha = {
  challenge_id: string;
  image: string;
  expires_in: number;
};

type CsrfResponse = {
  header: string;
  token: string;
};

type BackendPasswordResetChallenge = { reset_id: string; expires_in: number };
type BackendAccountSecurity = {
  email: string;
  email_verified: boolean;
  last_login_at: string | null;
  password_changed_at: string;
  active_session_count: number;
};

/**
 * 验证码接口路径按部署环境配置。
 *
 * 默认使用后端公开的标准路径，避免测试环境必须额外配置生产环境的
 * `/auth/challenge` 反向代理别名。生产环境如仍保留该别名，可通过
 * NEXT_PUBLIC_AUTH_CAPTCHA_PATH 显式兼容，不影响其他认证接口。
 */
const AUTH_CAPTCHA_PATH = (process.env.NEXT_PUBLIC_AUTH_CAPTCHA_PATH ?? "/api/v1/auth/captcha")
  .trim()
  .replace(/\/+$/, "") || "/api/v1/auth/captcha";

export class AuthError extends Error {
  code: string;
  status?: number;
  requestId?: string;

  constructor(code: string, message: string, status?: number, requestId?: string) {
    super(message);
    this.name = "AuthError";
    this.code = code;
    this.status = status;
    this.requestId = requestId;
  }
}

export function normalizeEmail(value: string): string {
  return value.trim().toLowerCase();
}

/**
 * 统一判断当前会话是否拥有管理员角色。
 * 后端可能返回 `admin` 或 Spring Security 风格的 `ROLE_ADMIN`，这里统一兼容大小写。
 */
export function hasAdminRole(roles: readonly string[]): boolean {
  return roles.some(role => {
    const normalizedRole = role.trim().toUpperCase();
    return normalizedRole === "ADMIN" || normalizedRole === "ROLE_ADMIN";
  });
}

export function validatePassword(password: string): string | null {
  if (password.length < 8) return "密码至少需要 8 位";
  if (password.length > 128) return "密码不能超过 128 位";
  if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) return "密码必须同时包含字母和数字";
  return null;
}

function mapUser(user: BackendUser): AuthUser {
  return {
    id: user.id,
    name: user.name,
    email: user.email,
    status: user.status,
    roles: [...user.roles],
    createdAt: user.created_at,
  };
}

function mapSession(session: BackendSession): AuthSession {
  return { user: mapUser(session.user), expiresIn: session.expires_in };
}

function toAuthError(error: unknown, fallback: string): AuthError {
  if (error instanceof AuthError) return error;
  if (error instanceof ApiError) {
    return new AuthError(error.code ?? "AUTH_REQUEST_FAILED", error.message || fallback, error.status, error.requestId);
  }
  return new AuthError("AUTH_REQUEST_FAILED", fallback);
}

export async function fetchCaptcha(scene: CaptchaScene): Promise<CaptchaChallenge> {
  try {
    const separator = AUTH_CAPTCHA_PATH.includes("?") ? "&" : "?";
    const captcha = await apiDataRequest<BackendCaptcha>(
      `${AUTH_CAPTCHA_PATH}${separator}scene=${encodeURIComponent(scene)}`,
      {
        method: "GET",
        cache: "no-store",
      },
    );
    return {
      challengeId: captcha.challenge_id,
      image: captcha.image,
      expiresIn: captcha.expires_in,
    };
  } catch (error) {
    throw toAuthError(error, "验证码加载失败，请稍后重试");
  }
}

export async function getCurrentSession(): Promise<AuthSession | null> {
  try {
    const user = await apiDataRequest<BackendUser>("/api/v1/auth/me", {
      method: "GET",
      cache: "no-store",
    });
    return { user: mapUser(user), expiresIn: null };
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw toAuthError(error, "无法恢复登录状态");
  }
}

export async function login(input: LoginInput): Promise<AuthSession> {
  try {
    const session = await apiDataRequest<BackendSession>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({
        email: normalizeEmail(input.email),
        password: input.password,
        challenge_id: input.challengeId,
        captcha_code: input.captchaCode.trim().toUpperCase(),
        remember: input.remember,
      }),
    });
    return mapSession(session);
  } catch (error) {
    throw toAuthError(error, "登录失败，请稍后重试");
  }
}

export async function register(input: RegisterInput): Promise<AuthSession> {
  try {
    const session = await apiDataRequest<BackendSession>("/api/v1/auth/register", {
      method: "POST",
      body: JSON.stringify({
        name: input.name.trim(),
        email: normalizeEmail(input.email),
        password: input.password,
        challenge_id: input.challengeId,
        captcha_code: input.captchaCode.trim().toUpperCase(),
      }),
    });
    return mapSession(session);
  } catch (error) {
    throw toAuthError(error, "注册失败，请稍后重试");
  }
}

export async function requestPasswordReset(input: PasswordForgotInput): Promise<{ resetId: string; expiresIn: number }> {
  try {
    const challenge = await apiDataRequest<BackendPasswordResetChallenge>("/api/v1/auth/password/forgot", {
      method: "POST",
      body: JSON.stringify({
        email: normalizeEmail(input.email),
        challenge_id: input.challengeId,
        captcha_code: input.captchaCode.trim().toUpperCase(),
      }),
    });
    return { resetId: challenge.reset_id, expiresIn: challenge.expires_in };
  } catch (error) {
    throw toAuthError(error, "无法发送邮件验证码，请稍后重试");
  }
}

export async function resetPassword(input: PasswordResetInput): Promise<void> {
  try {
    await apiDataRequest<{ password_reset: boolean }>("/api/v1/auth/password/reset", {
      method: "POST",
      body: JSON.stringify({
        email: normalizeEmail(input.email),
        reset_id: input.resetId,
        verification_code: input.verificationCode.trim(),
        new_password: input.newPassword,
      }),
    });
  } catch (error) {
    throw toAuthError(error, "密码重置失败，请稍后重试");
  }
}

export async function getAccountSecurity(): Promise<AccountSecurity> {
  try {
    const data = await apiDataRequest<BackendAccountSecurity>("/api/v1/auth/security", {
      method: "GET",
      cache: "no-store",
    });
    return {
      email: data.email,
      emailVerified: data.email_verified,
      lastLoginAt: data.last_login_at,
      passwordChangedAt: data.password_changed_at,
      activeSessionCount: data.active_session_count,
    };
  } catch (error) {
    throw toAuthError(error, "账户安全信息加载失败");
  }
}

async function csrfRequest<T>(path: string, body?: unknown): Promise<T> {
  const csrf = await apiDataRequest<CsrfResponse>("/api/v1/auth/csrf", { method: "GET", cache: "no-store" });
  return apiDataRequest<T>(path, {
    method: "POST",
    headers: { [csrf.header]: csrf.token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<number> {
  try {
    const result = await csrfRequest<{ revoked_sessions: number }>("/api/v1/auth/password/change", {
      current_password: currentPassword,
      new_password: newPassword,
    });
    return result.revoked_sessions;
  } catch (error) {
    throw toAuthError(error, "密码修改失败，请稍后重试");
  }
}

export async function revokeOtherSessions(): Promise<number> {
  try {
    const result = await csrfRequest<{ revoked_count: number }>("/api/v1/auth/sessions/revoke-others");
    return result.revoked_count;
  } catch (error) {
    throw toAuthError(error, "退出其他设备失败，请稍后重试");
  }
}

export async function logout(): Promise<void> {
  try {
    const csrf = await apiDataRequest<CsrfResponse>("/api/v1/auth/csrf", {
      method: "GET",
      cache: "no-store",
    });
    await apiDataRequest<{ logged_out: boolean }>("/api/v1/auth/logout", {
      method: "POST",
      headers: { [csrf.header]: csrf.token },
    });
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return;
    throw toAuthError(error, "退出失败，请稍后重试");
  }
}
