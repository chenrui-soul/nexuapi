package com.nexusapi.server.modules.health.event;

import com.nexusapi.server.modules.health.service.GroupHealthAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 渠道事务提交后再运行分组告警，确保通知故障不会反向回滚健康观测结果。 */
@Component
public class ChannelHealthChangedEventListener {
    private static final Logger log = LoggerFactory.getLogger(ChannelHealthChangedEventListener.class);
    private final GroupHealthAlertService alertService;

    public ChannelHealthChangedEventListener(GroupHealthAlertService alertService) {
        this.alertService = alertService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChannelHealthChanged(ChannelHealthChangedEvent event) {
        try {
            alertService.handleChannelChanged(event.channelId());
        } catch (RuntimeException exception) {
            // 只记录渠道标识与异常类型，不记录异常消息，避免第三方异常文本携带凭证或响应正文。
            log.error("Group health alert processing failed for channel {}, type={}",
                    event.channelId(), exception.getClass().getSimpleName());
        }
    }
}
