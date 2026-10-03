package com.nexusapi.server.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "请输入账号名称")
        @Size(max = 80, message = "账号名称不能超过 80 个字符")
        String name,

        @NotBlank(message = "请输入邮箱地址")
        @Size(max = 320, message = "邮箱地址过长")
        String email,

        @NotBlank(message = "请输入密码")
        @Size(min = 8, max = 128, message = "密码长度必须为 8 到 128 位")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[^\\p{Cntrl}]+$", message = "密码必须同时包含字母和数字")
        String password,

        @JsonProperty("challenge_id")
        @NotBlank(message = "缺少验证码标识")
        String challengeId,

        @JsonProperty("captcha_code")
        @NotBlank(message = "请输入验证码")
        @Pattern(regexp = "^[A-Za-z0-9]{4}$", message = "验证码格式不正确")
        String captchaCode
) {
}
