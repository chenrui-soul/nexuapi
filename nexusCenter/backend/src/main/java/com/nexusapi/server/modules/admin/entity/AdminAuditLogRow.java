package com.nexusapi.server.modules.admin.entity;

import java.time.Instant;
import java.util.UUID;

/** 管理端审计日志查询使用的数据行，快照 JSON 已在写入阶段完成敏感字段拦截。 */
public class AdminAuditLogRow {
    /** 审计记录自增标识。 */
    private long id;
    /** 操作用户标识；系统任务执行时可为空。 */
    private UUID actorUserId;
    /** 操作用户展示名称；用户已不存在时可为空。 */
    private String actorDisplayName;
    /** 操作主体类型，例如 user、admin 或 system。 */
    private String actorType;
    /** 稳定的审计动作代码。 */
    private String action;
    /** 被操作资源类型。 */
    private String resourceType;
    /** 被操作资源业务标识。 */
    private String resourceId;
    /** 操作前脱敏 JSON 快照文本。 */
    private String beforeJson;
    /** 操作后脱敏 JSON 快照文本。 */
    private String afterJson;
    /** 操作来源 IP 地址。 */
    private String ipAddress;
    /** 操作来源 User-Agent 不可逆摘要。 */
    private String userAgentHash;
    /** 关联 HTTP 请求标识。 */
    private String requestId;
    /** 审计记录创建时间。 */
    private Instant createdAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getActorDisplayName() { return actorDisplayName; }
    public void setActorDisplayName(String actorDisplayName) { this.actorDisplayName = actorDisplayName; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }
    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgentHash() { return userAgentHash; }
    public void setUserAgentHash(String userAgentHash) { this.userAgentHash = userAgentHash; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
