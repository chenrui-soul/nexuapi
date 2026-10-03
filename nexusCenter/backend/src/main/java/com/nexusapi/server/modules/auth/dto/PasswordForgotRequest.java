package com.nexusapi.server.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 发起找回密码时的公开请求；图形验证码用于阻止批量邮件滥用。 */
public record PasswordForgotRequest(
        @NotBlank(message = "请输入邮箱地址")
        @Size(max = 320, message = "邮箱地址过长")
        String email,

        @JsonProperty("challenge_id")
        @NotBlank(message = "缺少验证码标识")
        String challengeId,

        @JsonProperty("captcha_code")
        @NotBlank(message = "请输入验证码")
        @Pattern(regexp = "^[A-Za-z0-9]{4}$", message = "验证码格式不正确")
        String captchaCode
) {
}
