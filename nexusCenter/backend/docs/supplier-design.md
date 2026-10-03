# Wave 5B 供应商管理设计

## 1. 领域边界

三个容易混淆的概念保持独立：

| 概念 | 数据位置 | 含义 |
|---|---|---|
| 模型厂商 | `ai_models.provider` | 模型由谁研发，例如 OpenAI、Anthropic |
| 协议类型 | `channels.provider_type` | 渠道使用哪种请求协议，例如 openai、azure_openai |
| 商业供应商 | `suppliers` | 实际签约、结算并承担服务质量的主体 |

关系为 `supplier 1 -> N channels -> N channel_models`。停止供应商合作时不删除渠道和历史记录，只让 Gateway 不再选择该供应商的新请求。

## 2. 状态模型

合作状态和健康状态不能合并：

- `status`：`active / disabled / suspended / terminated`，由管理员维护。
- `health_status`：`healthy / degraded / unavailable / unconfigured`，Wave 7A 由探测和聚合维护。

路由放行条件：

```text
supplier.status = active
AND supplier.health_status != unavailable
AND channel/model/group/route 均满足原有可用性条件
```

`terminated` 是终态，不能通过普通更新重新激活；需要恢复合作时应创建新供应商记录，避免历史合同和结算主体被覆盖。

## 3. 历史渠道迁移

V6 创建系统保留供应商 `legacy-unassigned`，把 V6 之前的所有渠道迁入该供应商，再把 `channels.supplier_id` 设为非空。这样可以同时满足：

1. 已有配置升级后仍能继续路由。
2. 新代码不需要为 supplier_id 空值保留永久兼容分支。
3. 管理员可以逐个把历史渠道重新归类到真实供应商。

## 4. 管理接口契约

所有写接口使用 Cookie Session、CSRF 和 `ROLE_ADMIN`；更新使用完整快照和 `version` 乐观锁。

### `GET /api/v1/admin/suppliers`

查询参数：`page`、`page_size`、`query`、`status`、`health_status`。

### `POST /api/v1/admin/suppliers`

```json
{
  "code": "openai-direct",
  "name": "OpenAI Direct",
  "supplier_type": "direct",
  "status": "active",
  "billing_mode": "postpaid",
  "settlement_currency": "USD",
  "disabled_reason": null,
  "metadata": {}
}
```

创建时 `health_status` 固定为 `unconfigured`，不接受管理端伪造健康结果。

### `PUT /api/v1/admin/suppliers/{id}`

请求字段同创建接口并额外携带 `version`。旧版本返回 `409 CONFIGURATION_VERSION_CONFLICT`。

### 渠道接口变化

`POST/PUT /api/v1/admin/channels` 新增必填 `supplier_id`；渠道响应新增 `supplier_id`、`supplier_code` 和 `supplier_name`。渠道凭证规则不变，永不回显。

## 5. 成本与毛利

`channel_models` 保存普通输入、缓存输入和输出三种成本单价。最终 `request_logs` 保存本次实际采用的：

- `supplier_id`、`channel_model_id`
- 三种成本单价快照
- `supplier_cost_amount`、`supplier_cost_currency`
- `gross_margin_amount = billed_amount - supplier_cost_amount`

P0 不做汇率换算。管理员必须保证平台售价和供应商成本使用同一核算口径；币种快照用于对账和后续报表分组。

## 6. 供应商稳定性

最终请求日志不足以表达重试链。例如供应商 A 返回 500 后切换供应商 B 成功，只记录最终结果会把 A 的失败隐藏。因此新增 `upstream_attempt_logs`，每次真实发往上游的尝试记录一行。

归责供应商的错误包括：

- 网络、DNS、TLS 和连接失败
- 超时、408、429、5xx
- 上游 401/403 鉴权失败
- 上游 404 模型不存在
- 非法 JSON、SSE 空流或流中断

不归责供应商的错误包括：

- 用户 API 令牌、模型/分组权限、余额和平台限流
- 用户请求参数导致的上游 400/422
- 平台数据库、Redis、计费或渠道凭证解密失败
- 客户端主动断开连接

尝试日志写入属于可观测性支线；写入失败只记录平台 request_id，不能反向让已完成请求变成失败或触发客户端重试。

## 7. 安全规则

- 供应商 metadata、停用原因、错误摘要禁止保存 API 令牌、上游 APIKey、Token、密码、请求正文、响应正文和渠道凭证；字段名会移除空格、点号、下划线、连字符等分隔符后检查前后缀，防止 `x-api-key` 等变体绕过。
- 供应商状态在运行时 SQL 中 fail closed 过滤，不能只依赖管理页面。
- 供应商禁用只影响后续新选路；已经发出的上游请求继续完成并结算。
- 管理更新通过 `version` 防止两个页面并发覆盖合作状态。
- 所有表和字段使用 PostgreSQL `COMMENT` 写入中文说明；V1-V5 不回改。

## 8. 后续 Wave

- Wave 7A：定时探测、渠道与供应商健康聚合、熔断/半开/恢复、告警抑制和恢复通知。
- Wave 7B：按供应商、模型、渠道和分组聚合请求量、错误率、成本、收入和毛利，并对接管理仪表盘。
