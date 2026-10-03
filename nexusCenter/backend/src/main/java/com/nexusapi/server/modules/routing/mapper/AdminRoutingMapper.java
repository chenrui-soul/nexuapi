package com.nexusapi.server.modules.routing.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.routing.entity.AdminGroupSupplierCredentialRow;
import com.nexusapi.server.modules.routing.entity.AdminGroupUserGrantRow;
import com.nexusapi.server.modules.routing.entity.AdminRoutingGroupRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 计费分组和分组路由的数据访问层。 */
@Mapper
public interface AdminRoutingMapper {

    @Select("""
            SELECT rg.id, rg.code, rg.name, rg.description, rg.price_multiplier, rg.audience, rg.status,
                   rg.source_supplier_id, s.name AS source_supplier_name, rg.source_group_id,
                   rg.source_group_name, rg.sync_source, rg.source_status, rg.source_rate,
                   rg.source_billing_type, rg.source_managed, rg.source_last_seen_at, rg.source_synced_at,
                   (SELECT count(*) FROM routing_group_models rgm
                     WHERE rgm.group_id = rg.id AND rgm.source_status = 'active') AS source_model_count,
                   (SELECT count(*) FROM routing_group_models rgm
                     WHERE rgm.group_id = rg.id AND rgm.source_status = 'active'
                       AND rgm.upstream_last_status = 1) AS healthy_model_count,
                   (SELECT count(*) FROM routing_group_models rgm
                     WHERE rgm.group_id = rg.id AND rgm.source_status = 'stale') AS stale_model_count,
                   (SELECT count(*)
                      FROM routing_group_user_grants grant_row
                      JOIN users grant_user ON grant_user.id = grant_row.user_id
                     WHERE grant_row.group_id = rg.id
                       AND grant_row.status = 'active'
                       AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
                       AND grant_user.status = 'active'
                       AND grant_user.deleted_at IS NULL) AS authorized_user_count,
                   EXISTS (
                       SELECT 1
                         FROM routing_group_supplier_credentials credential_row
                         JOIN routing_group_suppliers supplier_row
                           ON supplier_row.group_id = credential_row.group_id
                          AND supplier_row.supplier_id = credential_row.supplier_id
                          AND supplier_row.status = 'active'
                        WHERE credential_row.group_id = rg.id
                          AND credential_row.status = 'active'
                          AND credential_row.encrypted_credential IS NOT NULL
                   ) AS credential_configured,
                   rg.created_at, rg.updated_at, rg.version
              FROM routing_groups rg
              LEFT JOIN suppliers s ON s.id = rg.source_supplier_id
             WHERE rg.id = #{id}
            """)
    @Results(id = "adminRoutingGroupRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "price_multiplier", property = "priceMultiplier"),
            @Result(column = "source_supplier_id", property = "sourceSupplierId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "source_supplier_name", property = "sourceSupplierName"),
            @Result(column = "source_group_id", property = "sourceGroupId"),
            @Result(column = "source_group_name", property = "sourceGroupName"),
            @Result(column = "sync_source", property = "syncSource"),
            @Result(column = "source_status", property = "sourceStatus"),
            @Result(column = "source_rate", property = "sourceRate"),
            @Result(column = "source_billing_type", property = "sourceBillingType"),
            @Result(column = "source_managed", property = "sourceManaged"),
            @Result(column = "source_last_seen_at", property = "sourceLastSeenAt"),
            @Result(column = "source_synced_at", property = "sourceSyncedAt"),
            @Result(column = "source_model_count", property = "sourceModelCount"),
            @Result(column = "healthy_model_count", property = "healthyModelCount"),
            @Result(column = "stale_model_count", property = "staleModelCount"),
            @Result(column = "authorized_user_count", property = "authorizedUserCount"),
            @Result(column = "credential_configured", property = "credentialConfigured"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    AdminRoutingGroupRow findGroupById(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT rg.id, rg.code, rg.name, rg.description, rg.price_multiplier, rg.audience, rg.status,
                   rg.source_supplier_id, s.name AS source_supplier_name, rg.source_group_id,
                   rg.source_group_name, rg.sync_source, rg.source_status, rg.source_rate,
                   rg.source_billing_type, rg.source_managed, rg.source_last_seen_at, rg.source_synced_at,
                   (SELECT count(*) FROM routing_group_models rgm
                     WHERE rgm.group_id = rg.id AND rgm.source_status = 'active') AS source_model_count,
                   (SELECT count(*) FROM routing_group_models rgm
                     WHERE rgm.group_id = rg.id AND rgm.source_status = 'active'
                       AND rgm.upstream_last_status = 1) AS healthy_model_count,
                   (SELECT count(*) FROM routing_group_models rgm
                     WHERE rgm.group_id = rg.id AND rgm.source_status = 'stale') AS stale_model_count,
                   (SELECT count(*)
                      FROM routing_group_user_grants grant_row
                      JOIN users grant_user ON grant_user.id = grant_row.user_id
                     WHERE grant_row.group_id = rg.id
                       AND grant_row.status = 'active'
                       AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
                       AND grant_user.status = 'active'
                       AND grant_user.deleted_at IS NULL) AS authorized_user_count,
                   EXISTS (
                       SELECT 1
                         FROM routing_group_supplier_credentials credential_row
                         JOIN routing_group_suppliers supplier_row
                           ON supplier_row.group_id = credential_row.group_id
                          AND supplier_row.supplier_id = credential_row.supplier_id
                          AND supplier_row.status = 'active'
                        WHERE credential_row.group_id = rg.id
                          AND credential_row.status = 'active'
                          AND credential_row.encrypted_credential IS NOT NULL
                   ) AS credential_configured,
                   rg.created_at, rg.updated_at, rg.version
              FROM routing_groups rg
              LEFT JOIN suppliers s ON s.id = rg.source_supplier_id
             WHERE 1 = 1
               <if test="query != null">
                 AND (rg.code ILIKE '%%' || #{query} || '%%'
                      OR rg.name ILIKE '%%' || #{query} || '%%'
                      OR rg.source_group_id ILIKE '%%' || #{query} || '%%'
                      OR s.name ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">
                 AND rg.status = #{status}
               </if>
             ORDER BY rg.updated_at DESC, rg.id
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ResultMap("adminRoutingGroupRow")
    List<AdminRoutingGroupRow> findGroupPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*) FROM routing_groups rg
              LEFT JOIN suppliers s ON s.id = rg.source_supplier_id
             WHERE 1 = 1
               <if test="query != null">
                 AND (rg.code ILIKE '%%' || #{query} || '%%'
                      OR rg.name ILIKE '%%' || #{query} || '%%'
                      OR rg.source_group_id ILIKE '%%' || #{query} || '%%'
                      OR s.name ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">
                 AND rg.status = #{status}
               </if>
            </script>
            """)
    long countGroups(@Param("query") String query, @Param("status") String status);

    @Select("""
            <script>
            SELECT count(*) FROM routing_groups
             WHERE lower(code) = lower(#{code})
               <if test="excludedId != null">AND id != #{excludedId}</if>
            </script>
            """)
    int countGroupCode(@Param("code") String code, @Param("excludedId") UUID excludedId);

    @Insert("""
            INSERT INTO routing_groups (id, code, name, description, price_multiplier, audience, status)
            VALUES (#{id}, #{code}, #{name}, #{description}, #{priceMultiplier}, #{audience}, #{status})
            """)
    int insertGroup(AdminRoutingGroupRow row);

    @Update("""
            UPDATE routing_groups
               SET code = #{code}, name = #{name}, description = #{description},
                   price_multiplier = #{priceMultiplier}, audience = #{audience}, status = #{status},
                   updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int updateGroup(AdminRoutingGroupRow row);

    /** 只返回当前仍有效且用户账号可用的授权，避免管理端把失效资格误判为可调用。 */
    @Select("""
            SELECT grant_row.user_id, u.display_name, u.email_ciphertext, u.status AS user_status,
                   grant_row.expires_at, grant_row.created_at, grant_row.updated_at
              FROM routing_group_user_grants grant_row
              JOIN users u ON u.id = grant_row.user_id
             WHERE grant_row.group_id = #{groupId}
               AND grant_row.status = 'active'
               AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
               AND u.status = 'active'
               AND u.deleted_at IS NULL
             ORDER BY u.display_name, u.id
            """)
    @Results(id = "adminGroupUserGrantRow", value = {
            @Result(column = "user_id", property = "userId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "email_ciphertext", property = "emailCiphertext"),
            @Result(column = "user_status", property = "userStatus"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<AdminGroupUserGrantRow> findActiveGroupUserGrants(@Param("groupId") UUID groupId);

    /** 批量校验授权目标必须是当前有效、未删除的用户。 */
    @Select("""
            <script>
            SELECT count(*)
              FROM users
             WHERE status = 'active'
               AND deleted_at IS NULL
               AND id IN
               <foreach collection="userIds" item="userId" open="(" separator="," close=")">#{userId}</foreach>
            </script>
            """)
    int countActiveUsers(@Param("userIds") List<UUID> userIds);

    /** 切换为普通或内部受众时撤销全部历史授权，防止以后重新启用旧权限。 */
    @Update("""
            UPDATE routing_group_user_grants
               SET status = 'revoked', updated_at = now()
             WHERE group_id = #{groupId} AND status = 'active'
            """)
    int revokeAllGroupUserGrants(@Param("groupId") UUID groupId);

    /** 批量替换授权名单时撤销本次未提交的用户，不物理删除授权历史。 */
    @Update("""
            <script>
            UPDATE routing_group_user_grants
               SET status = 'revoked', updated_at = now()
             WHERE group_id = #{groupId}
               AND status = 'active'
               AND user_id NOT IN
               <foreach collection="userIds" item="userId" open="(" separator="," close=")">#{userId}</foreach>
            </script>
            """)
    int revokeMissingGroupUserGrants(
            @Param("groupId") UUID groupId,
            @Param("userIds") List<UUID> userIds
    );

    /** 新增或恢复授权；重新授权会清除旧过期时间并记录本次管理员。 */
    @Insert("""
            <script>
            INSERT INTO routing_group_user_grants (
                group_id, user_id, status, granted_by, expires_at, created_at, updated_at
            ) VALUES
            <foreach collection="userIds" item="userId" separator=",">
                (#{groupId}, #{userId}, 'active', #{actorUserId}, NULL, now(), now())
            </foreach>
            ON CONFLICT (group_id, user_id)
            DO UPDATE SET status = 'active', granted_by = EXCLUDED.granted_by,
                          expires_at = NULL, updated_at = now()
            </script>
            """)
    int upsertGroupUserGrants(
            @Param("groupId") UUID groupId,
            @Param("userIds") List<UUID> userIds,
            @Param("actorUserId") UUID actorUserId
    );

    @Select("SELECT count(*) FROM ai_models WHERE id = #{id}")
    int countModel(@Param("id") UUID id);

    /** 批量校验服务分组提交的模型 ID，避免逐条查询造成不必要的数据库往返。 */
    @Select("""
            <script>
            SELECT count(*)
              FROM ai_models
             WHERE id IN
             <foreach collection="modelIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int countModels(@Param("modelIds") List<UUID> modelIds);

    /** 上游同步仍标记为开放的模型，是同步服务分组默认勾选状态的权威来源。 */
    @Select("""
            SELECT model_id
              FROM routing_group_models
             WHERE group_id = #{groupId}
               AND source_status = 'active'
             ORDER BY created_at, model_id
            """)
    List<UUID> findActiveGroupModelIds(@Param("groupId") UUID groupId);

    /** 人工分组取消勾选的模型保留历史，但不再进入用户目录和 API 令牌权限范围。 */
    @Update("""
            <script>
            UPDATE routing_group_models
               SET source_status = 'stale', updated_at = now(), version = version + 1
             WHERE group_id = #{groupId}
               AND source_type = 'manual'
               AND source_status = 'active'
             <if test="modelIds != null and !modelIds.isEmpty()">
               AND model_id NOT IN
               <foreach collection="modelIds" item="modelId" open="(" separator="," close=")">#{modelId}</foreach>
             </if>
            </script>
            """)
    int markMissingManualGroupModelsStale(
            @Param("groupId") UUID groupId,
            @Param("modelIds") List<UUID> modelIds
    );

    /** 将管理员为人工分组勾选的模型写入统一目录，并重新激活历史人工关系。 */
    @Insert("""
            <script>
            INSERT INTO routing_group_models (
                id, group_id, model_id, source_type, source_status
            ) VALUES
            <foreach collection="modelIds" item="modelId" separator=",">
                (gen_random_uuid(), #{groupId}, #{modelId}, 'manual', 'active')
            </foreach>
            ON CONFLICT (group_id, model_id)
            DO UPDATE SET source_status = 'active', updated_at = now(),
                          version = routing_group_models.version + 1
             WHERE routing_group_models.source_type = 'manual'
               AND routing_group_models.source_status != 'active'
            </script>
            """)
    int upsertManualGroupModels(
            @Param("groupId") UUID groupId,
            @Param("modelIds") List<UUID> modelIds
    );

    /** 修改组合配置前递增分组版本，确保两个管理员不能用同一份旧快照同时覆盖。 */
    @Update("""
            UPDATE routing_groups
               SET updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int touchGroupConfiguration(@Param("id") UUID id, @Param("version") long version);

    @Select("""
            SELECT rgs.id AS relation_id, #{groupId}::uuid AS group_id,
                   s.id AS supplier_id, s.name AS supplier_name,
                   coalesce(rgs.priority, 100) AS priority,
                   coalesce(rgs.weight, 100) AS weight,
                   coalesce(rgs.status, 'disabled') AS relation_status,
                   rgsc.id AS credential_id, rgsc.encrypted_credential,
                   rgsc.credential_key_version, rgsc.credential_fingerprint,
                   rgsc.status AS credential_status, rgsc.updated_at AS credential_updated_at,
                   coalesce(rgs.version, 0) AS relation_version,
                   coalesce(rgsc.version, 0) AS credential_version
              FROM suppliers s
              LEFT JOIN routing_group_suppliers rgs
                ON rgs.group_id = #{groupId} AND rgs.supplier_id = s.id
              LEFT JOIN routing_group_supplier_credentials rgsc
                ON rgsc.group_id = #{groupId} AND rgsc.supplier_id = s.id
             WHERE s.id = #{supplierId}
               AND (rgs.id IS NOT NULL OR rgsc.id IS NOT NULL)
            """)
    @Results(id = "adminGroupSupplierCredentialRow", value = {
            @Result(column = "relation_id", property = "relationId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "group_id", property = "groupId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_id", property = "supplierId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_name", property = "supplierName"),
            @Result(column = "relation_status", property = "relationStatus"),
            @Result(column = "credential_id", property = "credentialId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "encrypted_credential", property = "encryptedCredential"),
            @Result(column = "credential_key_version", property = "credentialKeyVersion"),
            @Result(column = "credential_fingerprint", property = "credentialFingerprint"),
            @Result(column = "credential_status", property = "credentialStatus"),
            @Result(column = "credential_updated_at", property = "credentialUpdatedAt"),
            @Result(column = "relation_version", property = "relationVersion"),
            @Result(column = "credential_version", property = "credentialVersion")
    })
    AdminGroupSupplierCredentialRow findGroupSupplierCredential(
            @Param("groupId") UUID groupId,
            @Param("supplierId") UUID supplierId
    );

    /** 返回分组供应商资源和凭证的脱敏状态，不把密文选入管理端列表。 */
    @Select("""
            SELECT rgs.id AS relation_id, #{groupId}::uuid AS group_id,
                   s.id AS supplier_id, s.name AS supplier_name,
                   coalesce(rgs.priority, 100) AS priority,
                   coalesce(rgs.weight, 100) AS weight,
                   coalesce(rgs.status, 'disabled') AS relation_status,
                   rgsc.id AS credential_id,
                   rgsc.credential_key_version, rgsc.credential_fingerprint,
                   rgsc.status AS credential_status, rgsc.updated_at AS credential_updated_at,
                   coalesce(rgs.version, 0) AS relation_version,
                   coalesce(rgsc.version, 0) AS credential_version
              FROM suppliers s
              LEFT JOIN routing_group_suppliers rgs
                ON rgs.group_id = #{groupId} AND rgs.supplier_id = s.id
              LEFT JOIN routing_group_supplier_credentials rgsc
                ON rgsc.group_id = #{groupId} AND rgsc.supplier_id = s.id
             WHERE rgs.id IS NOT NULL OR rgsc.id IS NOT NULL
             ORDER BY coalesce(rgs.priority, 100), s.name, s.id
            """)
    @ResultMap("adminGroupSupplierCredentialRow")
    List<AdminGroupSupplierCredentialRow> findGroupSupplierCredentials(@Param("groupId") UUID groupId);

    @Insert("""
            INSERT INTO routing_group_suppliers (
                id, group_id, supplier_id, priority, weight, status
            ) VALUES (
                #{relationId}, #{groupId}, #{supplierId}, #{priority}, #{weight}, #{relationStatus}
            )
            """)
    int insertGroupSupplier(AdminGroupSupplierCredentialRow row);

    @Update("""
            UPDATE routing_group_suppliers
               SET priority = #{priority}, weight = #{weight}, status = #{relationStatus},
                   updated_at = now(), version = version + 1
             WHERE id = #{relationId} AND version = #{relationVersion}
            """)
    int updateGroupSupplier(AdminGroupSupplierCredentialRow row);

    @Select("SELECT count(*) FROM suppliers WHERE id = #{supplierId} AND status = 'active'")
    int countActiveSupplier(@Param("supplierId") UUID supplierId);

    @Insert("""
            INSERT INTO routing_group_supplier_credentials (
                id, group_id, supplier_id, encrypted_credential, credential_key_version,
                credential_fingerprint, status
            ) VALUES (
                #{credentialId}, #{groupId}, #{supplierId}, #{encryptedCredential},
                #{credentialKeyVersion}, #{credentialFingerprint}, #{credentialStatus}
            )
            """)
    int insertGroupSupplierCredential(AdminGroupSupplierCredentialRow row);

    @Update("""
            <script>
            UPDATE routing_group_supplier_credentials
               SET status = #{credentialStatus}, updated_at = now(), version = version + 1
               <if test="encryptedCredential != null">
                 , encrypted_credential = #{encryptedCredential},
                   credential_key_version = #{credentialKeyVersion},
                   credential_fingerprint = #{credentialFingerprint}
               </if>
             WHERE id = #{credentialId} AND version = #{credentialVersion}
            </script>
            """)
    int updateGroupSupplierCredential(AdminGroupSupplierCredentialRow row);

}
