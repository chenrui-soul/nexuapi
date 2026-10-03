package com.nexusapi.server.common.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        @JsonProperty("page_size") int pageSize
) {
}

