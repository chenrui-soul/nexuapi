package com.nexusapi.server.modules.apikey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 将 API 令牌的关键管理操作写入审计日志。
 *
 * <p>调用方只能传入经过白名单裁剪的 before/after 视图，完整 Secret 和摘要不得进入审计数据。</p>
 */
@Service
public class ApiKeyAuditService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ApiKeyAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 记录操作者、资源、变更前后快照和请求来源，便于后续安全追踪。 */
    public void record(
            UUID actorUserId,
            String action,
            UUID resourceId,
            Map<String, Object> beforeData,
            Map<String, Object> afterData,
            ClientRequestMetadata metadata
    ) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs (
                    actor_user_id, actor_type, action, resource_type, resource_id,
                    before_data, after_data, ip_address, user_agent_hash, request_id
                ) VALUES (
                    ?, 'user', ?, 'api_key', ?, CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS inet), ?, ?
                )
                """,
                actorUserId,
                action,
                resourceId.toString(),
                toJson(beforeData),
                toJson(afterData),
                metadata.ipAddress(),
                metadata.userAgentHash(),
                metadata.requestId()
        );
    }

    private String toJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize API key audit data", exception);
        }
    }
}
