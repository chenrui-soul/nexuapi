package com.nexusapi.server.modules.health.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.health.entity.ChannelHealthStateRow;
import com.nexusapi.server.modules.health.entity.ChannelHealthTarget;
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

/** 渠道健康状态的数据访问层；失败观测递增由 PostgreSQL 原子完成，但不再触发接口熔断。 */
@Mapper
public interface ChannelHealthMapper {

    /**
     * 有界查询本轮应探测的渠道。
     * 可调用渠道按最近探测时间轮转，人工 disabled 渠道永不返回。
     */
    @Select("""
            SELECT c.id AS channel_id, c.supplier_id, c.status, c.base_url, c.health_probe_path,
                   credential.encrypted_credential, credential.credential_key_version, c.timeout_ms
              FROM channels c
              JOIN suppliers s ON s.id = c.supplier_id
              JOIN LATERAL (
                    SELECT rgsc.encrypted_credential, rgsc.credential_key_version
                      FROM routing_group_suppliers rgs
                      JOIN routing_groups g ON g.id = rgs.group_id AND g.status = 'active'
                      JOIN routing_group_supplier_credentials rgsc
                        ON rgsc.group_id = rgs.group_id AND rgsc.supplier_id = rgs.supplier_id
                       AND rgsc.status = 'active'
                     WHERE rgs.supplier_id = c.supplier_id AND rgs.status = 'active'
                     ORDER BY rgs.priority, rgsc.updated_at DESC, rgsc.id
                     LIMIT 1
              ) credential ON true
              LEFT JOIN LATERAL (
                    SELECT max(h.checked_at) AS checked_at
                      FROM health_checks h
                     WHERE h.target_type = 'channel' AND h.target_id = c.id
              ) latest ON true
             WHERE s.status = 'active'
               AND credential.encrypted_credential IS NOT NULL
               AND c.status IN ('active', 'degraded')
               AND (latest.checked_at IS NULL
                    OR latest.checked_at <= now() - (#{probeIntervalSeconds} * interval '1 second'))
             ORDER BY latest.checked_at NULLS FIRST, c.id
             LIMIT #{limit}
            """)
    @Results(id = "channelHealthTarget", value = {
            @Result(column = "channel_id", property = "channelId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_id", property = "supplierId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "base_url", property = "baseUrl"),
            @Result(column = "health_probe_path", property = "healthProbePath"),
            @Result(column = "encrypted_credential", property = "encryptedCredential"),
            @Result(column = "credential_key_version", property = "credentialKeyVersion"),
            @Result(column = "timeout_ms", property = "timeoutMs")
    })
    List<ChannelHealthTarget> findDueProbeTargets(
            @Param("probeIntervalSeconds") long probeIntervalSeconds,
            @Param("limit") int limit
    );

    /** 管理员手动探测读取单个渠道，不绕过供应商合作状态和凭证配置要求。 */
    @Select("""
            SELECT c.id AS channel_id, c.supplier_id, c.status, c.base_url, c.health_probe_path,
                   credential.encrypted_credential, credential.credential_key_version, c.timeout_ms
              FROM channels c
              JOIN suppliers s ON s.id = c.supplier_id
              JOIN LATERAL (
                    SELECT rgsc.encrypted_credential, rgsc.credential_key_version
                      FROM routing_group_suppliers rgs
                      JOIN routing_groups g ON g.id = rgs.group_id AND g.status = 'active'
                      JOIN routing_group_supplier_credentials rgsc
                        ON rgsc.group_id = rgs.group_id AND rgsc.supplier_id = rgs.supplier_id
                       AND rgsc.status = 'active'
                     WHERE rgs.supplier_id = c.supplier_id AND rgs.status = 'active'
                     ORDER BY rgs.priority, rgsc.updated_at DESC, rgsc.id
                     LIMIT 1
              ) credential ON true
             WHERE c.id = #{channelId}
               AND s.status = 'active'
               AND credential.encrypted_credential IS NOT NULL
            """)
    @ResultMap("channelHealthTarget")
    ChannelHealthTarget findProbeTargetById(@Param("channelId") UUID channelId);

    @Select("""
            SELECT id AS channel_id, supplier_id, status, consecutive_failures, circuit_open_until
              FROM channels
             WHERE id = #{channelId}
            """)
    @Results(id = "channelHealthState", value = {
            @Result(column = "channel_id", property = "channelId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_id", property = "supplierId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "consecutive_failures", property = "consecutiveFailures"),
            @Result(column = "circuit_open_until", property = "circuitOpenUntil")
    })
    ChannelHealthStateRow findState(@Param("channelId") UUID channelId);

    /**
     * 真实请求成功恢复自动健康状态，人工 disabled 渠道不会被修改。
     * 健康观测不是管理员配置变更，因此不得递增渠道配置 version。
     */
    @Update("""
            UPDATE channels
               SET status = 'active', consecutive_failures = 0, circuit_open_until = NULL,
                   last_error_summary = NULL
             WHERE id = #{channelId}
               AND status = 'degraded'
            """)
    int restoreFromGatewaySuccess(@Param("channelId") UUID channelId);

    /**
     * 探测成功只恢复探测自身造成的波动或遗留熔断状态；真实 Gateway 失败必须由真实调用成功恢复。
     * 并发期间被管理员 disabled 的渠道不会被修改。
     * 健康字段独立于管理员配置乐观锁，避免后台探测导致编辑页面保存冲突。
     */
    @Update("""
            UPDATE channels
               SET status = 'active', consecutive_failures = 0, circuit_open_until = NULL,
                   last_error_summary = NULL
             WHERE id = #{channelId}
               AND (
                    (status = 'degraded' AND last_error_summary LIKE 'probe_%')
                    OR status = 'circuit_open'
                    OR (status = 'active' AND last_error_summary LIKE 'probe_%'
                        AND (consecutive_failures != 0
                        OR circuit_open_until IS NOT NULL OR last_error_summary IS NOT NULL))
               )
            """)
    int restoreFromProbeSuccess(@Param("channelId") UUID channelId);

    /**
     * 管理员手动探测成功会恢复自动波动或遗留熔断状态，但绝不修改 disabled。
     * 手动探测仍属于健康观测，不递增管理员配置 version。
     */
    @Update("""
            UPDATE channels
               SET status = 'active', consecutive_failures = 0, circuit_open_until = NULL,
                   last_error_summary = NULL
             WHERE id = #{channelId}
               AND status IN ('active', 'degraded', 'circuit_open')
            """)
    int restoreFromManualProbeSuccess(@Param("channelId") UUID channelId);

    /**
     * 失败计数在同一条 SQL 内完成，防止并发请求丢失增量。
     * 失败只形成 degraded 观测状态，不再写入 circuit_open，也不再阻断真实流量。
     * 失败观测不得递增配置 version，否则周期探测会让管理员编辑表单立即过期。
     */
    @Update("""
            UPDATE channels
               SET consecutive_failures = LEAST(consecutive_failures + 1, 2147483647),
                   status = 'degraded',
                   circuit_open_until = NULL,
                   last_error_summary = #{safeSummary}
             WHERE id = #{channelId}
               AND status IN ('active', 'degraded', 'circuit_open')
            """)
    int incrementFailure(
            @Param("channelId") UUID channelId,
            @Param("safeSummary") String safeSummary
    );

    @Insert("""
            INSERT INTO health_checks (target_type, target_id, status, latency_ms, error_summary)
            VALUES ('channel', #{channelId}, #{status}, #{latencyMs}, #{errorSummary})
            """)
    int insertHealthCheck(
            @Param("channelId") UUID channelId,
            @Param("status") String status,
            @Param("latencyMs") Integer latencyMs,
            @Param("errorSummary") String errorSummary
    );

    /**
     * 只聚合供应商自动健康字段，不修改人工合作 status。
     * disabled 渠道不参与健康判定，避免管理员维护停用被误报为供应商故障。
     */
    @Update("""
            UPDATE suppliers s
               SET health_status = aggregate.health_status,
                   last_health_checked_at = now(),
                   updated_at = CASE
                       WHEN s.health_status IS DISTINCT FROM aggregate.health_status THEN now()
                       ELSE s.updated_at
                   END,
                   version = CASE
                       WHEN s.health_status IS DISTINCT FROM aggregate.health_status THEN s.version + 1
                       ELSE s.version
                   END
              FROM (
                    SELECT s2.id AS supplier_id,
                           CASE
                               WHEN count(c.id) FILTER (WHERE c.status != 'disabled') = 0 THEN 'unconfigured'
                               WHEN count(c.id) FILTER (WHERE c.status IN ('active', 'degraded')) = 0 THEN 'unavailable'
                               WHEN count(c.id) FILTER (WHERE c.status IN ('degraded', 'circuit_open')) > 0 THEN 'degraded'
                               ELSE 'healthy'
                           END AS health_status
                      FROM suppliers s2
                      LEFT JOIN channels c ON c.supplier_id = s2.id
                     WHERE s2.id = #{supplierId}
                     GROUP BY s2.id
              ) aggregate
             WHERE s.id = aggregate.supplier_id
            """)
    int aggregateSupplierHealth(@Param("supplierId") UUID supplierId);

    /** 探测端点未配置时只更新时间，不把“无法探测”误标成 healthy 或 unavailable。 */
    @Update("""
            UPDATE suppliers
               SET last_health_checked_at = now()
             WHERE id = #{supplierId}
            """)
    int touchSupplierHealthCheck(@Param("supplierId") UUID supplierId);
}
