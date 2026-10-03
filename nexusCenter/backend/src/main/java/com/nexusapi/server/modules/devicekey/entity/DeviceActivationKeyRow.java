package com.nexusapi.server.modules.devicekey.entity;

import java.time.Instant;
import java.util.UUID;

/** 独立设备激活密钥持久化对象；不复用 API Key 或系统访问令牌。 */
public class DeviceActivationKeyRow {
    /** 设备激活密钥记录标识。 */
    private UUID id;
    /** 管理员为密钥设置的可读名称。 */
    private String name;
    /** 使用该密钥激活的应用编码。 */
    private String applicationCode;
    /** 明文密钥前缀，用于列表识别。 */
    private String keyPrefix;
    /** 明文密钥后缀，用于列表识别。 */
    private String keySuffix;
    /** AES-GCM 加密后的完整密钥，仅供管理侧受控解密。 */
    private byte[] encryptedSecret;
    /** 完整密钥的 SHA-256 摘要，用于激活校验。 */
    private byte[] secretHash;
    /** 已绑定设备码的摘要；一枚密钥只能绑定一个设备。 */
    private byte[] deviceCodeHash;
    /** 密钥状态：active、disabled 或 expired。 */
    private String status;
    /** 首次绑定设备的时间。 */
    private Instant activatedAt;
    /** 密钥失效时间；null 表示长期有效。 */
    private Instant expiresAt;
    /** 最近一次设备激活校验时间。 */
    private Instant lastVerifiedAt;
    /** 密钥创建时间。 */
    private Instant createdAt;
    /** 密钥记录最后更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本号，防止并发状态更新覆盖。 */
    private long version;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getApplicationCode(){return applicationCode;} public void setApplicationCode(String v){applicationCode=v;}
    public String getKeyPrefix(){return keyPrefix;} public void setKeyPrefix(String v){keyPrefix=v;}
    public String getKeySuffix(){return keySuffix;} public void setKeySuffix(String v){keySuffix=v;}
    public byte[] getEncryptedSecret(){return encryptedSecret == null ? null : encryptedSecret.clone();} public void setEncryptedSecret(byte[] v){encryptedSecret=v == null ? null : v.clone();}
    public byte[] getSecretHash(){return secretHash == null ? null : secretHash.clone();} public void setSecretHash(byte[] v){secretHash=v == null ? null : v.clone();}
    public byte[] getDeviceCodeHash(){return deviceCodeHash == null ? null : deviceCodeHash.clone();} public void setDeviceCodeHash(byte[] v){deviceCodeHash=v == null ? null : v.clone();}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Instant getActivatedAt(){return activatedAt;} public void setActivatedAt(Instant v){activatedAt=v;}
    public Instant getExpiresAt(){return expiresAt;} public void setExpiresAt(Instant v){expiresAt=v;}
    public Instant getLastVerifiedAt(){return lastVerifiedAt;} public void setLastVerifiedAt(Instant v){lastVerifiedAt=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
    public long getVersion(){return version;} public void setVersion(long v){version=v;}
}
