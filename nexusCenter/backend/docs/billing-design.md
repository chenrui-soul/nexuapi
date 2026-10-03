# Wave 4 钱包与账本设计

## 1. 交付边界

- 控制台提供当前用户钱包余额和分页账本查询。
- `billing` 模块向后续 Gateway、支付和管理模块提供入账、预冻结、结算、释放和退款 Service API。
- 本 Wave 不接入真实支付渠道，不对普通用户暴露人工充值写接口。

## 2. 资金恒等式

```text
available = permanent_credits + expiring_credits
total = available + frozen_credits
ledger.amount = permanent_delta + expiring_delta + frozen_delta
ledger.balance_after = permanent_after + expiring_after + frozen_after
```

- 预冻结只把可用余额搬到冻结余额，`amount = 0`。
- 结算才产生真实消费，`amount = -actual_amount`。
- 释放只把冻结余额返回可用余额，`amount = 0`。
- 退款增加用户资产，`amount = +refund_amount`。

## 3. 扣款与退款顺序

1. 冻结时先使用有效期额度，再使用永久额度。
2. 结算金额小于冻结金额时，多余部分按原分桶返回。
3. 结算金额大于冻结金额时，差额继续按“有效期→永久”顺序原子扣除；不足则整个事务回滚。
4. 退款按原结算分桶返还，允许多次部分退款，累计不得超过已结算金额。

## 4. 并发与幂等

- 所有资金变更在 PostgreSQL 事务内使用 `SELECT ... FOR UPDATE` 锁定钱包或冻结单。
- 入账、冻结、结算、释放和退款都必须携幂等键。
- 同一用户的同一幂等键重放返回原结果；幂等键与原请求参数不一致时返回 409。
- 禁止直接修改或删除 `billing_ledger`，数据库触发器强制账本 append-only。

## 5. 状态机

```text
reserved → settled
reserved → released
settled → partial/full refund（状态仍为 settled，通过 refunded_amount 累计）
```

- `released` 不能再结算。
- `settled` 不能再释放。
- 结算和释放各自只能成功一次，相同幂等键可重放。

## 6. 控制台接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/wallet` | 当前用户的永久、有效期、冻结、可用和总余额 |
| GET | `/api/v1/wallet/ledger` | 当前用户的不可变账本，支持分页 |

Controller 只从 `NexusUserPrincipal` 取得 `userId`，不接受客户端传入的用户编号。

## 7. 验收标准

- 余额不足、超额退款、非法状态不能产生部分资金变更。
- 重复请求不重复扣款、冻结、入账或退款。
- 账户快照与账本分桶变化在每次操作后保持恒等。
- 禁止跨用户查询账户和账本。
- PostgreSQL 16 + Redis 7 集成测试覆盖完整“入账→冻结→结算→退款”链路。

## 8. 实现状态

- [x] V3 Flyway 增量迁移与安全回滚门禁。
- [x] `WalletController -> BillingService -> BillingMapper` 分层。
- [x] 充值/赠送入账、预冻结、差额结算、全额释放、多次部分退款。
- [x] 用户级幂等、钱包行锁、乐观版本和 API 令牌消费上限。
- [x] 账本数据库 append-only 触发器、敏感元数据拒绝和 8KB 上限。
- [x] 9 个资金集成测试，包含并发防透支和跨用户接口验证。

自动到期扣减有效期额度属于后续定时任务 Wave；V3 已在账本保留 `expires_at` 作为后续扩展入口。
