package com.nexusapi.server.modules.routing.entity;

import java.time.Instant;
import java.util.UUID;

/** 管理端读取特殊服务分组授权用户时使用的数据库行。 */
public class AdminGroupUserGrantRow {
    /** 被授权用户 ID。 */
    private UUID userId;
    /** 用户展示名称。 */
    private String displayName;
    /** 用户邮箱密文，仅在 Service 内解密后生成脱敏邮箱。 */
    private byte[] emailCiphertext;
    /** 用户当前状态。 */
    private String userStatus;
    /** 授权过期时间；为空表示长期有效。 */
    private Instant expiresAt;
    /** 首次授权时间。 */
    private Instant createdAt;
    /** 最近一次授权更新时间。 */
    private Instant updatedAt;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public byte[] getEmailCiphertext() { return emailCiphertext == null ? null : emailCiphertext.clone(); }
    public void setEmailCiphertext(byte[] emailCiphertext) { this.emailCiphertext = emailCiphertext == null ? null : emailCiphertext.clone(); }
    public String getUserStatus() { return userStatus; }
    public void setUserStatus(String userStatus) { this.userStatus = userStatus; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
