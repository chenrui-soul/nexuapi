package com.nexusapi.server.modules.notification.service;

import com.nexusapi.server.modules.notification.mapper.AdminNotificationMapper;
import org.springframework.stereotype.Service;

/** 健康告警等系统事件写入管理员站内通知的业务入口。 */
@Service
public class AdminNotificationService {
    private final AdminNotificationMapper mapper;

    public AdminNotificationService(AdminNotificationMapper mapper) {
        this.mapper = mapper;
    }

    /** 标题和正文由服务端固定模板生成，调用方不得传入上游响应或异常原文。 */
    public int notifyActiveAdmins(String type, String title, String content, String actionUrl) {
        return mapper.insertForActiveAdmins(type, title, content, actionUrl);
    }
}
