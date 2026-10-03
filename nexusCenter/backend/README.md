# NEXUS API Server

NEXUS API 的 Java 21 模块化单体后端。P0 阶段在一个 Spring Boot 进程中完成账户、API Key、模型路由、限流、计费和日志闭环，并保持 `gateway` 模块可独立拆分。

## 技术栈

- Java 21 + Spring Boot 3.5
- Spring MVC 管理接口 + WebClient/Reactor Netty 上游转发
- Spring Security + Redis Session
- MyBatis-Plus + PostgreSQL 16 + Flyway
- Redis 7、Resilience4j、Micrometer、Prometheus
- JUnit 5、Testcontainers、WireMock

## 本地启动

本机无需预装 Java 或 Maven，直接使用 Docker：

```bash
docker compose up --build
```

启动后：

- 服务地址：`http://localhost:8080`
- 健康检查：`http://localhost:8080/actuator/health`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI：`http://localhost:8080/v3/api-docs`

启动前必须复制 `.env.example` 为 `.env`，设置数据库、Redis 密码，并生成三把不同的 32 字节 Base64 密钥。密钥没有默认值，缺失时服务会拒绝启动：

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

该命令执行三次，结果分别填入 `NEXUS_FIELD_ENCRYPTION_KEY`、`NEXUS_LOOKUP_HMAC_KEY` 和 `NEXUS_API_KEY_HMAC_KEY_V1`。三把密钥不得复用或提交到 Git；字段加密密钥丢失后将无法解密已有邮箱和渠道凭证。

## 模块

业务代码位于 `com.nexusapi.server.modules`：

项目先按业务模块拆分，每个模块内部采用 `Controller -> Service -> Mapper` 分层，并配套 `dto`、`vo`、`entity`、`security`、`enums` 和 `support` 目录。

- `auth`：验证码、注册、登录和 Redis 会话（密码重置规划中）
- `user`：用户资料、角色、权限
- `apikey`：API Key 生命周期和调用权限
- `model`：公共模型、能力、价格
- `supplier`：商业供应商、合作状态、结算口径
- `channel`：上游渠道、凭证和模型映射
- `routing`：分组、选路和重试
- `gateway`：OpenAI 兼容协议和 SSE 中转
- `quota`：RPM、TPM、并发和额度预检
- `billing`：钱包、不可变账本、幂等冻结、结算、释放和退款（Wave 4 已完成）
- `requestlog`：调用明细和统计聚合
- `dashboard`：控制台指标查询
- `notification`：站内通知和公告
- `admin`：运营管理接口与审计

详细边界与调用链见 `docs/architecture.md`。

## P0 认证第一阶段

已完成验证码、注册、登录、Redis 会话恢复、当前用户和安全退出。邮箱使用 AES-256-GCM 加密保存并通过 HMAC 摘要查询，密码使用 Argon2id；Redis Session 只保存用户 UUID。认证接口与前端调用示例见 `docs/auth-api.md`。

`/v1/**` 使用独立 Bearer API Key 鉴权链，不能被控制台 Cookie Session 替代。Wave 5/5B 管理员上游与供应商配置见 `docs/upstream-configuration-design.md`、`docs/supplier-design.md`，钱包与资金闭环见 `docs/billing-design.md`。

## Wave 6 OpenAI 兼容网关

已完成以下运行时能力：

- `GET /v1/models`：仅返回 API Key 有权访问且存在健康路由的模型。
- `POST /v1/chat/completions`：支持普通 JSON 与 `text/event-stream` SSE，公开模型名会在上游请求前映射为渠道模型名。
- 路由：先按综合优先级，再按权重进行无放回排序；仅在首个 SSE 事件输出前对网络、超时、408、429 和 5xx 切换候选渠道。
- 供应商：每个渠道必须归属商业供应商；非 active 或 health unavailable 的供应商不会进入新请求候选，已发出的请求继续完成并结算。
- 限流：Redis Lua 原子执行 RPM、TPM 预占、API Key 并发和渠道并发；Redis 不可用时 fail closed。
- 计费：调用前按最大输出估算预冻结，结束后按实际 Token 结算；上游失败或准备阶段异常会释放冻结。
- 安全：执行模型、分组和 IP 白名单校验；渠道凭证只在构造上游请求前解密，响应、异常、调用日志和账本均不保存明文。

当前兼容范围仅包括 Models 与 Chat Completions；Responses、Embedding、图片和音频接口不在 Wave 6 范围。来源：`OpenAiGatewayController`、`OpenAiGatewayService`、`OpenAiGatewayIntegrationTest`。

## Wave 7A 健康探测与故障观测

已完成主动 `GET /models` 健康探测、Gateway 实时失败反馈、失败次数与最近错误观测、Redis 多实例防重锁和成功恢复。自 Flyway V39 起暂停自动熔断：失败渠道只标记为 `degraded`，仍参与真实请求候选，不再写入或按 `circuit_open` 阻断；人工 `disabled` 渠道仍不会参与调用或被自动恢复。用户 400/422、客户端断开和平台内部错误不会污染渠道失败次数。

历史设计和当前兼容边界见 `docs/wave7a-health-circuit-breaker.md`。

## Wave 8 经营分析

已完成小时/日级幂等聚合、管理员经营接口和 `/admin/analytics` 页面。总览与完整区间 P95读取原始事实表，趋势和排名读取聚合快照；最终请求成功率与每次上游尝试稳定性分别统计。收入、成本和毛利只使用请求完成时快照，金额以精确十进制字符串返回；P0 检测到混合结算币种时拒绝错误汇总。

详细统计口径、接口、配置项和限制见 `docs/wave8-dashboard-analytics.md`。

## 数据库字段说明

数据库结构仍由 Flyway 统一管理。`V5__add_database_comments.sql` 为 V1-V4 业务对象补齐中文数据字典；`V6__supplier_management_and_cost_observability.sql` 新增供应商、成本快照和上游尝试记录，并为新增表字段同步写入中文 COMMENT。MyBatis Row 字段同时使用中文 JavaDoc 说明用途和安全边界；测试会阻止新增字段遗漏说明。

V5 手工回滚脚本：`docs/migrations/V5__add_database_comments_rollback.sql`。该脚本只清除元数据注释，不删除表、字段或业务数据。

V6 手工回滚脚本：`docs/migrations/V6__supplier_management_and_cost_observability_rollback.sql`。执行前必须确认没有后续迁移依赖供应商和成本字段，并先备份相关业务数据。
