package com.nexusapi.server.modules.billing.scheduler;

import com.nexusapi.server.modules.billing.service.PaymentRefundService;
import com.nexusapi.server.common.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时补偿正式渠道退款；支付宝退款同步成功或查询成功后再收口本地状态。 */
@Component
public class PaymentRefundScheduler {
    private static final Logger log = LoggerFactory.getLogger(PaymentRefundScheduler.class);
    private final PaymentRefundService service;
    private final PaymentProperties properties;
    public PaymentRefundScheduler(PaymentRefundService service, PaymentProperties properties) { this.service = service; this.properties = properties; }
    @Scheduled(fixedDelayString = "${nexus.payment.refund-sweep-delay-ms:60000}")
    public void sweep() {
        if (!properties.alipay().enabled()) return;
        try { service.processPending(50); }
        catch (RuntimeException exception) { log.error("payment_refund_sweep_failed type={}", exception.getClass().getName()); }
    }
}
