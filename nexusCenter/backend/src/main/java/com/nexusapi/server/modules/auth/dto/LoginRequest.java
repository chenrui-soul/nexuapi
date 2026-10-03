package com.nexusapi.server.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "请输入邮箱地址")
        @Size(max = 320, message = "邮箱地址过长")
        String email,

        @NotBlank(message = "请输入密码")
        @Size(max = 128, message = "密码长度不能超过 128 位")
        String password,

        @JsonProperty("challenge_id")
        @NotBlank(message = "缺少验证码标识")
        String challengeId,

        @JsonProperty("captcha_code")
        @NotBlank(message = "请输入验证码")
        @Pattern(regexp = "^[A-Za-z0-9]{4}$", message = "验证码格式不正确")
        String captchaCode,

        boolean remember
) {
}
