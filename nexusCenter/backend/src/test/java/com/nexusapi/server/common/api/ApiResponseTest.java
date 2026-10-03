package com.nexusapi.server.common.api;

import com.nexusapi.server.common.web.RequestIds;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void shouldCarryRequestIdFromMdc() {
        MDC.put(RequestIds.MDC_KEY, "req_test1234");
        try {
            ApiResponse<String> response = ApiResponse.ok("ok");
            assertThat(response.success()).isTrue();
            assertThat(response.data()).isEqualTo("ok");
            assertThat(response.requestId()).isEqualTo("req_test1234");
        } finally {
            MDC.clear();
        }
    }
}
