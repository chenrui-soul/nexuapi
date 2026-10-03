package com.nexusapi.server.modules.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.modules.admin.mapper.AdminAuditMapper;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminAuditServiceTest {

    @Test
    void recordFieldNamedCredentialCannotEnterAuditLog() {
        AdminAuditMapper mapper = mock(AdminAuditMapper.class);
        AdminAuditService service = new AdminAuditService(mapper, new ObjectMapper());

        // 使用 record 模拟正常响应 DTO，验证敏感字段检查不会只覆盖 Map 类型。
        assertThatThrownBy(() -> service.record(
                UUID.randomUUID(),
                "admin.channel.create",
                "channel",
                UUID.randomUUID(),
                null,
                new UnsafeAuditResponse("must-not-be-persisted"),
                new ClientRequestMetadata("127.0.0.1", "test-agent-hash", "req_test")
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("敏感凭证字段");

        verifyNoInteractions(mapper);
    }

    @Test
    void recordRejectsSensitiveKeyWithPunctuationSeparators() {
        AdminAuditMapper mapper = mock(AdminAuditMapper.class);
        AdminAuditService service = new AdminAuditService(mapper, new ObjectMapper());

        // 点号、空格等分隔符不能绕过审计敏感字段检测。
        assertThatThrownBy(() -> service.record(
                UUID.randomUUID(),
                "admin.channel.update",
                "channel",
                UUID.randomUUID(),
                null,
                java.util.Map.of("access.token.value", "must-not-be-persisted"),
                new ClientRequestMetadata("127.0.0.1", "test-agent-hash", "req_test")
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("敏感凭证字段");

        verifyNoInteractions(mapper);
    }

    @Test
    void recordAllowsBooleanCredentialActiveStateWithoutAllowingCredentialValue() {
        AdminAuditMapper mapper = mock(AdminAuditMapper.class);
        AdminAuditService service = new AdminAuditService(mapper, new ObjectMapper());

        service.record(
                UUID.randomUUID(),
                "admin.routing_group.configuration.update",
                "routing_group",
                UUID.randomUUID(),
                null,
                java.util.Map.of("credential_active", true),
                new ClientRequestMetadata("127.0.0.1", "test-agent-hash", "req_test")
        );

        verify(mapper).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private record UnsafeAuditResponse(String credential) {
    }
}
