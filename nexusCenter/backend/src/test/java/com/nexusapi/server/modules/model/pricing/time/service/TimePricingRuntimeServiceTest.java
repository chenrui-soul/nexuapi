package com.nexusapi.server.modules.model.pricing.time.service;

import com.nexusapi.server.modules.model.pricing.time.entity.TimePricingRuleRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TimePricingRuntimeServiceTest {

    @Test
    void normalWindowIncludesStartAndExcludesEnd() {
        TimePricingRuleRow rule = rule("1", "09:00", "12:00");

        assertThat(TimePricingRuntimeService.matches(rule, Instant.parse("2026-08-24T01:00:00Z"))).isTrue();
        assertThat(TimePricingRuntimeService.matches(rule, Instant.parse("2026-08-24T04:00:00Z"))).isFalse();
    }

    @Test
    void crossMidnightWindowUsesSelectedDayAsStartDay() {
        TimePricingRuleRow rule = rule("1", "22:00", "02:00");

        assertThat(TimePricingRuntimeService.matches(rule, Instant.parse("2026-08-24T15:00:00Z"))).isTrue();
        assertThat(TimePricingRuntimeService.matches(rule, Instant.parse("2026-08-24T17:00:00Z"))).isTrue();
        assertThat(TimePricingRuntimeService.matches(rule, Instant.parse("2026-08-24T18:00:00Z"))).isFalse();
    }

    @Test
    void sundayCrossMidnightWrapsToMonday() {
        TimePricingRuleRow rule = rule("7", "23:00", "01:00");

        assertThat(TimePricingRuntimeService.matches(rule, Instant.parse("2026-08-23T15:30:00Z"))).isTrue();
        assertThat(TimePricingRuntimeService.matches(rule, Instant.parse("2026-08-23T16:30:00Z"))).isTrue();
    }

    @Test
    void overlapDetectionCoversCrossDayAndAllowsTouchingBoundaries() {
        TimePricingRuleRow mondayNight = rule("1", "22:00", "02:00");
        TimePricingRuleRow tuesdayEarly = rule("2", "01:00", "03:00");
        TimePricingRuleRow tuesdayAfter = rule("2", "02:00", "03:00");

        assertThat(TimePricingRuntimeService.overlaps(mondayNight, tuesdayEarly)).isTrue();
        assertThat(TimePricingRuntimeService.overlaps(mondayNight, tuesdayAfter)).isFalse();
    }

    private TimePricingRuleRow rule(String days, String start, String end) {
        return new TimePricingRuleRow(
                UUID.randomUUID(), "测试规则", new BigDecimal("1.5"), days,
                LocalTime.parse(start), LocalTime.parse(end), true,
                UUID.randomUUID(), null, null, 0
        );
    }
}

