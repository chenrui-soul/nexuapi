package com.nexusapi.server.modules.health.service;

import com.nexusapi.server.common.config.HealthProperties;
import com.nexusapi.server.modules.health.entity.GroupHealthStateRow;
import com.nexusapi.server.modules.health.entity.HealthAlertRow;
import com.nexusapi.server.modules.health.mapper.GroupHealthAlertMapper;
import com.nexusapi.server.modules.notification.service.AdminNotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 分组健康告警状态机。
 *
 * <p>每个分组在独立新事务中聚合，先使用 advisory lock 串行化，再决定打开、抑制、提醒或恢复，
 * 防止多实例同时处理同一渠道事件时生成重复 open 告警。</p>
 */
@Service
public class GroupHealthAlertService {
    private static final String ALERT_TYPE = "health_alert";
    private static final String ACTION_URL = "/admin/health";

    private final GroupHealthAlertMapper mapper;
    private final AdminNotificationService notificationService;
    private final HealthProperties properties;

    public GroupHealthAlertService(
            GroupHealthAlertMapper mapper,
            AdminNotificationService notificationService,
            HealthProperties properties
    ) {
        this.mapper = mapper;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    /** 渠道健康变化后查找所有受影响分组，并在一个新事务中完成串行状态转换。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleChannelChanged(UUID channelId) {
        if (channelId == null) {
            return;
        }
        List<UUID> groupIds = mapper.findAffectedGroupIds(channelId);
        for (UUID groupId : groupIds) {
            evaluateGroup(groupId);
        }
    }

    private void evaluateGroup(UUID groupId) {
        mapper.lockGroup(groupId);
        GroupHealthStateRow state = mapper.findGroupHealthState(groupId);
        if (state == null) {
            return;
        }

        String previousStatus = mapper.findLatestGroupHealthStatus(groupId);
        if (!state.getHealthStatus().equals(previousStatus)) {
            mapper.insertGroupHealthCheck(groupId, state.getHealthStatus(), historySummary(state));
        }

        HealthAlertRow openAlert = mapper.findOpenAlertForUpdate(groupId);
        if ("unavailable".equals(state.getHealthStatus())) {
            handleUnavailable(state, openAlert);
        } else if (openAlert != null && mapper.resolveAlert(openAlert.getId()) == 1) {
            notificationService.notifyActiveAdmins(
                    ALERT_TYPE,
                    "路由分组已恢复：" + state.getGroupName(),
                    "分组 " + state.getGroupCode() + " 已恢复可路由，当前可用 "
                            + state.getAvailableRouteCount() + " / " + state.getConfiguredRouteCount() + " 条。",
                    ACTION_URL
            );
        }
    }

    private void handleUnavailable(GroupHealthStateRow state, HealthAlertRow openAlert) {
        String title = "路由分组不可用：" + state.getGroupName();
        String summary = "分组 " + state.getGroupCode() + " 当前无可用路由，已配置 "
                + state.getConfiguredRouteCount() + " 条。";
        if (openAlert == null) {
            mapper.insertOpenAlert(state.getGroupId(), title, summary);
            HealthAlertRow created = mapper.findOpenAlertForUpdate(state.getGroupId());
            int recipients = notificationService.notifyActiveAdmins(ALERT_TYPE, title, summary, ACTION_URL);
            if (created != null && recipients > 0) {
                // 新告警插入时 occurrence_count 已是 1，因此这里只增加通知次数。
                mapper.markNotified(created.getId(), 0);
            }
            return;
        }

        Instant cooldownEndsAt = openAlert.getLastNotifiedAt() == null
                ? Instant.EPOCH
                : openAlert.getLastNotifiedAt().plus(properties.alertCooldown());
        if (Instant.now().isBefore(cooldownEndsAt)) {
            mapper.markSuppressed(openAlert.getId());
            return;
        }

        int recipients = notificationService.notifyActiveAdmins(ALERT_TYPE, title, summary, ACTION_URL);
        if (recipients > 0) {
            mapper.markNotified(openAlert.getId(), 1);
        } else {
            // 当前没有有效管理员时仍记录观察次数，但不伪造“已通知”。
            mapper.markSuppressed(openAlert.getId());
        }
    }

    /** 健康历史只写固定模板与计数，不包含渠道地址、凭证、异常原文或上游响应正文。 */
    private String historySummary(GroupHealthStateRow state) {
        return "configured=" + state.getConfiguredRouteCount()
                + ",available=" + state.getAvailableRouteCount();
    }
}
