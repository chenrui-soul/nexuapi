package com.nexusapi.server.modules.health.service;

import com.nexusapi.server.common.config.HealthProperties;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.health.entity.ChannelHealthTarget;
import com.nexusapi.server.modules.health.model.ChannelHealthProbeResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** 使用共享连接池执行无计费 GET 健康探测，只读取状态码并主动丢弃响应正文。 */
@Component
public class ChannelHealthProbeClient {
    private static final Pattern SAFE_PROBE_PATH = Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@/-]*$");
    /** 渠道地址可能直接配置到具体操作接口，探测时应回退到同一 API 根路径。 */
    private static final List<String> OPERATION_SUFFIXES = List.of(
            "/chat/completions", "/responses", "/images/generations", "/audio/transcriptions",
            "/audio/speech", "/embeddings", "/moderations", "/videos"
    );
    private final WebClient webClient;
    private final ChannelCredentialCipher credentialCipher;
    private final HealthProperties properties;

    public ChannelHealthProbeClient(
            WebClient upstreamWebClient,
            ChannelCredentialCipher credentialCipher,
            HealthProperties properties
    ) {
        this.webClient = upstreamWebClient;
        this.credentialCipher = credentialCipher;
        this.properties = properties;
    }

    public ChannelHealthProbeResult probe(ChannelHealthTarget target) {
        Instant startedAt = Instant.now();
        String credential;
        URI uri;
        try {
            credential = credentialCipher.decrypt(
                    target.getEncryptedCredential(), target.getCredentialKeyVersion()
            );
            uri = probeUri(target.getBaseUrl(), target.getHealthProbePath());
        } catch (RuntimeException configurationFailure) {
            return ChannelHealthProbeResult.unconfigured(elapsedMillis(startedAt), "probe_configuration_invalid");
        }

        try {
            Duration timeout = effectiveTimeout(target.getTimeoutMs());
            int status = requestStatus(uri, credential, timeout);
            if (isUnsupportedEndpoint(status) && !isDefaultModelsPath(target.getHealthProbePath())) {
                URI modelsUri = probeUri(target.getBaseUrl(), "/models");
                if (!modelsUri.equals(uri)) {
                    status = requestStatus(modelsUri, credential, remainingTimeout(startedAt, timeout));
                }
            }
            int latencyMs = elapsedMillis(startedAt);
            if (status >= 200 && status < 300) {
                return ChannelHealthProbeResult.healthy(latencyMs);
            }
            if (status == 401 || status == 403) {
                return ChannelHealthProbeResult.failure(latencyMs, "authentication", "probe_authentication_failed");
            }
            if (status == 408) {
                return ChannelHealthProbeResult.failure(latencyMs, "timeout", "probe_timeout");
            }
            if (status == 429) {
                return ChannelHealthProbeResult.failure(latencyMs, "rate_limit", "probe_rate_limited");
            }
            if (status >= 500) {
                return ChannelHealthProbeResult.failure(latencyMs, "upstream_5xx", "probe_upstream_5xx");
            }
            // 400/404/422 常见于未实现 /models 的兼容上游，不能据此误伤真实 Chat Completions 路由。
            return ChannelHealthProbeResult.unconfigured(latencyMs, "probe_endpoint_unsupported");
        } catch (RuntimeException requestFailure) {
            Throwable cause = Exceptions.unwrap(requestFailure);
            boolean timeout = cause instanceof TimeoutException;
            return ChannelHealthProbeResult.failure(
                    elapsedMillis(startedAt),
                    timeout ? "timeout" : "network",
                    timeout ? "probe_timeout" : "probe_connection_failure"
            );
        }
    }

    /** 自定义能力探测端点不兼容时，回退到同一 API 根路径的无计费 GET /models。 */
    private boolean isUnsupportedEndpoint(int status) {
        return status == 400 || status == 404 || status == 405 || status == 422;
    }

    private boolean isDefaultModelsPath(String configuredPath) {
        return configuredPath == null || configuredPath.isBlank() || "/models".equals(configuredPath.strip());
    }

    private int requestStatus(URI uri, String credential, Duration timeout) {
        return webClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()))
                .timeout(timeout)
                .blockOptional()
                .orElse(599);
    }

    /** 两次探测共享同一超时预算，避免回退请求把批处理耗时翻倍。 */
    private Duration remainingTimeout(Instant startedAt, Duration timeout) {
        long remainingMillis = timeout.toMillis() - Duration.between(startedAt, Instant.now()).toMillis();
        return Duration.ofMillis(Math.max(1L, remainingMillis));
    }

    private Duration effectiveTimeout(int channelTimeoutMs) {
        long configured = properties.probeTimeout().toMillis();
        long channel = channelTimeoutMs <= 0 ? configured : channelTimeoutMs;
        return Duration.ofMillis(Math.max(1L, Math.min(configured, channel)));
    }

    /**
     * 把受控相对路径解析到既有渠道的 API 根路径，且再次校验协议和主机没有变化。
     * 这里不接受绝对 URL、查询参数或路径穿越，数据库约束和服务校验之外再做一层防御。
     */
    private URI probeUri(String baseUrl, String configuredPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Missing channel base URL");
        }
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String probePath = configuredPath == null || configuredPath.isBlank() ? "/models" : configuredPath.strip();
        if (!SAFE_PROBE_PATH.matcher(probePath).matches()
                || probePath.contains("..") || probePath.contains("//")) {
            throw new IllegalArgumentException("Unsafe health probe path");
        }
        URI baseUri = URI.create(normalized);
        String scheme = baseUri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || baseUri.getHost() == null) {
            throw new IllegalArgumentException("Unsupported channel base URL");
        }
        String probeBase = stripOperationSuffix(normalized);
        URI uri = URI.create(probeBase.endsWith(probePath) ? probeBase : probeBase + probePath);
        if (!scheme.equalsIgnoreCase(uri.getScheme())
                || !baseUri.getHost().equalsIgnoreCase(uri.getHost())
                || baseUri.getPort() != uri.getPort()) {
            throw new IllegalArgumentException("Health probe must remain on channel host");
        }
        return uri;
    }

    /**
     * 渠道的 base_url 既支持 API 根地址，也支持具体操作地址；后者不能直接追加探测路径。
     * 这里只移除固定的协议操作后缀，不接受任意路径重写，避免改变管理员配置的主机和 API 前缀。
     */
    private String stripOperationSuffix(String baseUrl) {
        for (String suffix : OPERATION_SUFFIXES) {
            if (baseUrl.endsWith(suffix)) {
                return baseUrl.substring(0, baseUrl.length() - suffix.length());
            }
        }
        return baseUrl;
    }

    private int elapsedMillis(Instant startedAt) {
        long millis = Duration.between(startedAt, Instant.now()).toMillis();
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0L, millis)));
    }
}
