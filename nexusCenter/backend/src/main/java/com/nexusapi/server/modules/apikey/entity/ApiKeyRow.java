package com.nexusapi.server.modules.apikey.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * API 令牌管理和网关鉴权使用的 MyBatis 数据行。
 *
 * <p>本对象不包含完整 API 令牌和令牌摘要；完整 API 令牌仅在创建时展示一次，摘要只在鉴权查询内部使用。</p>
 */
public class ApiKeyRow {
    /** API 令牌记录的全局唯一标识。 */
    private UUID id;
    /** 所属用户标识，所有管理查询必须同时按该字段隔离用户资源。 */
    private UUID userId;
    /** 用户为 API 令牌设置的可读名称。 */
    private String name;
    /** 可公开展示的 Key 前缀，用于识别 Key 版本和类型，不具备鉴权能力。 */
    private String keyPrefix;
    /** 可公开展示的 Key 末尾字符，用于用户区分多个 Key。 */
    private String keySuffix;
    /**
     * API 令牌完整密钥的 AES-GCM 加密密文；仅用于用户本人复制，数据库不保存明文。
     * 历史令牌可能为空，表示该令牌创建于密文存储启用之前，只能重新生成。
     */
    private byte[] encryptedSecret;
    /** Key 状态：active、disabled、expired 或 revoked。 */
    private String status;
    /** 用户创建 Key 时自主选择且运行时唯一绑定的服务分组标识。 */
    private UUID serviceGroupId;
    /** 服务分组名称，只用于控制台脱敏展示，不参与权限判断。 */
    private String serviceGroupName;
    /** 未显式指定分组时使用的默认计费与路由分组标识。 */
    private UUID defaultGroupId;
    /** 允许调用的模型 UUID 列表 JSON；空数组表示不额外限制模型。 */
    private String allowedModelIdsJson;
    /** 允许使用的分组 UUID 列表 JSON；空数组表示不额外限制分组。 */
    private String allowedGroupIdsJson;
    /** 允许来源 IP 或 CIDR 列表 JSON；空数组表示不启用 IP 白名单。 */
    private String ipAllowlistJson;
    /** 每分钟最大请求数 RPM；null 表示不设置 Key 级限制。 */
    private Integer rpmLimit;
    /** 每分钟最大 Token 数 TPM；null 表示不设置 Key 级限制。 */
    private Long tpmLimit;
    /** 该 Key 允许的最大并发请求数；null 表示不设置 Key 级限制。 */
    private Integer concurrencyLimit;
    /** 该 Key 可累计消费的平台额度上限；null 表示不设置 Key 级上限。 */
    private BigDecimal creditLimit;
    /** 该 Key 已完成结算且扣除退款后的累计实扣积分。 */
    private BigDecimal usedCredits;
    /** 该 Key 当前处于 reserved 状态的预冻结积分。 */
    private BigDecimal reservedCredits;
    /** Key 到期时间；null 表示没有预设到期时间。 */
    private Instant expiresAt;
    /** 最近一次成功通过鉴权并使用该 Key 的时间。 */
    private Instant lastUsedAt;
    /** Key 创建时间。 */
    private Instant createdAt;
    /** Key 配置或状态最后更新时间。 */
    private Instant updatedAt;
    /** Key 被永久撤销的时间；未撤销时为 null。 */
    private Instant revokedAt;
    /** 乐观锁版本号，防止并发管理请求相互覆盖。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getKeySuffix() { return keySuffix; }
    public void setKeySuffix(String keySuffix) { this.keySuffix = keySuffix; }
    public byte[] getEncryptedSecret() { return encryptedSecret == null ? null : encryptedSecret.clone(); }
    public void setEncryptedSecret(byte[] value) { this.encryptedSecret = value == null ? null : value.clone(); }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getServiceGroupId() { return serviceGroupId; }
    public void setServiceGroupId(UUID serviceGroupId) { this.serviceGroupId = serviceGroupId; }
    public String getServiceGroupName() { return serviceGroupName; }
    public void setServiceGroupName(String serviceGroupName) { this.serviceGroupName = serviceGroupName; }
    public UUID getDefaultGroupId() { return defaultGroupId; }
    public void setDefaultGroupId(UUID defaultGroupId) { this.defaultGroupId = defaultGroupId; }
    public String getAllowedModelIdsJson() { return allowedModelIdsJson; }
    public void setAllowedModelIdsJson(String allowedModelIdsJson) { this.allowedModelIdsJson = allowedModelIdsJson; }
    public String getAllowedGroupIdsJson() { return allowedGroupIdsJson; }
    public void setAllowedGroupIdsJson(String allowedGroupIdsJson) { this.allowedGroupIdsJson = allowedGroupIdsJson; }
    public String getIpAllowlistJson() { return ipAllowlistJson; }
    public void setIpAllowlistJson(String ipAllowlistJson) { this.ipAllowlistJson = ipAllowlistJson; }
    public Integer getRpmLimit() { return rpmLimit; }
    public void setRpmLimit(Integer rpmLimit) { this.rpmLimit = rpmLimit; }
    public Long getTpmLimit() { return tpmLimit; }
    public void setTpmLimit(Long tpmLimit) { this.tpmLimit = tpmLimit; }
    public Integer getConcurrencyLimit() { return concurrencyLimit; }
    public void setConcurrencyLimit(Integer concurrencyLimit) { this.concurrencyLimit = concurrencyLimit; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = creditLimit; }
    public BigDecimal getUsedCredits() { return usedCredits; }
    public void setUsedCredits(BigDecimal usedCredits) { this.usedCredits = usedCredits; }
    public BigDecimal getReservedCredits() { return reservedCredits; }
    public void setReservedCredits(BigDecimal reservedCredits) { this.reservedCredits = reservedCredits; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
