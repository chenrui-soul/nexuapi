package com.nexusapi.server.modules.gateway.upstream;

import org.springframework.http.HttpStatus;

/** 只保存脱敏分类的上游异常，禁止携带响应原文、Authorization 或渠道凭证明文。 */
public class UpstreamCallException extends RuntimeException {
    private final HttpStatus clientStatus;
    private final String clientCode;
    private final boolean retryable;
    private final String safeSummary;
    private final Integer upstreamStatus;

    public UpstreamCallException(
            HttpStatus clientStatus,
            String clientCode,
            boolean retryable,
            String safeSummary,
            Integer upstreamStatus,
            Throwable cause
    ) {
        super(safeSummary, cause);
        this.clientStatus = clientStatus;
        this.clientCode = clientCode;
        this.retryable = retryable;
        this.safeSummary = safeSummary;
        this.upstreamStatus = upstreamStatus;
    }

    public HttpStatus clientStatus() { return clientStatus; }
    public String clientCode() { return clientCode; }
    public boolean retryable() { return retryable; }
    public String safeSummary() { return safeSummary; }
    public Integer upstreamStatus() { return upstreamStatus; }
}
