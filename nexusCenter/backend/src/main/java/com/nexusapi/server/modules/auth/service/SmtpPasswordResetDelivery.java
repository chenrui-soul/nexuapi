package com.nexusapi.server.modules.auth.service;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import jakarta.mail.MessagingException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 基于部署环境 SMTP 配置发送密码重置邮件，不记录收件人与验证码。 */
@Service
public class SmtpPasswordResetDelivery implements PasswordResetDelivery {
    private final JavaMailSender mailSender;
    private final NexusProperties.Auth properties;

    public SmtpPasswordResetDelivery(JavaMailSender mailSender, NexusProperties nexusProperties) {
        this.mailSender = mailSender;
        this.properties = nexusProperties.auth();
    }

    @Override
    public void assertAvailable() {
        if (!properties.passwordResetMailEnabled()
                || properties.passwordResetMailFrom() == null
                || properties.passwordResetMailFrom().isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_UNAVAILABLE);
        }
        try {
            if (mailSender instanceof JavaMailSenderImpl implementation) {
                implementation.testConnection();
            }
        } catch (MessagingException | MailException exception) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_UNAVAILABLE);
        }
    }

    @Override
    public void sendCode(String normalizedEmail, String verificationCode, Duration ttl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.passwordResetMailFrom());
        message.setTo(normalizedEmail);
        message.setSubject("NEXUS API 密码重置验证码");
        message.setText("你的密码重置验证码是：" + verificationCode + "\n\n验证码将在 "
                + ttl.toMinutes() + " 分钟后失效。如非本人操作，请忽略此邮件。");
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_UNAVAILABLE);
        }
    }
}
