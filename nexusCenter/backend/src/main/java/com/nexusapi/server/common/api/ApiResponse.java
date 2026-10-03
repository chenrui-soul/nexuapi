package com.nexusapi.server.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusapi.server.common.web.RequestIds;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        @JsonProperty("request_id") String requestId
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, RequestIds.current());
    }

    public static ApiResponse<Void> failure(String code, String message, Object details) {
        return new ApiResponse<>(false, null, new ApiError(code, message, details), RequestIds.current());
    }
}

