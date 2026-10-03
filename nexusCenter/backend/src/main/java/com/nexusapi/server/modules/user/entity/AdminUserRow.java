package com.nexusapi.server.modules.user.entity;

import java.time.Instant;
import java.util.UUID;

/** 管理员用户列表使用的内部数据行；邮箱密文只允许在 Service 内解密并脱敏。 */
public class AdminUserRow {
    /** 用户全局唯一标识。 */
    private UUID id;
    /** 用户在控制台展示的名称。 */
    private String displayName;
    /** AES-256-GCM 邮箱密文，禁止直接进入接口响应或日志。 */
    private byte[] emailCiphertext;
    /** 用户状态：active、pending、suspended 或 locked。 */
    private String status;
    /** 邮箱验证完成时间；为空表示未验证。 */
    private Instant emailVerifiedAt;
    /** 最近一次成功登录时间。 */
    private Instant lastLoginAt;
    /** 用户记录创建时间。 */
    private Instant createdAt;
    /** 用户记录最后更新时间。 */
    private Instant updatedAt;
    /** 用户管理配置乐观锁版本。 */
    private long version;
    /** 按字母排序、逗号分隔的角色代码，仅用于 Mapper 到 Service 的内部传输。 */
    private String rolesCsv;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public byte[] getEmailCiphertext() { return emailCiphertext == null ? null : emailCiphertext.clone(); }
    public void setEmailCiphertext(byte[] emailCiphertext) { this.emailCiphertext = emailCiphertext == null ? null : emailCiphertext.clone(); }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(Instant emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public String getRolesCsv() { return rolesCsv; }
    public void setRolesCsv(String rolesCsv) { this.rolesCsv = rolesCsv; }
}
