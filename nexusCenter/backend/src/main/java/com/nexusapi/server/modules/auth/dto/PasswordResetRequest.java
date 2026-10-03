package com.nexusapi.server.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 使用一次性邮件验证码确认密码重置。 */
public record PasswordResetRequest(
        @NotBlank(message = "请输入邮箱地址")
        @Size(max = 320, message = "邮箱地址过长")
        String email,

        @JsonProperty("reset_id")
        @NotBlank(message = "缺少密码重置标识")
        String resetId,

        @JsonProperty("verification_code")
        @NotBlank(message = "请输入邮件验证码")
        @Pattern(regexp = "^\\d{6}$", message = "邮件验证码必须为 6 位数字")
        String verificationCode,

        @JsonProperty("new_password")
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 128, message = "密码长度必须为 8 到 128 位")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[^\\p{Cntrl}]+$", message = "密码必须同时包含字母和数字")
        String newPassword
) {
}
