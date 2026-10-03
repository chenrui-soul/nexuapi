package com.nexusapi.server.modules.finance.service;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 多币种财务最小闭环：汇率按生效时间读取，转换结果由调用方写入对账快照。 */
@Service
public class MultiCurrencyFinanceService {
    private final JdbcTemplate jdbc;

    public MultiCurrencyFinanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID putRate(String baseCurrency, String quoteCurrency, BigDecimal rate,
                        Instant effectiveAt, String source) {
        String base = currency(baseCurrency);
        String quote = currency(quoteCurrency);
        if (base.equals(quote) || rate == null || rate.signum() <= 0 || effectiveAt == null) {
            throw validation("汇率参数无效");
        }
        return jdbc.queryForObject("""
                INSERT INTO currency_exchange_rates
                    (base_currency, quote_currency, rate, effective_at, source)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, base, quote, rate, effectiveAt,
                source == null || source.isBlank() ? "manual" : source.strip());
    }

    public BigDecimal convert(BigDecimal amount, String baseCurrency, String quoteCurrency, Instant at) {
        if (amount == null || amount.signum() < 0) throw validation("金额无效");
        String base = currency(baseCurrency);
        String quote = currency(quoteCurrency);
        if (base.equals(quote)) return amount.setScale(12, RoundingMode.HALF_UP);
        BigDecimal rate = jdbc.query("""
                SELECT rate FROM currency_exchange_rates
                 WHERE base_currency = ? AND quote_currency = ? AND effective_at <= ?
                 ORDER BY effective_at DESC, id DESC LIMIT 1
                """, rs -> rs.next() ? rs.getBigDecimal(1) : null, base, quote,
                at == null ? Instant.now() : at);
        if (rate == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "缺少该币种在指定时间的汇率", null);
        return amount.multiply(rate).setScale(12, RoundingMode.HALF_UP);
    }

    public List<Map<String, Object>> listRates() {
        return jdbc.queryForList("""
                SELECT id, base_currency, quote_currency, rate, effective_at, source, created_at
                  FROM currency_exchange_rates
                 ORDER BY effective_at DESC, id DESC LIMIT 200
                """);
    }

    private String currency(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw validation("币种必须是三位大写代码");
        return normalized;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
