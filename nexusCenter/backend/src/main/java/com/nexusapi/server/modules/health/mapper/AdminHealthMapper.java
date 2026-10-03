package com.nexusapi.server.modules.health.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.health.entity.AdminChannelHealthRow;
import com.nexusapi.server.modules.health.entity.AdminGroupHealthRow;
import com.nexusapi.server.modules.health.entity.AdminHealthCheckRow;
import com.nexusapi.server.modules.health.entity.HealthAlertRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 管理端健康只读查询 Mapper，所有接口都使用有界分页。 */
@Mapper
public interface AdminHealthMapper {

    @Select("""
            <script>
            SELECT c.id AS channel_id, c.name AS channel_name, s.name AS supplier_name,
                   c.status AS channel_status, c.health_probe_path, c.consecutive_failures,
                   c.circuit_open_until, latest.status AS latest_check_status,
                   latest.latency_ms AS latest_latency_ms, latest.error_summary AS latest_error_summary,
                   latest.checked_at AS latest_checked_at
              FROM channels c
              JOIN suppliers s ON s.id = c.supplier_id
              LEFT JOIN LATERAL (
                    SELECT h.status, h.latency_ms, h.error_summary, h.checked_at
                      FROM health_checks h
                     WHERE h.target_type = 'channel' AND h.target_id = c.id
                     ORDER BY h.checked_at DESC, h.id DESC LIMIT 1
              ) latest ON true
             WHERE 1 = 1
               <if test="query != null">
                 AND (lower(c.name) LIKE concat('%', #{query}, '%')
                      OR lower(s.name) LIKE concat('%', #{query}, '%'))
               </if>
               <if test="status != null">AND c.status = #{status}</if>
             ORDER BY CASE c.status WHEN 'degraded' THEN 1 ELSE 2 END,
                      c.updated_at DESC, c.id
             OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    @Results(id = "adminChannelHealth", value = {
            @Result(column = "channel_id", property = "channelId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "channel_name", property = "channelName"),
            @Result(column = "supplier_name", property = "supplierName"),
            @Result(column = "channel_status", property = "channelStatus"),
            @Result(column = "health_probe_path", property = "healthProbePath"),
            @Result(column = "consecutive_failures", property = "consecutiveFailures"),
            @Result(column = "circuit_open_until", property = "circuitOpenUntil"),
            @Result(column = "latest_check_status", property = "latestCheckStatus"),
            @Result(column = "latest_latency_ms", property = "latestLatencyMs"),
            @Result(column = "latest_error_summary", property = "latestErrorSummary"),
            @Result(column = "latest_checked_at", property = "latestCheckedAt")
    })
    List<AdminChannelHealthRow> findChannelPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*) FROM channels c JOIN suppliers s ON s.id = c.supplier_id
             WHERE 1 = 1
               <if test="query != null">
                 AND (lower(c.name) LIKE concat('%', #{query}, '%')
                      OR lower(s.name) LIKE concat('%', #{query}, '%'))
               </if>
               <if test="status != null">AND c.status = #{status}</if>
            </script>
            """)
    long countChannels(@Param("query") String query, @Param("status") String status);

