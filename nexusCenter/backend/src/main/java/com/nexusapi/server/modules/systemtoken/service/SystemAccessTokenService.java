package com.nexusapi.server.modules.systemtoken.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.systemtoken.dto.SystemAccessTokenCreateRequest;
import com.nexusapi.server.modules.systemtoken.dto.SystemAccessTokenStatusRequest;
import com.nexusapi.server.modules.systemtoken.entity.SystemAccessTokenRow;
import com.nexusapi.server.modules.systemtoken.mapper.SystemAccessTokenMapper;
import com.nexusapi.server.modules.systemtoken.model.SystemAccessScope;
import com.nexusapi.server.modules.systemtoken.support.GeneratedSystemAccessToken;
import com.nexusapi.server.modules.systemtoken.support.SystemAccessTokenGenerator;
import com.nexusapi.server.modules.systemtoken.vo.SystemAccessScopeResponse;
import com.nexusapi.server.modules.systemtoken.vo.SystemAccessTokenCreatedResponse;
import com.nexusapi.server.modules.systemtoken.vo.SystemAccessTokenItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 系统访问令牌的创建、列表、状态和撤销业务层。 */
@Service
public class SystemAccessTokenService {
    private static final Duration MIN_EXPIRY_LEAD = Duration.ofMinutes(5);
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final SystemAccessTokenMapper mapper;
    private final SystemAccessTokenGenerator generator;
    private final ObjectMapper objectMapper;
    private final int maxPerUser;

    public SystemAccessTokenService(
            SystemAccessTokenMapper mapper,
            SystemAccessTokenGenerator generator,
            ObjectMapper objectMapper,
            NexusProperties properties
    ) {
        this.mapper = mapper;
        this.generator = generator;
        this.objectMapper = objectMapper;
        this.maxPerUser = properties.systemAccessToken().maxPerUser();
    }

    @Transactional(readOnly = true)
    public List<SystemAccessTokenItemResponse> list(UUID userId) {
        return mapper.findAllForUser(userId).stream().map(this::toResponse).toList();
    }

    public List<SystemAccessScopeResponse> scopes() {
        return SystemAccessScope.valuesList().stream()
                .map(scope -> new SystemAccessScopeResponse(scope.code(), scope.label()))
                .toList();
    }

    @Transactional
    public SystemAccessTokenCreatedResponse create(UUID userId, SystemAccessTokenCreateRequest request) {
        if (mapper.lockActiveUser(userId) == null) throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        if (mapper.countNonRevoked(userId) >= maxPerUser) {
            throw new BusinessException(ErrorCode.SYSTEM_ACCESS_TOKEN_LIMIT_REACHED);
        }
        String name = normalizeName(request.name());
        List<String> scopes = SystemAccessScope.normalize(request.scopes());
        List<String> ipAllowlist = normalizeCidrs(request.ipAllowlist());
        if (request.expiresAt() != null && request.expiresAt().isBefore(Instant.now().plus(MIN_EXPIRY_LEAD))) {
            throw validation("有效期至少需要晚于当前时间 5 分钟");
        }
        GeneratedSystemAccessToken generated = generator.generate();
        UUID id = UUID.randomUUID();
        mapper.insert(
                id, userId, name, generated.prefix(), generated.suffix(), generated.hash(),
                generated.hashVersion(), json(scopes), json(ipAllowlist), request.expiresAt()
        );
        return new SystemAccessTokenCreatedResponse(toResponse(requireRow(id, userId)), generated.secret());
    }

    @Transactional
    public SystemAccessTokenItemResponse changeStatus(
            UUID userId,
            UUID id,
            SystemAccessTokenStatusRequest request
    ) {
        SystemAccessTokenRow current = requireRow(id, userId);
        String effective = effectiveStatus(current);
        if ("revoked".equals(effective) || "expired".equals(effective)) {
            throw new BusinessException(ErrorCode.SYSTEM_ACCESS_TOKEN_STATUS_CONFLICT);
        }
        if (request.status().equals(effective)) return toResponse(current);
        if (current.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.SYSTEM_ACCESS_TOKEN_VERSION_CONFLICT);
        }
        if (mapper.updateStatus(id, userId, request.status(), request.version()) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ACCESS_TOKEN_VERSION_CONFLICT);
        }
        return toResponse(requireRow(id, userId));
    }

    @Transactional
    public boolean revoke(UUID userId, UUID id) {
        SystemAccessTokenRow current = requireRow(id, userId);
        if (current.getRevokedAt() != null || "revoked".equals(current.getStatus())) return true;
        if (mapper.revoke(id, userId) != 1) {
            SystemAccessTokenRow latest = mapper.findByIdForUser(id, userId);
            if (latest != null && latest.getRevokedAt() != null) return true;
            throw new BusinessException(ErrorCode.SYSTEM_ACCESS_TOKEN_NOT_FOUND);
        }
        return true;
    }

    private SystemAccessTokenRow requireRow(UUID id, UUID userId) {
        SystemAccessTokenRow row = mapper.findByIdForUser(id, userId);
        if (row == null) throw new BusinessException(ErrorCode.SYSTEM_ACCESS_TOKEN_NOT_FOUND);
        return row;
    }

    private SystemAccessTokenItemResponse toResponse(SystemAccessTokenRow row) {
        return new SystemAccessTokenItemResponse(
                row.getId(), row.getName(), row.getTokenPrefix() + "..." + row.getTokenSuffix(),
                read(row.getScopesJson()), read(row.getIpAllowlistJson()), effectiveStatus(row),
                row.getExpiresAt(), row.getLastUsedAt(), row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private String effectiveStatus(SystemAccessTokenRow row) {
        return "active".equals(row.getStatus()) && row.getExpiresAt() != null
                && !row.getExpiresAt().isAfter(Instant.now()) ? "expired" : row.getStatus();
    }

    private String normalizeName(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw validation("系统访问令牌名称包含无效字符");
        }
        return value;
    }

    private List<String> normalizeCidrs(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : values) {
            String value = raw.strip();
            int separator = value.lastIndexOf('/');
            if (separator <= 0 || separator == value.length() - 1) throw invalidCidr();
            String addressValue = value.substring(0, separator);
            if (!IP_LITERAL.matcher(addressValue).matches()) throw invalidCidr();
            try {
                InetAddress address = InetAddress.getByName(addressValue);
                int prefix = Integer.parseInt(value.substring(separator + 1));
                int max = address.getAddress().length * 8;
                if (prefix < 0 || prefix > max) throw invalidCidr();
                result.add(address.getHostAddress() + '/' + prefix);
            } catch (Exception exception) {
                throw invalidCidr();
            }
        }
        return List.copyOf(result);
    }

    private BusinessException invalidCidr() { return validation("IP 白名单必须使用合法 CIDR，例如 203.0.113.8/32"); }
    private BusinessException validation(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null); }

    private String json(List<String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (Exception exception) { throw new IllegalStateException("Failed to serialize system access token config", exception); }
    }

    private List<String> read(String value) {
        try { return List.copyOf(objectMapper.readValue(value, STRING_LIST)); }
        catch (Exception exception) { throw new IllegalStateException("Invalid system access token config", exception); }
    }
}
