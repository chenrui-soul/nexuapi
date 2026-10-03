package com.nexusapi.server.modules.billing.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.billing.enums.CreditBucket;
import com.nexusapi.server.modules.billing.enums.CreditType;
import com.nexusapi.server.modules.system.PlatformSettingsService;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminRechargeService {
    public record Input(@JsonProperty("request_id") @NotNull UUID requestId,
                        @NotNull @DecimalMin("0.01") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 2) BigDecimal credits,
                        @NotBlank @Size(max = 200) String reason) {}
    public record Receipt(@JsonProperty("request_id") UUID requestId,
                          @JsonProperty("ledger_id") UUID ledgerId, BigDecimal credits,
                          @JsonProperty("available_credits") BigDecimal availableCredits, boolean replayed) {}
    private record Request(UUID user, UUID actor, BigDecimal credits, String reason, UUID ledger, BigDecimal available) {}
    private final JdbcTemplate jdbc;
    private final PlatformSettingsService settings;
    private final BillingService billing;
    private final AdminAuditService audit;

    public AdminRechargeService(JdbcTemplate jdbc, PlatformSettingsService settings, BillingService billing, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.billing = billing;
        this.audit = audit;
    }

    @Transactional
    public Receipt recharge(UUID user, UUID actor, Input input, ClientRequestMetadata metadata) {
        settings.requireManualRechargeEnabled();
        if (jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR KEY SHARE", UUID.class, user).isEmpty()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        String reason = input.reason().strip();
        jdbc.update("""
                INSERT INTO admin_recharge_requests(id, user_id, actor_id, credits, reason)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                """, input.requestId(), user, actor, input.credits(), reason);
        Request request = jdbc.queryForObject("SELECT * FROM admin_recharge_requests WHERE id = ? FOR UPDATE",
                (rs, n) -> new Request(rs.getObject("user_id", UUID.class), rs.getObject("actor_id", UUID.class),
                        rs.getBigDecimal("credits"), rs.getString("reason"), rs.getObject("ledger_id", UUID.class), rs.getBigDecimal("available_after")), input.requestId());
        if (!request.user().equals(user) || !request.actor().equals(actor)
                || request.credits().compareTo(input.credits()) != 0 || !request.reason().equals(reason)) {
            throw new BusinessException(ErrorCode.BILLING_IDEMPOTENCY_CONFLICT);
        }
        if (request.ledger() != null) {
            return new Receipt(input.requestId(), request.ledger(), request.credits(), request.available(), true);
        }
        var result = billing.credit(user, input.credits(), CreditBucket.PERMANENT, CreditType.RECHARGE,
                "admin-recharge:" + input.requestId(), "admin_recharge", input.requestId().toString(), null,
                Map.of("actor_id", actor.toString(), "reason", reason));
        jdbc.update("UPDATE admin_recharge_requests SET ledger_id = ?, available_after = ? WHERE id = ?",
                result.ledgerId(), result.wallet().availableCredits(), input.requestId());
        audit.record(actor, "user.credits.recharge", "user", user, null,
                Map.of("credits", input.credits(), "reason", reason, "ledger_id", result.ledgerId(), "request_id", input.requestId()), metadata);
        return new Receipt(input.requestId(), result.ledgerId(), input.credits(), result.wallet().availableCredits(), false);
    }
}
