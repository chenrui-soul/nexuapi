package com.nexusapi.server.modules.health.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.health.entity.GroupHealthStateRow;
import com.nexusapi.server.modules.health.entity.HealthAlertRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 分组健康聚合与告警状态机的数据访问层。 */
@Mapper
public interface GroupHealthAlertMapper {

    /** 查询使用指定渠道能力的服务分组，去重后逐组进行健康聚合。 */
    @Select("""
            SELECT DISTINCT rgm.group_id
              FROM routing_group_models rgm
              JOIN ai_models m ON m.id = rgm.model_id AND m.status = 'active'
              JOIN routing_group_suppliers rgs
                ON rgs.group_id = rgm.group_id AND rgs.status = 'active'
              JOIN channels c ON c.supplier_id = rgs.supplier_id AND c.id = #{channelId}
             WHERE rgm.source_status = 'active'
               AND (
                    c.endpoint_type = 'multimodal'
                    OR c.endpoint_type = m.capability_type
                    OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text')
               )
            """)
    List<UUID> findAffectedGroupIds(@Param("channelId") UUID channelId);

    /**
     * 使用 PostgreSQL 事务级 advisory lock 串行化同一分组的告警转换。
     * 锁会随事务提交自动释放，不需要应用层维护锁生命周期。
     */
    @Select("""
            SELECT 1
              FROM (SELECT pg_advisory_xact_lock(
                    hashtextextended(CAST(#{groupId} AS text), 71001)
              )) locked
            """)
    int lockGroup(@Param("groupId") UUID groupId);

    /** 实时聚合配置路由数与当前可路由数，不修改管理员维护的 routing_groups.status。 */
    @Select("""
            SELECT g.id AS group_id,
                   g.code AS group_code,
                   g.name AS group_name,
                   g.status AS configuration_status,
                   CASE
                       WHEN g.status != 'active' THEN 'unconfigured'
                       WHEN routes.configured_count = 0 THEN 'unconfigured'
                       WHEN routes.available_count = 0 THEN 'unavailable'
                       WHEN routes.available_count < routes.configured_count THEN 'degraded'
                       ELSE 'healthy'
                   END AS health_status,
                   routes.configured_count AS configured_route_count,
                   routes.available_count AS available_route_count
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
             WHERE g.id = #{groupId}
            """)
    @Results(id = "groupHealthState", value = {
            @Result(column = "group_id", property = "groupId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "group_code", property = "groupCode"),
            @Result(column = "group_name", property = "groupName"),
            @Result(column = "configuration_status", property = "configurationStatus"),
            @Result(column = "health_status", property = "healthStatus"),
            @Result(column = "configured_route_count", property = "configuredRouteCount"),
            @Result(column = "available_route_count", property = "availableRouteCount")
    })
    GroupHealthStateRow findGroupHealthState(@Param("groupId") UUID groupId);

    /** 最近一次分组健康结论用于只在状态转换时追加健康历史。 */
    @Select("""
            SELECT status
              FROM health_checks
             WHERE target_type = 'group' AND target_id = #{groupId}
             ORDER BY checked_at DESC, id DESC
             LIMIT 1
            """)
    String findLatestGroupHealthStatus(@Param("groupId") UUID groupId);

    @Insert("""
            INSERT INTO health_checks (target_type, target_id, status, latency_ms, error_summary)
            VALUES ('group', #{groupId}, #{status}, NULL, #{safeSummary})
            """)
    int insertGroupHealthCheck(
            @Param("groupId") UUID groupId,
            @Param("status") String status,
            @Param("safeSummary") String safeSummary
    );

    /** advisory lock 已完成串行化，此处再加行锁保护既有告警记录。 */
    @Select("""
            SELECT a.id, a.group_id, g.code AS group_code, g.name AS group_name,
                   a.alert_type, a.status, a.severity, a.title, a.summary,
                   a.occurrence_count, a.notification_count, a.suppressed_count,
                   a.opened_at, a.last_seen_at, a.last_notified_at, a.resolved_at, a.updated_at
              FROM health_alerts a
              JOIN routing_groups g ON g.id = a.group_id
             WHERE a.group_id = #{groupId}
               AND a.alert_type = 'group_unavailable'
               AND a.status = 'open'
             FOR UPDATE OF a
            """)
    @Results(id = "healthAlert", value = {
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
    HealthAlertRow findOpenAlertForUpdate(@Param("groupId") UUID groupId);

    @Insert("""
            INSERT INTO health_alerts (
                group_id, alert_type, status, severity, title, summary,
                occurrence_count, notification_count, suppressed_count
            ) VALUES (
                #{groupId}, 'group_unavailable', 'open', 'critical', #{title}, #{summary}, 1, 0, 0
            )
            """)
    int insertOpenAlert(
            @Param("groupId") UUID groupId,
            @Param("title") String title,
            @Param("summary") String summary
    );

    /** 冷却期内只累计观察次数和抑制次数，不生成重复站内通知。 */
    @Update("""
            UPDATE health_alerts
               SET occurrence_count = occurrence_count + 1,
                   suppressed_count = suppressed_count + 1,
                   last_seen_at = now(), updated_at = now()
             WHERE id = #{alertId} AND status = 'open'
            """)
    int markSuppressed(@Param("alertId") long alertId);

    /** 超过冷却期或首次打开时记录一次实际通知批次。 */
    @Update("""
            UPDATE health_alerts
               SET occurrence_count = occurrence_count + #{occurrenceIncrement},
                   notification_count = notification_count + 1,
                   last_seen_at = now(), last_notified_at = now(), updated_at = now()
             WHERE id = #{alertId} AND status = 'open'
            """)
    int markNotified(
            @Param("alertId") long alertId,
            @Param("occurrenceIncrement") int occurrenceIncrement
    );

    /** 恢复转换只允许解析仍处于 open 的告警，因此恢复通知天然只发送一次。 */
    @Update("""
            UPDATE health_alerts
               SET status = 'resolved', resolved_at = now(), last_seen_at = now(), updated_at = now()
             WHERE id = #{alertId} AND status = 'open'
            """)
    int resolveAlert(@Param("alertId") long alertId);
}
