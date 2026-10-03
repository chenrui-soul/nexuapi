package com.nexusapi.server.modules.subscription.scheduler;

import com.nexusapi.server.modules.subscription.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期收口已取消但等待在途请求完成的订阅退款。 */
@Component
public class SubscriptionRefundScheduler {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionRefundScheduler.class);
    private final SubscriptionService subscriptionService;

    public SubscriptionRefundScheduler(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Scheduled(fixedDelayString = "${nexus.subscription.refund-sweep-delay-ms:60000}")
    public void sweep() {
        try {
            subscriptionService.finalizePendingRefunds(100);
        } catch (RuntimeException exception) {
            // 单轮退款扫描失败不能终止调度线程；下一轮按订阅状态和唯一键继续收口。
            log.error("subscription_refund_sweep_failed type={}", exception.getClass().getName());
        }
    }
}
