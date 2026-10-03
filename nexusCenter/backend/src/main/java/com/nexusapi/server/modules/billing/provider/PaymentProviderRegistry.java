package com.nexusapi.server.modules.billing.provider;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 渠道注册表；新增支付渠道只需增加 Provider，不修改订单业务规则。 */
@Component
public class PaymentProviderRegistry {
    private final List<PaymentProvider> providers;

    public PaymentProviderRegistry(List<PaymentProvider> providers) { this.providers = providers; }

    public PaymentProvider get(String code) {
        return providers.stream().filter(provider -> provider.code().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED, "支付渠道不可用", null));
    }
}
