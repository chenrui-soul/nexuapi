package com.nexusapi.server.modules.supplier.entity;

/** 供应商详情页的渠道和模型资源数量聚合行。 */
public class AdminSupplierResourceSummaryRow {
    /** 供应商关联的全部渠道数量。 */
    private long channelCount;
    /** 当前满足供应商合作状态与渠道业务状态要求的可用渠道数量。 */
    private long availableChannelCount;
    /** 通过渠道模型映射关联的平台模型去重数量。 */
    private long modelCount;
    /** 当前具备至少一条可用映射的平台模型去重数量。 */
    private long activeModelCount;

    public long getChannelCount() { return channelCount; }
    public void setChannelCount(long channelCount) { this.channelCount = channelCount; }
    public long getAvailableChannelCount() { return availableChannelCount; }
    public void setAvailableChannelCount(long availableChannelCount) { this.availableChannelCount = availableChannelCount; }
    public long getModelCount() { return modelCount; }
    public void setModelCount(long modelCount) { this.modelCount = modelCount; }
    public long getActiveModelCount() { return activeModelCount; }
    public void setActiveModelCount(long activeModelCount) { this.activeModelCount = activeModelCount; }
}
