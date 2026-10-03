package com.nexusapi.server.modules.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** 管理员更新用户展示名称、账号状态和角色的完整快照。 */
public record AdminUserRequest(
        @NotBlank @Size(max = 80) @JsonProperty("display_name") String displayName,
        @NotBlank @Size(max = 24) String status,
        @NotEmpty @Size(max = 3) Set<@NotBlank @Size(max = 32) String> roles,
        @PositiveOrZero Long version
) {
}
