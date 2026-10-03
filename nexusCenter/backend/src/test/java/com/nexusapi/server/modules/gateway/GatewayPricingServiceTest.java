package com.nexusapi.server.modules.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.config.PricingProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.gateway.support.GatewayPricingService;
import com.nexusapi.server.modules.gateway.support.OpenAiUsage;
import com.nexusapi.server.modules.model.pricing.time.model.TimePricingSnapshot;
import com.nexusapi.server.modules.routing.model.RuntimeContextTierRow;
import com.nexusapi.server.modules.routing.model.RuntimeGroupRow;
import com.nexusapi.server.modules.routing.model.RuntimeModelRow;
import com.nexusapi.server.modules.routing.model.RuntimePricingRuleRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayPricingServiceTest {

    @Test
    void tokenPricingUsesRatiosCachedTokensAndGroupMultiplier() {
        GatewayPricingService service = service(PricingProperties.Mode.V2);
        RuntimeModelRow model = model(4, "2", 10000, 40000, 5000);

        GatewayPricingService.ChargeDecision decision = service.actualDecision(
                model, group("1.5"), List.of(), List.of(),
                new OpenAiUsage(1000, 100, 200), Map.of(), "req-token"
        );

        assertThat(decision.settlementAmount()).isEqualByComparingTo("0.003900000000");
        assertThat(decision.engineMode()).isEqualTo("v2");
    }

    @Test
    void timeMultiplierIsAppliedBetweenBaseUsageAndGroupMultiplier() {
        GatewayPricingService service = service(PricingProperties.Mode.V2);
        RuntimeModelRow model = model(2, "2", 0, 0, 0);
        UUID timeRuleId = UUID.randomUUID();
        Instant pricingTime = Instant.parse("2026-08-24T12:00:00Z");
        TimePricingSnapshot snapshot = new TimePricingSnapshot(
                timeRuleId, "晚高峰", new BigDecimal("1.25"), pricingTime, "Asia/Shanghai"
        );

        GatewayPricingService.V2Calculation result = service.calculateV2(
                model, group("1.5"), List.of(), List.of(),
                new GatewayPricingService.PricingUsage(0, 0, 0, 3, 0, 0, 1),
                Map.of(), snapshot, RoundingMode.HALF_UP
        );

        assertThat(result.baseUsageAmount()).isEqualByComparingTo("6.000000000000");
        assertThat(result.amount()).isEqualByComparingTo("11.250000000000");
    }

    @Test
    void legacySettlementAlsoUsesTheRequestTimeSnapshot() {
        GatewayPricingService service = service(PricingProperties.Mode.V1);
        RuntimeModelRow model = model(4, "2", 10000, 10000, 10000);
        model.setInputPrice(new BigDecimal("1"));
        TimePricingSnapshot snapshot = new TimePricingSnapshot(
                UUID.randomUUID(), "夜间加价", new BigDecimal("2"),
                Instant.parse("2026-08-24T15:00:00Z"), "Asia/Shanghai"
        );

        GatewayPricingService.ChargeDecision decision = service.actualDecision(
                model, group("1.5"), List.of(), List.of(),
                new OpenAiUsage(1_000_000, 0, 0), Map.of(), "req-v1-time", snapshot
        );

        assertThat(decision.baseUsageAmount()).isEqualByComparingTo("1.000000000000");
        assertThat(decision.settlementAmount()).isEqualByComparingTo("3.000000000000");
        assertThat(decision.timeMultiplier()).isEqualByComparingTo("2");
    }

    @Test
    void tokenPricingChargesAllSevenTokenBucketsWithoutDoubleCounting() {
        GatewayPricingService service = service(PricingProperties.Mode.V2);
        RuntimeModelRow model = model(4, "5", 10000, 50000, 1000);
        model.setAudioInputTokenRatio(30000);
        model.setAudioOutputTokenRatio(70000);
        model.setCacheWrite5mTokenRatio(0);
        model.setCacheWrite1hTokenRatio(0);

        // 1M ordinary input + 0.1M cached + 0.2M/0.1M cache writes + 0.1M audio input
        // + 0.2M ordinary output + 0.1M audio output.
        GatewayPricingService.V2Calculation result = service.calculateV2(
                model, group("0.7"), List.of(), List.of(),
                new GatewayPricingService.PricingUsage(1_500_000, 300_000, 100_000,
                        200_000, 100_000, 100_000, 100_000, 0, 0, 0, 1),
                Map.of(), RoundingMode.HALF_UP
        );

        // 5 * (1 + .1*.1 + .2*1.25 + .1*2 + .1*3 + .2*5 + .1*7) * .7 = 12.11
        assertThat(result.amount()).isEqualByComparingTo("12.110000000000");
        assertThat(result.cacheWrite5mTokenRatio()).isEqualTo(12500);
        assertThat(result.cacheWrite1hTokenRatio()).isEqualTo(20000);
    }

    @Test
    void audioSecondBillingIsIndependentFromVideoSecond() {
        GatewayPricingService service = service(PricingProperties.Mode.V2);
        RuntimeModelRow audio = model(6, "2", 0, 0, 0);
        GatewayPricingService.V2Calculation result = service.calculateV2(
                audio, group("1"), List.of(), List.of(),
                new GatewayPricingService.PricingUsage(0, 0, 0, 0, 1500, 0, 0),
                Map.of(), RoundingMode.HALF_UP
        );
        assertThat(result.billingType()).isEqualTo(6);
        assertThat(result.amount()).isEqualByComparingTo("3.000000000000");
    }

    @Test
    void imageRuleOverridesUnitPriceAndChargesSuccessfulQuantity() {
        GatewayPricingService service = service(PricingProperties.Mode.V2);
        RuntimeModelRow model = model(2, "1", 0, 0, 0);
        RuntimePricingRuleRow rule = new RuntimePricingRuleRow(
                UUID.randomUUID(), 10, "高清方图", "{\"quality\":\"hd\",\"size\":\"1024x1024\"}",
                null, new BigDecimal("2.5"), new BigDecimal("1.2")
        );

        GatewayPricingService.V2Calculation result = service.calculateV2(
                model, group("2"), List.of(rule), List.of(),
                new GatewayPricingService.PricingUsage(0, 0, 0, 3, 0, 0, 1),
                Map.of("quality", "hd", "size", "1024x1024"), RoundingMode.HALF_UP
        );

        assertThat(result.amount()).isEqualByComparingTo("18.000000000000");
        assertThat(result.matchedRuleId()).isEqualTo(rule.id());
    }

    @Test
    void unmatchedRejectRuleFailsClosed() {
        GatewayPricingService service = service(PricingProperties.Mode.V2);
        RuntimeModelRow model = model(2, "1", 0, 0, 0);
        model.setPricingUnmatchedBehavior("reject");
        RuntimePricingRuleRow rule = new RuntimePricingRuleRow(
                UUID.randomUUID(), 10, "仅高清", "{\"quality\":\"hd\"}",
                null, null, BigDecimal.ONE
        );

        assertThatThrownBy(() -> service.calculateV2(
                model, group("1"), List.of(rule), List.of(),
                new GatewayPricingService.PricingUsage(0, 0, 0, 1, 0, 0, 1),
                Map.of("quality", "standard"), RoundingMode.HALF_UP
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.PRICE_RULE_NOT_FOUND));
    }

    @Test
    void videoAndCharacterPricingUseTheirOwnUnits() {
        GatewayPricingService service = service(PricingProperties.Mode.V2);
        RuntimeModelRow video = model(3, "0.5", 0, 0, 0);
        RuntimeModelRow audio = model(5, "10", 0, 0, 0);

        GatewayPricingService.V2Calculation videoResult = service.calculateV2(
                video, group("1"), List.of(), List.of(),
                new GatewayPricingService.PricingUsage(0, 0, 0, 0, 2500, 0, 1),
                Map.of(), RoundingMode.HALF_UP
        );
        GatewayPricingService.V2Calculation audioResult = service.calculateV2(
                audio, group("1"), List.of(), List.of(),
                new GatewayPricingService.PricingUsage(0, 0, 0, 0, 0, 250000, 1),
                Map.of(), RoundingMode.HALF_UP
        );

        assertThat(videoResult.amount()).isEqualByComparingTo("1.250000000000");
        assertThat(audioResult.amount()).isEqualByComparingTo("2.500000000000");
    }

    @Test
    void wholeRequestContextTierUsesAbsoluteTokenRatios() {
        GatewayPricingService service = service(PricingProperties.Mode.V2);
        RuntimeModelRow model = model(4, "1", 10000, 20000, 5000);
        model.setContextTierMode(1);
        RuntimeContextTierRow tier = new RuntimeContextTierRow(
                UUID.randomUUID(), 10, 1000, null, 15000, 20000, 12000, 0, 0
        );

        GatewayPricingService.V2Calculation result = service.calculateV2(
                model, group("1"), List.of(), List.of(tier),
                new GatewayPricingService.PricingUsage(2000, 100, 500, 1, 0, 0, 1),
                Map.of(), RoundingMode.HALF_UP
        );

        assertThat(result.inputTokenRatio()).isEqualTo(15000);
        assertThat(result.outputTokenRatio()).isEqualTo(20000);
        assertThat(result.cachedInputTokenRatio()).isEqualTo(12000);
        assertThat(result.contextTierId()).isEqualTo(tier.id());
    }

    @Test
    void shadowModeKeepsLegacySettlementAndCapturesV2Difference() {
        GatewayPricingService service = service(PricingProperties.Mode.SHADOW);
        RuntimeModelRow model = model(4, "2", 10000, 10000, 10000);
        model.setInputPrice(new BigDecimal("1"));
        model.setOutputPrice(new BigDecimal("1"));
        model.setCachedInputPrice(new BigDecimal("1"));

        GatewayPricingService.ChargeDecision decision = service.actualDecision(
                model, group("1"), List.of(), List.of(),
                new OpenAiUsage(1000, 0, 0), Map.of(), "req-shadow"
        );

        assertThat(decision.settlementAmount()).isEqualByComparingTo("0.00100000");
        assertThat(decision.v2Amount()).isEqualByComparingTo("0.002000000000");
        assertThat(decision.engineMode()).isEqualTo("shadow");
    }

    private GatewayPricingService service(PricingProperties.Mode mode) {
        return new GatewayPricingService(
                new PricingProperties(mode, new BigDecimal("0.000000000001")),
                new ObjectMapper()
        );
    }

    private RuntimeModelRow model(int billingType, String unitPrice, long input, long output, long cached) {
        RuntimeModelRow row = new RuntimeModelRow();
        row.setId(UUID.randomUUID());
        row.setBillingType(billingType);
        row.setUnitPrice(new BigDecimal(unitPrice).setScale(12));
        row.setInputTokenRatio(input);
        row.setOutputTokenRatio(output);
        row.setCachedInputTokenRatio(cached);
        row.setPricingUnmatchedBehavior("base");
        row.setActivePricingVersionId(UUID.randomUUID());
        row.setInputPrice(BigDecimal.ZERO.setScale(10));
        row.setOutputPrice(BigDecimal.ZERO.setScale(10));
        row.setCachedInputPrice(BigDecimal.ZERO.setScale(10));
        return row;
    }

    private RuntimeGroupRow group(String multiplier) {
        RuntimeGroupRow row = new RuntimeGroupRow();
        row.setId(UUID.randomUUID());
        row.setPriceMultiplier(new BigDecimal(multiplier));
        return row;
    }
}
