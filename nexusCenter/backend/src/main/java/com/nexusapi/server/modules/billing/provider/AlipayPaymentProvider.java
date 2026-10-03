package com.nexusapi.server.modules.billing.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.config.PaymentProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 支付宝 RSA2 适配器。所有渠道请求都在事务外执行，避免网络调用持有业务行锁。 */
@Component
public class AlipayPaymentProvider implements PaymentProvider {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final PaymentProperties.Alipay properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AlipayPaymentProvider(PaymentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.alipay();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override public String code() { return "alipay"; }

    @Override
    public PaymentOrderResult createPayment(String orderNo, BigDecimal amount, String subject, String currency) {
        enabled();
        Map<String, String> params = base("alipay.trade.page.pay");
        params.put("biz_content", json(Map.of(
                "product_code", "FAST_INSTANT_TRADE_PAY",
                "out_trade_no", orderNo,
                "total_amount", amount.setScale(2).toPlainString(),
                "subject", subject,
                "body", subject
        )));
        params.put("notify_url", required(properties.notifyUrl(), "ALIPAY_NOTIFY_URL"));
        if (properties.returnUrl() != null && !properties.returnUrl().isBlank()) params.put("return_url", properties.returnUrl());
        String query = signedQuery(params);
        return new PaymentOrderResult(null, properties.gateway() + "?" + query);
    }

    @Override
    public PaymentCallback verifyPaymentCallback(Map<String, String> parameters) {
        enabled();
        if (!verify(parameters)) throw new BusinessException(ErrorCode.PAYMENT_SIGNATURE_INVALID);
        if (!properties.appId().equals(parameters.get("app_id"))) throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        if (properties.sellerId() != null && !properties.sellerId().isBlank()
                && !properties.sellerId().equals(parameters.get("seller_id"))) {
            throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID);
        }
        String outTradeNo = required(parameters.get("out_trade_no"), "out_trade_no");
        String tradeNo = required(parameters.get("trade_no"), "trade_no");
        String status = required(parameters.get("trade_status"), "trade_status");
        BigDecimal amount = decimal(parameters.get("total_amount"));
        String eventId = tradeNo + ":" + status + ":" + parameters.getOrDefault("gmt_payment", "");
        return new PaymentCallback(eventId, outTradeNo, tradeNo, status, amount, parameters.get("app_id"));
    }

    @Override
    public RefundResult requestRefund(String providerOrderId, String outTradeNo, BigDecimal amount, String reason, String outRequestNo) {
        return call("alipay.trade.refund", Map.of("trade_no", providerOrderId, "out_trade_no", outTradeNo,
                "refund_amount", amount.setScale(2).toPlainString(), "refund_reason", reason, "out_request_no", outRequestNo));
    }

    @Override
    public PaymentQueryResult queryPayment(String outTradeNo) {
        ApiResponse response = api("alipay.trade.query", Map.of("out_trade_no", outTradeNo));
        JsonNode body = response.body();
        boolean ok = "10000".equals(body.path("code").asText());
        String status = body.path("trade_status").asText(null);
        boolean paid = ok && ("TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status));
        BigDecimal amount = body.hasNonNull("total_amount") ? decimal(body.path("total_amount").asText()) : null;
        return new PaymentQueryResult(paid, body.path("trade_no").asText(null), body.path("out_trade_no").asText(outTradeNo), amount, status, body.path("code").asText(null));
    }

    @Override
    public RefundResult queryRefund(String providerOrderId, String outTradeNo, String outRequestNo) {
        return call("alipay.trade.fastpay.refund.query", Map.of(
                "trade_no", providerOrderId, "out_trade_no", outTradeNo, "out_request_no", outRequestNo
        ));
    }

    private RefundResult call(String method, Map<String, String> bizContent) {
        ApiResponse response = api(method, bizContent);
        JsonNode body = response.body();
        boolean ok = "10000".equals(body.path("code").asText());
        String refundId = body.path("trade_no").asText(null);
        if (refundId == null) refundId = body.path("out_trade_no").asText(null);
        BigDecimal amount = body.hasNonNull("refund_fee") ? decimal(body.path("refund_fee").asText())
                : body.hasNonNull("refund_amount") ? decimal(body.path("refund_amount").asText()) : null;
        return new RefundResult(ok, refundId, amount, body.path("code").asText(null), body.path("sub_msg").asText(body.path("msg").asText(null)));
    }

    private ApiResponse api(String method, Map<String, String> bizContent) {
        enabled();
        Map<String, String> params = base(method);
        params.put("biz_content", json(bizContent));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.gateway()))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form(signed(params)))).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR);
            String responseKey = method.replace('.', '_') + "_response";
            String signedContent = extractResponseContent(response.body(), responseKey);
            JsonNode root = objectMapper.readTree(response.body());
            String signature = root.path("sign").asText(null);
            if (!verifyContent(signedContent, signature)) throw new BusinessException(ErrorCode.PAYMENT_SIGNATURE_INVALID);
            return new ApiResponse(root.path(responseKey));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        }
    }

    private Map<String, String> base(String method) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", required(properties.appId(), "ALIPAY_APP_ID"));
        params.put("method", method);
        params.put("format", "JSON");
        params.put("return_url", properties.returnUrl());
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", LocalDateTime.now().format(TIME));
        params.put("version", "1.0");
        return params;
    }

    private Map<String, String> signed(Map<String, String> params) { params.put("sign", sign(canonical(params))); return params; }
    private String signedQuery(Map<String, String> params) { return form(signed(params)); }

    private boolean verify(Map<String, String> params) {
        String sign = params.get("sign");
        if (sign == null || properties.alipayPublicKey() == null || properties.alipayPublicKey().isBlank()) return false;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey(properties.alipayPublicKey()));
            verifier.update(canonical(params).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) { return false; }
    }

    private boolean verifyContent(String content, String signature) {
        if (content == null || signature == null) return false;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey(properties.alipayPublicKey()));
            verifier.update(content.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception exception) { return false; }
    }

    /** 提取支付宝原始 JSON 中被签名的 response 节点，避免重新序列化改变字段顺序。 */
    private String extractResponseContent(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        start += marker.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '{') throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        boolean string = false, escaped = false; int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (string) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') string = false; continue; }
            if (c == '"') string = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(start, i + 1);
        }
        throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR);
    }

    private record ApiResponse(JsonNode body) {}

    private String canonical(Map<String, String> params) {
        return new TreeMap<>(params).entrySet().stream().filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .filter(e -> !"sign".equals(e.getKey()) && !"sign_type".equals(e.getKey()))
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }

    private String form(Map<String, String> params) { return params.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&")); }
    private String enc(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private String sign(String content) { try { Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(privateKey(properties.privateKey())); s.update(content.getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(s.sign()); } catch (Exception e) { throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED); } }
    private PrivateKey privateKey(String value) throws Exception { return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decodeKey(value))); }
    private PublicKey publicKey(String value) throws Exception { return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decodeKey(value))); }
    private byte[] decodeKey(String value) { return Base64.getDecoder().decode(value.replaceAll("-----BEGIN (PRIVATE|PUBLIC) KEY-----", "").replaceAll("-----END (PRIVATE|PUBLIC) KEY-----", "").replaceAll("\\s", "")); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR); } }
    private BigDecimal decimal(String value) { try { return new BigDecimal(required(value, "amount")); } catch (Exception e) { throw new BusinessException(ErrorCode.PAYMENT_CALLBACK_INVALID); } }
    private String required(String value, String name) { if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED, name + " 未配置", null); return value; }
    private void enabled() { if (!properties.enabled()) throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED); }
}
