package com.nexusapi.server.modules.auth.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 登录认证所需的最小用户数据行。
 *
 * <p>该对象只在 Mapper 与认证 Service 之间传递，不作为接口响应，避免密码摘要和邮箱密文
 * 被序列化到客户端。</p>
 */
public class UserAuthRow {
    /** 用户全局唯一标识，也是会话中保存的唯一用户身份信息。 */
    private UUID id;
    /** 用户在控制台展示的名称，不参与登录凭证校验。 */
    private String displayName;
    /** 使用 AES-256-GCM 加密保存的邮箱密文，禁止写入日志或接口响应。 */
    private byte[] emailCiphertext;
    /** Argon2id 密码摘要，只用于服务端密码校验，绝不保存或返回明文密码。 */
    private String passwordHash;
    /** 用户状态：active、pending、suspended、locked 或 deleted。 */
    private String status;
    /** 用户记录创建时间。 */
    private Instant createdAt;
    /** 邮箱完成验证的时间；密码找回成功可补记为已验证。 */
    private Instant emailVerifiedAt;
    /** 最近一次成功登录时间。 */
    private Instant lastLoginAt;
    /** 最近一次设置或修改密码的时间。 */
    private Instant passwordChangedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public byte[] getEmailCiphertext() {
        return emailCiphertext;
    }

    public void setEmailCiphertext(byte[] emailCiphertext) {
        this.emailCiphertext = emailCiphertext;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(Instant emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(Instant passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }
}
