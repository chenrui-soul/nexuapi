package com.nexusapi.server.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 系统访问令牌安全链统一返回控制台 JSON 401，不暴露具体鉴权失败原因。 */
@Component
public class JsonSystemAccessTokenEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper;

    public JsonSystemAccessTokenEntryPoint(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(
                ErrorCode.SYSTEM_ACCESS_TOKEN_INVALID.name(),
                ErrorCode.SYSTEM_ACCESS_TOKEN_INVALID.defaultMessage(),
                null
        ));
    }
}
