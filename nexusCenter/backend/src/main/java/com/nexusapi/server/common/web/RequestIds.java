package com.nexusapi.server.common.web;

import org.slf4j.MDC;

public final class RequestIds {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";

    private RequestIds() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}

