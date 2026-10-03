package com.nexusapi.server.modules.routing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 管理员批量替换特殊服务分组授权用户的请求。 */
public record AdminGroupUserGrantRequest(
        @NotNull
        @Size(max = 500)
        @JsonProperty("user_ids")
        List<@NotNull UUID> userIds
) {
}
