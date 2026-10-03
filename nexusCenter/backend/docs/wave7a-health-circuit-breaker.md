# Wave 7A 渠道健康探测与熔断

> 当前状态（Flyway V39）：自动熔断已暂停。本文件保留 Wave 7A 的历史设计记录；当前运行时失败只会把渠道标记为 `degraded` 并记录失败次数/最近错误，Gateway 仍会选择该渠道。`circuit_open` 和 `circuit_open_until` 仅作数据库及接口兼容，不再自动写入或参与选路；人工 `disabled` 仍严格阻止调用。

## 交付目标

```text
主动探测或真实调用发现故障
  → 原子增加连续失败次数
  → 达到阈值后写入 circuit_open
  → 新请求立即从路由候选中排除
  → 熔断到期后由定时探测执行恢复验证
  → 探测成功恢复 active 并重新加入路由
```

## 状态机

| 当前状态 | 信号 | 下一状态 | 说明 |
|---|---|---|---|
| `active` | 第 1～2 次供应商责任失败 | `degraded` | 继续参与路由，成功后清零 |
| `degraded` | 连续失败达到阈值 | `circuit_open` | 设置 `circuit_open_until`，新请求立即排除 |
| `circuit_open` | 熔断尚未到期 | `circuit_open` | 不参与真实请求，也不提前探测 |
| `circuit_open` | 到期后定时探测成功 | `active` | 清零失败次数并重新加入路由 |
| `circuit_open` | 管理员手动探测成功 | `active` | 可信人工验证可在到期前恢复 |
| `circuit_open` | 到期后探测失败 | `circuit_open` | 延长熔断时间 |
| `disabled` | 任意自动信号 | `disabled` | 人工禁用优先，自动逻辑永不恢复 |

真实请求成功只能把 `degraded` 恢复为 `active`，不能恢复已经打开的熔断。这样不会让真实用户流量承担半开试探，也不会在熔断到期瞬间产生并发洪峰。

## 故障归责

计入渠道失败次数：

- 网络连接失败和超时
- HTTP 401/403
- HTTP 408、429、5xx
- 上游协议错误、空 SSE、SSE 首帧后上游断流
- 已确认的渠道模型 404

不计入渠道失败次数：

- 用户参数导致的 HTTP 400/422
- 客户端主动断开
- 平台计费、Redis、数据库或内部处理异常
- 主动 `GET /models` 返回 400/404/422；这通常表示探测端点不兼容，只记录 `unconfigured`

## 主动探测

- 默认每 60 秒查询一批最久未探测的 `active/degraded` 渠道。
- `circuit_open_until` 到期的渠道优先执行恢复探测。
- 默认请求 `GET {base_url}/models`；管理员可为单个渠道配置 `/health/ready` 等受控相对路径。
- 自定义路径必须以 `/` 开头，禁止绝对 URL、查询参数、片段、`..`、`//` 和跨主机跳转；数据库、Service 和 Probe Client 三层校验。
- 使用共享 Reactor Netty 连接池执行探测。
- 探测只读取 HTTP 状态码，响应正文会被主动丢弃，不写日志和数据库。
- 渠道凭证只在请求前短暂解密，`health_checks` 只保存固定分类和脱敏摘要。
- 多实例通过 Redis 全局锁防重；Redis 不可用时跳过本轮，避免重复探测并发累加失败次数。
- 单个渠道异常不会中断同一批次的其他渠道。

## 分组健康与告警

分组健康状态根据管理员配置状态与实时可路由数量聚合，但不会自动修改 `routing_groups.status`：

- 没有完整可用配置或分组被人工停用：`unconfigured`
- 已配置路由全部不可用：`unavailable`
- 部分路由可用：`degraded`
- 所有已配置路由均可用：`healthy`

渠道健康事务提交后发布内部事件，分组聚合在独立事务中运行。同一分组使用 PostgreSQL transaction advisory lock 串行化状态转换，避免多实例并发产生重复 open 告警或重复恢复通知。

