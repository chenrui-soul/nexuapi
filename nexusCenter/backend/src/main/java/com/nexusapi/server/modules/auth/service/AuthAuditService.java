package com.nexusapi.server.modules.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthAuditService {
    private final JdbcTemplate jdbcTemplate;

    public AuthAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(UUID actorUserId, String action, String resourceId, ClientRequestMetadata metadata) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    actor_user_id, actor_type, action, resource_type, resource_id,
                    ip_address, user_agent_hash, request_id
                ) VALUES (?, 'user', ?, 'auth', ?, CAST(? AS inet), ?, ?)
                """,
                actorUserId,
                action,
                resourceId,
                metadata.ipAddress(),
                metadata.userAgentHash(),
                metadata.requestId()
        );
    }
}
