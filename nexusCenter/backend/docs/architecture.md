# 后端架构说明

## 架构原则

项目采用包级模块化单体。先按 `auth`、`billing`、`gateway` 等业务模块划分，每个模块内部再采用 `Controller -> Service -> Mapper` 分层。模块之间通过 Service 公开接口或领域事件协作，禁止跨模块直接访问 Mapper 和数据库表。

认证模块的标准目录为：

```text
auth
├── controller  HTTP 入口
├── service     业务、事务和限流
├── mapper      MyBatis 数据访问
├── entity      数据库映射对象
├── dto         请求参数
├── vo          响应白名单对象
├── security    Session 和认证过滤器
├── enums       业务枚举
└── support     模块内部支撑组件
```

Spring MVC 承载控制台和管理接口，避免阻塞式 MyBatis 进入响应式事件循环；WebClient + Reactor Netty 专门负责上游 AI 的连接池、超时和 SSE 流式转发。

## 请求入口

```text
控制台 /api/v1/**
  -> Cookie Session 鉴权
  -> Controller
  -> Service
  -> MyBatis Mapper / Redis

OpenAI /v1/**
  -> Bearer API 令牌鉴权
  -> 限流与余额预检
  -> 模型/分组路由
  -> WebClient 上游请求
  -> SSE/JSON 响应
  -> 用量确认与幂等结算
  -> 请求日志
```

## 关键边界

1. `gateway` 只依赖 `apikey`、`quota`、`routing`、`billing` 和 `requestlog` 暴露的应用接口。
2. `billing` 独占余额写入权，其他模块不能直接更新 `wallet_accounts`。
3. `channel` 独占上游凭证解密权，任何日志对象不得携带明文凭证。
4. `requestlog` 默认只存请求元数据和用量，不存提示词或完整响应。
5. 控制台会话与 API 令牌是两套完全独立的安全链。

## 一致性策略

- 钱包扣费使用 PostgreSQL 本地事务、行锁和唯一幂等键。
- 流式请求先冻结预计额度，结束后按实际用量结算；失败时释放冻结。
- `request_id` 贯穿入口、路由、上游、账单和日志。
- P0 使用同库事务保证核心结算；P1 引入 RabbitMQ 后通过 outbox 发送统计和通知事件。

## 可拆分点

当网关吞吐或发布节奏与后台业务明显分化时，将 `gateway + routing + quota` 拆成独立服务。拆分前先把其应用接口收敛为显式 port，并用契约测试固定 API 令牌校验、预冻结和结算协议。
