package com.nexusapi.server.modules.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusapi.server.common.config.ModelSyncProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 使用服务端 Bearer 凭证读取菜菜 API 可见服务分组快照。 */
@Component
public class CaicaiPriceGroupClient {
    private static final int MAX_GROUPS = 1_000;

    private final WebClient webClient;
    private final ModelSyncProperties properties;

    public CaicaiPriceGroupClient(
            @Qualifier("upstreamWebClient") WebClient webClient,
            ModelSyncProperties properties
    ) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /**
     * 获取一份完整分组快照。凭证缺失时在发起网络请求前失败，
     * 避免把认证失败误记为空分组快照。
     */
    public PriceGroupSnapshot fetchVisible() {
        if (properties.priceGroupToken().isBlank()) {
            throw failure("price_group_credential_missing", "上游服务分组同步凭证未配置", false);
        }
        PriceGroupException last = null;
        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                return fetchOnce();
            } catch (PriceGroupException failure) {
                last = failure;
                if (!failure.retryable() || attempt == properties.maxRetries()) {
                    throw failure;
                }
            }
        }
        throw last == null ? failure("upstream_unavailable", "上游服务分组暂时不可用", true) : last;
    }

    private PriceGroupSnapshot fetchOnce() {
        JsonNode root;
        try {
            Duration timeout = properties.requestTimeout();
            root = webClient.get()
                    .uri(properties.priceGroupsUrl())
                    .headers(headers -> headers.setBearerAuth(properties.priceGroupToken()))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.releaseBody().then(Mono.error(
                                    failure("price_group_http_error", "上游服务分组返回异常状态", true)
                            ));
                        }
                        return response.bodyToMono(JsonNode.class);
                    })
                    .timeout(timeout)
                    .block(timeout.plusSeconds(1));
        } catch (PriceGroupException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("upstream_unavailable", "上游服务分组请求失败", true);
        }

        if (root == null || root.path("code").asInt(Integer.MIN_VALUE) != 0) {
            throw failure("price_group_contract_error", "上游服务分组业务状态异常", false);
        }
        JsonNode data = root.path("data");
        JsonNode groups = data.isArray() ? data : data.path("list");
        if (!groups.isArray() || groups.size() > MAX_GROUPS) {
            throw failure("price_group_contract_error", "上游服务分组结构无效", false);
        }
        List<JsonNode> snapshot = new ArrayList<>(groups.size());
        for (JsonNode group : groups) {
            if (!group.isObject()) {
                throw failure("price_group_contract_error", "上游服务分组条目结构无效", false);
            }
            snapshot.add(group.deepCopy());
        }
        return new PriceGroupSnapshot(List.copyOf(snapshot));
    }

    private PriceGroupException failure(String code, String summary, boolean retryable) {
        return new PriceGroupException(code, summary, retryable);
    }

    /** 上游已授权可见的完整服务分组快照。 */
    public record PriceGroupSnapshot(List<JsonNode> groups) {
    }

    /** 仅携带固定分类和脱敏摘要的分组接口异常。 */
    public static final class PriceGroupException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        PriceGroupException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
}
