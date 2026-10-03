package com.nexusapi.server.modules.apikey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.apikey.entity.ApiKeyRow;
import com.nexusapi.server.modules.apikey.mapper.ApiKeyMapper;
import com.nexusapi.server.modules.apikey.security.NexusApiKeyPrincipal;
import com.nexusapi.server.modules.apikey.support.ApiKeySecretHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将外部 Bearer Secret 验证为可供网关使用的 API 令牌 Principal。
 *
 * <p>鉴权不缓存有效 Key，每个新请求都从数据库确认 Key、用户和过期状态，
 * 因此禁用、撤销或账号停用能够立即影响后续请求。</p>
 */
@Service
public class ApiKeyAuthenticationService {
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "^sk-nx-v([1-9][0-9]{0,9})_([A-Za-z0-9_-]{43})$"
    );
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ApiKeyMapper mapper;
    private final ApiKeySecretHasher hasher;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationService(
            ApiKeyMapper mapper,
            ApiKeySecretHasher hasher,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    /**
     * 验证 Secret 并返回网关身份；任何外部可控的格式或凭证失败都统一返回 {@code null}。
     */
    @Transactional(readOnly = true)
    public NexusApiKeyPrincipal authenticate(String secret) {
        ParsedSecret parsed = parse(secret);
        if (parsed == null || !hasher.supportsVersion(parsed.version())) {
            return null;
        }

        // 只把 HMAC 摘要交给数据库查询，完整 Secret 不进入 SQL、日志或持久化对象。
        byte[] digest = hasher.hash(parsed.version(), secret);
        ApiKeyRow row = mapper.findActiveForAuthentication(parsed.version(), digest);
        if (row == null) {
            return null;
        }

        return new NexusApiKeyPrincipal(
                row.getId(),
                row.getUserId(),
                row.getKeyPrefix() + "..." + row.getKeySuffix(),
                row.getServiceGroupId() == null ? row.getDefaultGroupId() : row.getServiceGroupId(),
                row.getDefaultGroupId(),
                readUuidList(row.getAllowedModelIdsJson()),
                readUuidList(row.getAllowedGroupIdsJson()),
                readStringList(row.getIpAllowlistJson()),
                row.getRpmLimit(),
                row.getTpmLimit(),
                row.getConcurrencyLimit(),
                row.getCreditLimit(),
                row.getExpiresAt()
        );
    }

    /** Gateway 成功完成请求后记录最近使用时间；该写入不暴露 Mapper 给其他模块。 */
    @Transactional
    public void recordSuccessfulUse(UUID apiKeyId) {
        mapper.touchLastUsed(apiKeyId);
    }

    private ParsedSecret parse(String secret) {
        if (secret == null || secret.length() > 80) {
            return null;
        }
        Matcher matcher = SECRET_PATTERN.matcher(secret);
        if (!matcher.matches()) {
            return null;
        }
        try {
            int version = Integer.parseInt(matcher.group(1));
            byte[] randomPayload = Base64.getUrlDecoder().decode(matcher.group(2));
            return randomPayload.length == 32 ? new ParsedSecret(version) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<UUID> readUuidList(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, UUID_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid API key UUID configuration", exception);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid API key IP configuration", exception);
        }
    }

    private record ParsedSecret(int version) {
    }
}
