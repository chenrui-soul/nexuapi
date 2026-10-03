package com.nexusapi.server.modules.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.config.PaymentProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.billing.provider.AlipayPaymentProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlipayPaymentProviderTest {
    @Test
    void createsSignedPagePaymentWithoutExposingPrivateKey() throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA"); pair.initialize(2048); var keys = pair.generateKeyPair();
        var alipay = new PaymentProperties.Alipay(true, "app-test", b64(keys.getPrivate().getEncoded()),
                b64(keys.getPublic().getEncoded()), "seller-test", "https://example.test/gateway.do", "https://example.test/notify", "", "RSA2");
        var provider = new AlipayPaymentProvider(new PaymentProperties(false, new BigDecimal("100"), alipay), new ObjectMapper());
        var result = provider.createPayment("R123", new BigDecimal("10.00"), "充值", "CNY");
        assertThat(result.payUrl()).contains("sign=").doesNotContain(alipay.privateKey());
    }

    @Test
    void rejectsUnsignedCallback() throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA"); pair.initialize(2048); var keys = pair.generateKeyPair();
        var alipay = new PaymentProperties.Alipay(true, "app-test", b64(keys.getPrivate().getEncoded()), b64(keys.getPublic().getEncoded()), "seller-test", "https://example.test", "https://example.test/notify", "", "RSA2");
        var provider = new AlipayPaymentProvider(new PaymentProperties(false, new BigDecimal("100"), alipay), new ObjectMapper());
        assertThatThrownBy(() -> provider.verifyPaymentCallback(Map.of("app_id", "app-test", "out_trade_no", "R1", "trade_no", "T1", "trade_status", "TRADE_SUCCESS", "total_amount", "10.00")))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_SIGNATURE_INVALID);
    }

    private static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
}
