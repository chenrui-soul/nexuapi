package com.nexusapi.server.modules.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.apikey.security.NexusApiKeyPrincipal;
import com.nexusapi.server.modules.gateway.security.GatewayCallerPrincipal;
import com.nexusapi.server.modules.apikey.service.ApiKeyAuthenticationService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import com.nexusapi.server.modules.gateway.adapter.CapabilityAdapterRegistry;
import com.nexusapi.server.modules.gateway.adapter.ImageCapabilityAdapter;
import com.nexusapi.server.modules.gateway.adapter.TextCapabilityAdapter;
import com.nexusapi.server.modules.gateway.adapter.VideoCapabilityAdapter;
import com.nexusapi.server.modules.gateway.capability.audio.model.OpenAiAudioTranscriptionRequest;
import com.nexusapi.server.modules.gateway.capability.image.model.OpenAiImageGenerationRequest;
import com.nexusapi.server.modules.gateway.support.GatewayPricingService;
import com.nexusapi.server.modules.gateway.support.IpAllowlistMatcher;
import com.nexusapi.server.modules.gateway.support.OpenAiUsage;
import com.nexusapi.server.modules.gateway.support.OpenAiUsageAccumulator;
import com.nexusapi.server.modules.gateway.support.TokenEstimator;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.gateway.upstream.UpstreamCallException;
import com.nexusapi.server.modules.health.service.ChannelHealthService;
import com.nexusapi.server.modules.quota.service.GatewayQuotaService;
import com.nexusapi.server.modules.requestlog.model.GatewayRequestLog;
import com.nexusapi.server.modules.requestlog.model.GatewayUpstreamAttemptLog;
import com.nexusapi.server.modules.requestlog.service.RequestLogService;
import com.nexusapi.server.modules.requestlog.service.RequestPayloadSummaryService;
import com.nexusapi.server.modules.routing.model.RuntimeGroupRow;
import com.nexusapi.server.modules.routing.model.RuntimeModelRow;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import com.nexusapi.server.modules.routing.service.GatewayRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.AUDIO_SPEECH;
import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.AUDIO_TRANSCRIPTION;
import static com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation.RESPONSES;

/**
 * OpenAI 兼容能力的端到端编排层。
 *
 * <p>Controller 只负责 HTTP 契约；本 Service 统一执行权限、限流、路由、资金、上游调用和调用日志。</p>
 */
