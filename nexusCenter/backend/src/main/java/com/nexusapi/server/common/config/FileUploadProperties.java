package com.nexusapi.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 独立文件上传上游配置；凭证只允许由服务端环境变量提供。 */
@ConfigurationProperties(prefix = "nexus.file-upload")
public record FileUploadProperties(
        URI endpoint,
        String token,
        Duration requestTimeout,
        long maxFileBytes
) {
    private static final URI DEFAULT_ENDPOINT = URI.create("https://caicaiapi.cloud/v1/files/upload");
    private static final long DEFAULT_MAX_FILE_BYTES = 20L * 1024L * 1024L;

    public FileUploadProperties {
        endpoint = endpoint == null ? DEFAULT_ENDPOINT : endpoint;
        token = token == null ? "" : token.strip();
        requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? Duration.ofSeconds(90) : requestTimeout;
        maxFileBytes = maxFileBytes <= 0 ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
        String scheme = endpoint.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("nexus.file-upload.endpoint must use http or https");
        }
        if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("nexus.file-upload.endpoint must not contain user info or fragment");
        }
        if (maxFileBytes > DEFAULT_MAX_FILE_BYTES) {
            throw new IllegalArgumentException("nexus.file-upload.max-file-bytes cannot exceed 20MB");
        }
    }
}
