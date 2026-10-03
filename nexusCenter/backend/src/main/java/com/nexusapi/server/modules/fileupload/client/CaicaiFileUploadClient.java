package com.nexusapi.server.modules.fileupload.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusapi.server.common.config.FileUploadProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.fileupload.model.FileUploadRequest;
import com.nexusapi.server.modules.gateway.upstream.UpstreamCallException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/** 菜菜独立文件上传客户端；上游 Token 不进入请求对象、日志或数据库。 */
@Component
public class CaicaiFileUploadClient {
    private final WebClient webClient;
    private final FileUploadProperties properties;

    public CaicaiFileUploadClient(
            @Qualifier("upstreamWebClient") WebClient webClient,
            FileUploadProperties properties
    ) {
        this.webClient = webClient;
        this.properties = properties;
    }

    public UploadResponse upload(FileUploadRequest upload) {
        if (properties.token().isBlank()) {
            throw new BusinessException(
                    ErrorCode.FILE_UPLOAD_NOT_CONFIGURED, "文件上传服务尚未配置", null
            );
        }
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        ByteArrayResource resource = new ByteArrayResource(upload.content()) {
            @Override
            public String getFilename() {
                return upload.filename();
            }
        };
        body.part("file", resource).filename(upload.filename()).contentType(upload.contentType());

        UploadResponse response = webClient.post()
                .uri(properties.endpoint())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchangeToMono(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(JsonNode.class)
                                .map(responseBody -> new UploadResponse(status, responseBody));
                    }
                    return clientResponse.releaseBody().then(reactor.core.publisher.Mono.error(httpFailure(status)));
                })
                .timeout(properties.requestTimeout())
                .onErrorMap(error -> !(error instanceof UpstreamCallException)
                        && !(error instanceof BusinessException), this::networkFailure)
                .block();
        if (response == null || response.body() == null || !response.body().isObject()) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY, "upstream_protocol_error", false,
                    "upstream_invalid_json", null, null
            );
        }
        return response;
    }

    private UpstreamCallException httpFailure(int upstreamStatus) {
        HttpStatus clientStatus = upstreamStatus == 429
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        return new UpstreamCallException(
                clientStatus, "upstream_http_" + upstreamStatus, upstreamStatus == 429 || upstreamStatus >= 500,
                "upstream_http_" + upstreamStatus, upstreamStatus, null
        );
    }

    private UpstreamCallException networkFailure(Throwable raw) {
        Throwable error = Exceptions.unwrap(raw);
        boolean timeout = hasCause(error, cause -> cause instanceof TimeoutException
                || cause.getClass().getSimpleName().contains("Timeout"));
        String summary;
        if (timeout) summary = "upstream_timeout";
        else if (hasCause(error, cause -> cause instanceof ConnectException)) summary = "upstream_connection_refused";
        else if (hasCause(error, cause -> cause instanceof UnknownHostException)) summary = "upstream_dns_failure";
        else if (hasCause(error, cause -> cause instanceof SSLException)) summary = "upstream_tls_failure";
        else summary = "upstream_connection_failure";
        return new UpstreamCallException(
                timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                timeout ? "upstream_timeout" : "upstream_connection_error",
                true, summary, null, error
        );
    }

    private boolean hasCause(Throwable error, java.util.function.Predicate<Throwable> predicate) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (predicate.test(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    /** 上游成功状态和 JSON 正文。 */
    public record UploadResponse(int statusCode, JsonNode body) {
    }
}
