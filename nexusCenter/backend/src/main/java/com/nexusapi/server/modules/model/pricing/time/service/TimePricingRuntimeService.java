package com.nexusapi.server.modules.model.pricing.time.service;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.model.pricing.time.entity.TimePricingRuleRow;
import com.nexusapi.server.modules.model.pricing.time.mapper.TimePricingMapper;
import com.nexusapi.server.modules.model.pricing.time.model.TimePricingSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 请求开始时解析一次模型时段倍率；该结果由 RoutePlan 贯穿整个请求生命周期。 */
@Service
public class TimePricingRuntimeService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final TimePricingMapper mapper;

    public TimePricingRuntimeService(TimePricingMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public TimePricingSnapshot resolve(UUID modelId, Instant pricingTime) {
        List<TimePricingRuleRow> matched = mapper.findEnabledRulesForModel(modelId).stream()
                .filter(rule -> matches(rule, pricingTime))
                .toList();
        if (matched.size() > 1) {
            // 管理端已阻止重叠；这里仍然 fail closed，避免人工改库后产生不确定扣费。
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "模型存在重叠的启用时段计费规则", null);
        }
        if (matched.isEmpty()) {
            return TimePricingSnapshot.none(pricingTime);
        }
        TimePricingRuleRow rule = matched.getFirst();
        return new TimePricingSnapshot(
                rule.id(), rule.name(), rule.multiplier(), pricingTime, BUSINESS_ZONE.getId()
        );
    }

    /** 跨天规则把选中星期解释为开始日，例如周一 22:00–02:00 覆盖到周二凌晨。 */
    static boolean matches(TimePricingRuleRow rule, Instant pricingTime) {
        ZonedDateTime local = pricingTime.atZone(BUSINESS_ZONE);
        int currentDay = local.getDayOfWeek().getValue();
        int previousDay = currentDay == 1 ? 7 : currentDay - 1;
        LocalTime currentTime = local.toLocalTime();
        Set<Integer> days = parseDays(rule.daysOfWeekCsv());
        if (rule.startTime().isBefore(rule.endTime())) {
            return days.contains(currentDay)
                    && !currentTime.isBefore(rule.startTime())
                    && currentTime.isBefore(rule.endTime());
        }
        return (days.contains(currentDay) && !currentTime.isBefore(rule.startTime()))
                || (days.contains(previousDay) && currentTime.isBefore(rule.endTime()));
    }

    static Set<Integer> parseDays(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return List.of(csv.split(",")).stream()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(Integer::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 把规则拆成一周内的半开区间，用于准确判断普通和跨天时段是否重叠。 */
    static List<WeeklySegment> segments(String daysCsv, LocalTime startTime, LocalTime endTime) {
        List<WeeklySegment> segments = new ArrayList<>();
        for (int day : parseDays(daysCsv)) {
            if (startTime.isBefore(endTime)) {
                segments.add(new WeeklySegment(day, startTime.toSecondOfDay(), endTime.toSecondOfDay()));
                continue;
            }
            segments.add(new WeeklySegment(day, startTime.toSecondOfDay(), 24 * 60 * 60));
            if (!LocalTime.MIDNIGHT.equals(endTime)) {
                segments.add(new WeeklySegment(day == 7 ? 1 : day + 1, 0, endTime.toSecondOfDay()));
            }
        }
        return segments;
    }

    static boolean overlaps(TimePricingRuleRow left, TimePricingRuleRow right) {
        for (WeeklySegment first : segments(left.daysOfWeekCsv(), left.startTime(), left.endTime())) {
            for (WeeklySegment second : segments(right.daysOfWeekCsv(), right.startTime(), right.endTime())) {
                if (first.dayOfWeek() == second.dayOfWeek()
                        && Math.max(first.startSecond(), second.startSecond())
                        < Math.min(first.endSecond(), second.endSecond())) {
                    return true;
                }
            }
        }
        return false;
    }

    record WeeklySegment(int dayOfWeek, int startSecond, int endSecond) {
    }
}

