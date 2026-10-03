package com.nexusapi.server.modules.billing.service;

import com.nexusapi.server.modules.billing.mapper.PaymentMapper;
import com.nexusapi.server.modules.billing.provider.PaymentProviderRegistry;
import com.nexusapi.server.common.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 主动对账待支付订单，弥补浏览器关闭或网络丢失导致的支付通知缺失。 */
@Service
public class PaymentReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);
    private final PaymentMapper mapper;
    private final PaymentProviderRegistry registry;
    private final PaymentCallbackService callbackService;
    private final PaymentProperties properties;

    public PaymentReconciliationService(PaymentMapper mapper, PaymentProviderRegistry registry, PaymentCallbackService callbackService, PaymentProperties properties) {
        this.mapper = mapper; this.registry = registry; this.callbackService = callbackService; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${nexus.payment.reconciliation-sweep-delay-ms:600000}")
    public void sweep() {
        if (!properties.alipay().enabled()) return;
        for (var order : mapper.findPendingOrders(100)) {
            try {
                var result = registry.get(order.getPaymentProvider()).queryPayment(order.getOrderNo());
                callbackService.processQueriedPayment(order.getPaymentProvider(), order, result);
            } catch (RuntimeException exception) {
                log.error("payment_reconciliation_failed provider={} type={}", order.getPaymentProvider(), exception.getClass().getName());
            }
        }
    }
}
