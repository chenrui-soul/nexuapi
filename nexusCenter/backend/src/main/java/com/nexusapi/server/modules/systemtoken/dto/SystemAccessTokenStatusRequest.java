package com.nexusapi.server.modules.systemtoken.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 启用或停用系统访问令牌的乐观锁请求。 */
public record SystemAccessTokenStatusRequest(
        @NotBlank @Pattern(regexp = "active|disabled") String status,
        @Min(0) long version
) {
}
