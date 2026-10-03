package com.nexusapi.server.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 登录用户修改密码时只接收当前密码和新密码，不接受任何用户标识。 */
public record ChangePasswordRequest(
        @JsonProperty("current_password")
        @NotBlank(message = "请输入当前密码")
        @Size(max = 128, message = "当前密码长度不能超过 128 位")
        String currentPassword,

        @JsonProperty("new_password")
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 128, message = "密码长度必须为 8 到 128 位")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[^\\p{Cntrl}]+$", message = "密码必须同时包含字母和数字")
        String newPassword
) {
}
