# API 令牌模块设计（Wave 3）

## 1. Proposal

- **Intent**：为登录用户提供可独立管理、可立即撤销、不会泄露明文的 OpenAI 兼容 API 令牌，并为后续网关鉴权、限流、计费和调用日志提供稳定身份。
- **In scope**：用户 API 令牌的创建、分页列表、配置更新、启用/禁用、不可逆撤销、一次性明文展示、权限限制以及网关校验契约。
- **Out of scope**：系统只读访问令牌、管理员代管用户 Key、Key 用量聚合实现、钱包扣费、模型/分组管理和 `/v1/**` 网关转发。这些能力只在本设计中预留依赖契约。
- **Approach**：控制台接口继续使用 Cookie Session + CSRF；模型调用使用独立的 Bearer API 令牌安全链；模块内部采用 `Controller -> Service -> Mapper`。

## 2. 业务规则

### 2.1 Key 类型与数量

1. P0 只实现用户模型调用 Key，不实现前端页面中的“系统访问令牌”。该卡片在真实联调前应隐藏或标记为未开放。
2. 每个用户最多保留 20 个未撤销 Key；`revoked` Key 不占可用名额，但仍保留审计记录。
3. Key 名称去除首尾空格后长度为 1～100，同一用户允许重名，资源 ID 才是唯一标识。
4. 只有 `active`、未过期、所属用户为 `active` 且满足限制规则的 Key 可以通过网关鉴权。

### 2.2 Key 格式与存储

- 明文格式：`sk-nx-v1_<base64url>`。
- 随机部分使用 CSPRNG 生成 32 字节随机数，Base64 URL-safe 编码且不带填充，随机熵为 256 bit。
- 数据库只保存：HMAC-SHA256 摘要、哈希密钥版本、展示前缀和末尾识别字符。
- 摘要使用独立的 API 令牌 HMAC 密钥，禁止复用邮箱查询 HMAC 密钥。
- 完整明文只出现在创建成功响应中一次，不写数据库、不写普通缓存、不写审计详情、不写日志。
- 列表和后续接口只返回 `masked_key`，示例：`sk-nx-v1_Q8w7...c91f2a0b`。

### 2.3 状态机

```text
active <-> disabled
active/disabled -> revoked
active/disabled + expires_at <= now() -> effective expired
revoked -> terminal（不可恢复）
```

- `disabled` 可以重新启用。
- `revoked` 是不可逆终态，DELETE 操作采用逻辑撤销，不能物理删除。
- `expired` 以 `expires_at` 为最终判断依据，即使数据库中的 `status` 尚未被定时任务物化为 `expired`，网关仍必须拒绝。
- 已过期 Key 如需恢复，用户必须先将 `expires_at` 修改到未来，再显式启用。
- 禁用或撤销只保证后续新请求立即失效；已经进入上游处理的请求不强制中断，但仍按实际用量结算。

### 2.4 限制字段语义

| 字段 | `null`/空数组语义 | 规则 |
|---|---|---|
| `default_group_id` | 跟随用户默认分组 | 必须是用户可用分组 |
| `allowed_model_ids` | 不额外限制模型 | 最多 100 个 UUID，未知或停用模型按拒绝处理 |
| `allowed_group_ids` | 不额外限制分组 | 最多 50 个 UUID；设置默认分组时必须包含该分组 |
| `ip_allowlist` | 不限制来源 IP | 最多 50 条 IPv4/IPv6 CIDR，入库前规范化、去重 |
| `rpm_limit` | 继承账户/系统限制 | 必须大于 0，最终采用所有层级中的最小限制 |
| `tpm_limit` | 继承账户/系统限制 | 必须大于 0，最终采用所有层级中的最小限制 |
| `concurrency_limit` | 继承账户/系统限制 | 必须大于 0，最终采用所有层级中的最小限制 |
| `credit_limit` | 不设置 Key 独立消费上限 | API 使用 `null` 表示不限；前端输入 `0` 时转换为 `null` |
| `expires_at` | 永不过期 | 非空时必须至少晚于当前时间 5 分钟 |

