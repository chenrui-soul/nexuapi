package com.nexusapi.server.modules.billing.enums;

/** 只允许明确的资金入账来源，防止调用方伪造账本类型。 */
public enum CreditType {
    RECHARGE("recharge"),
    GRANT("grant"),
    ADJUSTMENT("adjustment");

    private final String value;

    CreditType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

