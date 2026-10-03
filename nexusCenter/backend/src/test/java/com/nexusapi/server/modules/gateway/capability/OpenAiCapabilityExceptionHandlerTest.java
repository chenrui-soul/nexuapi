package com.nexusapi.server.modules.gateway.capability;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import com.nexusapi.server.modules.gateway.upstream.UpstreamCallException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

class OpenAiCapabilityExceptionHandlerTest {
    private final OpenAiCapabilityExceptionHandler handler = new OpenAiCapabilityExceptionHandler();

    @Test
    void asyncStreamLifecycleExceptionsAreConsumedWithoutWritingAnErrorEnvelope() {
        assertThatCode(() -> handler.handleAsyncStreamLifecycle(new AsyncRequestTimeoutException()))
                .doesNotThrowAnyException();
        assertThatCode(() -> handler.handleAsyncStreamLifecycle(
                new AsyncRequestNotUsableException("client disconnected")))
                .doesNotThrowAnyException();
    }

    @Test
    void upstreamErrorsAlwaysUseJsonEvenWhenClientRequestsSse() {
        var response = handler.handleUpstream(new UpstreamCallException(
                HttpStatus.BAD_GATEWAY, "upstream_error", true,
                "upstream_http_403", 403, null));

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        org.assertj.core.api.Assertions.assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void unsupportedContentTypeIsAClientErrorWithTheOpenAiEnvelope() {
        var response = handler.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_JSON, List.of(MediaType.MULTIPART_FORM_DATA)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody().error().type()).isEqualTo("invalid_request_error");
        assertThat(response.getBody().error().code()).isEqualTo("invalid_content_type");
    }
}
