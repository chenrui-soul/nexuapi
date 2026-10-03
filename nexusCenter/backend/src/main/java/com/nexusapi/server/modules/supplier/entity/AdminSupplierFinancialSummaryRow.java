package com.nexusapi.server.modules.supplier.entity;

import java.math.BigDecimal;

/** 供应商最近 30 天请求、资金和真实上游尝试聚合行。 */
public class AdminSupplierFinancialSummaryRow {
    /** 最终请求总数。 */
    private long requestCount;
    /** 最终成功请求数。 */
    private long successCount;
    /** 平台向用户结算的收入。 */
    private BigDecimal billedAmount;
    /** 按实际 Token 计算的供应商成本。 */
    private BigDecimal supplierCostAmount;
    /** 平台收入减供应商成本后的毛利。 */
    private BigDecimal grossMarginAmount;
    /** 真实发往上游的尝试总数，包含重试。 */
    private long attemptCount;
    /** 真实上游尝试成功数。 */
    private long attemptSuccessCount;
    /** 归责供应商的真实上游失败数。 */
    private long attemptSupplierFailureCount;

    public long getRequestCount() { return requestCount; }
    public void setRequestCount(long requestCount) { this.requestCount = requestCount; }
    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }
    public BigDecimal getBilledAmount() { return billedAmount; }
    public void setBilledAmount(BigDecimal billedAmount) { this.billedAmount = billedAmount; }
    public BigDecimal getSupplierCostAmount() { return supplierCostAmount; }
    public void setSupplierCostAmount(BigDecimal supplierCostAmount) { this.supplierCostAmount = supplierCostAmount; }
    public BigDecimal getGrossMarginAmount() { return grossMarginAmount; }
    public void setGrossMarginAmount(BigDecimal grossMarginAmount) { this.grossMarginAmount = grossMarginAmount; }
    public long getAttemptCount() { return attemptCount; }
    public void setAttemptCount(long attemptCount) { this.attemptCount = attemptCount; }
    public long getAttemptSuccessCount() { return attemptSuccessCount; }
    public void setAttemptSuccessCount(long attemptSuccessCount) { this.attemptSuccessCount = attemptSuccessCount; }
    public long getAttemptSupplierFailureCount() { return attemptSupplierFailureCount; }
    public void setAttemptSupplierFailureCount(long value) { this.attemptSupplierFailureCount = value; }
}
