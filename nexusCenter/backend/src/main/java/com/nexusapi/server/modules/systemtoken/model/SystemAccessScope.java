package com.nexusapi.server.modules.systemtoken.model;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 系统访问令牌首版只开放当前账户的只读数据范围。 */
public enum SystemAccessScope {
    DASHBOARD_READ("dashboard:read", "仪表盘只读"),
    WALLET_READ("wallet:read", "钱包只读"),
    LOGS_READ("logs:read", "调用日志只读");

    private final String code;
    private final String label;

    SystemAccessScope(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }
    public String label() { return label; }

    public static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请至少选择一个只读权限范围", null);
        }
        Set<String> allowed = Set.of(java.util.Arrays.stream(SystemAccessScope.values())
                .map(SystemAccessScope::code).toArray(String[]::new));
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : values) {
            String value = raw == null ? "" : raw.strip().toLowerCase();
            if (!allowed.contains(value)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "系统访问令牌包含不支持的权限范围", null);
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    public static List<SystemAccessScope> valuesList() {
        return List.of(values());
    }
}
