package com.nexusapi.server.modules.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusapi.server.common.config.ModelSyncProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 分页读取菜菜 API 模型市场，并支持按 model_name 精确读取单模型详情。 */
@Component
public class CaicaiModelMarketClient {
    private final WebClient webClient;
    private final ModelSyncProperties properties;

    public CaicaiModelMarketClient(
            @Qualifier("upstreamWebClient") WebClient webClient,
            ModelSyncProperties properties
    ) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /**
     * 拉取全部分页。任何一页失败、总数变化或页内数量不完整都会放弃整轮，
     * 防止把一个不完整快照写入本地模型表。
     */
    public MarketSnapshot fetchAll() {
        MarketPage first = fetchPageWithRetry(1);
        if (first.total() > properties.maxModels()) {
            throw failure("snapshot_too_large", "上游模型数量超过同步安全上限", false);
        }
        int pageCount = first.total() == 0 ? 1 : (first.total() + first.pageSize() - 1) / first.pageSize();
        List<JsonNode> models = new ArrayList<>(first.total());
        models.addAll(first.models());
        for (int page = 2; page <= pageCount; page++) {
            MarketPage current = fetchPageWithRetry(page);
            if (current.total() != first.total() || current.pageSize() != first.pageSize()) {
                throw failure("snapshot_changed", "上游分页快照在同步期间发生变化", false);
            }
            models.addAll(current.models());
        }
        if (models.size() != first.total()) {
            throw failure("snapshot_incomplete", "上游分页数量与总数不一致", false);
        }
        return new MarketSnapshot(first.total(), List.copyOf(models));
    }

    /**
     * 按对外模型名读取单模型详情。上游仍复用同一个市场接口，精确筛选时必须返回且只能返回一条记录。
     */
    public JsonNode fetchByModelName(String modelName) {
        String normalizedName = modelName == null ? "" : modelName.strip();
        if (normalizedName.isEmpty() || normalizedName.length() > 160) {
            throw failure("upstream_contract_error", "单模型详情缺少合法模型名", false);
        }
        ModelMarketException last = null;
        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                return fetchDetailOnce(normalizedName);
            } catch (ModelMarketException failure) {
                last = failure;
                if (!failure.retryable() || attempt == properties.maxRetries()) {
                    throw failure;
                }
            }
        }
        throw last == null ? failure("upstream_unavailable", "上游单模型详情暂时不可用", true) : last;
    }

    private MarketPage fetchPageWithRetry(int page) {
        ModelMarketException last = null;
        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                return fetchPage(page);
            } catch (ModelMarketException failure) {
                last = failure;
                if (!failure.retryable() || attempt == properties.maxRetries()) {
                    throw failure;
                }
            }
        }
        throw last == null ? failure("upstream_unavailable", "上游模型市场暂时不可用", true) : last;
    }

    private MarketPage fetchPage(int page) {
        URI uri = UriComponentsBuilder.fromUri(properties.marketUrl())
                .replaceQueryParam("page", page)
                .replaceQueryParam("page_size", properties.pageSize())
                .build(true)
                .toUri();
        JsonNode root;
        try {
            Duration timeout = properties.requestTimeout();
            root = webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.releaseBody().then(Mono.error(
                                    failure("upstream_http_error", "上游模型市场返回异常状态", true)
                            ));
                        }
                        return response.bodyToMono(JsonNode.class);
                    })
                    .timeout(timeout)
                    .block(timeout.plusSeconds(1));
        } catch (ModelMarketException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("upstream_unavailable", "上游模型市场请求失败", true);
        }
        if (root == null || root.path("code").asInt(Integer.MIN_VALUE) != 0) {
            // 该接口参数错误时也可能返回 HTTP 200，因此必须同时校验业务 code。
            throw failure("upstream_contract_error", "上游模型市场业务状态异常", false);
        }
        JsonNode data = root.path("data");
        JsonNode list = data.path("list");
        int declaredPage = data.path("page").asInt(-1);
        int pageSize = data.path("page_size").asInt(-1);
        int total = data.path("total").asInt(-1);
        if (!data.isObject() || !list.isArray() || declaredPage != page || pageSize < 1 || pageSize > 100 || total < 0) {
            throw failure("upstream_contract_error", "上游模型市场分页结构无效", false);
        }
        int expected = Math.max(0, Math.min(pageSize, total - ((page - 1) * pageSize)));
        if (list.size() != expected) {
            throw failure("snapshot_incomplete", "上游模型市场当前页数量不完整", false);
        }
        List<JsonNode> models = new ArrayList<>(list.size());
        list.forEach(item -> {
            if (!item.isObject()) {
                throw failure("upstream_contract_error", "上游模型条目结构无效", false);
            }
            models.add(item.deepCopy());
        });
        return new MarketPage(total, pageSize, List.copyOf(models));
    }

    private JsonNode fetchDetailOnce(String modelName) {
        URI uri = UriComponentsBuilder.fromUri(properties.marketUrl())
                .replaceQueryParam("model_name", modelName)
                .replaceQueryParam("page")
                .replaceQueryParam("page_size")
                .build(true)
                .toUri();
        JsonNode root;
        try {
            Duration timeout = properties.requestTimeout();
            root = webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.releaseBody().then(Mono.error(
                                    failure("upstream_http_error", "上游单模型详情返回异常状态", true)
                            ));
                        }
                        return response.bodyToMono(JsonNode.class);
                    })
                    .timeout(timeout)
                    .block(timeout.plusSeconds(1));
        } catch (ModelMarketException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure("upstream_unavailable", "上游单模型详情请求失败", true);
        }
        if (root == null || root.path("code").asInt(Integer.MIN_VALUE) != 0) {
            throw failure("upstream_contract_error", "上游单模型详情业务状态异常", false);
        }
        JsonNode data = root.path("data");
        JsonNode list = data.path("list");
        int total = data.path("total").asInt(-1);
        if (!data.isObject() || !list.isArray() || total != 1 || list.size() != 1 || !list.get(0).isObject()) {
            throw failure("upstream_contract_error", "上游单模型详情必须返回唯一模型", false);
        }
        JsonNode detail = list.get(0).deepCopy();
        String returnedName = detail.path("model_name").asText("").strip();
        if (!returnedName.equalsIgnoreCase(modelName)) {
            throw failure("upstream_contract_error", "上游单模型详情名称与请求不一致", false);
        }
        return detail;
    }

    private ModelMarketException failure(String code, String summary, boolean retryable) {
        return new ModelMarketException(code, summary, retryable);
    }

    /** 经过完整分页一致性校验的模型市场快照。 */
    public record MarketSnapshot(int total, List<JsonNode> models) {
    }

    private record MarketPage(int total, int pageSize, List<JsonNode> models) {
    }

    /** 仅携带固定分类和脱敏摘要的外部接口异常。 */
    public static final class ModelMarketException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        ModelMarketException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
