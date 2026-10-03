package com.nexusapi.server.modules.auth.security;

import com.nexusapi.server.common.config.NexusProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthSessionService {
    public static final String SESSION_USER_ID = "NEXUS_USER_ID";

    private final NexusProperties.Auth properties;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public AuthSessionService(
            NexusProperties nexusProperties,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository
    ) {
        this.properties = nexusProperties.auth();
        this.sessionRepository = sessionRepository;
    }

    public long start(HttpServletRequest request, UUID userId, boolean remember) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        Duration timeout = remember ? properties.rememberSessionTtl() : properties.sessionTtl();
        int seconds = Math.toIntExact(timeout.toSeconds());
        session.setMaxInactiveInterval(seconds);
        session.setAttribute(SESSION_USER_ID, userId.toString());
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, userId.toString());
        return seconds;
    }

    public void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** 返回当前用户仍然有效的控制台会话数量，不向客户端暴露会话标识。 */
    public int count(UUID userId) {
        return sessions(userId).size();
    }

    /** 密码重置后撤销该用户全部控制台会话。 */
    public int revokeAll(UUID userId) {
        Map<String, ? extends Session> sessions = sessions(userId);
        sessions.keySet().forEach(sessionRepository::deleteById);
        return sessions.size();
    }

    /** 登录态敏感操作保留当前会话，仅撤销其他设备。 */
    public int revokeOthers(UUID userId, HttpServletRequest request) {
        HttpSession current = request.getSession(false);
        String currentId = current == null ? null : current.getId();
        int revoked = 0;
        for (String sessionId : sessions(userId).keySet()) {
            if (!sessionId.equals(currentId)) {
                sessionRepository.deleteById(sessionId);
                revoked++;
            }
        }
        return revoked;
    }

    private Map<String, ? extends Session> sessions(UUID userId) {
        return sessionRepository.findByIndexNameAndIndexValue(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                userId.toString()
        );
    }
}
