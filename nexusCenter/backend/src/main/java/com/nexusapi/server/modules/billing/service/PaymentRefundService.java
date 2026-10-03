package com.nexusapi.server.modules.billing.service;

import com.nexusapi.server.modules.billing.entity.PendingPaymentRefundRow;
import com.nexusapi.server.modules.billing.mapper.PaymentMapper;
import com.nexusapi.server.modules.billing.provider.PaymentProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式渠道退款执行和补偿；渠道网络调用在事务外执行，成功后短事务收口本地状态。 */
@Service
public class PaymentRefundService {
    private static final Logger log = LoggerFactory.getLogger(PaymentRefundService.class);
    private final PaymentMapper mapper;
    private final PaymentProviderRegistry registry;
    private final TransactionTemplate transactionTemplate;

    public PaymentRefundService(PaymentMapper mapper, PaymentProviderRegistry registry, PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.registry = registry; this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int processPending(int limit) {
        int completed = 0;
        for (PendingPaymentRefundRow refund : mapper.findPendingRefunds(Math.max(1, Math.min(limit, 100)))) {
            try {
                var provider = registry.get(refund.provider());
                var result = provider.requestRefund(refund.providerOrderId(), refund.orderNo(), refund.amount(), "用户取消订阅", refund.outRequestNo());
                // 网络超时或渠道返回“处理中”时，先主动查询同一退款请求号，避免重复发起退款。
                if (!result.succeeded()) {
                    var queried = provider.queryRefund(refund.providerOrderId(), refund.orderNo(), refund.outRequestNo());
                    if (queried.succeeded()) result = queried;
                }
                if (!result.succeeded()) continue;
                String providerRefundId = result.providerRefundId();
                Boolean marked = transactionTemplate.execute(status -> markSucceeded(refund, providerRefundId));
                if (Boolean.TRUE.equals(marked)) completed++;
            } catch (RuntimeException exception) {
                log.error("payment_refund_failed provider={} type={}", refund.provider(), exception.getClass().getName());
            }
        }
        return completed;
    }

    private boolean markSucceeded(PendingPaymentRefundRow refund, String providerRefundId) {
        if (mapper.markRefundSucceeded(refund.refundId(), providerRefundId) == 0) return false;
        mapper.markSubscriptionRefunded(refund.subscriptionId());
        mapper.markOrderRefunded(refund.orderId());
        return true;
    }
}
