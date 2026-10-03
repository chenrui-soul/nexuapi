package com.nexusapi.server.common.error;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.gateway.capability.OpenAiCapabilityExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void gatewayContentTypeMismatchUsesTheOpenAiErrorEnvelopeBeforeControllerSelection() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/images/generations");

        var response = handler.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON, List.of(MediaType.MULTIPART_FORM_DATA)), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isInstanceOf(OpenAiCapabilityExceptionHandler.OpenAiErrorEnvelope.class);
        var body = (OpenAiCapabilityExceptionHandler.OpenAiErrorEnvelope) response.getBody();
        assertThat(body.error().type()).isEqualTo("invalid_request_error");
        assertThat(body.error().code()).isEqualTo("invalid_content_type");
    }

    @Test
    void consoleContentTypeMismatchKeepsThePlatformEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

        var response = handler.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON, List.of(MediaType.MULTIPART_FORM_DATA)), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
    }
}
