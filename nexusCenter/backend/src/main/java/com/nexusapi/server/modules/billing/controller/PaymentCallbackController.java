package com.nexusapi.server.modules.billing.controller;

import com.nexusapi.server.modules.billing.service.PaymentCallbackService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 支付渠道公网回调入口；只接受渠道签名通知，不依赖用户 Cookie 或 CSRF。 */
@RestController
@RequestMapping("/api/v1/payments/callbacks")
public class PaymentCallbackController {
    private final PaymentCallbackService service;
    public PaymentCallbackController(PaymentCallbackService service) { this.service = service; }

    @PostMapping(value = "/alipay/payment", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    String alipayPayment(@RequestParam Map<String, String> parameters) {
        service.processPayment("alipay", new LinkedHashMap<>(parameters));
        return "success";
    }

    @PostMapping(value = "/{provider}/refund", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    String refund(@PathVariable String provider, @RequestParam Map<String, String> parameters) {
        service.processRefund(provider, new LinkedHashMap<>(parameters));
        return "success";
    }
}
