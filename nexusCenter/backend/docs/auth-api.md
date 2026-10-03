# P0 认证接口

接口前缀为 `/api/v1/auth`，所有响应统一为：

```json
{
  "success": true,
  "data": {},
  "request_id": "..."
}
```

失败时 `success` 为 `false`，并返回 `error.code` 和可展示的 `error.message`。前端所有认证请求都必须使用 `credentials: "include"`，不要把 Session ID 或 CSRF Token 写入 localStorage。

## 1. 获取验证码

```http
GET /api/v1/auth/captcha?scene=register
GET /api/v1/auth/captcha?scene=login
```

```json
{
  "success": true,
  "data": {
    "challenge_id": "uuid",
    "image": "data:image/svg+xml;base64,...",
    "expires_in": 300
  }
}
```

直接把 `data.image` 设置为 `<img src>`。验证码一次性使用，无论成功还是发起新一次提交，前端都应准备刷新。

## 2. 注册并自动登录

```http
POST /api/v1/auth/register
Content-Type: application/json
```

```json
{
  "name": "Nexus User",
  "email": "user@example.com",
  "password": "StrongPass2026",
  "challenge_id": "captcha uuid",
  "captcha_code": "ACEF"
}
```

成功返回 `201 Created`，并通过 HttpOnly `NEXUS_SESSION` Cookie 建立 Redis 会话。

## 3. 登录

```http
POST /api/v1/auth/login
Content-Type: application/json
```

```json
{
  "email": "user@example.com",
  "password": "StrongPass2026",
  "challenge_id": "captcha uuid",
  "captcha_code": "ACEF",
  "remember": false
}
```

登录成功响应包含 `data.user` 和 `data.expires_in`。普通会话默认 1800 秒，`remember: true` 的 Redis 会话默认 30 天。邮箱不存在和密码错误统一返回 `AUTH_INVALID_CREDENTIALS`。

## 4. 当前用户

```http
GET /api/v1/auth/me
```

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "Nexus User",
    "email": "user@example.com",
    "status": "active",
    "roles": ["user"],
    "created_at": "2026-08-17T00:00:00Z"
  }
}
```

会话不存在或已过期时返回 `401 AUTH_SESSION_EXPIRED`。

## 5. 安全退出

先获取 CSRF Token：

```http
GET /api/v1/auth/csrf
```

```json
{
  "success": true,
  "data": {
    "header": "X-CSRF-TOKEN",
    "token": "..."
  }
}
```

再将返回的 Token 放入响应指定的请求头：

```http
POST /api/v1/auth/logout
X-CSRF-TOKEN: <token>
```

成功后 Redis Session 立即失效。无 Token 或 Token 不匹配时返回 `403 PERMISSION_DENIED`。

## 前端 fetch 封装示例

```ts
const API_BASE = "/api/v1";

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...init.headers,
    },
  });
  const body = await response.json();
  if (!response.ok || !body.success) {
    throw new Error(body.error?.message ?? "请求失败");
  }
  return body.data as T;
}

export async function logout(): Promise<void> {
  const csrf = await api<{ header: string; token: string }>("/auth/csrf", {
    method: "GET",
  });
  await api("/auth/logout", {
    method: "POST",
    headers: { [csrf.header]: csrf.token },
  });
}
```

同域部署时建议由 Nginx 将 `/api` 转发到后端。开发环境如果前后端不同端口，前端仍需 `credentials: "include"`，后端 `NEXUS_ALLOWED_ORIGINS` 必须是精确 Origin，不能配置为 `*`。
