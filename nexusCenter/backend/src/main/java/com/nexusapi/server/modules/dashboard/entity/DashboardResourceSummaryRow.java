package com.nexusapi.server.modules.dashboard.entity;

/** 管理端运营总览资源数量的数据库聚合行，不受任何列表分页限制。 */
public class DashboardResourceSummaryRow {
    /** 供应商总数。 */
    private long supplierTotal;
    /** 状态为 active 的供应商数量。 */
    private long activeSupplierCount;
    /** 模型总数。 */
    private long modelTotal;
    /** 状态为 active 的可用模型数量。 */
    private long activeModelCount;
    /** 渠道总数。 */
    private long channelTotal;
    /** 状态为 active 的健康业务渠道数量。 */
    private long activeChannelCount;
    /** 状态为 open 的待处理健康告警数量。 */
    private long openAlertCount;

    public long getSupplierTotal() { return supplierTotal; }
    public void setSupplierTotal(long supplierTotal) { this.supplierTotal = supplierTotal; }
    public long getActiveSupplierCount() { return activeSupplierCount; }
    public void setActiveSupplierCount(long activeSupplierCount) { this.activeSupplierCount = activeSupplierCount; }
    public long getModelTotal() { return modelTotal; }
    public void setModelTotal(long modelTotal) { this.modelTotal = modelTotal; }
    public long getActiveModelCount() { return activeModelCount; }
    public void setActiveModelCount(long activeModelCount) { this.activeModelCount = activeModelCount; }
    public long getChannelTotal() { return channelTotal; }
    public void setChannelTotal(long channelTotal) { this.channelTotal = channelTotal; }
    public long getActiveChannelCount() { return activeChannelCount; }
    public void setActiveChannelCount(long activeChannelCount) { this.activeChannelCount = activeChannelCount; }
    public long getOpenAlertCount() { return openAlertCount; }
    public void setOpenAlertCount(long openAlertCount) { this.openAlertCount = openAlertCount; }
}
