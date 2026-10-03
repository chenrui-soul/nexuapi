package com.nexusapi.server.modules.systemtoken.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.systemtoken.entity.SystemAccessTokenRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 系统访问令牌持久化边界；任何查询都不得返回完整令牌。 */
@Mapper
public interface SystemAccessTokenMapper {
    String COLUMNS = "id, user_id, name, token_prefix, token_suffix, token_hash, hash_version, "
            + "scopes::text AS scopes_json, ip_allowlist::text AS ip_allowlist_json, status, "
            + "expires_at, last_used_at, revoked_at, created_at, updated_at, version";

    @Select("""
            SELECT id
              FROM users
             WHERE id = #{userId} AND status = 'active' AND deleted_at IS NULL
             FOR UPDATE
            """)
    UUID lockActiveUser(@Param("userId") UUID userId);

    @Select("""
            SELECT count(*)
              FROM system_access_tokens
             WHERE user_id = #{userId} AND status <> 'revoked'
            """)
    int countNonRevoked(@Param("userId") UUID userId);

    @Insert("""
            INSERT INTO system_access_tokens (
                id, user_id, name, token_prefix, token_suffix, token_hash, hash_version,
                scopes, ip_allowlist, status, expires_at
            ) VALUES (
                #{id}, #{userId}, #{name}, #{prefix}, #{suffix}, #{hash}, #{hashVersion},
                CAST(#{scopesJson} AS jsonb), CAST(#{ipAllowlistJson} AS jsonb), 'active', #{expiresAt}
            )
            """)
    int insert(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("name") String name,
            @Param("prefix") String prefix,
            @Param("suffix") String suffix,
            @Param("hash") byte[] hash,
            @Param("hashVersion") int hashVersion,
            @Param("scopesJson") String scopesJson,
            @Param("ipAllowlistJson") String ipAllowlistJson,
            @Param("expiresAt") Instant expiresAt
    );

    @Select("""
            SELECT
            """ + COLUMNS + """
              FROM system_access_tokens
             WHERE user_id = #{userId}
             ORDER BY created_at DESC, id DESC
            """)
    @Results(id = "systemAccessTokenRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "scopes_json", property = "scopesJson"),
            @Result(column = "ip_allowlist_json", property = "ipAllowlistJson")
    })
    List<SystemAccessTokenRow> findAllForUser(@Param("userId") UUID userId);

    @Select("""
            SELECT
            """ + COLUMNS + """
              FROM system_access_tokens
             WHERE id = #{id} AND user_id = #{userId}
             LIMIT 1
            """)
    @ResultMap("systemAccessTokenRow")
    SystemAccessTokenRow findByIdForUser(@Param("id") UUID id, @Param("userId") UUID userId);

    @Select("""
            SELECT t.id, t.user_id, t.name, t.token_prefix, t.token_suffix, t.token_hash, t.hash_version,
                   t.scopes::text AS scopes_json, t.ip_allowlist::text AS ip_allowlist_json, t.status,
                   t.expires_at, t.last_used_at, t.revoked_at, t.created_at, t.updated_at, t.version
              FROM system_access_tokens t
              JOIN users u ON u.id = t.user_id
             WHERE t.hash_version = #{hashVersion}
               AND t.token_hash = #{hash}
               AND t.status = 'active'
               AND t.revoked_at IS NULL
               AND (t.expires_at IS NULL OR t.expires_at > now())
               AND u.status = 'active'
               AND u.deleted_at IS NULL
             LIMIT 1
            """)
    @ResultMap("systemAccessTokenRow")
    SystemAccessTokenRow findActiveForAuthentication(
            @Param("hashVersion") int hashVersion,
            @Param("hash") byte[] hash
    );

    @Update("""
            UPDATE system_access_tokens
               SET status = #{status}, version = version + 1
             WHERE id = #{id} AND user_id = #{userId}
               AND status NOT IN ('revoked', 'expired') AND version = #{version}
            """)
    int updateStatus(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("status") String status,
            @Param("version") long version
    );

    @Update("""
            UPDATE system_access_tokens
               SET status = 'revoked', revoked_at = now(), version = version + 1
             WHERE id = #{id} AND user_id = #{userId} AND status <> 'revoked'
            """)
    int revoke(@Param("id") UUID id, @Param("userId") UUID userId);

    @Update("""
            UPDATE system_access_tokens SET last_used_at = now() WHERE id = #{id}
            """)
    int touchLastUsed(@Param("id") UUID id);
}
