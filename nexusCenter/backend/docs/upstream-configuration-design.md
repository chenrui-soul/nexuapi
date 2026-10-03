# Wave 5 模型、渠道、分组与价格配置设计

## 1. 交付目标

管理员能够配置一条完整上游路径：

```text
公开模型
  -> 渠道模型映射（上游模型名 + 成本价）
  -> 上游渠道（URL + 加密凭证）
  -> 商业供应商（合作状态 + 健康状态 + 结算币种）
  -> 分组路由（优先级 + 权重）
  -> 计费分组（倍率）
```

本 Wave 交付配置面；Gateway Wave 再读取这些配置执行实际请求、健康选路和重试。

## 2. 价格口径

- `ai_models.input_price/output_price/cached_input_price`：面对用户的基础售价。
- `channel_models.cost_input_price/cost_cached_input_price/cost_output_price`：上游供应商成本，用于毛利和路由分析，不直接向用户扣费。
- `suppliers.settlement_currency`：供应商结算币种；P0 不做汇率换算，售价和成本必须使用同一核算口径。
- `routing_groups.price_multiplier`：分组倍率。
- 后续计费公式：`基础售价 × 分组倍率 × 实际用量`。
- 金额统一使用 `BigDecimal` 和 PostgreSQL `NUMERIC`，禁止浮点数。

## 3. 管理接口

所有接口均要求 Cookie Session、CSRF 和 `ROLE_ADMIN`。

| 资源 | 查询 | 创建 | 更新 |
|---|---|---|---|
| 供应商 | `GET /api/v1/admin/suppliers` | `POST /api/v1/admin/suppliers` | `PUT /api/v1/admin/suppliers/{id}` |
| 模型 | `GET /api/v1/admin/models` | `POST /api/v1/admin/models` | `PUT /api/v1/admin/models/{id}` |
| 渠道 | `GET /api/v1/admin/channels` | `POST /api/v1/admin/channels` | `PUT /api/v1/admin/channels/{id}` |
| 渠道模型映射 | `GET /api/v1/admin/channel-models` | `POST /api/v1/admin/channel-models` | `PUT /api/v1/admin/channel-models/{id}` |
| 分组 | `GET /api/v1/admin/groups` | `POST /api/v1/admin/groups` | `PUT /api/v1/admin/groups/{id}` |
| 分组路由 | `GET /api/v1/admin/group-routes` | `POST /api/v1/admin/group-routes` | `PUT /api/v1/admin/group-routes/{id}` |

供应商的合作状态和健康状态分开维护。Gateway 只选择 `status = active` 且 `health_status != unavailable` 的供应商；详细规则见 [supplier-design.md](supplier-design.md)。

列表统一支持 `page`、`page_size`、`query` 和 `status`；写接口使用版本号进行乐观锁控制。

## 4. 凭证安全规则

1. 渠道创建时必须提供凭证，更新时不传表示保留原凭证。
2. 凭证使用 AES-256-GCM 加密，每次写入使用随机 96 位 IV 和 128 位认证标签。
3. 加密密钥从现有 32 字节主密钥派生，但使用独立派生标签和 AAD，与用户邮箱密文隔离。
4. 响应只返回 `credential_configured`、HMAC 指纹和更新时间。
5. 审计记录只写 `credential_rotated=true/false`，不写凭证、密文或请求体。
6. Controller、Service、Mapper、异常和日志均不得输出明文凭证。
7. 渠道模型 `config` 递归禁止 `authorization`、`apiKey`、`access-token` 等凭证字段及其大小写、下划线、连字符变体。

## 5. 状态与删除策略

- 管理员可设置模型：`active/disabled/maintenance`。
- 管理员可设置渠道、分组：`active/disabled`；`degraded/circuit_open` 留给健康检测和熔断模块。
- 渠道模型映射和分组路由：`active/disabled`。
- P0 不提供物理删除，避免级联删除破坏 API 令牌、日志和路由引用。

## 6. 一致性规则

- 同一渠道下，同一公开模型和上游模型名不能重复。
- 同一分组、模型和渠道模型映射不能重复。
- 分组路由中的 `model_id` 必须与 `channel_model_id` 指向的模型一致。
- 所有更新使用 `WHERE id = ? AND version = ?`，成功后版本号加一。
- 创建依赖数据库唯一约束防止重试产生重复资源；更新依靠版本号防止覆盖他人修改。

## 7. 本 Wave 不包含

- `/v1/models` 和 `/v1/chat/completions` 生产网关。
- SSE 流式转发。
- 渠道健康检查、熔断和自动状态切换。
- 运行时权重抽样、重试和错误分类。
- 管理后台前端页面；当前可通过 Swagger 或 API 客户端配置。

## 8. 交付验证

- PostgreSQL 16 上 Flyway V1 到 V4 迁移成功。
- Wave 5 管理配置集成测试 5/5 通过，覆盖权限、CSRF、乐观锁、映射一致性和凭证防回显。
- 审计服务会递归检查 Map、DTO、record 和嵌套 JSON，敏感字段命中时拒绝持久化。
- AES-256-GCM 密文篡改测试通过，认证标签校验失败时不会返回明文。
- 项目全量回归 35/35 通过，独立 Spring Boot JAR 打包成功。
