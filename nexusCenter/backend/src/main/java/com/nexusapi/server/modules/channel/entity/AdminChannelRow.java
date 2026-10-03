package com.nexusapi.server.modules.channel.entity;

import java.time.Instant;
import java.util.UUID;

/** MyBatis 渠道配置行，encryptedCredential 永不进入 HTTP 响应。 */
public class AdminChannelRow {
    /** 上游渠道全局唯一标识。 */
    private UUID id;
    /** 实际提供该渠道并负责商业结算的供应商标识。 */
    private UUID supplierId;
    /** 关联查询得到的供应商业务编码，不参与渠道表写入。 */
    private String supplierCode;
    /** 关联查询得到的供应商展示名称，不参与渠道表写入。 */
    private String supplierName;
    /** 管理端显示且全局唯一的渠道名称。 */
    private String name;
    /** 渠道协议或提供商类型，例如 openai、azure_openai。 */
    private String providerType;
    /** 平台固定的执行入口编码，由管理员从注册能力目录中选择。 */
    private String operationCode;
    /** 供应商接口能力类型：文本、图像、视频、音频、向量或多模态。 */
    private String endpointType;
    /** 调用该上游接口使用的 HTTP 请求方法，目前只允许 GET 或 POST。 */
    private String requestMethod;
    /** 上游接口地址，通常应包含具体接口路径，不得包含密钥或其他凭证参数。 */
    private String baseUrl;
    /** 同一上游主机下的受控相对健康探测路径，默认 /models。 */
    private String healthProbePath;
    /** 使用独立 AAD 域进行 AES-256-GCM 加密的渠道凭证密文，禁止回显或记录日志。 */
    private byte[] encryptedCredential;
    /** 加密渠道凭证所使用的密钥版本，用于安全轮换和向后解密。 */
    private int credentialKeyVersion;
    /** 凭证不可逆短指纹，仅用于管理员确认轮换结果，不具备鉴权能力。 */
    private String credentialFingerprint;
    /** 最近一次创建或轮换渠道凭证的时间。 */
    private Instant credentialUpdatedAt;
    /** 查询结果中计算出的凭证是否已配置标记，不直接持久化。 */
    private boolean credentialConfigured;
    /** 访问该渠道时使用的代理地址；不使用代理时为 null。 */
    private String proxyUrl;
    /** 渠道状态：active、disabled、degraded；circuit_open 仅用于历史兼容。 */
    private String status;
    /** 单次上游请求超时时间，单位毫秒。 */
    private int timeoutMs;
    /** 渠道允许的最大并发请求数；null 表示不设置渠道级限制。 */
    private Integer concurrencyLimit;
    /** 渠道路由优先级，数值越小越优先。 */
    private int priority;
    /** 同优先级渠道参与加权随机路由时的权重，必须大于零。 */
    private int weight;
    /** 连续上游失败观测次数，不参与路由阻断。 */
    private int consecutiveFailures;
    /** 历史熔断截止时间兼容值，当前版本不参与 Gateway 选路。 */
    private Instant circuitOpenUntil;
    /** 最近一次上游错误的脱敏摘要，禁止包含请求正文或凭证。 */
    private String lastErrorSummary;
    /** 渠道配置创建时间。 */
    private Instant createdAt;
    /** 渠道配置最后更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本号，防止并发配置或凭证轮换相互覆盖。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSupplierId() { return supplierId; }
    public void setSupplierId(UUID supplierId) { this.supplierId = supplierId; }
    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getOperationCode() { return operationCode; }
    public void setOperationCode(String operationCode) { this.operationCode = operationCode; }
    public String getEndpointType() { return endpointType; }
    public void setEndpointType(String endpointType) { this.endpointType = endpointType; }
    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getHealthProbePath() { return healthProbePath; }
    public void setHealthProbePath(String healthProbePath) { this.healthProbePath = healthProbePath; }
    public byte[] getEncryptedCredential() { return encryptedCredential == null ? null : encryptedCredential.clone(); }
    public void setEncryptedCredential(byte[] encryptedCredential) { this.encryptedCredential = encryptedCredential == null ? null : encryptedCredential.clone(); }
    public int getCredentialKeyVersion() { return credentialKeyVersion; }
    public void setCredentialKeyVersion(int credentialKeyVersion) { this.credentialKeyVersion = credentialKeyVersion; }
    public String getCredentialFingerprint() { return credentialFingerprint; }
    public void setCredentialFingerprint(String credentialFingerprint) { this.credentialFingerprint = credentialFingerprint; }
    public Instant getCredentialUpdatedAt() { return credentialUpdatedAt; }
    public void setCredentialUpdatedAt(Instant credentialUpdatedAt) { this.credentialUpdatedAt = credentialUpdatedAt; }
    public boolean isCredentialConfigured() { return credentialConfigured; }
    public void setCredentialConfigured(boolean credentialConfigured) { this.credentialConfigured = credentialConfigured; }
    public String getProxyUrl() { return proxyUrl; }
    public void setProxyUrl(String proxyUrl) { this.proxyUrl = proxyUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public Integer getConcurrencyLimit() { return concurrencyLimit; }
    public void setConcurrencyLimit(Integer concurrencyLimit) { this.concurrencyLimit = concurrencyLimit; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Instant getCircuitOpenUntil() { return circuitOpenUntil; }
    public void setCircuitOpenUntil(Instant circuitOpenUntil) { this.circuitOpenUntil = circuitOpenUntil; }
    public String getLastErrorSummary() { return lastErrorSummary; }
    public void setLastErrorSummary(String lastErrorSummary) { this.lastErrorSummary = lastErrorSummary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
