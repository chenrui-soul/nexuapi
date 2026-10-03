# P0 实施路线

1. 认证：已完成验证码、注册、登录、Redis 会话恢复、当前用户和退出；密码重置待实现。
2. 用户与公开目录：用户资料、模型列表、分组状态。
3. API 令牌：Wave 3A 管理闭环和 Wave 3B Bearer 鉴权切片已完成，包括一次性展示、摘要存储、配置与状态管理、审计、前端对接、`ApiKeyAuthenticationService`、Bearer Filter、Principal 及失效负例测试；Wave 6 已在 Gateway 实际执行 IP、模型、分组、RPM、TPM 和并发限制。详见 [api-key-design.md](api-key-design.md)。
4. 钱包：Wave 4 已完成余额、不可变账本、幂等冻结、差额结算、释放、多次部分退款、并发防透支和 API 令牌消费上限。自动额度到期定时任务留给后续 Wave。详见 [billing-design.md](billing-design.md)。
5. 管理配置：Wave 5 已完成模型售价、渠道、加密凭证、渠道模型映射、成本价、计费分组、倍率和分组路由；Wave 5B 已新增独立供应商、渠道归属、供应商一键停用、成本/毛利快照和逐次上游尝试记录。所有管理写操作要求 Cookie Session、CSRF、`ROLE_ADMIN` 和乐观锁。详见 [upstream-configuration-design.md](upstream-configuration-design.md) 与 [supplier-design.md](supplier-design.md)。
6. 网关闭环：Wave 6 已完成 `/v1/models`、`/v1/chat/completions`、SSE、优先级与权重路由、首帧前重试、Redis 原子限流、预冻结/结算/释放和脱敏调用日志；Wave 5B 进一步接入供应商状态过滤和 assigned/internal 分组显式授权校验。Gateway 集成测试 15/15、全量回归 55/55 通过。
7. 可观测性：Wave 7A 已完成自动健康探测、失败观测、供应商/分组健康聚合与告警；自动渠道熔断自 V39 起暂停，波动渠道仍参与选路，人工停用继续生效。Wave 8 已完成最终请求与逐次上游尝试的小时/日级聚合，以及收入、成本、毛利、P95、稳定性和四维排名仪表盘。详见 [wave7a-health-circuit-breaker.md](wave7a-health-circuit-breaker.md) 与 [wave8-dashboard-analytics.md](wave8-dashboard-analytics.md)。
8. 验收：PostgreSQL、Redis 与 WireMock 集成测试已覆盖核心网关路径；正式容量结论仍需后续 k6 压测。

每一步都应同时交付 Flyway 迁移、OpenAPI、权限校验和自动化测试，避免先堆接口再补安全与数据一致性。

## 数据字典与代码注释基线

- 已完成 11 个 MyBatis Row、170 个字段的中文 JavaDoc，重点说明金额单位、Token 数、状态、幂等键、密文、摘要、指纹和乐观锁。
- 已通过 Flyway V5 为 22 张业务表和 322 个字段写入 PostgreSQL `COMMENT` 元数据；V1-V4 不回改。
- 新增静态测试和数据库集成测试，后续新增 Row 字段或数据库字段漏写说明时会在测试阶段失败。
- V5 回滚脚本位于 `docs/migrations/V5__add_database_comments_rollback.sql`。