- 首次进入 `unavailable`：创建一个 `health_alerts.status='open'` 告警并通知所有有效管理员。
- 持续不可用且处于冷却窗口：只增加 `occurrence_count` 和 `suppressed_count`。
- 超过冷却窗口：再次发送故障提醒并增加 `notification_count`。
- 恢复为 `healthy/degraded/unconfigured`：解析 open 告警并只发送一次恢复通知。
- 告警标题、摘要和分组健康历史均由固定模板生成，不保存渠道凭证、Authorization、上游响应正文或异常原文。

## 管理员接口与页面

所有接口要求 Redis Cookie Session 和 `ROLE_ADMIN`，手动探测 POST 还必须通过 CSRF：

| 接口 | 用途 |
|---|---|
| `GET /api/v1/admin/health/channels` | 分页查询渠道状态、失败次数、熔断时间和最近探测 |
| `GET /api/v1/admin/health/groups` | 分页查询分组实时健康与可用路由数量 |
| `GET /api/v1/admin/health/checks` | 分页查询渠道和分组健康历史 |
| `GET /api/v1/admin/health/alerts` | 分页查询告警生命周期与通知/抑制计数 |
| `POST /api/v1/admin/health/channels/{id}/probe` | 管理员立即探测，并记录脱敏审计 |

前端 `/admin/health` 提供渠道、分组、告警和历史四个视图；渠道维护抽屉同步支持 `health_probe_path`。页面不会读取上游凭证，也不会将 CSRF 或敏感字段写入浏览器持久化存储。

## 供应商健康聚合

供应商人工合作状态 `status` 与自动健康状态 `health_status` 保持独立：

- 没有自动管理渠道：`unconfigured`
- 所有自动管理渠道均为 `circuit_open`：`unavailable`
- 同时存在健康和 `degraded/circuit_open` 渠道：`degraded`
- 所有自动管理渠道均为 `active`：`healthy`

自动逻辑只修改 `health_status`，绝不把人工停用、暂停或终止的供应商改回 `active`。

## 配置项

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `HEALTH_PROBE_ENABLED` | `true` | 是否启用后台主动探测 |
| `HEALTH_PROBE_INTERVAL` | `60s` | 正常渠道最小探测间隔和调度间隔 |
| `HEALTH_PROBE_INITIAL_DELAY` | `15s` | 应用启动后的首次探测延迟 |
| `HEALTH_PROBE_TIMEOUT` | `5s` | 单渠道探测超时上限 |
| `HEALTH_FAILURE_THRESHOLD` | `3` | 连续失败熔断阈值 |
| `HEALTH_CIRCUIT_OPEN_DURATION` | `5m` | 熔断保持时间 |
| `HEALTH_ALERT_COOLDOWN` | `30m` | 同一分组持续故障重复通知的冷却窗口 |
| `HEALTH_PROBE_LOCK_TTL` | `3m` | Redis 多实例防重锁 TTL；启动时会自动抬高到覆盖整批最坏耗时 |
| `HEALTH_PROBE_BATCH_SIZE` | `20` | 每轮最多探测渠道数，硬上限 200 |

## 当前限制

- P0 健康探测仍采用无业务费用的 HTTP GET 状态码判定，不执行需要请求体或会产生模型费用的深度推理探测。
- 告警当前只写站内通知；邮件、Webhook、短信和外部事件平台适配器留在后续通知 Wave。
- 分组告警由渠道健康变化驱动；管理员修改路由配置后的即时重算可在后续配置事件化节点补充。

## 验证结果

- Java 21 + PostgreSQL 16 + Redis 7 全量测试：76/76 通过，失败 0，错误 0，跳过 0。
- Flyway 空库顺序执行 V1-V8 成功，新增表和字段中文 COMMENT 校验通过。
- 前端生产构建成功，23 项 Node 测试通过，ESLint 通过。
- 自定义路径、手动恢复、disabled 保护、告警打开、冷却抑制、恢复通知、ROLE_ADMIN、CSRF 和敏感正文丢弃均有集成测试。
- 最终可执行 JAR：`target/nexus-api-server-0.1.0-SNAPSHOT.jar`，SHA-256 `3B5F5D525E5952E343C6815506C1248D8FBE43969BE3178F35E297CE715A1A6A`。
