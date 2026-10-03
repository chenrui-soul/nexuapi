package com.nexusapi.server.modules.fileupload;

import com.nexusapi.server.common.config.FileUploadProperties;
import com.nexusapi.server.modules.fileupload.client.CaicaiFileUploadClient;
import com.nexusapi.server.modules.fileupload.model.FileUploadRequest;
import com.nexusapi.server.modules.gateway.upstream.UpstreamCallException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 菜菜上传客户端的 multipart、服务端凭证和错误脱敏测试。 */
class CaicaiFileUploadClientTest {
    private HttpServer upstream;
    private CaicaiFileUploadClient client;
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/files/upload", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        client = new CaicaiFileUploadClient(
                WebClient.builder().build(),
                new FileUploadProperties(
                        URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1/files/upload"),
                        "server-side-upload-token", Duration.ofSeconds(5), 1024 * 1024
                )
        );
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    @Test
    void sendsSingleMultipartFileWithConfiguredBearerToken() {
        responseBody.set("{\"id\":\"file_http_server\",\"url\":\"https://files.example/file_http_server\"}");

        CaicaiFileUploadClient.UploadResponse response = client.upload(new FileUploadRequest(
                "upload-body".getBytes(StandardCharsets.UTF_8), "sample.txt", MediaType.TEXT_PLAIN
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().path("id").asText()).isEqualTo("file_http_server");
        assertThat(receivedAuthorization.get()).isEqualTo("Bearer server-side-upload-token");
        assertThat(receivedContentType.get()).startsWith("multipart/form-data;boundary=");
        assertThat(receivedBody.get()).contains("name=\"file\"").contains("filename=\"sample.txt\"")
                .contains("Content-Type: text/plain").contains("upload-body");
    }

    @Test
    void convertsUpstreamFailureWithoutLeakingResponseBody() {
        responseStatus.set(503);
        responseBody.set("secret upstream diagnostics");

        assertThatThrownBy(() -> client.upload(new FileUploadRequest(
                new byte[]{1}, "sample.bin", MediaType.APPLICATION_OCTET_STREAM
        )))
                .isInstanceOfSatisfying(UpstreamCallException.class, failure -> {
                    assertThat(failure.upstreamStatus()).isEqualTo(503);
                    assertThat(failure.safeSummary()).isEqualTo("upstream_http_503");
                    assertThat(failure.getMessage()).doesNotContain("secret upstream diagnostics");
                });
    }
}
