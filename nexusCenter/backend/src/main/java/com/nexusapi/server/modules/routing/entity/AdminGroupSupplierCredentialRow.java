package com.nexusapi.server.modules.routing.entity;

import java.time.Instant;
import java.util.UUID;

/** 服务分组与供应商关系及独立凭证的脱敏数据行。 */
public class AdminGroupSupplierCredentialRow {
    /** 关系主键。 */
    private UUID relationId;
    /** 服务分组标识。 */
    private UUID groupId;
    /** 供应商标识。 */
    private UUID supplierId;
    /** 供应商名称，仅用于管理端显示。 */
    private String supplierName;
    /** 分组内供应商优先级。 */
    private int priority;
    /** 分组内供应商权重。 */
    private int weight;
    /** 关系状态。 */
    private String relationStatus;
    /** 凭证记录主键；为空表示尚未配置分组独立凭证。 */
    private UUID credentialId;
    /** AES-GCM 密文，只允许 Service 写入和 Gateway 读取。 */
    private byte[] encryptedCredential;
    /** 加密主密钥版本。 */
    private int credentialKeyVersion;
    /** 不可逆短指纹。 */
    private String credentialFingerprint;
    /** 凭证状态。 */
    private String credentialStatus;
    /** 凭证最后轮换时间。 */
    private Instant credentialUpdatedAt;
    /** 关系乐观锁版本。 */
    private long relationVersion;
    /** 凭证乐观锁版本。 */
    private long credentialVersion;

    public UUID getRelationId() { return relationId; }
    public void setRelationId(UUID relationId) { this.relationId = relationId; }
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }
    public UUID getSupplierId() { return supplierId; }
    public void setSupplierId(UUID supplierId) { this.supplierId = supplierId; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public String getRelationStatus() { return relationStatus; }
    public void setRelationStatus(String relationStatus) { this.relationStatus = relationStatus; }
    public UUID getCredentialId() { return credentialId; }
    public void setCredentialId(UUID credentialId) { this.credentialId = credentialId; }
    public byte[] getEncryptedCredential() { return encryptedCredential == null ? null : encryptedCredential.clone(); }
    public void setEncryptedCredential(byte[] encryptedCredential) {
        this.encryptedCredential = encryptedCredential == null ? null : encryptedCredential.clone();
    }
    public int getCredentialKeyVersion() { return credentialKeyVersion; }
    public void setCredentialKeyVersion(int credentialKeyVersion) { this.credentialKeyVersion = credentialKeyVersion; }
    public String getCredentialFingerprint() { return credentialFingerprint; }
    public void setCredentialFingerprint(String credentialFingerprint) { this.credentialFingerprint = credentialFingerprint; }
    public String getCredentialStatus() { return credentialStatus; }
    public void setCredentialStatus(String credentialStatus) { this.credentialStatus = credentialStatus; }
    public Instant getCredentialUpdatedAt() { return credentialUpdatedAt; }
    public void setCredentialUpdatedAt(Instant credentialUpdatedAt) { this.credentialUpdatedAt = credentialUpdatedAt; }
    public long getRelationVersion() { return relationVersion; }
    public void setRelationVersion(long relationVersion) { this.relationVersion = relationVersion; }
    public long getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(long credentialVersion) { this.credentialVersion = credentialVersion; }
}
