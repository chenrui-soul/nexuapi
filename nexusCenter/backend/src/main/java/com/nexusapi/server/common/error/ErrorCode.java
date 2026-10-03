package com.nexusapi.server.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    ADMIN_RECHARGE_DISABLED(HttpStatus.FORBIDDEN, "手动充值已关闭，请在系统设置中开启"),
    AUTH_REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "当前已暂停新用户注册，请稍后再试"),
    AUTH_CAPTCHA_INVALID(HttpStatus.BAD_REQUEST, "验证码错误或已过期"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确"),
    AUTH_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "会话已过期"),
    AUTH_ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账号当前不可用"),
    AUTH_TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请稍后再试"),
    AUTH_PASSWORD_RESET_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "密码找回服务暂时不可用，请稍后再试"),
    AUTH_PASSWORD_RESET_INVALID(HttpStatus.BAD_REQUEST, "邮件验证码错误或已过期"),
    AUTH_CURRENT_PASSWORD_INVALID(HttpStatus.BAD_REQUEST, "当前密码不正确"),
    AUTH_NEW_PASSWORD_SAME(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同"),
    USER_EMAIL_EXISTS(HttpStatus.CONFLICT, "邮箱已注册"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "用户不存在"),
    USER_VERSION_CONFLICT(HttpStatus.CONFLICT, "用户信息已被其他管理员修改，请刷新后重试"),
    USER_SELF_PROTECTION(HttpStatus.CONFLICT, "不能停用当前管理员或移除自己的管理员角色"),
    API_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "API 令牌不存在"),
    API_KEY_LIMIT_REACHED(HttpStatus.CONFLICT, "API 令牌数量已达到上限"),
    API_KEY_VERSION_CONFLICT(HttpStatus.CONFLICT, "API 令牌已被其他操作修改，请刷新后重试"),
    API_KEY_STATUS_CONFLICT(HttpStatus.CONFLICT, "API 令牌当前状态不允许此操作"),
    API_KEY_INVALID(HttpStatus.UNAUTHORIZED, "API 令牌无效"),
    API_KEY_EXPIRED(HttpStatus.UNAUTHORIZED, "API 令牌已过期"),
    API_KEY_IP_NOT_ALLOWED(HttpStatus.FORBIDDEN, "当前来源 IP 不允许使用此密钥"),
    API_KEY_SCOPE_DENIED(HttpStatus.FORBIDDEN, "API 令牌没有访问当前资源的权限"),
    SYSTEM_ACCESS_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "系统访问令牌不存在"),
    SYSTEM_ACCESS_TOKEN_LIMIT_REACHED(HttpStatus.CONFLICT, "系统访问令牌数量已达到上限"),
    SYSTEM_ACCESS_TOKEN_VERSION_CONFLICT(HttpStatus.CONFLICT, "系统访问令牌已被其他操作修改，请刷新后重试"),
    SYSTEM_ACCESS_TOKEN_STATUS_CONFLICT(HttpStatus.CONFLICT, "系统访问令牌当前状态不允许此操作"),
    SYSTEM_ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "系统访问令牌无效或已过期"),
    SYSTEM_ACCESS_TOKEN_SCOPE_DENIED(HttpStatus.FORBIDDEN, "系统访问令牌没有访问当前资源的权限"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "请求超过限制"),
    QUOTA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂时不可用"),
    CONFIGURATION_NOT_FOUND(HttpStatus.NOT_FOUND, "配置项不存在"),
    CONFIGURATION_CONFLICT(HttpStatus.CONFLICT, "配置项已存在或与现有配置冲突"),
    CONFIGURATION_VERSION_CONFLICT(HttpStatus.CONFLICT, "配置已被其他管理员修改，请刷新后重试"),
    MODEL_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "模型当前不可用"),
    PRICE_RULE_NOT_FOUND(HttpStatus.BAD_REQUEST, "请求参数没有匹配到可用计费规则"),
    MODEL_SYNC_IN_PROGRESS(HttpStatus.CONFLICT, "模型同步任务正在执行"),
    MODEL_SYNC_FAILED(HttpStatus.BAD_GATEWAY, "模型市场同步失败"),
    GROUP_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "分组当前不可用"),
    SUBSCRIPTION_GROUP_NOT_ALLOWED(HttpStatus.FORBIDDEN, "当前订阅套餐不允许使用该服务分组"),
    SUBSCRIPTION_MODEL_NOT_ALLOWED(HttpStatus.FORBIDDEN, "当前订阅套餐不允许使用该模型"),
    SUBSCRIPTION_NOT_ACTIVE(HttpStatus.CONFLICT, "当前没有可取消的活动订阅"),
    SUBSCRIPTION_VERSION_CONFLICT(HttpStatus.CONFLICT, "订阅状态已被其他操作修改，请刷新后重试"),
    INSUFFICIENT_BALANCE(HttpStatus.PAYMENT_REQUIRED, "余额不足"),
    API_KEY_CREDIT_LIMIT_EXCEEDED(HttpStatus.PAYMENT_REQUIRED, "API 令牌消费上限不足"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "钱包账户不存在"),
    BILLING_RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "资金冻结单不存在"),
    BILLING_RESERVATION_STATE_CONFLICT(HttpStatus.CONFLICT, "冻结单当前状态不允许此操作"),
    BILLING_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "幂等键已用于不同的资金操作"),
    BILLING_REFUND_EXCEEDS_SETTLED(HttpStatus.CONFLICT, "退款金额超过可退金额"),
    RECHARGE_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "充值订单不存在"),
    RECHARGE_ORDER_STATE_CONFLICT(HttpStatus.CONFLICT, "充值订单当前状态不允许此操作"),
    PAYMENT_MOCK_DISABLED(HttpStatus.FORBIDDEN, "模拟支付未开启"),
    PAYMENT_PROVIDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "正式支付渠道尚未配置"),
    FILE_UPLOAD_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "文件上传服务尚未配置"),
    PAYMENT_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "支付渠道调用失败"),
    PAYMENT_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST, "支付回调签名无效"),
    PAYMENT_CALLBACK_INVALID(HttpStatus.BAD_REQUEST, "支付回调参数无效"),
    PAYMENT_CALLBACK_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "支付回调金额与订单不一致"),
    REQUEST_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "调用日志不存在"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "请求资源不存在"),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "上游请求超时"),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "上游服务异常"),
    UPSTREAM_PROTOCOL_ERROR(HttpStatus.BAD_GATEWAY, "上游响应协议异常"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数错误"),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "权限不足"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