## 3. PostgreSQL 数据库设计

### 3.1 当前 V1 表

`V1__baseline_schema.sql` 已创建 `api_keys`，当前字段可以覆盖 P0 主体：

| 字段组 | 字段 |
|---|---|
| 归属与展示 | `id`、`user_id`、`name`、`key_prefix`、`key_suffix` |
| 安全凭证 | `key_hash` |
| 状态 | `status`、`expires_at`、`revoked_at`、`last_used_at` |
| 路由权限 | `default_group_id`、`allowed_model_ids`、`allowed_group_ids` |
| 风控 | `ip_allowlist`、`rpm_limit`、`tpm_limit`、`concurrency_limit` |
| 计费限制 | `credit_limit` |
| 审计时间 | `created_at`、`updated_at` |

已经执行过的 V1 不允许改写，避免 Flyway checksum 不一致。实现阶段新增 `V2__harden_api_keys.sql`。

### 3.2 V2 增量设计

```sql
ALTER TABLE api_keys
    ADD COLUMN key_hash_version SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT api_keys_hash_length_valid
        CHECK (octet_length(key_hash) = 32),
    ADD CONSTRAINT api_keys_credit_limit_positive
        CHECK (credit_limit IS NULL OR credit_limit > 0),
    ADD CONSTRAINT api_keys_expiry_after_creation
        CHECK (expires_at IS NULL OR expires_at > created_at);

CREATE INDEX idx_api_keys_active_expiry
    ON api_keys (expires_at)
    WHERE status = 'active' AND expires_at IS NOT NULL;
```

字段说明：

- `key_hash_version`：支持 HMAC 密钥轮换，Key 文本中的 `v1` 必须与数据库版本一致。
- `version`：配置更新和状态变更时执行 `version = version + 1`，避免两个页面并发覆盖。
- `status_changed_at`：支持安全审计和状态问题排查。
- JSONB 权限字段在 P0 保留；Service 必须严格校验 UUID/CIDR、数量和引用对象可用性。模型数量或权限查询复杂度明显增长后，再迁移为关联表。

### 3.3 索引和访问约束

1. 网关仅通过 `uk_api_keys_hash` 等值查询，不允许按前缀鉴权。
2. 用户控制台查询必须包含 `user_id = 当前用户`，更新语句必须同时包含 `id`、`user_id` 和 `revoked_at IS NULL`。
3. 撤销使用单条条件更新：

   ```sql
   UPDATE api_keys
      SET status = 'revoked', revoked_at = now(), status_changed_at = now(),
          updated_at = now(), version = version + 1
    WHERE id = :id
      AND user_id = :user_id
      AND revoked_at IS NULL;
   ```

4. Key 不做物理删除，`request_logs.api_key_id` 和审计日志可以长期追溯。
5. `last_used_at` 不在每次请求同步写数据库；网关阶段采用 Redis 去抖或批量更新，同一个 Key 最多每 5 分钟落库一次。

## 4. REST API 契约

### 4.1 通用规则

- 控制台路径：`/api/v1/api-keys`。
- 认证：`NEXUS_SESSION` Cookie。
- 写接口：必须携带 `X-CSRF-TOKEN`。
- 数据格式：请求字段使用 `snake_case`，时间统一返回 UTC ISO-8601。
- 用户访问不属于自己的 Key 时统一返回 `API_KEY_NOT_FOUND`，不能暴露资源是否存在。
- API 响应继续使用项目现有 `ApiResponse<T>`，分页使用 `PageResponse<T>`。
- POST 创建不是透明可重试操作。调用方遇到网络结果不确定时必须先刷新列表；若发现已创建但明文丢失，应撤销后重新创建，禁止为了重试而持久化明文。

### 4.2 获取 Key 列表

`GET /api/v1/api-keys?page=1&page_size=20&query=&status=`

| 参数 | 必填 | 规则 |
|---|---:|---|
| `page` | 否 | 默认 1，最小 1 |
| `page_size` | 否 | 默认 20，最大 100 |
| `query` | 否 | 最长 100，只匹配名称和展示前缀，不匹配摘要 |
| `status` | 否 | `active/disabled/expired/revoked` |

