# NEXUS API 控制台

面向 AI 模型聚合服务的 React + TypeScript 控制台，包含仪表盘、模型市场、创作空间、订阅、钱包、API 密钥、调用日志与分组状态。

## 技术栈

- React 19 + TypeScript 5
- Vinext / Next.js 风格路由
- Vite 8
- GSAP + `@gsap/react`
- 原生 CSS 与 CSS Variables
- Cloudflare Worker / Sites 部署

## 本地开发

```bash
cp .env.example .env.local
npm run dev
```

## 登录与账号流程

当前已对接 Spring Boot 后端认证接口：

- `/login`：获取 Redis 验证码并登录
- `/register`：注册后通过 HttpOnly Cookie 自动登录
- `/`：通过 `/api/v1/auth/me` 恢复 Redis Session，未登录自动跳转
- 控制台用户菜单：先获取 CSRF Token，再安全退出
- `/forgot-password`：页面保留，后端密码重置接口上线前会明确提示暂未开放

前端不再保存用户密码摘要、Session ID 或自建 Token。身份会话由后端 Redis Session 和 HttpOnly `NEXUS_SESSION` Cookie 管理。

## 对接后端

统一通过 `lib/api.ts` 发起请求。默认请求同源 API；如果前后端分开部署，在 `.env.local` 中配置：

```bash
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
# 可选：仅当生产反向代理使用验证码别名时覆盖；默认无需配置。
NEXT_PUBLIC_AUTH_CAPTCHA_PATH=/api/v1/auth/captcha
```

后端 `NEXUS_ALLOWED_ORIGINS` 必须包含前端精确 Origin，本地默认为 `http://localhost:3000`。请求层强制携带 Cookie 会话，并将后端统一响应解包为业务数据。

```ts
import { apiDataRequest } from "@/lib/api";

type Model = { id: string; name: string };
const models = await apiDataRequest<Model[]>("/api/v1/models");
```

建议后端准备好后优先提供 OpenAPI / Swagger、服务地址和登录方式，再依次接入用户信息、仪表盘、模型、API 密钥与账单接口。
