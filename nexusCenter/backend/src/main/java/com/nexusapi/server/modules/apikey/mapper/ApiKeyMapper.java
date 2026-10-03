package com.nexusapi.server.modules.apikey.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.apikey.entity.ApiKeyRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API 令牌的 MyBatis 数据访问层。
 *
 * <p>所有面向用户资源的查询和写入必须同时限定 {@code id + user_id}；
 * Service 不能先按 id 查询后再在内存判断归属，否则容易产生越权窗口。</p>
 */
@Mapper
public interface ApiKeyMapper {

    /** 锁定有效用户行，用于串行化同一用户的并发创建和数量上限检查。 */
    @Select("SELECT id FROM users WHERE id = #{userId} AND status = 'active' AND deleted_at IS NULL FOR UPDATE")
    UUID lockActiveUser(@Param("userId") UUID userId);

    @Select("SELECT count(*) FROM api_keys WHERE user_id = #{userId} AND revoked_at IS NULL")
    int countNonRevoked(@Param("userId") UUID userId);

    /** 查询当前用户的 API 令牌列表，所有筛选条件都在用户边界内执行。 */
    @Select("""
            <script>
            SELECT k.id, k.user_id, k.name, k.key_prefix, k.key_suffix, k.encrypted_secret, k.status, k.service_group_id,
                   (SELECT name FROM routing_groups WHERE id = k.service_group_id) AS service_group_name,
                   k.default_group_id,
                   k.allowed_model_ids::text AS allowed_model_ids_json,
                   k.allowed_group_ids::text AS allowed_group_ids_json,
                   k.ip_allowlist::text AS ip_allowlist_json,
                   k.rpm_limit, k.tpm_limit, k.concurrency_limit, k.credit_limit,
                   usage.used_credits, usage.reserved_credits,
                   k.expires_at, k.last_used_at, k.created_at, k.updated_at, k.revoked_at, k.version
              FROM api_keys k
              LEFT JOIN LATERAL (
                    SELECT COALESCE(SUM(
                               CASE WHEN reservation.status = 'settled'
                                    THEN reservation.settled_amount - reservation.refunded_amount
                                    ELSE 0 END
                           ), 0) AS used_credits,
                           COALESCE(SUM(
                               CASE WHEN reservation.status = 'reserved'
                                    THEN reservation.reserved_amount
                                    ELSE 0 END
                           ), 0) AS reserved_credits
                      FROM wallet_reservations reservation
                     WHERE reservation.user_id = k.user_id
                       AND reservation.api_key_id = k.id
              ) usage ON true
             WHERE k.user_id = #{userId}
             <if test="query != null and query != ''">
               AND (lower(k.name) LIKE concat('%', lower(#{query}), '%')
                    OR lower(k.key_prefix) LIKE concat('%', lower(#{query}), '%'))
             </if>
             <if test="status != null">
               AND (CASE
                      WHEN k.status != 'revoked' AND k.expires_at IS NOT NULL AND k.expires_at &lt;= now() THEN 'expired'
                      ELSE k.status
                    END) = #{status}
             </if>
             ORDER BY k.created_at DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results(id = "apiKeyRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "name", property = "name"),
            @Result(column = "key_prefix", property = "keyPrefix"),
            @Result(column = "key_suffix", property = "keySuffix"),
            @Result(column = "encrypted_secret", property = "encryptedSecret"),
            @Result(column = "status", property = "status"),
            @Result(column = "service_group_id", property = "serviceGroupId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "service_group_name", property = "serviceGroupName"),
            @Result(column = "default_group_id", property = "defaultGroupId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "allowed_model_ids_json", property = "allowedModelIdsJson"),
            @Result(column = "allowed_group_ids_json", property = "allowedGroupIdsJson"),
            @Result(column = "ip_allowlist_json", property = "ipAllowlistJson"),
            @Result(column = "rpm_limit", property = "rpmLimit"),
            @Result(column = "tpm_limit", property = "tpmLimit"),
            @Result(column = "concurrency_limit", property = "concurrencyLimit"),
            @Result(column = "credit_limit", property = "creditLimit"),
            @Result(column = "used_credits", property = "usedCredits"),
            @Result(column = "reserved_credits", property = "reservedCredits"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "last_used_at", property = "lastUsedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "revoked_at", property = "revokedAt"),
            @Result(column = "version", property = "version")
    })
    List<ApiKeyRow> findPage(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /** 使用与列表完全一致的用户边界和筛选条件统计分页总数。 */
    @Select("""
            <script>
            SELECT count(*)
              FROM api_keys
             WHERE user_id = #{userId}
             <if test="query != null and query != ''">
               AND (lower(name) LIKE concat('%', lower(#{query}), '%')
                    OR lower(key_prefix) LIKE concat('%', lower(#{query}), '%'))
             </if>
             <if test="status != null">
               AND (CASE
                      WHEN status != 'revoked' AND expires_at IS NOT NULL AND expires_at &lt;= now() THEN 'expired'
                      ELSE status
                    END) = #{status}
             </if>
            </script>
            """)
    long countPage(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("status") String status
    );

    /** 按资源 ID 和当前用户共同查询，避免跨用户读取 API 令牌配置。 */
    @Select("""
            SELECT k.id, k.user_id, k.name, k.key_prefix, k.key_suffix, k.encrypted_secret, k.status, k.service_group_id,
                   (SELECT name FROM routing_groups WHERE id = k.service_group_id) AS service_group_name,
                   k.default_group_id,
                   k.allowed_model_ids::text AS allowed_model_ids_json,
                   k.allowed_group_ids::text AS allowed_group_ids_json,
                   k.ip_allowlist::text AS ip_allowlist_json,
                   k.rpm_limit, k.tpm_limit, k.concurrency_limit, k.credit_limit,
                   usage.used_credits, usage.reserved_credits,
                   k.expires_at, k.last_used_at, k.created_at, k.updated_at, k.revoked_at, k.version
              FROM api_keys k
              LEFT JOIN LATERAL (
                    SELECT COALESCE(SUM(
                               CASE WHEN reservation.status = 'settled'
                                    THEN reservation.settled_amount - reservation.refunded_amount
                                    ELSE 0 END
                           ), 0) AS used_credits,
                           COALESCE(SUM(
                               CASE WHEN reservation.status = 'reserved'
                                    THEN reservation.reserved_amount
                                    ELSE 0 END
                           ), 0) AS reserved_credits
                      FROM wallet_reservations reservation
                     WHERE reservation.user_id = k.user_id
                       AND reservation.api_key_id = k.id
              ) usage ON true
             WHERE k.id = #{id}
               AND k.user_id = #{userId}
             LIMIT 1
            """)
    @ResultMap("apiKeyRow")
    ApiKeyRow findByIdForUser(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * 使用版本化 HMAC 摘要查询仍然有效的 Key，并在同一条 SQL 中确认所属用户仍处于 active 状态。
     */
    @Select("""
            SELECT k.id, k.user_id, k.name, k.key_prefix, k.key_suffix, k.encrypted_secret, k.status, k.service_group_id,
                   (SELECT name FROM routing_groups WHERE id = k.service_group_id) AS service_group_name,
                   k.default_group_id,
                   k.allowed_model_ids::text AS allowed_model_ids_json,
                   k.allowed_group_ids::text AS allowed_group_ids_json,
                   k.ip_allowlist::text AS ip_allowlist_json,
                   k.rpm_limit, k.tpm_limit, k.concurrency_limit, k.credit_limit,
                   CAST(0 AS numeric) AS used_credits,
                   CAST(0 AS numeric) AS reserved_credits,
                   k.expires_at, k.last_used_at, k.created_at, k.updated_at, k.revoked_at, k.version
              FROM api_keys k
              JOIN users u ON u.id = k.user_id
              JOIN routing_groups g ON g.id = k.service_group_id
             WHERE k.key_hash_version = #{hashVersion}
               AND k.key_hash = #{keyHash}
               AND k.status = 'active'
               AND k.revoked_at IS NULL
               AND (k.expires_at IS NULL OR k.expires_at > now())
               AND u.status = 'active'
               AND u.deleted_at IS NULL
               AND g.status = 'active'
               AND (
                    g.audience = 'all'
                    OR (g.audience = 'assigned' AND EXISTS (
                        SELECT 1
                          FROM routing_group_user_grants grant_row
                         WHERE grant_row.group_id = g.id
                           AND grant_row.user_id = k.user_id
                           AND grant_row.status = 'active'
                           AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
                    ))
               )
             LIMIT 1
            """)
    @ResultMap("apiKeyRow")
    ApiKeyRow findActiveForAuthentication(
            @Param("hashVersion") int hashVersion,
            @Param("keyHash") byte[] keyHash
    );

    /** 成功完成模型调用后更新时间，不改变 Key 配置版本，避免与控制台乐观锁冲突。 */
    @Update("UPDATE api_keys SET last_used_at = now() WHERE id = #{id} AND status = 'active' AND revoked_at IS NULL")
    int touchLastUsed(@Param("id") UUID id);

    @Insert("""
            INSERT INTO api_keys (
                id, user_id, name, key_prefix, key_suffix, key_hash, key_hash_version,
                status, service_group_id, default_group_id, allowed_model_ids, allowed_group_ids, ip_allowlist, encrypted_secret,
                rpm_limit, tpm_limit, concurrency_limit, credit_limit, expires_at
            ) VALUES (
                #{id}, #{userId}, #{name}, #{keyPrefix}, #{keySuffix}, #{keyHash}, #{keyHashVersion},
                'active', #{serviceGroupId}, #{serviceGroupId}, CAST(#{allowedModelIdsJson} AS jsonb),
                CAST(#{allowedGroupIdsJson} AS jsonb), CAST(#{ipAllowlistJson} AS jsonb), #{encryptedSecret},
                #{rpmLimit}, #{tpmLimit}, #{concurrencyLimit}, #{creditLimit}, #{expiresAt}
            )
            """)
    int insert(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("name") String name,
            @Param("keyPrefix") String keyPrefix,
            @Param("keySuffix") String keySuffix,
            @Param("keyHash") byte[] keyHash,
            @Param("keyHashVersion") int keyHashVersion,
            @Param("serviceGroupId") UUID serviceGroupId,
            @Param("allowedModelIdsJson") String allowedModelIdsJson,
            @Param("allowedGroupIdsJson") String allowedGroupIdsJson,
            @Param("ipAllowlistJson") String ipAllowlistJson,
            @Param("encryptedSecret") byte[] encryptedSecret,
            @Param("rpmLimit") Integer rpmLimit,
            @Param("tpmLimit") Long tpmLimit,
            @Param("concurrencyLimit") Integer concurrencyLimit,
            @Param("creditLimit") BigDecimal creditLimit,
            @Param("expiresAt") Instant expiresAt
    );

    /** 使用 version 乐观锁更新完整配置，避免并发页面相互覆盖。 */
    @Update("""
            UPDATE api_keys
               SET name = #{name},
                   service_group_id = #{serviceGroupId},
                   default_group_id = #{serviceGroupId},
                   allowed_model_ids = CAST(#{allowedModelIdsJson} AS jsonb),
                   allowed_group_ids = CAST(#{allowedGroupIdsJson} AS jsonb),
                   ip_allowlist = CAST(#{ipAllowlistJson} AS jsonb),
                   rpm_limit = #{rpmLimit},
                   tpm_limit = #{tpmLimit},
                   concurrency_limit = #{concurrencyLimit},
                   credit_limit = #{creditLimit},
                   expires_at = #{expiresAt},
                   updated_at = now(),
                   version = version + 1
             WHERE id = #{id}
               AND user_id = #{userId}
               AND version = #{version}
               AND revoked_at IS NULL
            """)
    int updateConfiguration(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("name") String name,
            @Param("serviceGroupId") UUID serviceGroupId,
            @Param("allowedModelIdsJson") String allowedModelIdsJson,
            @Param("allowedGroupIdsJson") String allowedGroupIdsJson,
            @Param("ipAllowlistJson") String ipAllowlistJson,
            @Param("rpmLimit") Integer rpmLimit,
            @Param("tpmLimit") Long tpmLimit,
            @Param("concurrencyLimit") Integer concurrencyLimit,
            @Param("creditLimit") BigDecimal creditLimit,
            @Param("expiresAt") Instant expiresAt,
            @Param("version") long version
    );

    /** 使用 version 乐观锁切换状态，保证状态变更不会覆盖更新后的记录。 */
    @Update("""
            UPDATE api_keys
               SET status = #{status},
                   status_changed_at = now(),
                   updated_at = now(),
                   version = version + 1
             WHERE id = #{id}
               AND user_id = #{userId}
               AND version = #{version}
               AND revoked_at IS NULL
            """)
    int updateStatus(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("status") String status,
            @Param("version") long version
    );

    /** 撤销只命中当前用户且尚未撤销的记录，为 Service 的幂等语义提供原子条件。 */
    @Update("""
            UPDATE api_keys
               SET status = 'revoked',
                   revoked_at = now(),
                   status_changed_at = now(),
                   updated_at = now(),
                   version = version + 1
             WHERE id = #{id}
               AND user_id = #{userId}
               AND revoked_at IS NULL
            """)
    int revoke(@Param("id") UUID id, @Param("userId") UUID userId);

    @Select("""
            <script>
            SELECT count(*)
              FROM ai_models
             WHERE status = 'active'
               AND id IN
               <foreach collection="ids" item="id" open="(" separator="," close=")">
                 #{id}
               </foreach>
            </script>
            """)
    int countActiveModels(@Param("ids") List<UUID> ids);

    /** 校验模型白名单是否全部属于指定分组且模型当前启用、公开。 */
    @Select("""
            <script>
            SELECT count(DISTINCT m.id)
              FROM ai_models m
              JOIN routing_group_models rgm
                ON rgm.model_id = m.id AND rgm.group_id = #{groupId} AND rgm.source_status = 'active'
             WHERE m.id IN
               <foreach collection="modelIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND m.status = 'active'
               AND m.public_visible = true
            </script>
            """)
    int countModelsInGroup(
            @Param("groupId") UUID groupId,
            @Param("modelIds") List<UUID> modelIds
    );

    @Select("""
            <script>
            SELECT count(*)
              FROM routing_groups g
             WHERE g.status = 'active'
               AND (
                    g.audience = 'all'
                    OR (g.audience = 'assigned' AND EXISTS (
                        SELECT 1
                          FROM routing_group_user_grants grant_row
                          JOIN users authorized_user ON authorized_user.id = grant_row.user_id
                         WHERE grant_row.group_id = g.id
                           AND grant_row.user_id = #{userId}
                           AND grant_row.status = 'active'
                           AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
                           AND authorized_user.status = 'active'
                           AND authorized_user.deleted_at IS NULL
                    ))
               )
               AND g.id IN
               <foreach collection="ids" item="id" open="(" separator="," close=")">
                 #{id}
               </foreach>
            </script>
            """)
    int countSelectableGroups(@Param("userId") UUID userId, @Param("ids") List<UUID> ids);
}
