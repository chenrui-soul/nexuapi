package com.nexusapi.server.modules.routing.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Gateway 运行时使用的计费与路由分组只读快照。 */
public class RuntimeGroupRow {
    /** 路由分组主键。 */
    private UUID id;
    /** 供扩展请求头选择分组的稳定编码。 */
    private String code;
    /** 分组可见范围：all、assigned 或 internal。 */
    private String audience;
    /** 用户实际售价倍率，参与预冻结和最终结算。 */
    private BigDecimal priceMultiplier;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public BigDecimal getPriceMultiplier() { return priceMultiplier; }
    public void setPriceMultiplier(BigDecimal priceMultiplier) { this.priceMultiplier = priceMultiplier; }
}
