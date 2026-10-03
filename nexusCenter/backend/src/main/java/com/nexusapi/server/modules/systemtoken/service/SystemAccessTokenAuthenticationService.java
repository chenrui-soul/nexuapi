package com.nexusapi.server.modules.systemtoken.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.systemtoken.entity.SystemAccessTokenRow;
import com.nexusapi.server.modules.systemtoken.mapper.SystemAccessTokenMapper;
import com.nexusapi.server.modules.systemtoken.security.NexusSystemAccessPrincipal;
import com.nexusapi.server.modules.systemtoken.support.SystemAccessTokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 验证 nx-sys Bearer 凭证；失败统一返回 null，不泄露失败细节。 */
@Service
public class SystemAccessTokenAuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(SystemAccessTokenAuthenticationService.class);
    private static final Pattern PATTERN = Pattern.compile("^nx-sys-v([1-9][0-9]{0,9})_([A-Za-z0-9_-]{43})$");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final SystemAccessTokenMapper mapper;
    private final SystemAccessTokenHasher hasher;
    private final ObjectMapper objectMapper;

    public SystemAccessTokenAuthenticationService(
            SystemAccessTokenMapper mapper,
            SystemAccessTokenHasher hasher,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public NexusSystemAccessPrincipal authenticate(String secret) {
        Parsed parsed = parse(secret);
        if (parsed == null || !hasher.supports(parsed.version())) return null;
        SystemAccessTokenRow row = mapper.findActiveForAuthentication(
                parsed.version(), hasher.hash(parsed.version(), secret)
        );
        if (row == null) return null;
        return new NexusSystemAccessPrincipal(
                row.getId(), row.getUserId(), row.getTokenPrefix() + "..." + row.getTokenSuffix(),
                read(row.getScopesJson()), read(row.getIpAllowlistJson()), row.getExpiresAt()
        );
    }

    public void recordSuccessfulUse(java.util.UUID tokenId) {
        try {
            mapper.touchLastUsed(tokenId);
        } catch (RuntimeException exception) {
            // 最近使用时间只是观测数据，写入失败不能反向破坏已经成功的只读业务请求。
            log.warn("Failed to update system access token last_used_at: tokenId={}", tokenId, exception);
        }
    }

    private Parsed parse(String secret) {
        if (secret == null || secret.length() > 90) return null;
        Matcher matcher = PATTERN.matcher(secret);
        if (!matcher.matches()) return null;
        try {
            int version = Integer.parseInt(matcher.group(1));
            return Base64.getUrlDecoder().decode(matcher.group(2)).length == 32 ? new Parsed(version) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<String> read(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid system access token configuration", exception);
        }
    }

    private record Parsed(int version) { }
}