响应数据项：

```json
{
  "id": "uuid",
  "name": "Production Web",
  "masked_key": "sk-nx-v1_Q8w7...c91f2a0b",
  "status": "active",
  "default_group_id": null,
  "allowed_model_ids": [],
  "allowed_group_ids": [],
  "ip_allowlist": [],
  "rpm_limit": null,
  "tpm_limit": null,
  "concurrency_limit": null,
  "credit_limit": null,
  "used_credits": "0.000000000000",
  "reserved_credits": "0.000000000000",
  "remaining_credits": null,
  "expires_at": null,
  "last_used_at": null,
  "created_at": "2026-08-17T08:00:00Z",
  "updated_at": "2026-08-17T08:00:00Z",
  "version": 0
}
```

### 4.3 创建 Key

`POST /api/v1/api-keys`

```json
{
  "name": "Production Web",
  "default_group_id": null,
  "allowed_model_ids": [],
  "allowed_group_ids": [],
  "ip_allowlist": ["203.0.113.10/32"],
  "rpm_limit": 60,
  "tpm_limit": 200000,
  "concurrency_limit": 5,
  "credit_limit": 100.00000000,
  "expires_at": "2027-08-17T08:00:00Z"
}
```

- 成功：`201 Created`。
- `secret` 只在本响应出现一次。

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "Production Web",
    "secret": "sk-nx-v1_完整密钥仅此一次",
    "masked_key": "sk-nx-v1_Q8w7...c91f2a0b",
    "status": "active",
    "created_at": "2026-08-17T08:00:00Z",
    "version": 0
  },
  "request_id": "request-id"
}
```

### 4.4 修改配置

`PATCH /api/v1/api-keys/{id}`

- 可修改：名称、默认分组、模型/分组/IP 白名单、限流、消费上限、过期时间。
- 不可修改：密钥明文、摘要、前后缀、归属用户和创建时间。
- 当前接口采用“完整可编辑配置快照”语义，不是字段级局部 PATCH；前端必须回传未在当前表单中编辑的限制字段，避免把既有配置覆盖为空。
- 请求必须携带当前 `version`。版本不一致返回 `409 API_KEY_VERSION_CONFLICT`。
- PATCH 不负责启用、禁用和撤销。

```json
{
  "name": "Production Web v2",
  "default_group_id": null,
  "allowed_model_ids": ["uuid"],
  "allowed_group_ids": [],
  "ip_allowlist": ["203.0.113.10/32"],
  "rpm_limit": 60,
  "tpm_limit": 200000,
  "concurrency_limit": 5,
  "credit_limit": null,
  "expires_at": "2027-08-17T08:00:00Z",
  "version": 0
}
```

### 4.5 修改状态

`PUT /api/v1/api-keys/{id}/status`

```json
{
  "status": "disabled",
  "version": 1
}
```

- 只接受 `active` 或 `disabled`。
- PUT 是幂等操作；重复设置为当前状态返回成功，不重复写审计日志。
- `revoked` 或仍然过期的 Key 不能启用。

### 4.6 撤销 Key

`DELETE /api/v1/api-keys/{id}`

- 成功返回 `200 OK` 和 `{ "revoked": true }`。
- 同一用户重复撤销返回相同成功结果，保证接口幂等。
- 撤销后不能恢复，前端必须二次确认。

### 4.7 Key 用量

Key 级实时用量直接随 `GET /api/v1/api-keys` 列表数据返回，避免前端为每枚 Key 发起额外请求：

- `used_credits`：已结算积分，口径为 `settled_amount - refunded_amount`。
- `reserved_credits`：当前 `reserved` 状态的预冻结积分。
- `remaining_credits`：设置 `credit_limit` 时为 `max(credit_limit - used_credits - reserved_credits, 0)`；未设置上限时为 `null`。
- 金额字段以字符串返回，避免浏览器浮点数丢失计费精度。

## 5. 错误码

| 错误码 | HTTP | 使用场景 |
|---|---:|---|
| `API_KEY_NOT_FOUND` | 404 | Key 不存在或不属于当前用户 |
| `API_KEY_LIMIT_REACHED` | 409 | 未撤销 Key 已达到用户上限 |
| `API_KEY_VERSION_CONFLICT` | 409 | 乐观锁版本不一致 |
| `API_KEY_STATUS_CONFLICT` | 409 | 撤销、过期等状态不允许当前操作 |
| `API_KEY_INVALID` | 401 | 网关收到未知、禁用或已撤销的 Key |
| `API_KEY_EXPIRED` | 401 | 网关收到已过期 Key |
| `API_KEY_IP_NOT_ALLOWED` | 403 | 来源 IP 不在白名单 |
| `API_KEY_SCOPE_DENIED` | 403 | 模型或分组不在授权范围 |
| `VALIDATION_ERROR` | 400 | 字段格式、数量、CIDR 或引用对象不合法 |
| `RATE_LIMIT_EXCEEDED` | 429 | RPM、TPM 或并发限制触发 |

错误响应不能包含 Key 摘要、完整前缀、用户邮箱、SQL、内部类名或堆栈。

## 6. 安全规则

### 6.1 控制台管理接口

1. 所有接口必须从 `NexusUserPrincipal.userId()` 获取用户 ID，禁止接受请求体中的 `user_id`。
2. 创建、更新、状态变更和撤销必须通过 CSRF 校验。
3. Service 层负责归属校验；Mapper 的用户操作 SQL 必须带 `user_id`，形成双层越权防护。
4. 创建频率限制：每用户每分钟最多 5 次、每 IP 每分钟最多 20 次；超限返回 429。
5. 创建、配置变更、启用、禁用和撤销写入 `audit_logs`，但 `before_data`、`after_data` 禁止包含 `secret`、`key_hash` 和完整 Authorization。
6. 接口响应 VO 必须采用白名单字段，数据库 Entity 不得直接序列化。

### 6.2 网关鉴权

1. 仅接受单个 `Authorization: Bearer <key>`，缺失、重复、格式错误一律 401。
2. 先解析格式和版本，再用对应版本的独立 HMAC 密钥计算摘要，通过 `key_hash_version + key_hash` 索引精确查询；完整 Secret 不进入 SQL。
3. 鉴权层每次新请求检查 Key 状态、过期时间和用户状态；Principal 只携带 IP 白名单、模型/分组权限与限额配置，由后续 Gateway/Quota 在已知请求上下文时 fail closed 执行。P0 不缓存有效 Key，确保禁用和撤销对后续请求立即生效。
4. `X-Forwarded-For` 只在应用部署于受信任反向代理后使用；否则使用直接连接地址，防止伪造 IP 绕过白名单。
5. 网关日志只能记录 `api_key_id` 和脱敏展示值，禁止记录 Authorization Header。
6. HMAC 配置缺失时应用拒绝启动；未知 Key 版本按鉴权失败处理并记录安全指标，不记录传入密钥。

### 6.3 密钥轮换

- 配置必须支持“当前写入版本 + 历史验证版本”。
- 新建 Key 只使用当前版本；旧 Key 继续按自身 `key_hash_version` 验证。
- 删除历史 HMAC 密钥前，必须确认数据库已不存在对应版本的有效 Key。
- HMAC 密钥只能通过环境变量或密钥管理服务注入，不能进入代码库、日志或 Swagger 示例。

## 7. 行为场景与验收标准

### Requirement: 一次性安全创建

- GIVEN 用户已登录且未达到 Key 数量上限
- WHEN 用户提交合法创建请求
- THEN 系统返回 201 和一次性 `secret`
- AND 数据库只存在 32 字节摘要、版本、前缀和后缀
- AND 日志与审计数据中不存在完整密钥

### Requirement: 用户数据隔离

- GIVEN 用户 A 已登录且用户 B 拥有一个 Key
- WHEN 用户 A 使用用户 B 的资源 ID 查询、修改或撤销
- THEN 系统统一返回 404 `API_KEY_NOT_FOUND`
- AND 用户 B 的数据没有变化

### Requirement: 立即失效

- GIVEN Key 当前为 active
- WHEN 所有者禁用或撤销该 Key
- THEN 后续 `/v1/**` 新请求立即返回 401
- AND 撤销后的 Key 无法重新启用

### Requirement: 乐观并发控制

- GIVEN 两个页面都读取到 version=3
- WHEN 页面一保存后页面二继续提交 version=3
- THEN 页面二返回 409 `API_KEY_VERSION_CONFLICT`
- AND 页面一的变更不被覆盖

### Requirement: 限制规则 fail closed

- GIVEN Key 配置了 IP 或模型白名单
- WHEN 来源地址无法可信确定、模型 ID 不存在或不在白名单
- THEN 系统拒绝调用
- AND 不因为解析失败自动降级为“不限制”

## 8. 实现文件规划

```text
modules/apikey
├── controller/ApiKeyController.java
├── service/ApiKeyService.java
├── service/ApiKeyAuthenticationService.java
├── mapper/ApiKeyMapper.java
├── entity/ApiKeyRow.java
├── dto/ApiKeyCreateRequest.java
├── dto/ApiKeyUpdateRequest.java
├── dto/ApiKeyStatusRequest.java
├── vo/ApiKeyCreatedResponse.java
├── vo/ApiKeyItemResponse.java
├── security/ApiKeyAuthenticationFilter.java
├── security/NexusApiKeyPrincipal.java
├── enums/ApiKeyStatus.java
└── support/ApiKeyGenerator.java
```

模块边界：

- `apikey` 独占 Key 生成、摘要验证、状态和权限读取。
- `gateway` 只能使用 `ApiKeyAuthenticationService` 暴露的认证结果，不能直接访问 `ApiKeyMapper`。
- `billing` 负责 `credit_limit` 的强一致消费检查，`apikey` 不直接写钱包或账本。
- `quota` 负责 RPM/TPM/并发计数，`apikey` 只提供配置。
- `requestlog` 只保存 `api_key_id` 和脱敏标识。

## 9. 开发任务

### Wave 3A：API 令牌管理闭环

1. [x] 新增 V2 Flyway 迁移和回滚说明。
2. [x] 新增独立 API 令牌 HMAC 配置、生成器和摘要器。
3. [x] 实现 Entity、Mapper、Service、DTO、VO、Controller。
4. [x] 扩充错误码和审计动作。
5. [x] 实现创建、列表、更新、状态变更和撤销集成测试。
6. [x] 对接前端 API 令牌页面，删除本地演示令牌、演示系统令牌和伪造用量。

### Wave 3B：网关鉴权切片

1. [x] 实现 `ApiKeyAuthenticationService`、`ApiKeyAuthenticationFilter` 和 `NexusApiKeyPrincipal`。
2. [x] 将 `/v1/**` 从默认拒绝切换为“通过有效 API 令牌后允许”，并与控制台 Cookie Session 安全链隔离。
3. [x] 使用测试专用 `/v1/test-authentication` 探针验证 Principal，不提前实现生产模型或聊天端点。
4. [x] 验证缺失、重复、格式错误、未知版本、无效、禁用、撤销、过期和用户停用场景。
5. [ ] IP 白名单、模型/分组权限与限额执行留给 Gateway/Quota 集成 Wave，当前 Principal 已提供所需白名单字段。

## 10. 编码前门禁

- [x] 数据库不保存完整 API 令牌。
- [x] V1 不改写，增量通过 V2。
- [x] 用户归属和 IDOR 返回策略已定义。
- [x] 状态机和撤销不可逆规则已定义。
- [x] Key 格式、熵、摘要算法和密钥轮换已定义。
- [x] 每个写接口的 CSRF、并发和审计规则已定义。
- [x] 前端当前字段已映射：名称、分组、配额、有效期、模型白名单。
- [x] P0 不实现的系统访问令牌和用量聚合已明确排除。
- [x] 用户已确认设计，Wave 3A 管理闭环与 Wave 3B Bearer 鉴权切片均已完成编码、安全审查和自动化验证。
