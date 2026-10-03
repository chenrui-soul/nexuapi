package com.nexusapi.server.modules.subscription.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** plans 表的订阅目录持久化对象。 */
public class PlanRow {
    /** 套餐主键。 */
    private UUID id;
    /** 套餐唯一业务编码。 */
    private String code;
    /** 套餐展示名称。 */
    private String name;
    /** 套餐权益与适用场景说明。 */
    private String description;
    /** 计费周期，例如 monthly 或 yearly。 */
    private String billingCycle;
    /** 当前计费周期的销售价格。 */
    private BigDecimal price;
    /** 每个周期随套餐发放的限时积分。 */
    private BigDecimal includedCredits;
    /** 套餐允许的最大并发数，空值表示不额外限制。 */
    private Integer concurrencyLimit;
    /** 套餐扩展权益的 JSON 序列化内容。 */
    private String entitlementsJson;
    /** 套餐业务状态。 */
    private String status;
    /** 前端套餐列表的展示顺序。 */
    private int displayOrder;
    /** 是否在用户侧标记为推荐套餐。 */
    private boolean featured;
    /** 乐观锁版本号。 */
    private long version;
    /** 套餐创建时间。 */
    private Instant createdAt;
    /** 套餐最后更新时间。 */
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getIncludedCredits() { return includedCredits; }
    public void setIncludedCredits(BigDecimal includedCredits) { this.includedCredits = includedCredits; }
    public Integer getConcurrencyLimit() { return concurrencyLimit; }
    public void setConcurrencyLimit(Integer concurrencyLimit) { this.concurrencyLimit = concurrencyLimit; }
    public String getEntitlementsJson() { return entitlementsJson; }
    public void setEntitlementsJson(String entitlementsJson) { this.entitlementsJson = entitlementsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
    public boolean isFeatured() { return featured; }
    public void setFeatured(boolean featured) { this.featured = featured; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
