package com.nexusapi.server.modules.health.entity;

import java.util.UUID;

/** 定时探测所需的最小渠道快照；凭证密文不得进入日志、响应或健康检查明细。 */
public class ChannelHealthTarget {
    /** 被探测渠道主键。 */
    private UUID channelId;
    /** 渠道所属商业供应商主键，用于健康聚合。 */
    private UUID supplierId;
    /** 当前业务状态：active、degraded 或 disabled。 */
    private String status;
    /** OpenAI 兼容 API 根地址。 */
    private String baseUrl;
    /** 同一上游主机下的受控相对健康探测路径。 */
    private String healthProbePath;
    /** AES-GCM 渠道凭证密文，只在发起探测前短暂解密。 */
    private byte[] encryptedCredential;
    /** 渠道凭证密钥版本。 */
    private int credentialKeyVersion;
    /** 渠道业务请求超时毫秒数，探测会再受全局较小上限约束。 */
    private int timeoutMs;

    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public UUID getSupplierId() { return supplierId; }
    public void setSupplierId(UUID supplierId) { this.supplierId = supplierId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getHealthProbePath() { return healthProbePath; }
    public void setHealthProbePath(String healthProbePath) { this.healthProbePath = healthProbePath; }
    public byte[] getEncryptedCredential() { return encryptedCredential == null ? null : encryptedCredential.clone(); }
    public void setEncryptedCredential(byte[] encryptedCredential) {
        this.encryptedCredential = encryptedCredential == null ? null : encryptedCredential.clone();
    }
    public int getCredentialKeyVersion() { return credentialKeyVersion; }
    public void setCredentialKeyVersion(int credentialKeyVersion) { this.credentialKeyVersion = credentialKeyVersion; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
