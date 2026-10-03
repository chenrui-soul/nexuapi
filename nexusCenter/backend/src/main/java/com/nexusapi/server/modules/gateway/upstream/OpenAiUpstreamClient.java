package com.nexusapi.server.modules.gateway.upstream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/** 使用共享连接池调用 OpenAI 兼容上游，并把所有失败归一化为不含敏感值的异常。 */
@Component
public class OpenAiUpstreamClient {
    private static final int STREAM_PREFETCH_EVENTS = 16;
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final WebClient webClient;
    private final ChannelCredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;

    public OpenAiUpstreamClient(
            WebClient upstreamWebClient,
            ChannelCredentialCipher credentialCipher,
            ObjectMapper objectMapper
    ) {
        this.webClient = upstreamWebClient;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    public JsonNode chatCompletion(RuntimeRouteRow route, ObjectNode payload) {
        String credential = decrypt(route);
        JsonNode response = webClient.post()
                .uri(chatCompletionsUri(route.getBaseUrl()))
                .headers(headers -> applyHeaders(headers, credential, route.getConfigJson()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchangeToMono(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(JsonNode.class);
                    }
                    return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(httpFailure(status, body)));
                })
                .timeout(Duration.ofMillis(route.getTimeoutMs()))
                .onErrorMap(error -> !(error instanceof UpstreamCallException), this::networkFailure)
                .block();
        if (response == null || !response.isObject()) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY,
                    "upstream_protocol_error",
                    false,
                    "upstream_invalid_json",
                    null,
                    null
            );
        }
        return response;
    }

    /** 按渠道已配置的完整上游地址转发 JSON GET/POST；不自动追加 Chat 或 Images 路径。 */
    public JsonNode jsonRequest(RuntimeRouteRow route, HttpMethod method, JsonNode payload, String pathSuffix) {
        String credential = decrypt(route);
        URI uri = appendSafePath(route.getBaseUrl(), pathSuffix);
        if (method == HttpMethod.GET && payload != null && payload.isObject()) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUri(uri);
            payload.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                    builder.queryParam(entry.getKey(), entry.getValue().asText());
                }
            });
            uri = builder.build(true).toUri();
        }
        WebClient.RequestBodySpec request = webClient.method(method)
                .uri(uri)
                .headers(headers -> applyHeaders(headers, credential, route.getConfigJson()))
                .accept(MediaType.APPLICATION_JSON);
        WebClient.RequestHeadersSpec<?> configured;
        if (method == HttpMethod.GET || payload == null) {
            configured = request;
        } else {
            configured = request.contentType(MediaType.APPLICATION_JSON).bodyValue(payload);
        }
        JsonNode response = configured.exchangeToMono(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(JsonNode.class);
                    }
                    return clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(body -> Mono.error(httpFailure(status, body)));
                })
                .timeout(Duration.ofMillis(route.getTimeoutMs()))
                .onErrorMap(error -> !(error instanceof UpstreamCallException), this::networkFailure)
                .block();
        if (response == null || !response.isObject()) {
            throw new UpstreamCallException(HttpStatus.BAD_GATEWAY, "upstream_protocol_error", false,
                    "upstream_invalid_json", null, null);
        }
        return response;
    }

    /**
     * 按渠道配置的完整地址发送 JSON，并以受控二进制载体返回音频正文和 Content-Type。
     * 音频正文只存在于当前请求内存中，不进入日志、异常或持久化对象。
     */
    public BinaryResponse binaryJsonRequest(RuntimeRouteRow route, ObjectNode payload) {
        String credential = decrypt(route);
        BinaryResponse response = webClient.post()
                .uri(appendSafePath(route.getBaseUrl(), null))
                .headers(headers -> applyHeaders(headers, credential, route.getConfigJson()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.ALL)
                .bodyValue(payload)
                .exchangeToMono(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        MediaType contentType = clientResponse.headers().contentType()
                                .orElse(MediaType.APPLICATION_OCTET_STREAM);
                        return clientResponse.bodyToMono(byte[].class)
                                .defaultIfEmpty(new byte[0])
                                .map(body -> new BinaryResponse(body, contentType));
                    }
                    return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(httpFailure(status, body)));
                })
                .timeout(Duration.ofMillis(route.getTimeoutMs()))
                .onErrorMap(error -> !(error instanceof UpstreamCallException), this::networkFailure)
                .block();
        if (response == null || response.body().length == 0) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY, "upstream_protocol_error", false,
                    "upstream_empty_binary", null, null
            );
        }
        return response;
    }

    /**
     * 将单个音频文件和普通字段转发为 multipart，并要求上游返回 JSON。
     * fields 使用列表保留 timestamp_granularities[] 等可重复参数。
     */
    public JsonNode multipartJsonRequest(
            RuntimeRouteRow route,
            Map<String, List<String>> fields,
            String fileField,
            UploadPart upload
    ) {
        String credential = decrypt(route);
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        fields.forEach((name, values) -> {
            if (name == null || values == null) return;
            for (String value : new ArrayList<>(values)) {
                if (value != null) body.part(name, value);
            }
        });
        ByteArrayResource resource = new ByteArrayResource(upload.content()) {
            @Override
            public String getFilename() {
                return upload.filename();
            }
        };
        body.part(fileField, resource)
                .filename(upload.filename())
                .contentType(upload.contentType());
        JsonNode response = webClient.post()
                .uri(appendSafePath(route.getBaseUrl(), null))
                .headers(headers -> applyHeaders(headers, credential, route.getConfigJson()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchangeToMono(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(JsonNode.class);
                    }
                    return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(raw -> Mono.error(httpFailure(status, raw)));
                })
                .timeout(Duration.ofMillis(route.getTimeoutMs()))
                .onErrorMap(error -> !(error instanceof UpstreamCallException), this::networkFailure)
                .block();
        if (response == null || !response.isObject()) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY, "upstream_protocol_error", false,
                    "upstream_invalid_json", null, null
            );
        }
        return response;
    }

    /** 将任务详情 ID 作为受控路径段追加到渠道地址，拒绝路径穿越。 */
    private URI appendSafePath(String baseUrl, String pathSuffix) {
        if (pathSuffix == null || pathSuffix.isBlank()) return URI.create(baseUrl);
        String suffix = pathSuffix.strip();
        if (suffix.contains("..") || suffix.contains("?") || suffix.contains("#") || suffix.contains("//")) {
            throw new UpstreamCallException(HttpStatus.BAD_REQUEST, "invalid_request_error", false,
                    "invalid_task_id", null, null);
        }
        if (baseUrl.contains("{id}")) {
            String taskId = suffix.startsWith("/") ? suffix.substring(1) : suffix;
            if (taskId.isBlank() || taskId.contains("/")) {
                throw new UpstreamCallException(HttpStatus.BAD_REQUEST, "invalid_request_error", false,
                        "invalid_task_id", null, null);
            }
            return URI.create(baseUrl.replace("{id}", taskId));
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + (suffix.startsWith("/") ? suffix : "/" + suffix));
    }

    /**
     * 将图片生成或编辑请求按 OpenAI 兼容 JSON/multipart 协议转发到渠道图片地址。
     *
     * <p>上传文件只在当前 WebClient 请求生命周期内存在，不写入日志、路由对象或数据库；
     * 调用方已经完成文件数量、类型和大小校验。</p>
     */
    public JsonNode imageGeneration(
            RuntimeRouteRow route,
            Map<String, String> fields,
            List<UploadPart> images,
            List<String> imageUrls
    ) {
        String credential = decrypt(route);
        try {
            return imageGenerationOnce(route, credential, fields, images, imageUrls);
        } catch (UpstreamCallException exception) {
            // 一些 OpenAI-compatible 图片渠道只支持自己的质量枚举，或完全不接受 quality。
            // 400/422 且错误明确指向 quality 时，去掉这个可选字段重试一次；
            // 此时上游尚未返回成功结果，平台只会完成一次计费和一次渠道尝试记录。
            if (!exception.qualityUnsupported() || fields == null || !fields.containsKey("quality")) {
                throw exception;
            }
            Map<String, String> compatibleFields = new LinkedHashMap<>(fields);
            compatibleFields.remove("quality");
            return imageGenerationOnce(route, credential, compatibleFields, images, imageUrls);
        }
    }

    private JsonNode imageGenerationOnce(
            RuntimeRouteRow route,
            String credential,
            Map<String, String> fields,
            List<UploadPart> images,
            List<String> imageUrls
    ) {
        if (images == null || images.isEmpty()) {
            // 部分图片渠道（包括当前 gpt-image-2 上游）在无参考图时只接受 JSON；
            // 只有携带参考图时才使用 multipart，避免把 multipart 边界交给 JSON 解析器。
            ObjectNode payload = objectMapper.createObjectNode();
            fields.forEach((name, value) -> {
                if (value == null) return;
                if ("n".equals(name)) {
                    payload.put(name, Integer.parseInt(value));
                } else if ("extra_params".equals(name)) {
                    try {
                        payload.set(name, objectMapper.readTree(value));
                    } catch (Exception exception) {
                        throw new UpstreamCallException(
                                HttpStatus.BAD_GATEWAY, "upstream_configuration_error", false,
                                "upstream_invalid_image_payload", null, exception
                        );
                    }
                } else {
                    payload.put(name, value);
                }
            });
            if (imageUrls != null && !imageUrls.isEmpty()) {
                var urlArray = payload.putArray("images");
                imageUrls.forEach(urlArray::add);
            }
            return imageGenerationJson(route, credential, payload);
        }
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        fields.forEach((name, value) -> {
            if (value != null) {
                var part = body.part(name, value);
                if ("extra_params".equals(name)) part.contentType(MediaType.APPLICATION_JSON);
            }
        });
        for (UploadPart image : images) {
            ByteArrayResource resource = new ByteArrayResource(image.content()) {
                @Override
                public String getFilename() {
                    return image.filename();
                }
            };
            body.part("images", resource)
                    .filename(image.filename())
                    .contentType(image.contentType());
        }
        JsonNode response = webClient.post()
                .uri(imageGenerationsUri(route.getBaseUrl()))
                .headers(headers -> applyHeaders(headers, credential, route.getConfigJson()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchangeToMono(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(JsonNode.class);
                    }
                    return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(raw -> Mono.error(httpFailure(status, raw)));
                })
                .timeout(Duration.ofMillis(route.getTimeoutMs()))
                .onErrorMap(error -> !(error instanceof UpstreamCallException), this::networkFailure)
                .block();
        if (response == null || !response.isObject()) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY,
                    "upstream_protocol_error",
                    false,
                    "upstream_invalid_json",
                    null,
                    null
            );
        }
        return response;
    }

    private JsonNode imageGenerationJson(RuntimeRouteRow route, String credential, ObjectNode payload) {
        JsonNode response = webClient.post()
                .uri(imageGenerationsUri(route.getBaseUrl()))
                .headers(headers -> applyHeaders(headers, credential, route.getConfigJson()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchangeToMono(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(JsonNode.class);
                    }
                    return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(raw -> Mono.error(httpFailure(status, raw)));
                })
                .timeout(Duration.ofMillis(route.getTimeoutMs()))
                .onErrorMap(error -> !(error instanceof UpstreamCallException), this::networkFailure)
                .block();
        if (response == null || !response.isObject()) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY,
                    "upstream_protocol_error",
                    false,
                    "upstream_invalid_json",
                    null,
                    null
            );
        }
        return response;
    }

    /**
     * 返回阻塞迭代器供 MVC 流式线程逐事件消费。
     * 调用方必须在提交 200 响应前先读取首个事件，以便首帧前完成失败路由切换。
     */
    public Iterator<String> openChatCompletionStream(RuntimeRouteRow route, ObjectNode payload) {
        return openJsonStream(route, payload, chatCompletionsUri(route.getBaseUrl()));
    }

    /**
     * 按渠道维护的完整上游地址发送 JSON SSE 请求；用于 Responses 等非 Chat 流式协议，
     * 不自动追加任何接口路径。
     */
    public Iterator<String> openJsonStream(RuntimeRouteRow route, ObjectNode payload) {
        return openJsonStream(route, payload, URI.create(route.getBaseUrl()));
    }

    private Iterator<String> openJsonStream(RuntimeRouteRow route, ObjectNode payload, URI uri) {
        String credential = decrypt(route);
        Flux<String> stream = webClient.post()
                .uri(uri)
                .headers(headers -> applyHeaders(headers, credential, route.getConfigJson()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .exchangeToFlux(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToFlux(SSE_TYPE)
                                // Comment/ping frames have no data. Reactor map rejects null before
                                // a downstream filter can run, so discard heartbeats before mapping.
                                .filter(event -> event.data() != null)
                                .map(ServerSentEvent::data);
                    }
                    return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMapMany(body -> Flux.error(httpFailure(status, body)));
                })
                // Flux timeout 是逐事件空闲超时；连接和首包仍受 Reactor Netty 与渠道 timeout_ms 约束。
                .timeout(Duration.ofMillis(route.getTimeoutMs()))
                .onErrorMap(error -> !(error instanceof UpstreamCallException), this::networkFailure);
        // MVC 会在首事件后把消费权交给异步流线程。预取一个小批次可让 Reactor 在交接期间
        // 持续接收已经到达的 SSE，避免上游随即断开时把尾部 delta/completed 与连接错误一起丢弃。
        // 迭代器仍逐事件返回，调用方会立即 flush，不会等待预取批次填满。
        return normalizeIteratorFailures(stream.toIterable(STREAM_PREFETCH_EVENTS).iterator());
    }

    /**
     * Reactor 的阻塞迭代边界可能包装订阅异常；这里统一解包并重新分类，
     * 确保首事件读取失败仍能被 Gateway 识别为可重试的 UpstreamCallException。
     */
    private Iterator<String> normalizeIteratorFailures(Iterator<String> delegate) {
        return new NormalizingIterator(delegate);
    }

    /** 保留 Reactor 阻塞迭代器的取消能力，同时统一归一化读取异常。 */
    private final class NormalizingIterator implements Iterator<String>, Runnable {
        private final Iterator<String> delegate;

        private NormalizingIterator(Iterator<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            try {
                return delegate.hasNext();
            } catch (RuntimeException exception) {
                throw normalizeIteratorFailure(exception);
            }
        }

        @Override
        public String next() {
            try {
                return delegate.next();
            } catch (RuntimeException exception) {
                throw normalizeIteratorFailure(exception);
            }
        }

        @Override
        public void run() {
            if (delegate instanceof Runnable cancel) {
                cancel.run();
            }
        }
    }

    private UpstreamCallException normalizeIteratorFailure(RuntimeException exception) {
        Throwable unwrapped = Exceptions.unwrap(exception);
        if (unwrapped instanceof UpstreamCallException upstream) {
            return upstream;
        }
        return networkFailure(unwrapped);
    }

    private String decrypt(RuntimeRouteRow route) {
        try {
            // 明文只存在于构造当前上游请求的局部变量中，不进入路由对象、日志、异常或持久化。
            return credentialCipher.decrypt(route.getEncryptedCredential(), route.getCredentialKeyVersion());
        } catch (RuntimeException exception) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY,
                    "upstream_configuration_error",
                    false,
                    "upstream_credential_unavailable",
                    null,
                    null
            );
        }
    }

    private void applyHeaders(HttpHeaders headers, String credential, String configJson) {
        headers.setBearerAuth(credential);
        Map<String, Object> config = readConfig(configJson);
        setOptionalHeader(headers, "OpenAI-Organization", config.get("organization"));
        setOptionalHeader(headers, "OpenAI-Project", config.get("project"));
    }

    private void setOptionalHeader(HttpHeaders headers, String name, Object value) {
        if (!(value instanceof String text)) {
            return;
        }
        String normalized = text.strip();
        if (!normalized.isEmpty() && normalized.length() <= 256
                && normalized.codePoints().noneMatch(Character::isISOControl)) {
            headers.set(name, normalized);
        }
    }

    private Map<String, Object> readConfig(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, OBJECT_MAP);
        } catch (Exception exception) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY,
                    "upstream_configuration_error",
                    false,
                    "upstream_config_invalid",
                    null,
                    null
            );
        }
    }

    private URI chatCompletionsUri(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!normalized.endsWith("/chat/completions")) {
            normalized += "/chat/completions";
        }
        return URI.create(normalized);
    }

    /** 渠道地址通常已配置到 /v1/images/generations；未配置操作后缀时补齐同一 OpenAI 兼容路径。 */
    private URI imageGenerationsUri(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!normalized.endsWith("/images/generations")) {
            normalized += "/images/generations";
        }
        return URI.create(normalized);
    }

    private UpstreamCallException httpFailure(int upstreamStatus, String body) {
        // 供应商凭证失效只代表当前渠道不可用；首帧前允许网关切换到下一家
        // 供应商，全部候选失败时再向客户端返回统一的 502。
        boolean retryable = upstreamStatus == 401 || upstreamStatus == 403
                || upstreamStatus == 408 || upstreamStatus == 429 || upstreamStatus >= 500;
        HttpStatus clientStatus;
        String code;
        if (upstreamStatus == 408) {
            clientStatus = HttpStatus.GATEWAY_TIMEOUT;
            code = "upstream_timeout";
        } else if (upstreamStatus == 429) {
            clientStatus = HttpStatus.TOO_MANY_REQUESTS;
            code = "upstream_rate_limited";
        } else if (upstreamStatus == 400 || upstreamStatus == 422) {
            clientStatus = HttpStatus.BAD_REQUEST;
            code = safeUpstreamCode(body, "invalid_request_error");
        } else {
            clientStatus = HttpStatus.BAD_GATEWAY;
            code = "upstream_error";
        }
        return new UpstreamCallException(
                clientStatus,
                code,
                retryable,
                "upstream_http_" + upstreamStatus,
                upstreamStatus,
                (upstreamStatus == 400 || upstreamStatus == 422) && isQualityUnsupported(body),
                null
        );
    }

    /** 只把明确的质量参数兼容错误标记给图片降级逻辑，不暴露上游原始错误正文。 */
    private boolean isQualityUnsupported(String body) {
        if (body == null || body.isBlank()) return false;
        String normalized = body.toLowerCase(Locale.ROOT);
        if (!normalized.contains("quality")) return false;
        return normalized.contains("unsupported")
                || normalized.contains("not supported")
                || normalized.contains("not in")
                || normalized.contains("invalid")
                || normalized.contains("不支持")
                || normalized.contains("不在支持范围");
    }

    private String safeUpstreamCode(String body, String fallback) {
        try {
            String code = objectMapper.readTree(body).path("error").path("code").asText("");
            if (!code.isBlank() && code.length() <= 64 && code.matches("[A-Za-z0-9_.-]+")) {
                return code;
            }
        } catch (Exception ignored) {
            // 错误体无法解析时只使用平台固定错误码，绝不拼接上游原文。
        }
        return fallback;
    }

    private UpstreamCallException networkFailure(Throwable error) {
        boolean timeout = hasCause(error, cause -> cause instanceof TimeoutException
                || cause.getClass().getSimpleName().contains("Timeout"));
        return new UpstreamCallException(
                timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                timeout ? "upstream_timeout" : "upstream_connection_error",
                true,
                timeout ? "upstream_timeout" : safeNetworkSummary(error),
                null,
                error
        );
    }

    /** 只记录固定分类，不把异常消息中的 URL、代理或凭证带入业务日志。 */
    private String safeNetworkSummary(Throwable error) {
        if (hasCause(error, cause -> cause instanceof ConnectException)) return "upstream_connection_refused";
        if (hasCause(error, cause -> cause instanceof UnknownHostException)) return "upstream_dns_failure";
        if (hasCause(error, cause -> cause instanceof SSLException)) return "upstream_tls_failure";
        if (hasCause(error, cause -> "PrematureCloseException".equals(cause.getClass().getSimpleName()))) {
            return "upstream_connection_premature_close";
        }
        return "upstream_connection_failure";
    }

    private boolean hasCause(Throwable error, java.util.function.Predicate<Throwable> predicate) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (predicate.test(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    /** 图片文件的短生命周期传输载体；不包含上游凭证和用户请求正文。 */
    public record UploadPart(byte[] content, String filename, MediaType contentType) {
        public UploadPart {
            content = content == null ? new byte[0] : content.clone();
            filename = filename == null || filename.isBlank() ? "image" : filename;
            contentType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType;
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /** 二进制上游响应；数组在构造和读取时均复制，避免跨请求修改。 */
    public record BinaryResponse(byte[] body, MediaType contentType) {
        public BinaryResponse {
            body = body == null ? new byte[0] : body.clone();
            contentType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType;
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
