package com.nexusapi.server.modules.health.entity;

import java.util.UUID;

/** 路由分组实时健康聚合结果，不修改管理员维护的分组配置状态。 */
public class GroupHealthStateRow {
    /** 路由分组主键。 */
    private UUID groupId;
    /** 分组业务编码。 */
    private String groupCode;
    /** 分组展示名称。 */
    private String groupName;
    /** 管理员维护的配置状态。 */
    private String configurationStatus;
    /** 自动聚合健康状态：healthy、degraded、unavailable 或 unconfigured。 */
    private String healthStatus;
    /** 已启用且配置关系完整的路由数量。 */
    private int configuredRouteCount;
    /** 当前可以被 Gateway 选择的路由数量。 */
    private int availableRouteCount;

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }
    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getConfigurationStatus() { return configurationStatus; }
    public void setConfigurationStatus(String configurationStatus) { this.configurationStatus = configurationStatus; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }
    public int getConfiguredRouteCount() { return configuredRouteCount; }
    public void setConfiguredRouteCount(int configuredRouteCount) { this.configuredRouteCount = configuredRouteCount; }
    public int getAvailableRouteCount() { return availableRouteCount; }
    public void setAvailableRouteCount(int availableRouteCount) { this.availableRouteCount = availableRouteCount; }
}
