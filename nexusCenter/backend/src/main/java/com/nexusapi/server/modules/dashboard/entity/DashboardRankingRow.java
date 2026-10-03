package com.nexusapi.server.modules.dashboard.entity;

import java.math.BigDecimal;

/** 供应商、模型、渠道或分组经营排名数据库行。 */
public class DashboardRankingRow {
    /** 维度业务标识字符串。 */
    private String dimensionId;
    /** 当前维度名称；资源不存在时回退为业务标识。 */
    private String dimensionName;
    /** 最终请求总数。 */
    private long requestCount;
    /** 最终成功请求数。 */
    private long successCount;
    /** 最终失败请求数。 */
    private long failureCount;
    /** 输入 Token 总数。 */
    private long inputTokens;
    /** 输出 Token 总数。 */
    private long outputTokens;
    /** 缓存输入 Token 总数。 */
    private long cachedTokens;
    /** 平台实际结算收入。 */
    private BigDecimal billedAmount;
    /** 供应商实际成本。 */
    private BigDecimal supplierCostAmount;
    /** 平台实际毛利。 */
    private BigDecimal grossMarginAmount;
    /** 最终请求耗时总和，单位毫秒。 */
    private long latencySumMs;
    /** 真实上游调用尝试总数。 */
    private long attemptCount;
    /** 真实上游调用成功次数。 */
    private long attemptSuccessCount;
    /** 归责供应商的真实上游失败尝试次数。 */
    private long attemptSupplierFailureCount;

    public String getDimensionId() { return dimensionId; }
    public void setDimensionId(String dimensionId) { this.dimensionId = dimensionId; }
    public String getDimensionName() { return dimensionName; }
    public void setDimensionName(String dimensionName) { this.dimensionName = dimensionName; }
    public long getRequestCount() { return requestCount; }
    public void setRequestCount(long requestCount) { this.requestCount = requestCount; }
    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }
    public long getFailureCount() { return failureCount; }
    public void setFailureCount(long failureCount) { this.failureCount = failureCount; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(long cachedTokens) { this.cachedTokens = cachedTokens; }
    public BigDecimal getBilledAmount() { return billedAmount; }
    public void setBilledAmount(BigDecimal billedAmount) { this.billedAmount = billedAmount; }
    public BigDecimal getSupplierCostAmount() { return supplierCostAmount; }
    public void setSupplierCostAmount(BigDecimal supplierCostAmount) { this.supplierCostAmount = supplierCostAmount; }
    public BigDecimal getGrossMarginAmount() { return grossMarginAmount; }
    public void setGrossMarginAmount(BigDecimal grossMarginAmount) { this.grossMarginAmount = grossMarginAmount; }
    public long getLatencySumMs() { return latencySumMs; }
    public void setLatencySumMs(long latencySumMs) { this.latencySumMs = latencySumMs; }
    public long getAttemptCount() { return attemptCount; }
    public void setAttemptCount(long attemptCount) { this.attemptCount = attemptCount; }
    public long getAttemptSuccessCount() { return attemptSuccessCount; }
    public void setAttemptSuccessCount(long attemptSuccessCount) { this.attemptSuccessCount = attemptSuccessCount; }
    public long getAttemptSupplierFailureCount() { return attemptSupplierFailureCount; }
    public void setAttemptSupplierFailureCount(long value) { this.attemptSupplierFailureCount = value; }
}

