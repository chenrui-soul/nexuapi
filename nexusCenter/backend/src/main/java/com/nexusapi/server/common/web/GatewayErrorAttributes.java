package com.nexusapi.server.common.web;

import jakarta.servlet.RequestDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.Arrays;
import java.util.Map;

/** Records the original container error without logging headers, credentials or payloads. */
@Component
public class GatewayErrorAttributes extends DefaultErrorAttributes {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorAttributes.class);

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(request, options);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI, WebRequest.SCOPE_REQUEST);
        if (uri instanceof String path && (path.equals("/v1") || path.startsWith("/v1/"))) {
            Object requestId = request.getAttribute(RequestIds.MDC_KEY, WebRequest.SCOPE_REQUEST);
            if (requestId != null) attributes.put("request_id", requestId);
            Throwable error = getError(request);
            String exceptionType = error == null ? "none" : error.getClass().getName();
            Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE, WebRequest.SCOPE_REQUEST);
            // Never log error messages: firewall messages can contain complete header values.
            String category = category(message instanceof String value ? value : "");
            log.warn("gateway_container_error request_id={} status={} category={} exception={} frames={}",
                    requestId, attributes.get("status"), category, exceptionType,
                    error == null ? "[]" : Arrays.toString(Arrays.stream(error.getStackTrace()).limit(6).toArray()));
        }
        return attributes;
    }

    private static String category(String message) {
        if (message.contains("header value")) return "rejected_header_value";
        if (message.contains("header name")) return "rejected_header_name";
        if (message.contains("not normalized")) return "non_normalized_path";
        if (message.contains("potentially malicious")) return "rejected_path";
        if (message.contains("HTTP method")) return "rejected_method";
        return "container_error";
    }
}
