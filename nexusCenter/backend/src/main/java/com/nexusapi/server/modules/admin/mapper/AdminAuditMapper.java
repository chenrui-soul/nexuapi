package com.nexusapi.server.modules.admin.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.admin.entity.AdminAuditLogRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 管理配置审计的持久化入口，只接受已经剔除敏感字段的 JSON。 */
@Mapper
public interface AdminAuditMapper {

    @Insert("""
            INSERT INTO audit_logs (
                actor_user_id, actor_type, action, resource_type, resource_id,
                before_data, after_data, ip_address, user_agent_hash, request_id
            ) VALUES (
                #{actorUserId}, 'user', #{action}, #{resourceType}, #{resourceId},
                CAST(#{beforeJson} AS jsonb), CAST(#{afterJson} AS jsonb),
                CAST(#{ipAddress} AS inet), #{userAgentHash}, #{requestId}
            )
            """)
    int insert(
            @Param("actorUserId") UUID actorUserId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("beforeJson") String beforeJson,
            @Param("afterJson") String afterJson,
            @Param("ipAddress") String ipAddress,
            @Param("userAgentHash") String userAgentHash,
            @Param("requestId") String requestId
    );

    @Select("""
            <script>
            SELECT a.id, a.actor_user_id, u.display_name AS actor_display_name, a.actor_type,
                   a.action, a.resource_type, a.resource_id,
                   COALESCE(a.before_data, '{}'::jsonb)::text AS before_json,
                   COALESCE(a.after_data, '{}'::jsonb)::text AS after_json,
                   CAST(a.ip_address AS text) AS ip_address, a.user_agent_hash, a.request_id, a.created_at
              FROM audit_logs a
              LEFT JOIN users u ON u.id = a.actor_user_id
             WHERE 1 = 1
               <if test="query != null">
                 AND (a.action ILIKE '%' || #{query} || '%'
                      OR a.resource_type ILIKE '%' || #{query} || '%'
                      OR COALESCE(a.resource_id, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(a.request_id, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(u.display_name, '') ILIKE '%' || #{query} || '%')
               </if>
               <if test="resourceType != null">AND a.resource_type = #{resourceType}</if>
               <if test="actorType != null">AND a.actor_type = #{actorType}</if>
             ORDER BY a.created_at DESC, a.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results(id = "adminAuditLogRow", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "actor_user_id", property = "actorUserId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "actor_display_name", property = "actorDisplayName"),
            @Result(column = "actor_type", property = "actorType"),
            @Result(column = "action", property = "action"),
            @Result(column = "resource_type", property = "resourceType"),
            @Result(column = "resource_id", property = "resourceId"),
            @Result(column = "before_json", property = "beforeJson"),
            @Result(column = "after_json", property = "afterJson"),
            @Result(column = "ip_address", property = "ipAddress"),
            @Result(column = "user_agent_hash", property = "userAgentHash"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<AdminAuditLogRow> findPage(
            @Param("query") String query,
            @Param("resourceType") String resourceType,
            @Param("actorType") String actorType,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*)
              FROM audit_logs a
              LEFT JOIN users u ON u.id = a.actor_user_id
             WHERE 1 = 1
               <if test="query != null">
                 AND (a.action ILIKE '%' || #{query} || '%'
                      OR a.resource_type ILIKE '%' || #{query} || '%'
                      OR COALESCE(a.resource_id, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(a.request_id, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(u.display_name, '') ILIKE '%' || #{query} || '%')
               </if>
               <if test="resourceType != null">AND a.resource_type = #{resourceType}</if>
               <if test="actorType != null">AND a.actor_type = #{actorType}</if>
            </script>
            """)
    long countLogs(
            @Param("query") String query,
            @Param("resourceType") String resourceType,
            @Param("actorType") String actorType
    );
}
