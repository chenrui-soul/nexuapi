package com.nexusapi.server.modules.system;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Service
public class PlatformSettingsService {
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    public PlatformSettingsService(JdbcTemplate jdbc, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }
    public record Settings(@JsonProperty("registration_enabled") boolean registrationEnabled,
                           @JsonProperty("manual_recharge_enabled") boolean manualRechargeEnabled, long version) {}
    public record Update(@JsonProperty("registration_enabled") @NotNull Boolean registrationEnabled,
                         @JsonProperty("manual_recharge_enabled") Boolean manualRechargeEnabled,
                         @NotNull @Min(0) Long version) {}

    public Settings get() {
        return jdbc.queryForObject("SELECT registration_enabled, manual_recharge_enabled, version FROM platform_settings WHERE id = 1",
                (rs, n) -> new Settings(rs.getBoolean(1), rs.getBoolean(2), rs.getLong(3)));
    }

    /** The shared row lock lasts for the registration transaction; a completed close cannot race an insert. */
    public void requireRegistrationEnabled() {
        Boolean enabled = jdbc.queryForObject(
                "SELECT registration_enabled FROM platform_settings WHERE id = 1 FOR SHARE", Boolean.class);
        if (!Boolean.TRUE.equals(enabled)) throw new BusinessException(ErrorCode.AUTH_REGISTRATION_DISABLED);
    }

    public void requireManualRechargeEnabled() {
        Boolean enabled = jdbc.queryForObject(
                "SELECT manual_recharge_enabled FROM platform_settings WHERE id = 1 FOR SHARE", Boolean.class);
        if (!Boolean.TRUE.equals(enabled)) throw new BusinessException(ErrorCode.ADMIN_RECHARGE_DISABLED);
    }

    @Transactional
    public Settings update(Update input, UUID actor, ClientRequestMetadata metadata) {
        Settings before = get();
        int changed = jdbc.update("""
                UPDATE platform_settings SET registration_enabled = ?,
                manual_recharge_enabled = COALESCE(?, manual_recharge_enabled), version = version + 1, updated_at = now()
                WHERE id = 1 AND version = ?
                """, input.registrationEnabled(), input.manualRechargeEnabled(), input.version());
        if (changed != 1) throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        Settings after = get();
        audit.record(actor, "platform.settings.update", "platform_settings", new UUID(0, 1), before, after, metadata);
        return after;
    }
}
