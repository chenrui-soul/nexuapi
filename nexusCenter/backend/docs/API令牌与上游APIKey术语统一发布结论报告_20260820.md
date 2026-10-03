# API 令牌与上游 APIKey 术语统一发布结论报告

## 发布结论

2026-08-20 已完成正式部署。用户侧统一使用“API 令牌”，管理员侧统一使用“上游 APIKey”，两类凭证在界面、提示、注释、审计和设计文档中不再混称。

本次发布没有新增 Flyway，没有修改正式数据库结构或业务配置，也没有更改 `ApiKeyService`、`/api/v1/api-keys`、`api_keys` 等兼容性技术标识。

## 发布范围

- 后端：替换正式容器 `/app/app.jar`，保留原环境变量、网络、端口和健康检查配置。
- 前端：使用最新 `dist` 生产产物，将 3000 端口从 Vite 开发服务器切换到 Vinext 生产服务器。
- 文档：同步术语表、设计说明、计划和发布记录。

## 发布前验证

- 前端测试 39/39 通过，生产构建和 ESLint 通过。
- 后端 Maven 打包通过，隔离环境测试 108/108 通过。
- Flyway V1-V17 隔离校验通过。
- 用户侧旧称和管理员侧旧称源码扫描均为 0 命中。

## 备份

- 回滚目录：`.ai-memory/backups/release-terminology-20260820-202818/`
- PostgreSQL 备份：`nexus-before-terminology.dump`
- 数据库备份 SHA-256：`11B7F1F5CF8163A04DD91F295B753A7D694848AB2EF48E0DADD6E94487730B0C`
- `pg_restore -l` 校验：833 项
- 发布前 JAR SHA-256：`720D90AD00AE361F5A0967F33D1BB9CC984C6C2963878A165BE65AC5DDD5DDDC`
- 新 JAR SHA-256：`C03B65C184D2D850814FF541E4AC2F1856BBF0B28361A6848036A90CE28B6386`

## 发布后验收

| 验收项 | 结果 |
| --- | --- |
| 后端 readiness | HTTP 200，`UP` |
| 后端容器 | healthy |
| 正式 Flyway | `17|true` |
| 运行 JAR | SHA-256 与新产物一致 |
| 未认证 `/v1/models` | HTTP 401，鉴权边界正常 |
| 前端登录页 | HTTP 200 |
| 前端运行模式 | Vinext production，监听 3000 |
| “API 令牌”生产产物 | 87 处 |
| “上游 APIKey”生产产物 | 54 处 |
| 两类旧称 | 0 命中 |

## 回滚方案

### 后端快速回滚

1. 停止 `nexus-api-app-v16-wave10a`。
2. 将 `app-before-terminology.jar` 复制回容器 `/app/app.jar`。
3. 重新启动容器。
4. 验证 readiness HTTP 200、Flyway V17 和关键接口。

### 前端回滚

1. 使用 `.ai-memory/backups/terminology-api-token-20260820-195830/` 恢复术语修改前文件。
2. 重新执行前端测试与生产构建。
3. 重启 Vinext 生产服务器并验证登录页和关键页面。

数据库本次没有发生结构或业务配置变更，不应仅为了代码回滚恢复数据库。如确需恢复，必须先停止写入、保留故障现场并验证备份可恢复性。

## 安全说明

发布、备份、日志和报告均未记录完整 API 令牌、上游 APIKey、密码或加密主密钥。

## 发布后登录页热修复

初次烟雾检查只验证登录页 HTML 为 HTTP 200，没有逐项请求 HTML 引用的 JavaScript 和 CSS，因此未发现 Vinext 0.0.50 在 Windows 生产服务器中的静态缓存键路径分隔符问题。浏览器实际访问时，`/assets/*.js` 和 CSS 返回 404，导致验证码和登录交互未初始化。

已在项目 `scripts/start.mjs` 增加 Windows 静态缓存键规范化兼容层，并重新启动生产服务器。热修复后核心 JavaScript、CSS 均返回 HTTP 200；浏览器验证码正常显示、刷新按钮可用、登录按钮可用，空表单验证提示正常。详细证据见 `docs/前端登录页静态资源404修复报告_20260820.md`。
