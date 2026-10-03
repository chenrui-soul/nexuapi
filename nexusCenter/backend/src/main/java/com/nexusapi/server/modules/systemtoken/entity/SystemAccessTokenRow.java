package com.nexusapi.server.modules.systemtoken.entity;

import java.time.Instant;
import java.util.UUID;

/** system_access_tokens 表的内部持久化对象，不包含令牌明文。 */
public class SystemAccessTokenRow {
    /** 系统访问令牌主键。 */
    private UUID id;
    /** 令牌所属用户主键。 */
    private UUID userId;
    /** 用户设置的令牌名称。 */
    private String name;
    /** 用于快速识别令牌类型的公开前缀。 */
    private String tokenPrefix;
    /** 列表中展示的令牌末尾脱敏标识。 */
    private String tokenSuffix;
    /** 使用独立 HMAC 密钥计算的令牌摘要，不保存明文。 */
    private byte[] tokenHash;
    /** 令牌摘要所使用的密钥版本。 */
    private int hashVersion;
    /** 只读权限范围的 JSON 序列化内容。 */
    private String scopesJson;
    /** 可访问来源 IP 或 CIDR 列表的 JSON 序列化内容。 */
    private String ipAllowlistJson;
    /** 令牌业务状态。 */
    private String status;
    /** 令牌到期时间，空值表示长期有效。 */
    private Instant expiresAt;
    /** 令牌最近一次通过鉴权的时间。 */
    private Instant lastUsedAt;
    /** 令牌被不可逆撤销的时间。 */
    private Instant revokedAt;
    /** 令牌创建时间。 */
    private Instant createdAt;
    /** 令牌最后更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本号。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTokenPrefix() { return tokenPrefix; }
    public void setTokenPrefix(String tokenPrefix) { this.tokenPrefix = tokenPrefix; }
    public String getTokenSuffix() { return tokenSuffix; }
    public void setTokenSuffix(String tokenSuffix) { this.tokenSuffix = tokenSuffix; }
    public byte[] getTokenHash() { return tokenHash == null ? null : tokenHash.clone(); }
    public void setTokenHash(byte[] tokenHash) { this.tokenHash = tokenHash == null ? null : tokenHash.clone(); }
    public int getHashVersion() { return hashVersion; }
    public void setHashVersion(int hashVersion) { this.hashVersion = hashVersion; }
    public String getScopesJson() { return scopesJson; }
    public void setScopesJson(String scopesJson) { this.scopesJson = scopesJson; }
    public String getIpAllowlistJson() { return ipAllowlistJson; }
    public void setIpAllowlistJson(String ipAllowlistJson) { this.ipAllowlistJson = ipAllowlistJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
