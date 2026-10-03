package com.nexusapi.server.modules.billing.scheduler;

import com.nexusapi.server.modules.billing.service.BillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 分用户、短事务回收已到期的可用限时积分，避免一次任务长时间锁住整张钱包表。 */
@Component
public class ExpiringCreditBatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExpiringCreditBatchScheduler.class);
    private static final int USER_BATCH_SIZE = 100;
    private final BillingService billingService;

    public ExpiringCreditBatchScheduler(BillingService billingService) {
        this.billingService = billingService;
    }

    @Scheduled(fixedDelayString = "${nexus.billing.expiring-credit-sweep-delay-ms:60000}")
    public void sweep() {
        for (UUID userId : billingService.findDueBatchUserIds(USER_BATCH_SIZE)) {
            try {
                billingService.expireDueBatches(userId);
            } catch (RuntimeException exception) {
                // 不输出金额、用户信息或异常消息；下一轮继续依靠批次状态和幂等键重试。
                log.error("Expiring credit sweep failed, type={}", exception.getClass().getName());
            }
        }
    }
}