@Service
public class OpenAiGatewayService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiGatewayService.class);
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00000000");
    private static final String RESERVE_PREFIX = "gateway:reserve:";
    private static final String SETTLE_PREFIX = "gateway:settle:";
    private static final String RELEASE_PREFIX = "gateway:release:";

    private final ObjectMapper objectMapper;
    private final GatewayRoutingService routingService;
    private final GatewayQuotaService quotaService;
    private final GatewayPricingService pricingService;
    private final TokenEstimator tokenEstimator;
    private final BillingService billingService;
    private final OpenAiUpstreamClient upstreamClient;
    private final CapabilityAdapterRegistry adapterRegistry;
    private final ChannelHealthService channelHealthService;
    private final RequestLogService requestLogService;
    private final RequestPayloadSummaryService payloadSummaryService;
    private final ApiKeyAuthenticationService apiKeyAuthenticationService;
    private final NexusProperties.Gateway properties;

    public OpenAiGatewayService(
            ObjectMapper objectMapper,
            GatewayRoutingService routingService,
            GatewayQuotaService quotaService,
            GatewayPricingService pricingService,
            TokenEstimator tokenEstimator,
            BillingService billingService,
            OpenAiUpstreamClient upstreamClient,
            CapabilityAdapterRegistry adapterRegistry,
            ChannelHealthService channelHealthService,
            RequestLogService requestLogService,
            RequestPayloadSummaryService payloadSummaryService,
            ApiKeyAuthenticationService apiKeyAuthenticationService,
            NexusProperties nexusProperties
    ) {
        this.objectMapper = objectMapper;
        this.routingService = routingService;
        this.quotaService = quotaService;
        this.pricingService = pricingService;
        this.tokenEstimator = tokenEstimator;
        this.billingService = billingService;
        this.upstreamClient = upstreamClient;
        this.adapterRegistry = adapterRegistry;
        this.channelHealthService = channelHealthService;
        this.requestLogService = requestLogService;
        this.payloadSummaryService = payloadSummaryService;
        this.apiKeyAuthenticationService = apiKeyAuthenticationService;
        this.properties = nexusProperties.gateway();
    }

    public ObjectNode listModels(GatewayCallerPrincipal principal, ClientRequestMetadata metadata) {
        assertIpAllowed(principal, metadata.ipAddress());
        GatewayQuotaService.ApiKeyLease lease = quotaService.acquireApiKey(principal, 0L, false);
        try {
            ArrayNode data = objectMapper.createArrayNode();
            for (RuntimeModelRow model : routingService.listAvailableModels(principal)) {
                ObjectNode item = data.addObject();
                item.put("id", model.getPublicName());
                item.put("object", "model");
                item.put("created", model.getCreatedAt() == null ? 0L : model.getCreatedAt().getEpochSecond());
                item.put("owned_by", model.getProvider());
            }
            ObjectNode response = objectMapper.createObjectNode();
            response.put("object", "list");
            response.set("data", data);
            return response;
        } finally {
            quotaService.releaseApiKey(lease, 0L);
        }
    }

    public GatewayResult chatCompletions(
            GatewayCallerPrincipal principal,
            ObjectNode request,
            String groupSelector,
            ClientRequestMetadata metadata
    ) {
        payloadSummaryService.captureRequest(metadata.requestId(), request);
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请求体必须是 JSON 对象", null);
        }
        assertIpAllowed(principal, metadata.ipAddress());
        validateChatRequest(request);
        if (routingService.isImageModel(request.path("model").asText(null))) {
            return imageChatCompletions(principal, request, groupSelector, metadata);
        }
        boolean streaming = request.path("stream").asBoolean(false);
        GatewayRoutingService.RoutePlan plan = routingService.resolve(
                principal,
                request.path("model").asText(null),
                groupSelector,
                streaming
        );
        long estimatedInput = tokenEstimator.estimateInputTokens(request);
        long maxOutput = tokenEstimator.resolveMaxOutputTokens(
                request,
                plan.model(),
                properties.defaultMaxOutputTokens()
        );
        long reservedTokens = safeAdd(estimatedInput, maxOutput);
        GatewayQuotaService.ApiKeyLease apiKeyLease = quotaService.acquireApiKey(
                principal, reservedTokens, true, plan.subscriptionId(), plan.subscriptionConcurrencyLimit()
        );
        BillingReservation reservation;
        try {
            reservation = reserveIfNeeded(principal, plan, metadata.requestId(), estimatedInput, maxOutput);
        } catch (RuntimeException exception) {
            quotaService.releaseApiKey(apiKeyLease, 0L);
            throw exception;
        }

        if (streaming) {
            return prepareStream(
                    principal, request, plan, metadata, estimatedInput, apiKeyLease, reservation,
                    StreamProtocol.CHAT_COMPLETIONS
            );
        }
        return executeJson(principal, request, plan, metadata, estimatedInput, apiKeyLease, reservation);
    }

    /**
     * 将只支持 Chat Completions 的桌面客户端请求桥接到图片生成能力。
     * 图片仍按原能力路由和按张计费，返回值仅包装为客户端可展示的 Markdown。
     */
    private GatewayResult imageChatCompletions(
            GatewayCallerPrincipal principal,
            ObjectNode request,
            String groupSelector,
            ClientRequestMetadata metadata
    ) {
        String publicModel = request.path("model").asText();
        OpenAiImageGenerationRequest imageRequest = OpenAiImageGenerationRequest.create(
                objectMapper,
                publicModel,
                imagePrompt(request),
                "1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        JsonNode imageResponse = imageGenerations(principal, imageRequest, groupSelector, metadata);
        String content = imageMarkdown(imageResponse);
        long created = imageResponse.path("created").asLong(Instant.now().getEpochSecond());
        String completionId = "chatcmpl-" + metadata.requestId();
        if (!request.path("stream").asBoolean(false)) {
            return new JsonResult(imageChatJson(completionId, publicModel, created, content));
        }
        StreamingResponseBody body = output -> {
            ObjectNode contentChunk = imageChatChunk(completionId, publicModel, created);
            ObjectNode delta = (ObjectNode) contentChunk.path("choices").path(0).path("delta");
            delta.put("role", "assistant");
            delta.put("content", content);
            writeSse(output, contentChunk.toString(), StreamProtocol.CHAT_COMPLETIONS);

            ObjectNode finishChunk = imageChatChunk(completionId, publicModel, created);
            ((ObjectNode) finishChunk.path("choices").path(0)).put("finish_reason", "stop");
            writeSse(output, finishChunk.toString(), StreamProtocol.CHAT_COMPLETIONS);
            writeSse(output, "[DONE]", StreamProtocol.CHAT_COMPLETIONS);
        };
        return new StreamResult(body);
    }

    private String imagePrompt(ObjectNode request) {
        JsonNode messages = request.path("messages");
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.path(index);
            if (!"user".equals(message.path("role").asText())) continue;
            String prompt = chatTextContent(message.get("content"));
            if (!prompt.isBlank()) return prompt;
        }
        throw validation("图片模型需要非空的 user 消息作为 prompt");
    }

    private String chatTextContent(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            if (!part.isObject()) continue;
            String type = part.path("type").asText();
            if (!"text".equals(type) && !"input_text".equals(type)) continue;
            String value = part.path("text").asText();
            if (value.isBlank()) continue;
            if (!text.isEmpty()) text.append('\n');
            text.append(value);
        }
        return text.toString();
    }

    private String imageMarkdown(JsonNode response) {
        JsonNode data = response.path("data");
        StringBuilder markdown = new StringBuilder();
        for (int index = 0; index < data.size(); index++) {
            JsonNode item = data.path(index);
            String target = item.path("url").asText();
            if (target.isBlank()) target = item.path("image_url").asText();
            if (target.isBlank() && item.path("b64_json").isTextual()) {
                target = "data:image/png;base64," + item.path("b64_json").asText();
            }
            if (target.isBlank()) continue;
            if (!markdown.isEmpty()) markdown.append("\n\n");
            String label = index == 0 ? "Generated image" : "Generated image " + (index + 1);
            markdown.append("![").append(label).append("](").append(target).append(')');
        }
        if (markdown.isEmpty()) throw upstreamProtocol("upstream_image_content_missing");
        return markdown.toString();
    }

    private ObjectNode imageChatJson(String id, String model, long created, String content) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", id);
        response.put("object", "chat.completion");
        response.put("created", created);
        response.put("model", model);
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        choice.put("finish_reason", "stop");
        ObjectNode usage = response.putObject("usage");
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", 0);
        usage.put("total_tokens", 0);
        return response;
    }

    private ObjectNode imageChatChunk(String id, String model, long created) {
        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        choice.putNull("finish_reason");
        return chunk;
    }

    /**
     * Responses 与 Chat 共处文本能力 Controller，但保持独立的上游地址、请求结构和 SSE 事件协议。
     */
    public GatewayResult responses(
            GatewayCallerPrincipal principal,
            ObjectNode request,
            String groupSelector,
            ClientRequestMetadata metadata
    ) {
        payloadSummaryService.captureRequest(metadata.requestId(), request);
        boolean streaming = request.path("stream").asBoolean(false);
        if (!streaming) {
            return new JsonResult(jsonCapability(
                    principal, request, request.path("model").asText(), groupSelector, RESPONSES, null, metadata
            ));
        }

        assertIpAllowed(principal, metadata.ipAddress());
        GatewayRoutingService.RoutePlan plan = routingService.resolveCapability(
                principal, request.path("model").asText(), groupSelector, RESPONSES
        );
        if (!plan.model().isSupportsStreaming()) {
            throw new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE, "该模型不支持流式响应", null);
        }
        long estimatedInput = tokenEstimator.estimateInputTokens(request);
        long maxOutput = tokenEstimator.resolveMaxOutputTokens(
                request, plan.model(), properties.defaultMaxOutputTokens()
        );
        GatewayQuotaService.ApiKeyLease apiKeyLease = quotaService.acquireApiKey(
                principal, safeAdd(estimatedInput, maxOutput), true,
                plan.subscriptionId(), plan.subscriptionConcurrencyLimit()
        );
        BillingReservation reservation;
        try {
            reservation = reserveIfNeeded(
                    principal, plan, metadata.requestId(), estimatedInput, maxOutput
            );
        } catch (RuntimeException exception) {
            quotaService.releaseApiKey(apiKeyLease, 0L);
            throw exception;
        }
        return prepareStream(
                principal, request, plan, metadata, estimatedInput, apiKeyLease, reservation,
                StreamProtocol.RESPONSES
        );
    }

    /**
     * 同步图片生成闭环：鉴权、图片能力路由、按张预冻结、multipart 上游调用、结算和脱敏日志。
     */
    public JsonNode imageGenerations(
            GatewayCallerPrincipal principal,
            OpenAiImageGenerationRequest request,
            String groupSelector,
            ClientRequestMetadata metadata
    ) {
        // 记录图片生成的完整业务入参，便于管理员排查 prompt、尺寸和质量参数。
        // 上传图片只记录数量与字节数，不把文件原文写入日志。
        ObjectNode requestLog = objectMapper.createObjectNode();
        requestLog.put("operation", "image_generation");
        requestLog.put("model", request.model());
        requestLog.put("prompt", request.prompt());
        requestLog.put("n", request.quantity());
        requestLog.put("aspect_ratio", request.aspectRatio());
        requestLog.put("quality", request.quality());
        if (request.resolution() != null) requestLog.put("resolution", request.resolution());
        requestLog.put("reference_image_count", request.images().size());
        requestLog.put("reference_image_bytes", request.images().stream()
                .mapToLong(part -> part.content() == null ? 0L : part.content().length).sum());
        requestLog.put("reference_image_url_count", request.imageUrls().size());
        payloadSummaryService.captureRequest(metadata.requestId(), requestLog);
        assertIpAllowed(principal, metadata.ipAddress());
        GatewayRoutingService.RoutePlan plan = routingService.resolveImage(
                principal, request.model(), groupSelector
        );
        GatewayPricingService.PricingUsage usage = imageUsage(request.quantity());
        Map<String, String> pricingParameters = imagePricingParameters(request);
        GatewayQuotaService.ApiKeyLease apiKeyLease = quotaService.acquireApiKey(
                principal, 0L, true, plan.subscriptionId(), plan.subscriptionConcurrencyLimit()
        );
        BillingReservation reservation;
        try {
            reservation = reserveMediaIfNeeded(
                    principal, plan, metadata.requestId(), usage, pricingParameters
            );
        } catch (RuntimeException exception) {
            quotaService.releaseApiKey(apiKeyLease, 0L);
            throw exception;
        }
        return executeImageJson(
                principal, request, plan, metadata, usage, pricingParameters, apiKeyLease, reservation
        );
    }

    /**
     * 语音合成闭环：JSON 文本请求、二进制音频响应，并按 Unicode 字符数执行媒体计费。
     */
    public BinaryCapabilityResult audioSpeech(
            GatewayCallerPrincipal principal,
            ObjectNode request,
            String modelName,
            String groupSelector,
            ClientRequestMetadata metadata
    ) {
        payloadSummaryService.captureRequest(metadata.requestId(), request);
        assertIpAllowed(principal, metadata.ipAddress());
        GatewayRoutingService.RoutePlan plan = routingService.resolveCapability(
                principal, modelName, groupSelector, AUDIO_SPEECH
        );
        ObjectNode payload = request.deepCopy();
        payload.put("model", plan.candidates().getFirst().getUpstreamModel());
        String input = payload.path("input").asText();
        GatewayPricingService.PricingUsage usage = audioUsage(
                0L, input.codePointCount(0, input.length())
        );
        GatewayQuotaService.ApiKeyLease lease = quotaService.acquireApiKey(
                principal, 0L, true, plan.subscriptionId(), plan.subscriptionConcurrencyLimit()
        );
        BillingReservation reservation;
        try {
            reservation = reserveMediaIfNeeded(principal, plan, metadata.requestId(), usage, Map.of());
        } catch (RuntimeException exception) {
            quotaService.releaseApiKey(lease, 0L);
            throw exception;
        }
        return executeAudioSpeech(principal, payload, plan, metadata, usage, lease, reservation);
    }

    /**
     * 音频转写闭环：multipart 文件上传，固定向上游请求 verbose_json 获取可信时长，
     * 再按客户端 response_format 输出并按实际音频毫秒数结算。
     */
    public BinaryCapabilityResult audioTranscription(
            GatewayCallerPrincipal principal,
            OpenAiAudioTranscriptionRequest request,
            String groupSelector,
            ClientRequestMetadata metadata
    ) {
        payloadSummaryService.captureRequestSummary(metadata.requestId(),
                Map.of("operation", "audio_transcription", "model", request.model(), "response_format", request.responseFormat()),
                request.fileContent() == null ? 0L : request.fileContent().length);
        assertIpAllowed(principal, metadata.ipAddress());
        GatewayRoutingService.RoutePlan plan = routingService.resolveCapability(
                principal, request.model(), groupSelector, AUDIO_TRANSCRIPTION
        );
        Map<String, List<String>> fields = transcriptionFields(
                request, plan.candidates().getFirst().getUpstreamModel()
        );
        // 上游完成前无法取得准确时长；先冻结一秒，结算阶段由账本原子补扣或释放差额。
        GatewayPricingService.PricingUsage reservationUsage = audioUsage(1_000L, 0L);
        GatewayQuotaService.ApiKeyLease lease = quotaService.acquireApiKey(
                principal, 0L, true, plan.subscriptionId(), plan.subscriptionConcurrencyLimit()
        );
        BillingReservation reservation;
        try {
            reservation = reserveMediaIfNeeded(
                    principal, plan, metadata.requestId(), reservationUsage, Map.of()
            );
        } catch (RuntimeException exception) {
            quotaService.releaseApiKey(lease, 0L);
            throw exception;
        }
        return executeAudioTranscription(principal, request, fields, plan, metadata, lease, reservation);
    }

    /**
     * 通用 JSON 对外能力闭环：Responses、视频提交/查询、异步图片任务等接口共用同一套鉴权、路由、扣费和日志。
     * 运行入口由程序枚举固定；api_interfaces 文档及模型文档关联不会参与路由、校验或上游调用。
     */
    public JsonNode jsonCapability(
            GatewayCallerPrincipal principal,
            ObjectNode request,
            String modelName,
            String groupSelector,
            PublicGatewayOperation operation,
            String pathSuffix,
            ClientRequestMetadata metadata
    ) {
        payloadSummaryService.captureRequest(metadata.requestId(), request);
        assertIpAllowed(principal, metadata.ipAddress());
        String capabilityType = operation.capabilityType();
        HttpMethod method = operation.httpMethod();
        String selectedModel = modelName == null || modelName.isBlank()
                ? firstAvailableModel(principal, capabilityType) : modelName;
        GatewayRoutingService.RoutePlan plan = routingService.resolveCapability(
                principal, selectedModel, groupSelector, operation
        );
        ObjectNode payload = request == null ? objectMapper.createObjectNode() : request.deepCopy();
        long videoDurationSeconds = operation == PublicGatewayOperation.VIDEO_CREATE
                ? readVideoDurationSeconds(payload) : 0L;
        payload.put("model", plan.candidates().getFirst().getUpstreamModel());
        boolean embeddingBilling = "embedding".equalsIgnoreCase(capabilityType);
        boolean tokenBilling = "text".equalsIgnoreCase(capabilityType)
                || "multimodal".equalsIgnoreCase(capabilityType)
                || embeddingBilling;
        boolean billableMedia = !tokenBilling && method != HttpMethod.GET;
        long estimatedInput = tokenBilling ? tokenEstimator.estimateInputTokens(payload) : 0L;
        GatewayPricingService.PricingUsage mediaUsage = tokenBilling ? null
                : operation == PublicGatewayOperation.VIDEO_CREATE
                ? videoUsage(videoDurationSeconds) : imageUsage(1);
        Map<String, String> pricingParameters = Map.of();
        GatewayQuotaService.ApiKeyLease lease = quotaService.acquireApiKey(
                principal, tokenBilling ? estimatedInput : 0L, true,
                plan.subscriptionId(), plan.subscriptionConcurrencyLimit()
        );
        BillingReservation reservation;
        try {
            long reservedOutputTokens = embeddingBilling ? 0L
                    : Math.min(properties.defaultMaxOutputTokens(), plan.model().getMaxOutputTokens() == null
                    ? properties.defaultMaxOutputTokens() : plan.model().getMaxOutputTokens());
            reservation = tokenBilling
                    ? reserveIfNeeded(principal, plan, metadata.requestId(), estimatedInput, reservedOutputTokens)
                    : (billableMedia
                    ? reserveMediaIfNeeded(principal, plan, metadata.requestId(), mediaUsage, pricingParameters)
                    : BillingReservation.none());
        } catch (RuntimeException exception) {
            quotaService.releaseApiKey(lease, 0L);
            throw exception;
        }
        Instant startedAt = Instant.now();
        RuntimeRouteRow selected = null;
        UpstreamCallException lastFailure = null;
        String switchReason = null;
        boolean billingClosed = !reservation.present();
        boolean apiKeyReleased = false;
        try {
            int attempts = tokenBilling
                    ? textAttemptCount(plan.candidates())
                    : Math.min(maxAttempts(), plan.candidates().size());
            for (int index = 0; index < attempts; index++) {
                selected = attemptRoute(plan.candidates(), index);
                GatewayQuotaService.ChannelLease channelLease = null;
                Instant attemptStarted = Instant.now();
                try {
                    channelLease = tokenBilling
                            ? acquireTextChannel(selected)
                            : quotaService.acquireChannel(
                                    selected.getChannelId(), selected.getChannelConcurrencyLimit()
                            );
                    // 重试切换渠道时必须同步切换该渠道的上游模型名，避免继续发送首个候选的映射值。
                    payload.put("model", selected.getUpstreamModel());
                    JsonNode response;
                    if ("video".equalsIgnoreCase(capabilityType)) {
                        VideoCapabilityAdapter adapter = adapterRegistry.video(selected);
                        ObjectNode upstreamPayload = adapter.toUpstreamRequest(
                                payload, selected.getUpstreamModel(), operation
                        );
                        JsonNode upstreamResponse = adapter.execute(selected, method, upstreamPayload, pathSuffix);
                        response = adapter.toClientResponse(
                                upstreamResponse, plan.model().getPublicName(), operation
                        );
                    } else if ("image".equalsIgnoreCase(capabilityType)) {
                        ImageCapabilityAdapter adapter = adapterRegistry.image(selected);
                        ObjectNode upstreamPayload = adapter.toUpstreamTaskRequest(
                                payload, selected.getUpstreamModel(), operation
                        );
                        JsonNode upstreamResponse = adapter.executeTask(
                                selected, method, upstreamPayload, pathSuffix
                        );
                        response = adapter.toClientTaskResponse(
                                upstreamResponse, plan.model().getPublicName(), operation
                        );
                    } else {
                        response = upstreamClient.jsonRequest(selected, method, payload, pathSuffix);
                    }
                    payloadSummaryService.captureResponse(metadata.requestId(), response);
                    // JSON 能力已完整收到上游响应，计费、健康记录和日志不继续占用供应商并发槽位。
                    quotaService.releaseChannel(channelLease);
                    channelLease = null;
                    BigDecimal billed;
                    OpenAiUsage usage = emptyUsage();
                    if (tokenBilling) {
                        OpenAiUsageAccumulator accumulator = new OpenAiUsageAccumulator(objectMapper, tokenEstimator, estimatedInput);
                        accumulator.accept(response);
                        usage = accumulator.result();
                        billed = finalizeBilling(principal, reservation, plan, usage, metadata.requestId());
                    } else {
                        billed = billableMedia
                                ? finalizeMediaBilling(principal, reservation, plan, mediaUsage, pricingParameters, metadata.requestId())
                                : ZERO_AMOUNT;
                    }
                    billingClosed = true;
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStarted,
                            "success", null, null, null);
                    recordChannelSuccessSafely(selected, metadata.requestId());
                    quotaService.releaseApiKey(lease, usage.totalTokens());
                    apiKeyReleased = true;
                    recordSuccessfulUseSafely(principal.apiKeyId(), metadata.requestId());
                    recordRequest(principal, plan, selected, metadata, startedAt, HttpStatus.OK.value(), null,
                            usage, billed, false, index, null, switchReason);
                    return restorePublicModel(response, plan.model().getPublicName());
                } catch (BusinessException exception) {
                    boolean retry = exception.errorCode() == ErrorCode.RATE_LIMIT_EXCEEDED
                            && index + 1 < attempts && plan.candidates().size() > 1;
                    if (!retry) {
                        recordRequest(principal, plan, selected, metadata, startedAt,
                                exception.errorCode().status().value(), exception.errorCode().name(),
                                emptyUsage(), ZERO_AMOUNT, false, index,
                                "channel_concurrency_rejected", switchReason);
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, "channel_concurrency");
                } catch (UpstreamCallException exception) {
                    lastFailure = exception;
                    String category = supplierErrorCategory(exception.clientCode(), exception.safeSummary());
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStarted,
                            category == null ? nonSupplierOutcome(exception) : "supplier_failure",
                            exception.upstreamStatus(), category, exception.safeSummary());
                    if (category != null) recordChannelFailureSafely(selected, category, exception.safeSummary(), metadata.requestId());
                    boolean retry = index + 1 < attempts && selected.isRetryable() && exception.retryable();
                    if (!retry) {
                        recordRequest(principal, plan, selected, metadata, startedAt,
                                exception.clientStatus().value(), exception.clientCode(), emptyUsage(), ZERO_AMOUNT,
                                false, index, exception.safeSummary(), switchReason);
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, exception.safeSummary());
                    quotaService.releaseChannel(channelLease);
                    channelLease = null;
                    pauseBeforeSingleRouteRetry(plan.candidates(), metadata.requestId(), index);
                } finally {
                    quotaService.releaseChannel(channelLease);
                }
            }
            throw lastFailure == null ? genericUpstreamFailure() : lastFailure;
        } catch (RuntimeException exception) {
            if (!billingClosed) {
                attachCleanupFailure(exception, () -> releaseAfterFailure(principal, reservation, metadata.requestId()));
            }
            if (!apiKeyReleased) {
                attachCleanupFailure(exception, () -> quotaService.releaseApiKey(lease, 0L));
            }
            throw exception;
        }
    }

    private String firstAvailableModel(GatewayCallerPrincipal principal, String capabilityType) {
        return routingService.listAvailableModels(principal).stream()
                .filter(model -> capabilityType.equalsIgnoreCase(model.getCapabilityType()))
                .map(RuntimeModelRow::getPublicName)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_AVAILABLE));
    }

    private BinaryCapabilityResult executeAudioSpeech(
            GatewayCallerPrincipal principal,
            ObjectNode payload,
            GatewayRoutingService.RoutePlan plan,
            ClientRequestMetadata metadata,
            GatewayPricingService.PricingUsage usage,
            GatewayQuotaService.ApiKeyLease lease,
            BillingReservation reservation
    ) {
        Instant startedAt = Instant.now();
        RuntimeRouteRow selected = null;
        UpstreamCallException lastFailure = null;
        String switchReason = null;
        boolean billingClosed = !reservation.present();
        boolean apiKeyReleased = false;
        try {
            int attempts = Math.min(maxAttempts(), plan.candidates().size());
            for (int index = 0; index < attempts; index++) {
                selected = plan.candidates().get(index);
                GatewayQuotaService.ChannelLease channelLease = null;
                Instant attemptStarted = Instant.now();
                try {
                    channelLease = quotaService.acquireChannel(
                            selected.getChannelId(), selected.getChannelConcurrencyLimit()
                    );
                    payload.put("model", selected.getUpstreamModel());
                    OpenAiUpstreamClient.BinaryResponse response = upstreamClient.binaryJsonRequest(selected, payload);
                    payloadSummaryService.captureResponseSummary(metadata.requestId(),
                            Map.of("operation", "audio_speech", "content_type",
                                    response.contentType() == null ? "unknown" : response.contentType().toString()),
                            response.body() == null ? 0L : response.body().length);
                    // 渠道并发只保护真实上游 I/O；收到响应后立即归还租约，避免结算和日志写库占用渠道槽位。
                    quotaService.releaseChannel(channelLease);
                    channelLease = null;
                    MediaType contentType = speechContentType(
                            response.contentType(), payload.path("response_format").asText("mp3")
                    );
                    BigDecimal billed = finalizeMediaBilling(
                            principal, reservation, plan, usage, Map.of(), metadata.requestId()
                    );
                    billingClosed = true;
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStarted,
                            "success", null, null, null);
                    recordChannelSuccessSafely(selected, metadata.requestId());
                    quotaService.releaseApiKey(lease, 0L);
                    apiKeyReleased = true;
                    recordSuccessfulUseSafely(principal.apiKeyId(), metadata.requestId());
                    recordRequest(principal, plan, selected, metadata, startedAt, HttpStatus.OK.value(), null,
                            emptyUsage(), billed, false, index, null, switchReason);
                    return new BinaryCapabilityResult(response.body(), contentType);
                } catch (BusinessException exception) {
                    boolean retry = exception.errorCode() == ErrorCode.RATE_LIMIT_EXCEEDED
                            && index + 1 < attempts;
                    if (!retry) {
                        recordRequest(principal, plan, selected, metadata, startedAt,
                                exception.errorCode().status().value(), exception.errorCode().name(),
                                emptyUsage(), ZERO_AMOUNT, false, index,
                                "channel_concurrency_rejected", switchReason);
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, "channel_concurrency");
                } catch (UpstreamCallException exception) {
                    lastFailure = exception;
                    String category = supplierErrorCategory(exception.clientCode(), exception.safeSummary());
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStarted,
                            category == null ? nonSupplierOutcome(exception) : "supplier_failure",
                            exception.upstreamStatus(), category, exception.safeSummary());
                    if (category != null) {
                        recordChannelFailureSafely(selected, category, exception.safeSummary(), metadata.requestId());
                    }
                    boolean retry = index + 1 < attempts && selected.isRetryable() && exception.retryable();
                    if (!retry) {
                        recordRequest(principal, plan, selected, metadata, startedAt,
                                exception.clientStatus().value(), exception.clientCode(), emptyUsage(), ZERO_AMOUNT,
                                false, index, exception.safeSummary(), switchReason);
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, exception.safeSummary());
                } finally {
                    quotaService.releaseChannel(channelLease);
                }
            }
            throw lastFailure == null ? genericUpstreamFailure() : lastFailure;
        } catch (RuntimeException exception) {
            if (!billingClosed) {
                attachCleanupFailure(exception, () -> releaseAfterFailure(principal, reservation, metadata.requestId()));
            }
            if (!apiKeyReleased) {
                attachCleanupFailure(exception, () -> quotaService.releaseApiKey(lease, 0L));
            }
            throw exception;
        }
    }

    private BinaryCapabilityResult executeAudioTranscription(
            GatewayCallerPrincipal principal,
            OpenAiAudioTranscriptionRequest request,
            Map<String, List<String>> fields,
            GatewayRoutingService.RoutePlan plan,
            ClientRequestMetadata metadata,
            GatewayQuotaService.ApiKeyLease lease,
            BillingReservation reservation
    ) {
        Instant startedAt = Instant.now();
        RuntimeRouteRow selected = null;
        UpstreamCallException lastFailure = null;
        String switchReason = null;
        boolean billingClosed = !reservation.present();
        boolean apiKeyReleased = false;
        try {
            int attempts = Math.min(maxAttempts(), plan.candidates().size());
            for (int index = 0; index < attempts; index++) {
                selected = plan.candidates().get(index);
                GatewayQuotaService.ChannelLease channelLease = null;
                Instant attemptStarted = Instant.now();
                try {
                    channelLease = quotaService.acquireChannel(
                            selected.getChannelId(), selected.getChannelConcurrencyLimit()
                    );
                    fields.put("model", List.of(selected.getUpstreamModel()));
                    JsonNode upstreamResponse = transcriptionRequestWithCompatibility(
                            selected, fields, request
                    );
                    payloadSummaryService.captureResponse(metadata.requestId(), upstreamResponse);
                    // 上游响应已完整接收，后续转写解析、计费和日志不应继续占用渠道并发租约。
                    quotaService.releaseChannel(channelLease);
                    channelLease = null;
                    long durationMillis = transcriptionDurationMillis(upstreamResponse);
                    GatewayPricingService.PricingUsage actualUsage = audioUsage(durationMillis, 0L);
                    BinaryCapabilityResult clientResponse = transcriptionClientResponse(
                            upstreamResponse, request.responseFormat()
                    );
                    BigDecimal billed = finalizeMediaBilling(
                            principal, reservation, plan, actualUsage, Map.of(), metadata.requestId()
                    );
                    billingClosed = true;
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStarted,
                            "success", null, null, null);
                    recordChannelSuccessSafely(selected, metadata.requestId());
                    quotaService.releaseApiKey(lease, 0L);
                    apiKeyReleased = true;
                    recordSuccessfulUseSafely(principal.apiKeyId(), metadata.requestId());
                    recordRequest(principal, plan, selected, metadata, startedAt, HttpStatus.OK.value(), null,
                            emptyUsage(), billed, false, index, null, switchReason);
                    return clientResponse;
                } catch (BusinessException exception) {
                    boolean retry = exception.errorCode() == ErrorCode.RATE_LIMIT_EXCEEDED
                            && index + 1 < attempts;
                    if (!retry) {
                        recordRequest(principal, plan, selected, metadata, startedAt,
                                exception.errorCode().status().value(), exception.errorCode().name(),
                                emptyUsage(), ZERO_AMOUNT, false, index,
                                "channel_concurrency_rejected", switchReason);
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, "channel_concurrency");
                } catch (UpstreamCallException exception) {
                    lastFailure = exception;
                    String category = supplierErrorCategory(exception.clientCode(), exception.safeSummary());
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStarted,
                            category == null ? nonSupplierOutcome(exception) : "supplier_failure",
                            exception.upstreamStatus(), category, exception.safeSummary());
                    if (category != null) {
                        recordChannelFailureSafely(selected, category, exception.safeSummary(), metadata.requestId());
                    }
                    boolean retry = index + 1 < attempts && selected.isRetryable() && exception.retryable();
                    if (!retry) {
                        recordRequest(principal, plan, selected, metadata, startedAt,
                                exception.clientStatus().value(), exception.clientCode(), emptyUsage(), ZERO_AMOUNT,
                                false, index, exception.safeSummary(), switchReason);
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, exception.safeSummary());
                } finally {
                    quotaService.releaseChannel(channelLease);
                }
            }
            throw lastFailure == null ? genericUpstreamFailure() : lastFailure;
        } catch (RuntimeException exception) {
            if (!billingClosed) {
                attachCleanupFailure(exception, () -> releaseAfterFailure(principal, reservation, metadata.requestId()));
            }
            if (!apiKeyReleased) {
                attachCleanupFailure(exception, () -> quotaService.releaseApiKey(lease, 0L));
            }
            throw exception;
        }
    }

    /** 仅恢复平台公开模型字段，不把渠道模型名暴露给客户端。 */
    private JsonNode restorePublicModel(JsonNode response, String publicModel) {
        if (response != null && response.isObject() && response.has("model")) {
            ObjectNode copy = ((ObjectNode) response).deepCopy();
            copy.put("model", publicModel);
            return copy;
        }
        return response;
    }

    private JsonNode executeImageJson(
            GatewayCallerPrincipal principal,
            OpenAiImageGenerationRequest request,
            GatewayRoutingService.RoutePlan plan,
            ClientRequestMetadata metadata,
            GatewayPricingService.PricingUsage usage,
            Map<String, String> pricingParameters,
            GatewayQuotaService.ApiKeyLease apiKeyLease,
            BillingReservation reservation
    ) {
        Instant startedAt = Instant.now();
        RuntimeRouteRow selected = null;
        UpstreamCallException lastFailure = null;
        String switchReason = null;
        boolean billingClosed = false;
        boolean apiKeyReleaseAttempted = false;
        try {
            int attempts = Math.min(maxAttempts(), plan.candidates().size());
            for (int index = 0; index < attempts; index++) {
                selected = plan.candidates().get(index);
                GatewayQuotaService.ChannelLease channelLease = null;
                Instant attemptStartedAt = null;
                try {
                    channelLease = quotaService.acquireChannel(
                            selected.getChannelId(), selected.getChannelConcurrencyLimit()
                    );
                    attemptStartedAt = Instant.now();
                    ImageCapabilityAdapter adapter = adapterRegistry.image(selected);
                    JsonNode upstreamResponse = adapter.generate(
                            selected, adapter.toUpstreamFields(request, selected.getUpstreamModel()),
                            request.images(), request.imageUrls()
                    );
                    JsonNode response = adapter.toClientResponse(upstreamResponse, plan.model().getPublicName());
                    payloadSummaryService.captureResponse(metadata.requestId(), response);
                    // 图片响应已从上游读取完成，释放渠道槽位后再执行计费与日志持久化。
                    quotaService.releaseChannel(channelLease);
                    channelLease = null;
                    long generatedQuantity = imageResponseQuantity(response);
                    GatewayPricingService.PricingUsage actualUsage = imageUsage(generatedQuantity);
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStartedAt,
                            "success", null, null, null);
                    recordChannelSuccessSafely(selected, metadata.requestId());
                    BigDecimal billed = finalizeMediaBilling(
                            principal, reservation, plan, actualUsage, pricingParameters, metadata.requestId()
                    );
                    billingClosed = true;
                    apiKeyReleaseAttempted = true;
                    quotaService.releaseApiKey(apiKeyLease, 0L);
                    recordSuccessfulUseSafely(principal.apiKeyId(), metadata.requestId());
                    recordImageRequest(
                            principal, plan, selected, metadata, startedAt, HttpStatus.OK.value(), null,
                            billed, index, null, switchReason
                    );
                    return response;
                } catch (BusinessException exception) {
                    if (exception.errorCode() != ErrorCode.RATE_LIMIT_EXCEEDED || index + 1 >= attempts) {
                        recordImageRequest(
                                principal, plan, selected, metadata, startedAt,
                                exception.errorCode().status().value(), exception.errorCode().name(),
                                ZERO_AMOUNT, index, "channel_concurrency_rejected", switchReason
                        );
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, "channel_concurrency");
                } catch (UpstreamCallException exception) {
                    if (attemptStartedAt != null) {
                        String category = supplierErrorCategory(exception.clientCode(), exception.safeSummary());
                        recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStartedAt,
                                category == null ? nonSupplierOutcome(exception) : "supplier_failure",
                                exception.upstreamStatus(), category, exception.safeSummary());
                        if (category != null) {
                            recordChannelFailureSafely(
                                    selected, category, exception.safeSummary(), metadata.requestId()
                            );
                        }
                    }
                    lastFailure = exception;
                    boolean retry = index + 1 < attempts && selected.isRetryable() && exception.retryable();
                    if (!retry) {
                        recordImageRequest(
                                principal, plan, selected, metadata, startedAt,
                                exception.clientStatus().value(), exception.clientCode(),
                                ZERO_AMOUNT, index, exception.safeSummary(), switchReason
                        );
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, exception.safeSummary());
                } finally {
                    quotaService.releaseChannel(channelLease);
                }
            }
            throw lastFailure == null ? genericUpstreamFailure() : lastFailure;
        } catch (RuntimeException exception) {
            if (!billingClosed) {
                attachCleanupFailure(exception, () -> releaseAfterFailure(
                        principal, reservation, metadata.requestId()
                ));
            }
            if (!apiKeyReleaseAttempted) {
                apiKeyReleaseAttempted = true;
                attachCleanupFailure(exception, () -> quotaService.releaseApiKey(apiKeyLease, 0L));
            }
            throw exception;
        }
    }

    private JsonResult executeJson(
            GatewayCallerPrincipal principal,
            ObjectNode request,
            GatewayRoutingService.RoutePlan plan,
            ClientRequestMetadata metadata,
            long estimatedInput,
            GatewayQuotaService.ApiKeyLease apiKeyLease,
            BillingReservation reservation
    ) {
        Instant startedAt = Instant.now();
        RuntimeRouteRow selected = null;
        UpstreamCallException lastFailure = null;
        String switchReason = null;
        boolean billingClosed = false;
        boolean apiKeyReleaseAttempted = false;
        try {
            int attempts = textAttemptCount(plan.candidates());
            for (int index = 0; index < attempts; index++) {
                selected = attemptRoute(plan.candidates(), index);
                GatewayQuotaService.ChannelLease channelLease = null;
                Instant attemptStartedAt = null;
                OpenAiUsage attemptUsage = emptyUsage();
                try {
                    channelLease = acquireTextChannel(selected);
                    attemptStartedAt = Instant.now();
                    TextCapabilityAdapter adapter = adapterRegistry.text(selected);
                    JsonNode upstreamResponse = adapter.complete(
                            selected, adapter.toUpstreamRequest(request, selected.getUpstreamModel(), false)
                    );
                    JsonNode response = adapter.toClientResponse(upstreamResponse, plan.model().getPublicName());
                    payloadSummaryService.captureResponse(metadata.requestId(), response);
                    // 仅把上游网络调用计入渠道并发；Token 统计、结算和日志均在租约释放后执行。
                    quotaService.releaseChannel(channelLease);
                    channelLease = null;
                    OpenAiUsageAccumulator usageAccumulator = new OpenAiUsageAccumulator(objectMapper, tokenEstimator, estimatedInput);
                    usageAccumulator.accept(response);
                    OpenAiUsage usage = usageAccumulator.result();
                    attemptUsage = usage;
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStartedAt,
                            "success", null, null, null);
                    recordChannelSuccessSafely(selected, metadata.requestId());
                    BigDecimal billed = finalizeBilling(principal, reservation, plan, usage, metadata.requestId());
                    billingClosed = true;
                    // Redis 释放不是天然幂等操作。先标记“已尝试”，避免连接结果不确定时重复递减其他并发请求的计数。
                    apiKeyReleaseAttempted = true;
                    quotaService.releaseApiKey(apiKeyLease, usage.totalTokens());
                    recordSuccessfulUseSafely(principal.apiKeyId(), metadata.requestId());
                    JsonNode clientResponse = response;
                    recordRequest(
                            principal, plan, selected, metadata, startedAt, HttpStatus.OK.value(), null,
                            usage, billed, false, index, null, switchReason
                    );
                    return new JsonResult(clientResponse);
                } catch (BusinessException exception) {
                    if (exception.errorCode() != ErrorCode.RATE_LIMIT_EXCEEDED
                            || index + 1 >= attempts || plan.candidates().size() == 1) {
                        recordRequest(
                                principal, plan, selected, metadata, startedAt, exception.errorCode().status().value(),
                                exception.errorCode().name(), attemptUsage, ZERO_AMOUNT, false, index,
                                "channel_concurrency_rejected", switchReason
                        );
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, "channel_concurrency");
                } catch (UpstreamCallException exception) {
                    if (attemptStartedAt != null) {
                        String category = supplierErrorCategory(exception.clientCode(), exception.safeSummary());
                        recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStartedAt,
                                category == null ? nonSupplierOutcome(exception) : "supplier_failure",
                                exception.upstreamStatus(), category, exception.safeSummary());
                        if (category != null) {
                            recordChannelFailureSafely(selected, category, exception.safeSummary(), metadata.requestId());
                        }
                    }
                    lastFailure = exception;
                    boolean retry = index + 1 < attempts && selected.isRetryable() && exception.retryable();
                    if (!retry) {
                        recordRequest(
                                principal, plan, selected, metadata, startedAt, exception.clientStatus().value(),
                                exception.clientCode(), emptyUsage(), ZERO_AMOUNT, false, index,
                                exception.safeSummary(), switchReason
                        );
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, exception.safeSummary());
                    quotaService.releaseChannel(channelLease);
                    channelLease = null;
                    pauseBeforeSingleRouteRetry(plan.candidates(), metadata.requestId(), index);
                } finally {
                    quotaService.releaseChannel(channelLease);
                }
            }
            throw lastFailure == null ? genericUpstreamFailure() : lastFailure;
        } catch (RuntimeException exception) {
            // 任何未预期异常都必须独立尝试关闭资金和 API 令牌租约；一个清理动作失败不能阻止另一个动作。
            if (!billingClosed) {
                attachCleanupFailure(exception, () -> releaseAfterFailure(principal, reservation, metadata.requestId()));
            }
            if (!apiKeyReleaseAttempted) {
                apiKeyReleaseAttempted = true;
                attachCleanupFailure(exception, () -> quotaService.releaseApiKey(apiKeyLease, 0L));
            }
            throw exception;
        }
    }

    private StreamResult prepareStream(
            GatewayCallerPrincipal principal,
            ObjectNode request,
            GatewayRoutingService.RoutePlan plan,
            ClientRequestMetadata metadata,
            long estimatedInput,
            GatewayQuotaService.ApiKeyLease apiKeyLease,
            BillingReservation reservation,
            StreamProtocol protocol
    ) {
        Instant startedAt = Instant.now();
        String switchReason = null;
        boolean apiKeyReleaseAttempted = false;
        try {
            int attempts = textAttemptCount(plan.candidates());
            for (int index = 0; index < attempts; index++) {
                // 多渠道优先逐个切换；只有一个文本渠道时，首个可见事件前允许按 max-attempts
                // 重试同一渠道，吸收供应商偶发的首包断连。响应一旦提交后仍禁止重试。
                RuntimeRouteRow selected = attemptRoute(plan.candidates(), index);
                GatewayQuotaService.ChannelLease channelLease = null;
                boolean handedOffToStream = false;
                Instant attemptStartedAt = null;
                try {
                    channelLease = acquireTextChannel(selected);
                    attemptStartedAt = Instant.now();
                    TextCapabilityAdapter adapter = adapterRegistry.text(selected);
                    Iterator<String> iterator = protocol == StreamProtocol.CHAT_COMPLETIONS
                            ? adapter.openChatStream(
                                    selected,
                                    adapter.toUpstreamRequest(request, selected.getUpstreamModel(), true)
                            )
                            : adapter.openResponsesStream(
                                    selected,
                                    responsesAttemptRequest(
                                            adapter, request, selected.getUpstreamModel(), plan.candidates(), index
                                    )
                            );
                    List<String> initialEvents = new ArrayList<>();
                    if (!iterator.hasNext()) {
                        throw new UpstreamCallException(
                                HttpStatus.BAD_GATEWAY,
                                "upstream_protocol_error",
                                true,
                                "upstream_empty_stream",
                                null,
                                null
                        );
                    }
                    String firstEvent = iterator.next();
                    if (protocol == StreamProtocol.RESPONSES && adapter.isFailureEvent(firstEvent, true)) {
                        throw responsesFailureEvent();
                    }
                    // 只在首个正常事件到达前重试。收到 response.created 后立即交给 MVC 流线程，
                    // 后续事件实时透传；已经向客户端输出后禁止重放，避免重复或相互矛盾的内容。
                    // v63 的完整响应缓存模式保留在 scripts/release/v63-responses-retry-affinity-escape-20260907。
                    initialEvents.add(firstEvent);
                    StreamingResponseBody body = streamBody(
                            principal, plan, selected, metadata, startedAt, estimatedInput,
                            apiKeyLease, channelLease, reservation, iterator, initialEvents, index, switchReason,
                            attemptStartedAt, protocol, adapter
                    );
                    handedOffToStream = true;
                    return new StreamResult(body);
                } catch (BusinessException exception) {
                    if (exception.errorCode() != ErrorCode.RATE_LIMIT_EXCEEDED
                            || index + 1 >= attempts || plan.candidates().size() == 1) {
                        recordRequest(
                                principal, plan, selected, metadata, startedAt, exception.errorCode().status().value(),
                                exception.errorCode().name(), emptyUsage(), ZERO_AMOUNT, true, index,
                                "channel_concurrency_rejected", switchReason
                        );
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, "channel_concurrency");
                } catch (UpstreamCallException exception) {
                    if (attemptStartedAt != null) {
                        String category = supplierErrorCategory(exception.clientCode(), exception.safeSummary());
                        recordAttempt(selected, plan.model().getId(), metadata.requestId(), index, attemptStartedAt,
                                category == null ? nonSupplierOutcome(exception) : "supplier_failure",
                                exception.upstreamStatus(), category, exception.safeSummary());
                        if (category != null) {
                            recordChannelFailureSafely(selected, category, exception.safeSummary(), metadata.requestId());
                        }
                    }
                    boolean retry = index + 1 < attempts && selected.isRetryable() && exception.retryable();
                    if (!retry) {
                        recordRequest(
                                principal, plan, selected, metadata, startedAt, exception.clientStatus().value(),
                                exception.clientCode(), emptyUsage(), ZERO_AMOUNT, true, index,
                                exception.safeSummary(), switchReason
                        );
                        throw exception;
                    }
                    switchReason = appendReason(switchReason, exception.safeSummary());
                    if (protocol == StreamProtocol.RESPONSES && plan.candidates().size() == 1) {
                        if (index == 0) {
                            switchReason = appendReason(switchReason, "responses_cache_bypass");
                        } else if (index == 1) {
                            switchReason = appendReason(switchReason, "responses_session_affinity_bypass");
                        }
                    }
                    quotaService.releaseChannel(channelLease);
                    channelLease = null;
                    pauseBeforeSingleRouteRetry(plan.candidates(), metadata.requestId(), index);
                } finally {
                    if (!handedOffToStream) {
                        quotaService.releaseChannel(channelLease);
                    }
                }
            }
            throw genericUpstreamFailure();
        } catch (RuntimeException exception) {
            attachCleanupFailure(exception, () -> releaseAfterFailure(principal, reservation, metadata.requestId()));
            if (!apiKeyReleaseAttempted) {
                apiKeyReleaseAttempted = true;
                attachCleanupFailure(exception, () -> quotaService.releaseApiKey(apiKeyLease, 0L));
            }
            throw exception;
        }
    }

    /** Chat 与 Responses 都只预读首事件，后续事件由 MVC 流线程实时透传。 */
    private StreamingResponseBody streamBody(
            GatewayCallerPrincipal principal,
            GatewayRoutingService.RoutePlan plan,
            RuntimeRouteRow selected,
            ClientRequestMetadata metadata,
            Instant startedAt,
            long estimatedInput,
            GatewayQuotaService.ApiKeyLease apiKeyLease,
            GatewayQuotaService.ChannelLease channelLease,
            BillingReservation reservation,
            Iterator<String> iterator,
            List<String> initialEvents,
            int retryCount,
            String switchReason,
            Instant attemptStartedAt,
            StreamProtocol protocol,
            TextCapabilityAdapter adapter
    ) {
        return output -> {
            OpenAiUsageAccumulator accumulator = new OpenAiUsageAccumulator(objectMapper, tokenEstimator, estimatedInput);
            OpenAiUsage usage = emptyUsage();
            BigDecimal billed = ZERO_AMOUNT;
            boolean billingClosed = false;
            int status = HttpStatus.OK.value();
            String errorCode = null;
            String upstreamSummary = null;
            boolean attemptRecorded = false;
            try {
                boolean responsesCompleted = false;
                for (String initialEvent : initialEvents) {
                    String clientEvent = toClientStreamEvent(
                            initialEvent, plan.model().getPublicName(), protocol, adapter
                    );
                    writeSse(output, clientEvent, protocol);
                    accumulator.acceptSseData(clientEvent);
                    if (protocol == StreamProtocol.RESPONSES && adapter.isFailureEvent(initialEvent, true)) {
                        throw responsesFailureEvent();
                    }
                    responsesCompleted = protocol == StreamProtocol.RESPONSES
                            && adapter.isCompletedEvent(initialEvent, true);
                }
                while (!responsesCompleted && iterator.hasNext()) {
                    String data = iterator.next();
                    String clientEvent = toClientStreamEvent(data, plan.model().getPublicName(), protocol, adapter);
                    writeSse(output, clientEvent, protocol);
                    accumulator.acceptSseData(clientEvent);
                    // Responses 的失败是协议内终止事件。首帧已经提交后只能结束当前流，禁止切换渠道。
                    if (protocol == StreamProtocol.RESPONSES && adapter.isFailureEvent(data, true)) {
                        throw responsesFailureEvent();
                    }
                    responsesCompleted = protocol == StreamProtocol.RESPONSES
                            && adapter.isCompletedEvent(data, true);
                }
                // 上游连接正常收尾但没有发送 response.completed 仍属于不完整协议流。
                // 如果直接按成功结算，客户端只能看到半截 SSE，最终报
                // "stream disconnected before completion"。统一转为协议级失败事件。
                if (protocol == StreamProtocol.RESPONSES && !responsesCompleted) {
                    throw new UpstreamCallException(
                            HttpStatus.BAD_GATEWAY,
                            "upstream_protocol_error",
                            true,
                            "upstream_incomplete_stream",
                            null,
                            null
                    );
                }
                usage = accumulator.result();
                if (!attemptRecorded) {
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), retryCount, attemptStartedAt,
                            "success", null, null, null);
                    attemptRecorded = true;
                    recordChannelSuccessSafely(selected, metadata.requestId());
                }
                billed = finalizeBilling(principal, reservation, plan, usage, metadata.requestId());
                billingClosed = true;
                recordSuccessfulUseSafely(principal.apiKeyId(), metadata.requestId());
            } catch (IOException clientDisconnect) {
                status = 499;
                errorCode = "client_disconnected";
                upstreamSummary = "client_disconnected";
                if (!attemptRecorded) {
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), retryCount, attemptStartedAt,
                            "client_cancelled", null, null, "client_disconnected");
                    attemptRecorded = true;
                }
                drain(iterator, accumulator, plan.model().getPublicName(), protocol, adapter);
                usage = accumulator.result();
                billed = finalizeBillingSafely(principal, reservation, plan, usage, metadata.requestId());
                billingClosed = true;
            } catch (RuntimeException streamFailure) {
                UpstreamCallException upstream = streamFailure instanceof UpstreamCallException value ? value : null;
                status = upstream == null ? HttpStatus.BAD_GATEWAY.value() : upstream.clientStatus().value();
                errorCode = upstream == null ? "stream_failed" : upstream.clientCode();
                upstreamSummary = upstream == null ? "stream_failed_after_output" : upstream.safeSummary();
                if (!attemptRecorded) {
                    String category = upstream == null
                            ? null
                            : supplierErrorCategory(upstream.clientCode(), upstream.safeSummary());
                    recordAttempt(selected, plan.model().getId(), metadata.requestId(), retryCount, attemptStartedAt,
                            upstream == null ? "platform_failure"
                                    : (category == null ? nonSupplierOutcome(upstream) : "supplier_failure"),
                            upstream == null ? null : upstream.upstreamStatus(), category, upstreamSummary);
                    if (category != null) {
                        recordChannelFailureSafely(selected, category, upstreamSummary, metadata.requestId());
                    }
                    attemptRecorded = true;
                }
                usage = accumulator.result();
                if (!accumulator.hasGeneratedOutput()
                        || (upstream != null
                        && "upstream_responses_failure_event".equals(upstream.safeSummary()))) {
                    // 首个协议事件、估算输入 Token 和失败终止帧都不构成可收费的生成结果。
                    // 只有已经交付内容后才允许按累积用量结算，避免上游首包/中途无输出断连扣费。
                    releaseAfterFailureSafely(principal, reservation, metadata.requestId());
                    billed = ZERO_AMOUNT;
                } else {
                    billed = finalizeBillingSafely(principal, reservation, plan, usage, metadata.requestId());
                }
                billingClosed = true;
                // HTTP 200 已在首事件提交后锁定，后续上游异常不能再改状态；补发协议级终止事件，
                // 避免客户端把半截流误报为裸连接断开。显式上游 response.failed 已经写出，不重复发送。
                if (!(upstream != null && "upstream_responses_failure_event".equals(upstream.safeSummary()))) {
                    try {
                        if (protocol == StreamProtocol.RESPONSES) {
                            writeSse(
                                    output,
                                    responsesFailureEventJson(plan.model().getPublicName(), errorCode),
                                    StreamProtocol.RESPONSES
                            );
                        } else {
                            writeSse(
                                    output,
                                    chatFailureEventJson(plan.model().getPublicName(), errorCode),
                                    StreamProtocol.CHAT_COMPLETIONS
                            );
                            writeSse(output, "[DONE]", StreamProtocol.CHAT_COMPLETIONS);
                        }
                    } catch (IOException ignored) {
                        // 客户端已经断开时无法补发，finally 仍会完成清理和日志记录。
                    }
                }
            } finally {
                if (!billingClosed) {
                    releaseAfterFailureSafely(principal, reservation, metadata.requestId());
                }
                // 流已经可能提交给客户端，因此各清理动作必须彼此隔离；某一步失败不能跳过后续租约释放和日志记录。
                OpenAiUsage completedUsage = usage;
                BigDecimal completedBilled = billed;
                int completedStatus = status;
                String completedErrorCode = errorCode;
                String completedUpstreamSummary = upstreamSummary;
                payloadSummaryService.captureResponseSummary(metadata.requestId(),
                        Map.of("status_code", completedStatus,
                                "input_tokens", completedUsage.inputTokens(),
                                "output_tokens", completedUsage.outputTokens(),
                                "cached_tokens", completedUsage.cachedInputTokens(),
                                "error_code", completedErrorCode == null ? "" : completedErrorCode),
                        0L);
                runStreamCleanup(metadata.requestId(), () -> cancelStream(iterator));
                runStreamCleanup(metadata.requestId(), () -> quotaService.releaseChannel(channelLease));
                runStreamCleanup(metadata.requestId(), () -> quotaService.releaseApiKey(apiKeyLease, completedUsage.totalTokens()));
                runStreamCleanup(metadata.requestId(), () -> recordRequest(
                            principal, plan, selected, metadata, startedAt, completedStatus, completedErrorCode,
                            completedUsage, completedBilled, true, retryCount, completedUpstreamSummary, switchReason
                ));
            }
        };
    }

    private void drain(
            Iterator<String> iterator,
            OpenAiUsageAccumulator accumulator,
            String publicModel,
            StreamProtocol protocol,
            TextCapabilityAdapter adapter
    ) {
        try {
            while (iterator.hasNext()) {
                accumulator.acceptSseData(toClientStreamEvent(iterator.next(), publicModel, protocol, adapter));
            }
        } catch (RuntimeException ignored) {
            // 客户端已经断开，继续读取只为尽量拿到 usage 并关闭上游；二次失败不再扩大异常面。
        }
    }

    private boolean isResponsesCreatedEvent(String event) {
        if (event == null || event.isBlank()) return false;
        try {
            return "response.created".equals(objectMapper.readTree(event).path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String responsesFailureEventJson(String publicModel, String errorCode) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "response.failed");
        ObjectNode response = root.putObject("response");
        response.put("id", "resp_failed_" + UUID.randomUUID().toString().replace("-", ""));
        response.put("object", "response");
        response.put("model", publicModel);
        response.put("status", "failed");
        ObjectNode error = response.putObject("error");
        error.put("code", errorCode == null || errorCode.isBlank() ? "stream_failed" : errorCode);
        error.put("message", "上游流式响应未能完成");
        return root.toString();
    }

    private String chatFailureEventJson(String publicModel, String errorCode) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("message", "上游流式响应未能完成");
        error.put("type", "api_error");
        error.put("code", errorCode == null || errorCode.isBlank() ? "stream_failed" : errorCode);
        root.put("model", publicModel);
        return root.toString();
    }

    /**
     * Responses clients commonly dispatch SSE by the {@code event:} field before inspecting the JSON body.
     * The Reactor decoder gives us only data, so reconstruct the standard event name from the canonical
     * {@code type} property while retaining the exact JSON payload supplied by the adapter.
     */
    private void writeSse(OutputStream output, String data, StreamProtocol protocol) throws IOException {
        if (protocol == StreamProtocol.RESPONSES) {
            String eventName = responsesEventName(data);
            if (eventName != null) {
                output.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        String normalized = data == null ? "" : data;
        for (String line : normalized.split("\\R", -1)) {
            output.write(("data: " + line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        output.write('\n');
        output.flush();
    }

    private String responsesEventName(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            String type = objectMapper.readTree(data).path("type").asText();
            return type.matches("[A-Za-z0-9_.-]{1,128}") ? type : null;
        } catch (Exception ignored) {
            // A non-JSON upstream frame still passes through as a data frame; it cannot inject SSE fields.
            return null;
        }
    }

    /** 完成或失败后主动取消阻塞迭代订阅，避免终止事件后的传输层尾部异常继续占用连接。 */
    private void cancelStream(Iterator<String> iterator) {
        if (iterator instanceof Runnable cancel) {
            cancel.run();
        }
    }

    /**
     * 校验 Chat Completions 当前入口的必要字段。
     *
     * <p>接口文档 JSON 只用于维护字段说明；真实请求仍由网关入口执行安全校验，避免把文档配置当成可执行代码。</p>
     */
    private void validateChatRequest(ObjectNode request) {
        if (!request.hasNonNull("model") || !request.path("model").isTextual()) {
            throw validation("model 不能为空");
        }
        if (!request.path("messages").isArray() || request.path("messages").isEmpty()) {
            throw validation("messages 必须是非空数组");
        }
        if (request.has("stream") && !request.path("stream").isBoolean()) {
            throw validation("stream 必须是布尔值");
        }
        try {
            if (objectMapper.writeValueAsBytes(request).length > properties.maxInMemoryBytes()) {
                throw validation("请求体超过 Gateway 上限");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw validation("请求 JSON 无法序列化");
        }
    }

    /** 由能力适配器完成具体 SSE 事件归一化；保留旧重载以兼容内部历史调用。 */
    private String toClientStreamEvent(
            String upstreamEvent,
            String publicModel,
            StreamProtocol protocol,
            TextCapabilityAdapter adapter
    ) {
        return adapter.toClientStreamEvent(
                upstreamEvent, publicModel, protocol == StreamProtocol.RESPONSES
        );
    }

    private UpstreamCallException responsesFailureEvent() {
        return new UpstreamCallException(
                HttpStatus.BAD_GATEWAY,
                "upstream_error",
                true,
                "upstream_responses_failure_event",
                null,
                null
        );
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }

    private GatewayPricingService.PricingUsage imageUsage(long quantity) {
        return new GatewayPricingService.PricingUsage(
                0, 0, 0, 0, 0, 0, 0, Math.max(1L, quantity), 0, 0, 0
        );
    }

    /** 视频创建按请求时长计费；缺省值与公开协议一致为 5 秒，非法值在上游调用前拒绝。 */
    private GatewayPricingService.PricingUsage videoUsage(long durationSeconds) {
        return new GatewayPricingService.PricingUsage(
                0, 0, 0, 0, 0, 0, 0,
                0, Math.multiplyExact(durationSeconds, 1_000L), 0, 1
        );
    }

    private long readVideoDurationSeconds(ObjectNode payload) {
        JsonNode duration = payload.get("duration");
        if (duration == null || duration.isNull()) {
            payload.put("duration", 5);
            return 5L;
        }
        if (!duration.isIntegralNumber() || !duration.canConvertToInt()) {
            throw validation("duration 必须是 1 到 15 之间的整数");
        }
        int seconds = duration.intValue();
        if (seconds < 1 || seconds > 15) {
            throw validation("duration 必须是 1 到 15 之间的整数");
        }
        return seconds;
    }

    /** 音频用量同时保留请求次数，按模型价格版本自动选择字符或音频秒数公式。 */
    private GatewayPricingService.PricingUsage audioUsage(long durationMillis, long characterCount) {
        return new GatewayPricingService.PricingUsage(
                0, 0, 0, 0, 0, 0, 0,
                0, Math.max(0L, durationMillis), Math.max(0L, characterCount), 1
        );
    }

    private Map<String, List<String>> transcriptionFields(
            OpenAiAudioTranscriptionRequest request,
            String upstreamModel
    ) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("model", List.of(upstreamModel));
        // 强制 verbose_json 只用于取得上游实际 duration；客户端格式在返回前转换。
        fields.put("response_format", List.of("verbose_json"));
        if (request.language() != null) fields.put("language", List.of(request.language()));
        if (request.prompt() != null) fields.put("prompt", List.of(request.prompt()));
        if (request.temperature() != null) fields.put("temperature", List.of(request.temperature()));
        List<String> granularities = request.timestampGranularities();
        if (("srt".equals(request.responseFormat()) || "vtt".equals(request.responseFormat()))
                && !granularities.contains("segment")) {
            granularities = new java.util.ArrayList<>(granularities);
            granularities.add("segment");
        }
        if (!granularities.isEmpty()) fields.put("timestamp_granularities[]", List.copyOf(granularities));
        return fields;
    }

    /**
     * 部分 OpenAI 兼容供应商不接受 verbose_json。先按旧契约请求以取得 duration，
     * 遇到明确的 400 再降级为最小 json 请求；失败请求不会进入结算。
     */
    private JsonNode transcriptionRequestWithCompatibility(
            RuntimeRouteRow route,
            Map<String, List<String>> fields,
            OpenAiAudioTranscriptionRequest request
    ) {
        OpenAiUpstreamClient.UploadPart upload = new OpenAiUpstreamClient.UploadPart(
                request.fileContent(), request.filename(), request.contentType()
        );
        try {
            return upstreamClient.multipartJsonRequest(route, fields, "file", upload);
        } catch (UpstreamCallException firstFailure) {
            if (firstFailure.upstreamStatus() == null || firstFailure.upstreamStatus() != 400
                    || !fields.containsKey("response_format")
                    || !"verbose_json".equals(fields.get("response_format").getFirst())) {
                throw firstFailure;
            }
            Map<String, List<String>> fallback = new LinkedHashMap<>(fields);
            fallback.put("response_format", List.of("json"));
            return upstreamClient.multipartJsonRequest(route, fallback, "file", upload);
        }
    }

    private MediaType speechContentType(MediaType upstreamType, String responseFormat) {
        if (upstreamType != null && ("audio".equalsIgnoreCase(upstreamType.getType())
                || MediaType.APPLICATION_OCTET_STREAM.includes(upstreamType))) {
            return upstreamType;
        }
        if (upstreamType != null && !MediaType.APPLICATION_OCTET_STREAM.equals(upstreamType)) {
            throw upstreamProtocol("upstream_invalid_audio_content_type");
        }
        return switch (responseFormat.toLowerCase(Locale.ROOT)) {
            case "opus" -> MediaType.parseMediaType("audio/ogg");
            case "aac" -> MediaType.parseMediaType("audio/aac");
            case "flac" -> MediaType.parseMediaType("audio/flac");
            case "wav" -> MediaType.parseMediaType("audio/wav");
            case "pcm" -> MediaType.APPLICATION_OCTET_STREAM;
            default -> MediaType.parseMediaType("audio/mpeg");
        };
    }

    /** verbose_json 的 duration 优先；缺失时使用最后一个 segment.end 作为受控兜底。 */
    private long transcriptionDurationMillis(JsonNode response) {
        BigDecimal seconds = decimal(response.path("duration"));
        if (seconds == null) {
            JsonNode segments = response.path("segments");
            if (segments.isArray() && !segments.isEmpty()) {
                seconds = decimal(segments.get(segments.size() - 1).path("end"));
            }
        }
        if (seconds == null || seconds.signum() <= 0) {
            // 兼容只返回 {text} 的供应商：冻结阶段已按 1 秒保守预留，缺少媒体元数据时
            // 以该快照结算，避免请求成功后因供应商省略 duration 反向制造 502。
            return 1_000L;
        }
        try {
            return seconds.multiply(BigDecimal.valueOf(1000L))
                    .setScale(0, RoundingMode.CEILING)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw upstreamProtocol("upstream_invalid_audio_duration");
        }
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || !node.isNumber()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BinaryCapabilityResult transcriptionClientResponse(JsonNode response, String responseFormat) {
        JsonNode textNode = response.path("text");
        if (!textNode.isTextual()) throw upstreamProtocol("upstream_missing_transcription_text");
        try {
            return switch (responseFormat) {
                case "verbose_json" -> new BinaryCapabilityResult(
                        objectMapper.writeValueAsBytes(response), MediaType.APPLICATION_JSON
                );
                case "text" -> new BinaryCapabilityResult(
                        textNode.asText().getBytes(StandardCharsets.UTF_8),
                        MediaType.parseMediaType("text/plain;charset=UTF-8")
                );
                case "srt" -> new BinaryCapabilityResult(
                        subtitle(response.path("segments"), false).getBytes(StandardCharsets.UTF_8),
                        MediaType.parseMediaType("application/x-subrip;charset=UTF-8")
                );
                case "vtt" -> new BinaryCapabilityResult(
                        subtitle(response.path("segments"), true).getBytes(StandardCharsets.UTF_8),
                        MediaType.parseMediaType("text/vtt;charset=UTF-8")
                );
                default -> {
                    ObjectNode json = objectMapper.createObjectNode();
                    json.put("text", textNode.asText());
                    yield new BinaryCapabilityResult(
                            objectMapper.writeValueAsBytes(json), MediaType.APPLICATION_JSON
                    );
                }
            };
        } catch (IOException exception) {
            throw upstreamProtocol("upstream_transcription_serialization_failed");
        }
    }

    private String subtitle(JsonNode segments, boolean webVtt) {
        if (!segments.isArray() || segments.isEmpty()) {
            throw upstreamProtocol("upstream_missing_transcription_segments");
        }
        StringBuilder output = new StringBuilder(webVtt ? "WEBVTT\n\n" : "");
        int index = 1;
        for (JsonNode segment : segments) {
            BigDecimal start = decimal(segment.path("start"));
            BigDecimal end = decimal(segment.path("end"));
            JsonNode text = segment.path("text");
            if (start == null || end == null || !text.isTextual()) {
                throw upstreamProtocol("upstream_invalid_transcription_segments");
            }
            if (!webVtt) output.append(index++).append('\n');
            output.append(subtitleTime(start, webVtt)).append(" --> ")
                    .append(subtitleTime(end, webVtt)).append('\n')
                    .append(text.asText().strip()).append("\n\n");
        }
        return output.toString();
    }

    private String subtitleTime(BigDecimal seconds, boolean webVtt) {
        long millis;
        try {
            millis = seconds.multiply(BigDecimal.valueOf(1000L))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw upstreamProtocol("upstream_invalid_transcription_timestamp");
        }
        if (millis < 0) throw upstreamProtocol("upstream_invalid_transcription_timestamp");
        long hours = millis / 3_600_000L;
        long minutes = millis % 3_600_000L / 60_000L;
        long secondsPart = millis % 60_000L / 1_000L;
        long milliseconds = millis % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d",
                hours, minutes, secondsPart, webVtt ? '.' : ',', milliseconds);
    }

    private UpstreamCallException upstreamProtocol(String summary) {
        return new UpstreamCallException(
                HttpStatus.BAD_GATEWAY, "upstream_protocol_error", false,
                summary, null, null
        );
    }

    /** 价格规则使用上游已约定的条件键；resolution 统一成规则中的大写档位。 */
    private Map<String, String> imagePricingParameters(OpenAiImageGenerationRequest request) {
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put("quality", request.quality());
        parameters.put("resolution", request.pricingResolution().toUpperCase(java.util.Locale.ROOT));
        return parameters;
    }

    /** 只发送接口文档确认字段；平台公开模型名转换为渠道映射中的上游模型名。 */
    private Map<String, String> imageUpstreamFields(
            OpenAiImageGenerationRequest request,
            String upstreamModel
    ) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("model", upstreamModel);
        fields.put("prompt", request.prompt());
        fields.put("n", String.valueOf(request.quantity()));
        fields.put("aspect_ratio", request.aspectRatio());
        fields.put("quality", request.quality());
        // 当前图片供应商通过 extra_params.resolution 识别分辨率档位。
        if (request.resolution() != null) {
            fields.put("extra_params", "{\"resolution\":\"" + request.resolution() + "\"}");
        }
        return fields;
    }

    /** 只有包含非空 data 数组的图片响应才视为成功，结算数量以实际上游返回图片数为准。 */
    private long imageResponseQuantity(JsonNode response) {
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray() || data.isEmpty() || data.size() > 10) {
            throw new UpstreamCallException(
                    HttpStatus.BAD_GATEWAY, "upstream_protocol_error", false,
                    "upstream_invalid_image_response", null, null
            );
        }
        for (JsonNode item : data) {
            if (!item.isObject()) {
                throw new UpstreamCallException(
                        HttpStatus.BAD_GATEWAY, "upstream_protocol_error", false,
                        "upstream_invalid_image_response", null, null
                );
            }
        }
        return data.size();
    }

    private BillingReservation reserveMediaIfNeeded(
            GatewayCallerPrincipal principal,
            GatewayRoutingService.RoutePlan plan,
            String requestId,
            GatewayPricingService.PricingUsage usage,
            Map<String, String> parameters
    ) {
        GatewayPricingService.ChargeDecision decision = pricingService.mediaReservationDecision(
                plan.model(), plan.group(), plan.pricingRules(), plan.contextTiers(), usage, parameters,
                plan.timePricing()
        );
        BigDecimal amount = decision.settlementAmount();
        if (amount.signum() == 0) return BillingReservation.none();
        BillingService.ReservationResult result = billingService.reserve(
                principal.userId(), principal.apiKeyId(), plan.group().getId(), requestId, amount,
                principal.creditLimit(), RESERVE_PREFIX + requestId,
                Map.of(
                        "public_model", plan.model().getPublicName(),
                        "group_id", plan.group().getId().toString(),
                        "quantity", usage.quantity(),
                        "billing_type", decision.billingType()
                )
        );
        return new BillingReservation(result.reservationId(), amount);
    }

    private BigDecimal finalizeMediaBilling(
            GatewayCallerPrincipal principal,
            BillingReservation reservation,
            GatewayRoutingService.RoutePlan plan,
            GatewayPricingService.PricingUsage usage,
            Map<String, String> parameters,
            String requestId
    ) {
        if (!reservation.present()) return ZERO_AMOUNT;
        GatewayPricingService.ChargeDecision decision = pricingService.mediaActualDecision(
                plan.model(), plan.group(), plan.pricingRules(), plan.contextTiers(), usage, parameters, requestId,
                plan.timePricing()
        );
        BigDecimal actual = decision.settlementAmount();
        if (actual.signum() == 0) {
            billingService.release(
                    principal.userId(), reservation.id(), RELEASE_PREFIX + requestId,
                    Map.of("reason", "zero_charge")
            );
            requestLogService.recordBillingDetail(
                    requestId, principal.userId(), principal.apiKeyId(), plan.model().getId(),
                    plan.group().getId(), plan.group().getPriceMultiplier(), usage, decision, "released"
            );
            return ZERO_AMOUNT;
        }
        try {
            billingService.settle(
                    principal.userId(), reservation.id(), actual, principal.creditLimit(),
                    SETTLE_PREFIX + requestId,
                    Map.of("quantity", usage.quantity(), "billing_type", decision.billingType())
            );
            requestLogService.recordBillingDetail(
                    requestId, principal.userId(), principal.apiKeyId(), plan.model().getId(),
                    plan.group().getId(), plan.group().getPriceMultiplier(), usage, decision, "settled"
            );
            return actual;
        } catch (RuntimeException exception) {
            releaseAfterFailure(principal, reservation, requestId);
            throw exception;
        }
    }

    private void recordImageRequest(
            GatewayCallerPrincipal principal,
            GatewayRoutingService.RoutePlan plan,
            RuntimeRouteRow route,
            ClientRequestMetadata metadata,
            Instant startedAt,
            int status,
            String errorCode,
            BigDecimal billed,
            int retryCount,
            String upstreamSummary,
            String switchReason
    ) {
        recordRequest(
                principal, plan, route, metadata, startedAt, status, errorCode,
                emptyUsage(), billed, false, retryCount, upstreamSummary, switchReason
        );
    }

    private BillingReservation reserveIfNeeded(
            GatewayCallerPrincipal principal,
            GatewayRoutingService.RoutePlan plan,
            String requestId,
            long estimatedInput,
            long maxOutput
    ) {
        GatewayPricingService.ChargeDecision decision = pricingService.reservationDecision(
                plan.model(), plan.group(), plan.pricingRules(), plan.contextTiers(),
                estimatedInput, maxOutput, Map.of(), plan.timePricing()
        );
        BigDecimal amount = decision.settlementAmount();
        if (amount.signum() == 0) {
            return BillingReservation.none();
        }
        BillingService.ReservationResult result = billingService.reserve(
                principal.userId(),
                principal.apiKeyId(),
                plan.group().getId(),
                requestId,
                amount,
                principal.creditLimit(),
                RESERVE_PREFIX + requestId,
                Map.of(
                        "public_model", plan.model().getPublicName(),
                        "group_id", plan.group().getId().toString(),
                        "estimated_input_tokens", estimatedInput,
                        "max_output_tokens", maxOutput
                )
        );
        return new BillingReservation(result.reservationId(), amount);
    }

    private BigDecimal finalizeBilling(
            GatewayCallerPrincipal principal,
            BillingReservation reservation,
            GatewayRoutingService.RoutePlan plan,
            OpenAiUsage usage,
            String requestId
    ) {
        if (!reservation.present()) {
            return ZERO_AMOUNT;
        }
        GatewayPricingService.ChargeDecision decision = pricingService.actualDecision(
                plan.model(), plan.group(), plan.pricingRules(), plan.contextTiers(), usage, Map.of(), requestId,
                plan.timePricing()
        );
        BigDecimal actual = decision.settlementAmount();
        if (actual.signum() == 0) {
            billingService.release(
                    principal.userId(), reservation.id(), RELEASE_PREFIX + requestId,
                    Map.of("reason", "zero_charge")
            );
            requestLogService.recordBillingDetail(
                    requestId, principal.userId(), principal.apiKeyId(), plan.model().getId(), plan.group().getId(),
                    plan.group().getPriceMultiplier(), usage, decision, "released"
            );
            return ZERO_AMOUNT;
        }
        try {
            billingService.settle(
                    principal.userId(), reservation.id(), actual, principal.creditLimit(),
                    SETTLE_PREFIX + requestId,
                    Map.of(
                            "input_tokens", usage.inputTokens(),
                            "output_tokens", usage.outputTokens(),
                            "cached_tokens", usage.cachedInputTokens()
                    )
            );
            requestLogService.recordBillingDetail(
                    requestId, principal.userId(), principal.apiKeyId(), plan.model().getId(), plan.group().getId(),
                    plan.group().getPriceMultiplier(), usage, decision, "settled"
            );
            return actual;
        } catch (RuntimeException exception) {
            // 预冻结按最大输出估算，正常不应补扣失败；若发生异常，释放原冻结避免长期占款。
            releaseAfterFailure(principal, reservation, requestId);
            throw exception;
        }
    }

    private BigDecimal finalizeBillingSafely(
            GatewayCallerPrincipal principal,
            BillingReservation reservation,
            GatewayRoutingService.RoutePlan plan,
            OpenAiUsage usage,
            String requestId
    ) {
        try {
            return finalizeBilling(principal, reservation, plan, usage, requestId);
        } catch (RuntimeException ignored) {
            releaseAfterFailureSafely(principal, reservation, requestId);
            return ZERO_AMOUNT;
        }
    }

    private void releaseAfterFailureSafely(
            GatewayCallerPrincipal principal,
            BillingReservation reservation,
            String requestId
    ) {
        try {
            releaseAfterFailure(principal, reservation, requestId);
        } catch (RuntimeException exception) {
            // 流式响应可能已经提交，只记录安全分类；不输出异常消息、账单元数据或任何上游上下文。
            log.error("Failed to release gateway billing reservation, requestId={}, type={}",
                    requestId, exception.getClass().getName());
        }
    }

    private void releaseAfterFailure(
            GatewayCallerPrincipal principal,
            BillingReservation reservation,
            String requestId
    ) {
        if (!reservation.present()) {
            return;
        }
        try {
            billingService.release(
                    principal.userId(), reservation.id(), RELEASE_PREFIX + requestId,
                    Map.of("reason", "upstream_failed")
            );
        } catch (BusinessException exception) {
            if (exception.errorCode() != ErrorCode.BILLING_RESERVATION_STATE_CONFLICT) {
                throw exception;
            }
        }
    }

    private void attachCleanupFailure(RuntimeException original, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private void runStreamCleanup(String requestId, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            log.error("Gateway stream cleanup failed, requestId={}, type={}",
                    requestId, exception.getClass().getName());
        }
    }

    private void recordSuccessfulUseSafely(UUID apiKeyId, String requestId) {
        if (apiKeyId == null) return;
        try {
            // last_used_at 仅用于管理端观测，不能在上游已成功并完成结算后反向制造 5xx，诱发客户端重复请求和重复扣费。
            apiKeyAuthenticationService.recordSuccessfulUse(apiKeyId);
        } catch (RuntimeException exception) {
            log.error("Failed to update API key last-used time, requestId={}, type={}",
                    requestId, exception.getClass().getName());
        }
    }

    private void recordRequest(
            GatewayCallerPrincipal principal,
            GatewayRoutingService.RoutePlan plan,
            RuntimeRouteRow route,
            ClientRequestMetadata metadata,
            Instant startedAt,
            int status,
            String errorCode,
            OpenAiUsage usage,
            BigDecimal billed,
            boolean streaming,
            int retryCount,
            String upstreamSummary,
            String switchReason
    ) {
        Instant completed = Instant.now();
        GatewayPricingService.SupplierCostSnapshot supplierCost = route == null
                ? new GatewayPricingService.SupplierCostSnapshot(ZERO_AMOUNT, null, null, null, null)
                : pricingService.supplierCost(route, usage);
        BigDecimal grossMargin = billed.subtract(supplierCost.amount()).setScale(12, java.math.RoundingMode.HALF_UP);
        requestLogService.recordAsync(new GatewayRequestLog(
                UUID.randomUUID(),
                metadata.requestId(),
                principal.userId(),
                principal.apiKeyId(),
                plan.model().getId(),
                route == null ? null : route.getSupplierId(),
                route == null ? null : route.getChannelId(),
                null,
                plan.group().getId(),
                plan.model().getPublicName(),
                route == null ? null : route.getUpstreamModel(),
                startedAt,
                completed,
                Math.max(0L, Duration.between(startedAt, completed).toMillis()),
                status,
                safeText(errorCode, 64),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                billed,
                plan.group().getPriceMultiplier(),
                supplierCost.inputPrice(),
                supplierCost.cachedInputPrice(),
                supplierCost.outputPrice(),
                supplierCost.amount(),
                supplierCost.currency(),
                grossMargin,
                supplierErrorCategory(errorCode, upstreamSummary),
                streaming,
                retryCount,
                metadata.ipAddress(),
                metadata.userAgentHash(),
                safeText(upstreamSummary, 1000),
                safeText(switchReason, 500),
                "{}", "{}", "{}", "{}", 0L, 0L
        ));
    }

    /**
     * 每次真实上游请求独立记录，避免“第一家失败、第二家成功”时把第一家失败隐藏。
     * 观测写入由 RequestLogService 降级处理，不能反向改变网关主流程。
     */
    private void recordAttempt(
            RuntimeRouteRow route,
            UUID modelId,
            String requestId,
            int attemptNo,
            Instant startedAt,
            String outcome,
            Integer upstreamStatus,
            String errorCategory,
            String errorSummary
    ) {
        if (route == null || startedAt == null) {
            return;
        }
        Instant completedAt = Instant.now();
        requestLogService.recordAttemptAsync(new GatewayUpstreamAttemptLog(
                UUID.randomUUID(), requestId, route.getSupplierId(), route.getChannelId(),
                null, modelId, attemptNo, startedAt, completedAt,
                Math.max(0L, Duration.between(startedAt, completedAt).toMillis()),
                outcome, upstreamStatus, safeText(errorCategory, 64), safeText(errorSummary, 1000)
        ));
    }

    /**
     * 健康状态属于旁路控制信号，写入失败不能让已经成功的上游请求转成用户错误，
     * 也不能触发同一请求再次调用上游。
     */
    private void recordChannelSuccessSafely(RuntimeRouteRow route, String requestId) {
        if (route == null) {
            return;
        }
        try {
            channelHealthService.recordGatewaySuccess(route.getChannelId());
        } catch (RuntimeException healthFailure) {
            log.warn(
                    "channel_health_success_feedback_failed requestId={} channelId={} type={}",
                    requestId, route.getChannelId(), healthFailure.getClass().getSimpleName()
            );
        }
    }

    /** 只反馈已经归责供应商的固定分类，用户 400/422 和平台内部异常不会污染健康观测计数。 */
    private void recordChannelFailureSafely(
            RuntimeRouteRow route,
            String category,
            String safeSummary,
            String requestId
    ) {
        if (route == null || category == null) {
            return;
        }
        // 分组独立 Key 的鉴权或额度问题只影响当前分组，不能误伤共享该渠道的其他性能分组。
        if (route.isGroupCredentialOverride()
                && ("authentication".equals(category) || "rate_limit".equals(category))) {
            return;
        }
        try {
            channelHealthService.recordGatewayFailure(route.getChannelId(), category, safeSummary);
        } catch (RuntimeException healthFailure) {
            log.warn(
                    "channel_health_failure_feedback_failed requestId={} channelId={} category={} type={}",
                    requestId, route.getChannelId(), category, healthFailure.getClass().getSimpleName()
            );
        }
    }

    private String nonSupplierOutcome(UpstreamCallException exception) {
        Integer status = exception.upstreamStatus();
        return status != null && (status == 400 || status == 422)
                ? "upstream_rejected"
                : "platform_failure";
    }

    /** 只把上游责任错误归到供应商，用户参数和平台配置错误返回 null。 */
    private String supplierErrorCategory(String errorCode, String summary) {
        Integer upstreamStatus = upstreamStatus(summary);
        if (upstreamStatus != null) {
            if (upstreamStatus == 400 || upstreamStatus == 422) {
                return null;
            }
            if (upstreamStatus == 401 || upstreamStatus == 403) {
                return "authentication";
            }
            if (upstreamStatus == 404) {
                return "model_not_found";
            }
            if (upstreamStatus == 408) {
                return "timeout";
            }
            if (upstreamStatus == 429) {
                return "rate_limit";
            }
            if (upstreamStatus >= 500) {
                return "upstream_5xx";
            }
        }
        if (errorCode == null) {
            return null;
        }
        return switch (errorCode) {
            case "upstream_connection_error" -> "network";
            case "upstream_timeout" -> "timeout";
            case "upstream_rate_limited" -> "rate_limit";
            case "upstream_protocol_error" -> "protocol";
            case "stream_failed" -> "stream_interrupted";
            default -> null;
        };
    }

    private Integer upstreamStatus(String summary) {
        if (summary == null || !summary.startsWith("upstream_http_")) {
            return null;
        }
        try {
            return Integer.parseInt(summary.substring("upstream_http_".length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void assertIpAllowed(GatewayCallerPrincipal principal, String clientIp) {
        if (!IpAllowlistMatcher.isAllowed(clientIp, principal.ipAllowlist())) {
            throw new BusinessException(ErrorCode.API_KEY_IP_NOT_ALLOWED);
        }
    }

    private int maxAttempts() {
        return Math.max(1, properties.maxAttempts());
    }

    /**
     * 单渠道原样重试会继续命中同一个提示词缓存或会话分片。首轮失败后逐级移除
     * 仅用于上游缓存/跟踪的字段，模型输入和工具上下文保持不变。
     */
    private ObjectNode responsesAttemptRequest(
            TextCapabilityAdapter adapter,
            ObjectNode request,
            String upstreamModel,
            List<RuntimeRouteRow> candidates,
            int attemptIndex
    ) {
        ObjectNode payload = adapter.toResponsesUpstreamRequest(request, upstreamModel);
        if (candidates.size() != 1 || attemptIndex <= 0) {
            return payload;
        }
        payload.remove(List.of("prompt_cache_key", "prompt_cache_retention"));
        if (attemptIndex >= 2) {
            payload.remove("client_metadata");
        }
        return payload;
    }

    /** 文本调用在仅有一个候选时也使用配置的尝试次数；多候选仍然每条最多尝试一次。 */
    private int textAttemptCount(List<RuntimeRouteRow> candidates) {
        int configured = maxAttempts();
        return candidates.size() == 1 ? configured : Math.min(configured, candidates.size());
    }

    private RuntimeRouteRow attemptRoute(List<RuntimeRouteRow> candidates, int attemptIndex) {
        return candidates.get(attemptIndex % candidates.size());
    }

    private GatewayQuotaService.ChannelLease acquireTextChannel(RuntimeRouteRow route) {
        return quotaService.acquireChannelQueued(
                route.getChannelId(), route.getChannelConcurrencyLimit(), properties.textQueueMaxWait()
        );
    }

    /** 单渠道使用递增退避和抖动，避免供应商故障时所有排队请求持续同步重试。 */
    private void pauseBeforeSingleRouteRetry(
            List<RuntimeRouteRow> candidates,
            String requestId,
            int attemptIndex
    ) {
        if (candidates.size() != 1) return;
        long jitter = Math.floorMod(requestId == null ? 0 : requestId.hashCode(), 501);
        long backoff = 750L << Math.min(attemptIndex, 3);
        try {
            Thread.sleep(Math.min(6_000L, backoff + jitter));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private long safeAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    private String appendReason(String existing, String value) {
        String next = existing == null ? value : existing + ";" + value;
        return safeText(next, 500);
    }

    private String safeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", "_");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private OpenAiUsage emptyUsage() {
        return new OpenAiUsage(0L, 0L, 0L);
    }

    private UpstreamCallException genericUpstreamFailure() {
        return new UpstreamCallException(
                HttpStatus.BAD_GATEWAY,
                "upstream_error",
                false,
                "upstream_unavailable",
                null,
                null
        );
    }

    public sealed interface GatewayResult permits JsonResult, StreamResult {
    }

    public record JsonResult(JsonNode body) implements GatewayResult {
    }

    public record StreamResult(StreamingResponseBody body) implements GatewayResult {
    }

    /** 同一 Controller 下两种文本 SSE 协议的内部边界。 */
    private enum StreamProtocol {
        CHAT_COMPLETIONS,
        RESPONSES
    }

    /** 对外二进制能力响应；正文数组采用防御性复制，避免跨请求共享修改。 */
    public record BinaryCapabilityResult(byte[] body, MediaType contentType) {
        public BinaryCapabilityResult {
            body = body == null ? new byte[0] : body.clone();
            contentType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType;
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private record BillingReservation(UUID id, BigDecimal reservedAmount) {
        static BillingReservation none() {
            return new BillingReservation(null, ZERO_AMOUNT);
        }

        boolean present() {
            return id != null;
        }
    }
}
