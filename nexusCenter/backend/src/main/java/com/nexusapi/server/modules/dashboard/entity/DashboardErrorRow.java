package com.nexusapi.server.modules.dashboard.entity;

/** 真实上游尝试错误分类统计数据库行。 */
public class DashboardErrorRow {
    /** 归一化错误分类，不包含上游异常原文。 */
    private String category;
    /** 统计区间内该分类出现次数。 */
    private long occurrenceCount;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public long getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(long occurrenceCount) { this.occurrenceCount = occurrenceCount; }
}
