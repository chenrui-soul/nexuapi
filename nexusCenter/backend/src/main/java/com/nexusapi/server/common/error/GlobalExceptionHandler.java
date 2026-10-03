package com.nexusapi.server.common.error;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.gateway.capability.OpenAiCapabilityExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        return ResponseEntity.status(code.status())
                .body(ApiResponse.failure(code.name(), exception.getMessage(), exception.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(ErrorCode.VALIDATION_ERROR.name(), ErrorCode.VALIDATION_ERROR.defaultMessage(), details));
    }

    /** 将控制器方法参数上的 Jakarta Validation 约束失败统一转换为 HTTP 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            int separator = path.lastIndexOf('.');
            String field = separator >= 0 ? path.substring(separator + 1) : path;
            details.putIfAbsent(field, violation.getMessage());
        });
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(ErrorCode.VALIDATION_ERROR.name(), ErrorCode.VALIDATION_ERROR.defaultMessage(), details));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(ErrorCode.PERMISSION_DENIED.status())
                .body(ApiResponse.failure(ErrorCode.PERMISSION_DENIED.name(), ErrorCode.PERMISSION_DENIED.defaultMessage(), null));
    }

    /** 已移除或未定义的 API 不应被通用异常兜底误报为服务器错误。 */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.status())
                .body(ApiResponse.failure(
                        ErrorCode.RESOURCE_NOT_FOUND.name(),
                        ErrorCode.RESOURCE_NOT_FOUND.defaultMessage(),
                        null
                ));
    }

    /**
     * Content-Type 校验发生在 Controller 选择阶段，`/v1/**` 的局部 Advice 此时尚未生效。
     * 网关端点仍须维持 OpenAI 错误契约，控制台 API 保留原有通用响应格式。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        if (request.getRequestURI().startsWith("/v1/")) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OpenAiCapabilityExceptionHandler.OpenAiErrorEnvelope(
                            new OpenAiCapabilityExceptionHandler.OpenAiError(
                                    "Unsupported Content-Type", "invalid_request_error", null, "invalid_content_type"
                            )
                    ));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(ErrorCode.VALIDATION_ERROR.name(),
                        ErrorCode.VALIDATION_ERROR.defaultMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unhandled request error", exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.failure(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.defaultMessage(), null));
    }
}
