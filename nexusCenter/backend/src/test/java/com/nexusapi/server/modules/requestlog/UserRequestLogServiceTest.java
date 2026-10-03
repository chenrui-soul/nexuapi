package com.nexusapi.server.modules.requestlog;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.modules.requestlog.mapper.RequestLogMapper;
import com.nexusapi.server.modules.requestlog.service.UserRequestLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRequestLogServiceTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private RequestLogMapper mapper;
    private UserRequestLogService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mapper = mock(RequestLogMapper.class);
        service = new UserRequestLogService(mapper);
        userId = UUID.randomUUID();
        when(mapper.findUserPage(any(), isNull(), isNull(), isNull(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(mapper.countUserPage(any(), isNull(), isNull(), isNull(), any(), any())).thenReturn(0L);
    }

    @Test
    void todayUsesShanghaiCalendarDayBoundaries() {
        service.list(userId, 1, 20, null, null, null, "today", null, null);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).findUserPage(eq(userId), isNull(), isNull(), isNull(), from.capture(), to.capture(), eq(0), eq(20));

        assertThat(from.getValue().atZone(BUSINESS_ZONE).toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(from.getValue().atZone(BUSINESS_ZONE).toLocalDate())
                .isEqualTo(to.getValue().atZone(BUSINESS_ZONE).toLocalDate());
        assertThat(Duration.between(from.getValue(), to.getValue()))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofDays(1));
    }

    @Test
    void customRangeIsPassedToBothListAndCountQueries() {
        Instant from = Instant.parse("2026-09-01T16:00:00Z");
        Instant to = Instant.parse("2026-09-07T16:00:00Z");

        service.list(userId, 1, 20, null, null, null, "custom", from, to);

        verify(mapper).findUserPage(userId, null, null, null, from, to, 0, 20);
        verify(mapper).countUserPage(userId, null, null, null, from, to);
    }

    @Test
    void invalidCustomRangesAreRejectedBeforeQuerying() {
        Instant from = Instant.parse("2026-09-01T16:00:00Z");

        assertThatThrownBy(() -> service.list(userId, 1, 20, null, null, null, "custom", from, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同时提供");
        assertThatThrownBy(() -> service.list(userId, 1, 20, null, null, null, "custom", from, from.plus(Duration.ofDays(91))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("90 天");
    }
}