    @Select("""
            <script>
            SELECT g.id AS group_id, g.code AS group_code, g.name AS group_name,
                   g.status AS configuration_status,
                   CASE
                       WHEN g.status != 'active' THEN 'unconfigured'
                       WHEN routes.configured_count = 0 THEN 'unconfigured'
                       WHEN routes.available_count = 0 THEN 'unavailable'
                       WHEN routes.available_count &lt; routes.configured_count THEN 'degraded'
                       ELSE 'healthy'
                   END AS health_status,
                   routes.configured_count AS configured_route_count,
                   routes.available_count AS available_route_count,
                   latest.checked_at AS latest_checked_at
              FROM routing_groups g
              LEFT JOIN LATERAL (
                    SELECT count(*) AS configured_count,
                           count(*) FILTER (
                               WHERE c.status IN ('active', 'degraded')
                                 AND s.status = 'active' AND s.health_status != 'unavailable'
                           ) AS available_count
                      FROM routing_group_models rgm
                      JOIN ai_models m ON m.id = rgm.model_id AND m.status = 'active'
                      JOIN routing_group_suppliers rgs ON rgs.group_id = rgm.group_id AND rgs.status = 'active'
                      JOIN routing_group_supplier_credentials rgsc
                        ON rgsc.group_id = rgm.group_id AND rgsc.supplier_id = rgs.supplier_id
                       AND rgsc.status = 'active' AND rgsc.encrypted_credential IS NOT NULL
                      JOIN suppliers s ON s.id = rgs.supplier_id
                      JOIN channels c ON c.supplier_id = s.id
                     WHERE rgm.group_id = g.id AND rgm.source_status = 'active'
                       AND (
                            c.endpoint_type = 'multimodal'
                            OR c.endpoint_type = m.capability_type
                            OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text')
                       )
              ) routes ON true
              LEFT JOIN LATERAL (
                    SELECT h.checked_at FROM health_checks h
                     WHERE h.target_type = 'group' AND h.target_id = g.id
                     ORDER BY h.checked_at DESC, h.id DESC LIMIT 1
              ) latest ON true
             WHERE 1 = 1
               <if test="query != null">
                 AND (lower(g.code) LIKE concat('%', #{query}, '%')
                      OR lower(g.name) LIKE concat('%', #{query}, '%'))
               </if>
             <if test="status != null">AND (CASE
                 WHEN g.status != 'active' THEN 'unconfigured'
                 WHEN routes.configured_count = 0 THEN 'unconfigured'
                 WHEN routes.available_count = 0 THEN 'unavailable'
                 WHEN routes.available_count &lt; routes.configured_count THEN 'degraded'
                 ELSE 'healthy' END) = #{status}</if>
             ORDER BY CASE WHEN routes.available_count = 0 THEN 0 ELSE 1 END,
                      g.code
             OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    @Results(id = "adminGroupHealth", value = {
            @Result(column = "group_id", property = "groupId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "group_code", property = "groupCode"),
            @Result(column = "group_name", property = "groupName"),
            @Result(column = "configuration_status", property = "configurationStatus"),
            @Result(column = "health_status", property = "healthStatus"),
            @Result(column = "configured_route_count", property = "configuredRouteCount"),
            @Result(column = "available_route_count", property = "availableRouteCount"),
            @Result(column = "latest_checked_at", property = "latestCheckedAt")
    })
    List<AdminGroupHealthRow> findGroupPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*)
              FROM (
                    SELECT g.id,
                           CASE
                               WHEN g.status != 'active' THEN 'unconfigured'
                               WHEN routes.configured_count = 0 THEN 'unconfigured'
                               WHEN routes.available_count = 0 THEN 'unavailable'
                               WHEN routes.available_count &lt; routes.configured_count THEN 'degraded'
                               ELSE 'healthy'
                           END AS health_status
                      FROM routing_groups g
                      LEFT JOIN LATERAL (
                            SELECT count(*) AS configured_count,
                                   count(*) FILTER (
                                       WHERE c.status IN ('active', 'degraded')
                                         AND s.status = 'active' AND s.health_status != 'unavailable'
                                   ) AS available_count
                              FROM routing_group_models rgm
                              JOIN ai_models m ON m.id = rgm.model_id AND m.status = 'active'
                              JOIN routing_group_suppliers rgs ON rgs.group_id = rgm.group_id AND rgs.status = 'active'
                              JOIN routing_group_supplier_credentials rgsc
                                ON rgsc.group_id = rgm.group_id AND rgsc.supplier_id = rgs.supplier_id
                               AND rgsc.status = 'active' AND rgsc.encrypted_credential IS NOT NULL
                              JOIN suppliers s ON s.id = rgs.supplier_id
                              JOIN channels c ON c.supplier_id = s.id
                             WHERE rgm.group_id = g.id AND rgm.source_status = 'active'
                               AND (
                                    c.endpoint_type = 'multimodal'
                                    OR c.endpoint_type = m.capability_type
                                    OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text')
                               )
                      ) routes ON true
                     WHERE 1 = 1
                       <if test="query != null">
                         AND (lower(g.code) LIKE concat('%', #{query}, '%')
                              OR lower(g.name) LIKE concat('%', #{query}, '%'))
                       </if>
              ) aggregate
             WHERE 1 = 1
               <if test="status != null">AND aggregate.health_status = #{status}</if>
            </script>
            """)
    long countGroups(@Param("query") String query, @Param("status") String status);

    @Select("""
            <script>
            SELECT h.id, h.target_type, h.target_id,
                   CASE WHEN h.target_type = 'channel' THEN c.name ELSE g.name END AS target_name,
                   h.status, h.latency_ms, h.error_summary, h.checked_at
              FROM health_checks h
              LEFT JOIN channels c ON h.target_type = 'channel' AND c.id = h.target_id
              LEFT JOIN routing_groups g ON h.target_type = 'group' AND g.id = h.target_id
             WHERE 1 = 1
               <if test="targetType != null">AND h.target_type = #{targetType}</if>
               <if test="status != null">AND h.status = #{status}</if>
             ORDER BY h.checked_at DESC, h.id DESC
             OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    @Results(id = "adminHealthCheck", value = {
            @Result(column = "target_type", property = "targetType"),
            @Result(column = "target_id", property = "targetId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "target_name", property = "targetName"),
            @Result(column = "latency_ms", property = "latencyMs"),
            @Result(column = "error_summary", property = "errorSummary"),
            @Result(column = "checked_at", property = "checkedAt")
    })
    List<AdminHealthCheckRow> findCheckPage(
            @Param("targetType") String targetType,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*) FROM health_checks
             WHERE 1 = 1
               <if test="targetType != null">AND target_type = #{targetType}</if>
               <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countChecks(@Param("targetType") String targetType, @Param("status") String status);

    @Select("""
            <script>
            SELECT a.id, a.group_id, g.code AS group_code, g.name AS group_name,
                   a.alert_type, a.status, a.severity, a.title, a.summary,
                   a.occurrence_count, a.notification_count, a.suppressed_count,
                   a.opened_at, a.last_seen_at, a.last_notified_at, a.resolved_at, a.updated_at
              FROM health_alerts a JOIN routing_groups g ON g.id = a.group_id
             WHERE 1 = 1
               <if test="status != null">AND a.status = #{status}</if>
             ORDER BY CASE a.status WHEN 'open' THEN 0 ELSE 1 END, a.updated_at DESC, a.id DESC
             OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    @Results(id = "adminHealthAlert", value = {
            @Result(column = "group_id", property = "groupId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "group_code", property = "groupCode"),
            @Result(column = "group_name", property = "groupName"),
            @Result(column = "alert_type", property = "alertType"),
            @Result(column = "occurrence_count", property = "occurrenceCount"),
            @Result(column = "notification_count", property = "notificationCount"),
            @Result(column = "suppressed_count", property = "suppressedCount"),
            @Result(column = "opened_at", property = "openedAt"),
            @Result(column = "last_seen_at", property = "lastSeenAt"),
            @Result(column = "last_notified_at", property = "lastNotifiedAt"),
            @Result(column = "resolved_at", property = "resolvedAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<HealthAlertRow> findAlertPage(
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>SELECT count(*) FROM health_alerts
             <if test="status != null">WHERE status = #{status}</if></script>
            """)
    long countAlerts(@Param("status") String status);
}
