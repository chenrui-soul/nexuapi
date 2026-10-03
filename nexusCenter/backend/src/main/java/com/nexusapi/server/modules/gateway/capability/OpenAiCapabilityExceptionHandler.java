package com.nexusapi.server.modules.gateway.capability;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.common.web.RequestIds;
import com.nexusapi.server.modules.gateway.capability.audio.controller.OpenAiAudioCapabilityController;
import com.nexusapi.server.modules.gateway.capability.chat.controller.OpenAiChatCapabilityController;
import com.nexusapi.server.modules.gateway.capability.embedding.controller.OpenAiEmbeddingCapabilityController;
import com.nexusapi.server.modules.gateway.capability.image.controller.OpenAiImageCapabilityController;
import com.nexusapi.server.modules.gateway.capability.image.controller.OpenAiImageTaskCapabilityController;
import com.nexusapi.server.modules.gateway.capability.video.controller.OpenAiVideoCapabilityController;
import com.nexusapi.server.modules.fileupload.controller.FileUploadController;
import com.nexusapi.server.modules.gateway.upstream.UpstreamCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/** 仅为对外模型能力输出 OpenAI 风格错误，不改变控制台 `/api/**` 的响应契约。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        OpenAiAudioCapabilityController.class,
        OpenAiChatCapabilityController.class,
        OpenAiEmbeddingCapabilityController.class,
        OpenAiImageCapabilityController.class,
        OpenAiImageTaskCapabilityController.class,
        OpenAiVideoCapabilityController.class,
        FileUploadController.class
})
public class OpenAiCapabilityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCapabilityExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<OpenAiErrorEnvelope> handleBusiness(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        HttpHeaders headers = new HttpHeaders();
        // 客户端经常在同一个接口上声明 Accept: text/event-stream。前置校验或
        // 路由失败尚未提交 SSE 时，错误仍必须固定为 JSON，避免内容协商再次失败。
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (code == ErrorCode.RATE_LIMIT_EXCEEDED) {
            int retryAfter = exception.details() instanceof Number number ? Math.max(1, number.intValue()) : 60;
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        }
        return new ResponseEntity<>(new OpenAiErrorEnvelope(new OpenAiError(
                exception.getMessage(),
                type(code),
                null,
                clientCode(code)
        )), headers, code.status());
    }

    @ExceptionHandler(UpstreamCallException.class)
    ResponseEntity<OpenAiErrorEnvelope> handleUpstream(UpstreamCallException exception) {
        HttpHeaders headers = new HttpHeaders();
        // 上游在首帧前返回 4xx/5xx 时尚未进入 SSE 输出阶段，不能让 Accept 头
        // 把 OpenAiErrorEnvelope 协商成 text/event-stream。
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (exception.clientStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            headers.set(HttpHeaders.RETRY_AFTER, "1");
        }
        return new ResponseEntity<>(new OpenAiErrorEnvelope(new OpenAiError(
                "Upstream request failed",
                exception.clientStatus().is4xxClientError() ? "invalid_request_error" : "api_error",
                null,
                exception.clientCode()
        )), headers, exception.clientStatus());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<OpenAiErrorEnvelope> handleUnreadable(HttpMessageNotReadableException exception) {
        return invalidRequest("Invalid JSON request body", "invalid_json");
    }

    /** 请求在 Controller 参数绑定前因 Content-Type 不匹配被拒绝时，也保持 OpenAI 错误契约。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<OpenAiErrorEnvelope> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return invalidRequest("Unsupported Content-Type", "invalid_content_type");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<OpenAiErrorEnvelope> handleMissingParameter(MissingServletRequestParameterException exception) {
        return invalidRequest(exception.getParameterName() + " is required", "validation_error");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<OpenAiErrorEnvelope> handleUploadLimit(MaxUploadSizeExceededException exception) {
        // multipart 解析可能在 Controller 之前失败，仍保持对外能力统一的 OpenAI 错误契约。
        return invalidRequest("Uploaded file is too large", "file_too_large");
    }

    /**
     * SSE 响应一旦开始写出，客户端断开或异步超时后不能再写 JSON 错误体。
     * 否则 Spring 会尝试用 text/event-stream 转换 OpenAiErrorEnvelope，产生二次
     * No converter 异常并掩盖真正的流断开原因。网关自身已经负责租约、计费和日志收口，
     * 这里仅记录生命周期事件并结束当前响应。
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    void handleAsyncStreamLifecycle(Exception exception) {
        log.debug("Closing OpenAI async stream, type={}", exception.getClass().getSimpleName());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<OpenAiErrorEnvelope> handleUnexpected(Exception exception) {
        // 不记录异常 message/stack，避免第三方客户端异常对象意外携带 Header、文件名或响应原文。
        log.error("Unhandled gateway error, requestId={}, type={}", RequestIds.current(), exception.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new OpenAiErrorEnvelope(new OpenAiError(
                        "Internal server error",
                        "api_error",
                        null,
                        "internal_error"
                )));
    }

    private ResponseEntity<OpenAiErrorEnvelope> invalidRequest(String message, String code) {
        return ResponseEntity.badRequest()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new OpenAiErrorEnvelope(new OpenAiError(
                message, "invalid_request_error", null, code
        )));
    }

    private String type(ErrorCode code) {
        return switch (code) {
            case RATE_LIMIT_EXCEEDED -> "rate_limit_error";
            case INSUFFICIENT_BALANCE, API_KEY_CREDIT_LIMIT_EXCEEDED -> "insufficient_quota";
            case API_KEY_SCOPE_DENIED, API_KEY_IP_NOT_ALLOWED, SUBSCRIPTION_GROUP_NOT_ALLOWED,
                    SUBSCRIPTION_MODEL_NOT_ALLOWED,
                    PERMISSION_DENIED -> "permission_error";
            case QUOTA_UNAVAILABLE, INTERNAL_ERROR, UPSTREAM_ERROR, UPSTREAM_TIMEOUT,
                    UPSTREAM_PROTOCOL_ERROR, FILE_UPLOAD_NOT_CONFIGURED -> "api_error";
            default -> "invalid_request_error";
        };
    }

    private String clientCode(ErrorCode code) {
        return switch (code) {
            case MODEL_NOT_AVAILABLE -> "model_not_found";
            case GROUP_NOT_AVAILABLE -> "group_not_found";
            case RATE_LIMIT_EXCEEDED -> "rate_limit_exceeded";
            case INSUFFICIENT_BALANCE -> "insufficient_balance";
            case API_KEY_CREDIT_LIMIT_EXCEEDED -> "insufficient_quota";
            case API_KEY_SCOPE_DENIED -> "permission_denied";
            case SUBSCRIPTION_GROUP_NOT_ALLOWED -> "subscription_group_not_allowed";
            case SUBSCRIPTION_MODEL_NOT_ALLOWED -> "subscription_model_not_allowed";
            case API_KEY_IP_NOT_ALLOWED -> "ip_not_allowed";
            case QUOTA_UNAVAILABLE -> "quota_unavailable";
            default -> code.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    public record OpenAiErrorEnvelope(OpenAiError error) {
    }

    public record OpenAiError(String message, String type, String param, String code) {
    }
}
