package com.nexusapi.server.modules.health.entity;

import java.time.Instant;
import java.util.UUID;

/** 分组健康告警数据库行，计数用于解释重复通知是否已被冷却窗口抑制。 */
public class HealthAlertRow {
    /** 告警自增主键。 */
    private long id;
    /** 关联路由分组主键。 */
    private UUID groupId;
    /** 分组业务编码。 */
    private String groupCode;
    /** 分组展示名称。 */
    private String groupName;
    /** 告警类型。 */
    private String alertType;
    /** 告警状态：open 或 resolved。 */
    private String status;
    /** 告警严重度。 */
    private String severity;
    /** 固定模板生成的告警标题。 */
    private String title;
    /** 固定模板生成的脱敏摘要。 */
    private String summary;
    /** 累计观察到故障的次数。 */
    private int occurrenceCount;
    /** 已发送通知次数。 */
    private int notificationCount;
    /** 已抑制重复通知次数。 */
    private int suppressedCount;
    /** 告警打开时间。 */
    private Instant openedAt;
    /** 最近确认故障时间。 */
    private Instant lastSeenAt;
    /** 最近通知时间。 */
    private Instant lastNotifiedAt;
    /** 告警恢复时间。 */
    private Instant resolvedAt;
    /** 告警最后更新时间。 */
    private Instant updatedAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }
    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public int getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }
    public int getNotificationCount() { return notificationCount; }
    public void setNotificationCount(int notificationCount) { this.notificationCount = notificationCount; }
    public int getSuppressedCount() { return suppressedCount; }
    public void setSuppressedCount(int suppressedCount) { this.suppressedCount = suppressedCount; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getLastNotifiedAt() { return lastNotifiedAt; }
    public void setLastNotifiedAt(Instant lastNotifiedAt) { this.lastNotifiedAt = lastNotifiedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
