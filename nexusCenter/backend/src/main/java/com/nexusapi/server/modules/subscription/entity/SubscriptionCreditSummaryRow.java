package com.nexusapi.server.modules.subscription.entity;

import java.math.BigDecimal;

/** 当前订阅积分批次的聚合真值。 */
public class SubscriptionCreditSummaryRow {
    /** 累计发放积分。 */
    private BigDecimal grantedCredits;
    /** 已结算且未退款积分。 */
    private BigDecimal usedCredits;
    /** 在途冻结积分。 */
    private BigDecimal frozenCredits;
    /** 当前可用积分。 */
    private BigDecimal remainingCredits;
    /** 已到期失效积分。 */
    private BigDecimal expiredCredits;

    public BigDecimal getGrantedCredits() { return grantedCredits; }
    public void setGrantedCredits(BigDecimal grantedCredits) { this.grantedCredits = grantedCredits; }
    public BigDecimal getUsedCredits() { return usedCredits; }
    public void setUsedCredits(BigDecimal usedCredits) { this.usedCredits = usedCredits; }
    public BigDecimal getFrozenCredits() { return frozenCredits; }
    public void setFrozenCredits(BigDecimal frozenCredits) { this.frozenCredits = frozenCredits; }
    public BigDecimal getRemainingCredits() { return remainingCredits; }
    public void setRemainingCredits(BigDecimal remainingCredits) { this.remainingCredits = remainingCredits; }
    public BigDecimal getExpiredCredits() { return expiredCredits; }
    public void setExpiredCredits(BigDecimal expiredCredits) { this.expiredCredits = expiredCredits; }
}

