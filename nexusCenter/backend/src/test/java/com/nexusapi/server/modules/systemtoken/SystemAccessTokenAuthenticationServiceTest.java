package com.nexusapi.server.modules.systemtoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.systemtoken.mapper.SystemAccessTokenMapper;
import com.nexusapi.server.modules.systemtoken.service.SystemAccessTokenAuthenticationService;
import com.nexusapi.server.modules.systemtoken.support.SystemAccessTokenHasher;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** 系统令牌观测字段写入失败不能破坏只读接口成功响应。 */
class SystemAccessTokenAuthenticationServiceTest {
    @Test
    void lastUsedObservationFailureDoesNotFailBusinessRequest() {
        SystemAccessTokenMapper mapper = mock(SystemAccessTokenMapper.class);
        SystemAccessTokenAuthenticationService service = new SystemAccessTokenAuthenticationService(
                mapper, mock(SystemAccessTokenHasher.class), new ObjectMapper()
        );
        UUID tokenId = UUID.randomUUID();
        doThrow(new IllegalStateException("database unavailable")).when(mapper).touchLastUsed(tokenId);

        assertThatCode(() -> service.recordSuccessfulUse(tokenId)).doesNotThrowAnyException();
    }
}
