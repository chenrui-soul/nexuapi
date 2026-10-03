package com.nexusapi.server.modules.auth.service;

import java.time.Duration;

/** 密码重置验证码投递边界，测试环境可替换为内存记录器，禁止向接口返回验证码。 */
public interface PasswordResetDelivery {
    /** 在读取用户数据前确认投递通道可用，避免通过不同错误枚举账号。 */
    void assertAvailable();

    /** 将一次性验证码发送到规范化邮箱，调用方不得记录邮箱与验证码。 */
    void sendCode(String normalizedEmail, String verificationCode, Duration ttl);
}
