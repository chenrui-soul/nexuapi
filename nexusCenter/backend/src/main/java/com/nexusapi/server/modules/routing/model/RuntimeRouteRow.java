package com.nexusapi.server.modules.routing.model;

import java.util.UUID;

/**
 * 一条已经通过数据库状态过滤的上游候选路由。
 *
 * <p>密文只在 Gateway 调用上游前短暂解密；该对象不得进入日志、响应或长期缓存。</p>
 */
public class RuntimeRouteRow {
    /** 分组供应商关系主键，用于重试诊断；不再对应可人工维护的路由规则。 */
    private UUID routeId;
    /** 实际商业供应商主键，用于停用过滤、成本和稳定性统计。 */
    private UUID supplierId;
    /** 供应商三位大写结算币种代码。 */
    private String supplierCostCurrency;
    /** 上游渠道主键，用于渠道并发限流和调用日志。 */
    private UUID channelId;
    /** 渠道展示名称，只用于内部诊断，不返回凭证。 */
    private String channelName;
    /** OpenAI 兼容提供方类型。 */
    private String providerType;
    /** 上游 API 根地址。 */
    private String baseUrl;
    /** AES-GCM 分组供应商凭证密文。 */
    private byte[] encryptedCredential;
    /** 分组供应商凭证密钥版本。 */
    private int credentialKeyVersion;
    /** 是否正在使用分组独立凭证，用于避免单个分组 Key 限流或失效污染整条渠道健康度。 */
    private boolean groupCredentialOverride;
    /** 可选代理地址；当前 P0 只保留配置，不在共享 WebClient 中动态切换。 */
    private String proxyUrl;
    /** 当前渠道请求超时毫秒数。 */
    private int timeoutMs;
    /** 渠道并发上限；空值表示不额外限制。 */
    private Integer channelConcurrencyLimit;
    /** 实际发给上游的模型名称。 */
    private String upstreamModel;
    /** 模型维护配置的协议适配器注册键。 */
    private String adapterKey;
    /** 渠道模型安全配置 JSON，不允许包含凭证字段。 */
    private String configJson;
    /** 供应商普通输入 Token 成本单价快照。 */
    private java.math.BigDecimal supplierInputPrice;
    /** 供应商缓存输入 Token 成本单价快照。 */
    private java.math.BigDecimal supplierCachedInputPrice;
    /** 供应商输出 Token 成本单价快照。 */
    private java.math.BigDecimal supplierOutputPrice;
    /** 渠道、映射和分组路由优先级之和，数值越小越优先。 */
    private long effectivePriority;
    /** 三层权重乘积，用于同优先级候选的加权随机。 */
    private long effectiveWeight;
    /** 当前路由是否允许在首个响应输出前重试。 */
    private boolean retryable;

    public UUID getRouteId() { return routeId; }
    public void setRouteId(UUID routeId) { this.routeId = routeId; }
    public UUID getSupplierId() { return supplierId; }
    public void setSupplierId(UUID supplierId) { this.supplierId = supplierId; }
    public String getSupplierCostCurrency() { return supplierCostCurrency; }
    public void setSupplierCostCurrency(String supplierCostCurrency) { this.supplierCostCurrency = supplierCostCurrency; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public byte[] getEncryptedCredential() { return encryptedCredential == null ? null : encryptedCredential.clone(); }
    public void setEncryptedCredential(byte[] encryptedCredential) {
        this.encryptedCredential = encryptedCredential == null ? null : encryptedCredential.clone();
    }
    public int getCredentialKeyVersion() { return credentialKeyVersion; }
    public void setCredentialKeyVersion(int credentialKeyVersion) { this.credentialKeyVersion = credentialKeyVersion; }
    public boolean isGroupCredentialOverride() { return groupCredentialOverride; }
    public void setGroupCredentialOverride(boolean groupCredentialOverride) { this.groupCredentialOverride = groupCredentialOverride; }
    public String getProxyUrl() { return proxyUrl; }
    public void setProxyUrl(String proxyUrl) { this.proxyUrl = proxyUrl; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public Integer getChannelConcurrencyLimit() { return channelConcurrencyLimit; }
    public void setChannelConcurrencyLimit(Integer channelConcurrencyLimit) { this.channelConcurrencyLimit = channelConcurrencyLimit; }
    public String getUpstreamModel() { return upstreamModel; }
    public void setUpstreamModel(String upstreamModel) { this.upstreamModel = upstreamModel; }
    public String getAdapterKey() { return adapterKey; }
    public void setAdapterKey(String adapterKey) { this.adapterKey = adapterKey; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public java.math.BigDecimal getSupplierInputPrice() { return supplierInputPrice; }
    public void setSupplierInputPrice(java.math.BigDecimal supplierInputPrice) { this.supplierInputPrice = supplierInputPrice; }
    public java.math.BigDecimal getSupplierCachedInputPrice() { return supplierCachedInputPrice; }
    public void setSupplierCachedInputPrice(java.math.BigDecimal supplierCachedInputPrice) { this.supplierCachedInputPrice = supplierCachedInputPrice; }
    public java.math.BigDecimal getSupplierOutputPrice() { return supplierOutputPrice; }
    public void setSupplierOutputPrice(java.math.BigDecimal supplierOutputPrice) { this.supplierOutputPrice = supplierOutputPrice; }
    public long getEffectivePriority() { return effectivePriority; }
    public void setEffectivePriority(long effectivePriority) { this.effectivePriority = effectivePriority; }
    public long getEffectiveWeight() { return effectiveWeight; }
    public void setEffectiveWeight(long effectiveWeight) { this.effectiveWeight = effectiveWeight; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
}
