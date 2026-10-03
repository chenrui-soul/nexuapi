package com.nexusapi.server.modules.systemtoken.support;

/** 系统访问令牌生成结果；secret 只允许存在于创建响应内。 */
public record GeneratedSystemAccessToken(
        String secret,
        String prefix,
        String suffix,
        byte[] hash,
        int hashVersion
) {
    public GeneratedSystemAccessToken {
        hash = hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }
}
