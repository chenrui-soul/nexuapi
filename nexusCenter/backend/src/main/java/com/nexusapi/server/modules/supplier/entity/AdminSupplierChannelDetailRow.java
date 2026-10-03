package com.nexusapi.server.modules.supplier.entity;

import java.time.Instant;
import java.util.UUID;

/** 供应商详情页渠道行；原始 Base URL 只在服务层用于生成脱敏 origin。 */
public class AdminSupplierChannelDetailRow {
    /** 渠道标识。 */
    private UUID id;
    /** 渠道显示名称。 */
    private String name;
    /** 上游协议适配器类型。 */
    private String providerType;
    /** 渠道原始地址，仅供服务层脱敏，不得直接进入响应。 */
    private String baseUrl;
    /** 渠道业务状态；circuit_open 只可能来自尚未清理的历史数据。 */
    private String status;
    /** 连续失败观测次数，不参与路由阻断。 */
    private int consecutiveFailures;
    /** 历史熔断截止时间兼容值，当前版本不参与选路。 */
    private Instant circuitOpenUntil;
    /** 当前渠道关联的模型映射数量。 */
    private long mappingCount;
    /** 最近一次真实上游尝试结果。 */
    private String lastAttemptOutcome;
    /** 最近一次归一化错误分类，不包含异常原文。 */
    private String lastErrorCategory;
    /** 最近一次真实上游尝试时间。 */
    private Instant lastAttemptAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Instant getCircuitOpenUntil() { return circuitOpenUntil; }
    public void setCircuitOpenUntil(Instant circuitOpenUntil) { this.circuitOpenUntil = circuitOpenUntil; }
    public long getMappingCount() { return mappingCount; }
    public void setMappingCount(long mappingCount) { this.mappingCount = mappingCount; }
    public String getLastAttemptOutcome() { return lastAttemptOutcome; }
    public void setLastAttemptOutcome(String lastAttemptOutcome) { this.lastAttemptOutcome = lastAttemptOutcome; }
    public String getLastErrorCategory() { return lastErrorCategory; }
    public void setLastErrorCategory(String lastErrorCategory) { this.lastErrorCategory = lastErrorCategory; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
}
