package com.nexusapi.server.modules.supplier.entity;

import java.time.Instant;
import java.util.UUID;

/** MyBatis 供应商配置行，合作状态和自动健康状态保持独立。 */
public class AdminSupplierRow {
    /** 供应商全局唯一标识。 */
    private UUID id;
    /** 供应商稳定业务编码，忽略大小写后全局唯一。 */
    private String code;
    /** 管理端显示的供应商名称，忽略大小写后全局唯一。 */
    private String name;
    /** 供应商类型：direct、reseller、aggregator 或 other。 */
    private String supplierType;
    /** 人工合作状态：active、disabled、suspended 或 terminated。 */
    private String status;
    /** 自动健康状态：healthy、degraded、unavailable 或 unconfigured。 */
    private String healthStatus;
    /** 结算方式：prepaid、postpaid、monthly_settlement 或 other。 */
    private String billingMode;
    /** 三位大写结算币种代码；P0 不进行汇率换算。 */
    private String settlementCurrency;
    /** 非 active 状态的业务原因，禁止包含凭证或合同敏感值。 */
    private String disabledReason;
    /** 最近一次从 active 切换到非 active 的时间。 */
    private Instant disabledAt;
    /** 最近一次自动健康聚合或探测完成时间，由 Wave 7A 维护。 */
    private Instant lastHealthCheckedAt;
    /** 非敏感扩展元数据 JSON 文本，写入前会递归拒绝凭证字段。 */
    private String metadataJson;
    /** 供应商记录创建时间。 */
    private Instant createdAt;
    /** 供应商记录最后更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本号，防止并发状态和结算配置相互覆盖。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSupplierType() { return supplierType; }
    public void setSupplierType(String supplierType) { this.supplierType = supplierType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }
    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String billingMode) { this.billingMode = billingMode; }
    public String getSettlementCurrency() { return settlementCurrency; }
    public void setSettlementCurrency(String settlementCurrency) { this.settlementCurrency = settlementCurrency; }
    public String getDisabledReason() { return disabledReason; }
    public void setDisabledReason(String disabledReason) { this.disabledReason = disabledReason; }
    public Instant getDisabledAt() { return disabledAt; }
    public void setDisabledAt(Instant disabledAt) { this.disabledAt = disabledAt; }
    public Instant getLastHealthCheckedAt() { return lastHealthCheckedAt; }
    public void setLastHealthCheckedAt(Instant lastHealthCheckedAt) { this.lastHealthCheckedAt = lastHealthCheckedAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
