package com.nexusapi.server.modules.channel.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.channel.entity.AdminChannelRow;
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

/** 渠道数据访问层。 */
@Mapper
public interface AdminChannelMapper {

    @Select("""
            SELECT c.id, c.supplier_id, s.code AS supplier_code, s.name AS supplier_name,
                   c.name, c.provider_type, c.operation_code, c.endpoint_type,
                   c.request_method, c.base_url, c.health_probe_path,
                   (c.encrypted_credential IS NOT NULL) AS credential_configured,
                   c.credential_key_version, c.credential_fingerprint, c.credential_updated_at,
                   c.proxy_url, c.status, c.timeout_ms, c.concurrency_limit, c.priority, c.weight,
                   c.consecutive_failures, c.circuit_open_until, c.last_error_summary,
                   c.created_at, c.updated_at, c.version
              FROM channels c
              JOIN suppliers s ON s.id = c.supplier_id
             WHERE c.id = #{id}
            """)
    @Results(id = "adminChannelRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_id", property = "supplierId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_code", property = "supplierCode"),
            @Result(column = "supplier_name", property = "supplierName"),
            @Result(column = "provider_type", property = "providerType"),
            @Result(column = "operation_code", property = "operationCode"),
            @Result(column = "endpoint_type", property = "endpointType"),
            @Result(column = "request_method", property = "requestMethod"),
            @Result(column = "base_url", property = "baseUrl"),
            @Result(column = "health_probe_path", property = "healthProbePath"),
            @Result(column = "credential_configured", property = "credentialConfigured"),
            @Result(column = "credential_key_version", property = "credentialKeyVersion"),
            @Result(column = "credential_fingerprint", property = "credentialFingerprint"),
            @Result(column = "credential_updated_at", property = "credentialUpdatedAt"),
            @Result(column = "proxy_url", property = "proxyUrl"),
            @Result(column = "timeout_ms", property = "timeoutMs"),
            @Result(column = "concurrency_limit", property = "concurrencyLimit"),
            @Result(column = "consecutive_failures", property = "consecutiveFailures"),
            @Result(column = "circuit_open_until", property = "circuitOpenUntil"),
            @Result(column = "last_error_summary", property = "lastErrorSummary"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    AdminChannelRow findChannelById(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT c.id, c.supplier_id, s.code AS supplier_code, s.name AS supplier_name,
                   c.name, c.provider_type, c.operation_code, c.endpoint_type,
                   c.request_method, c.base_url, c.health_probe_path,
                   (c.encrypted_credential IS NOT NULL) AS credential_configured,
                   c.credential_key_version, c.credential_fingerprint, c.credential_updated_at,
                   c.proxy_url, c.status, c.timeout_ms, c.concurrency_limit, c.priority, c.weight,
                   c.consecutive_failures, c.circuit_open_until, c.last_error_summary,
                   c.created_at, c.updated_at, c.version
              FROM channels c
              JOIN suppliers s ON s.id = c.supplier_id
             WHERE 1 = 1
               <if test="query != null">
                 AND (c.name ILIKE '%%' || #{query} || '%%'
                      OR c.provider_type ILIKE '%%' || #{query} || '%%'
                      OR c.operation_code ILIKE '%%' || #{query} || '%%'
                      OR s.code ILIKE '%%' || #{query} || '%%'
                      OR s.name ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">
                 AND c.status = #{status}
               </if>
               <if test="supplierId != null">
                 AND c.supplier_id = #{supplierId}
               </if>
             ORDER BY c.updated_at DESC, c.id
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ResultMap("adminChannelRow")
    List<AdminChannelRow> findChannelPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("supplierId") UUID supplierId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*) FROM channels c
              JOIN suppliers s ON s.id = c.supplier_id
             WHERE 1 = 1
               <if test="query != null">
                 AND (c.name ILIKE '%%' || #{query} || '%%'
                      OR c.provider_type ILIKE '%%' || #{query} || '%%'
                      OR c.operation_code ILIKE '%%' || #{query} || '%%'
                      OR s.code ILIKE '%%' || #{query} || '%%'
                      OR s.name ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">
                 AND c.status = #{status}
               </if>
               <if test="supplierId != null">
                 AND c.supplier_id = #{supplierId}
               </if>
            </script>
            """)
    long countChannels(
            @Param("query") String query,
            @Param("status") String status,
            @Param("supplierId") UUID supplierId
    );

    @Select("""
            <script>
            SELECT count(*) FROM channels
             WHERE lower(name) = lower(#{name})
               <if test="excludedId != null">AND id != #{excludedId}</if>
            </script>
            """)
    int countChannelName(@Param("name") String name, @Param("excludedId") UUID excludedId);

    @Select("SELECT count(*) FROM suppliers WHERE id = #{id}")
    int countSupplier(@Param("id") UUID id);

    @Insert("""
            INSERT INTO channels (
                id, supplier_id, name, provider_type, operation_code, endpoint_type,
                request_method, base_url, health_probe_path,
                proxy_url, status, timeout_ms,
                concurrency_limit, priority, weight, metadata
            ) VALUES (
                #{id}, #{supplierId}, #{name}, #{providerType}, #{operationCode}, #{endpointType},
                #{requestMethod}, #{baseUrl}, #{healthProbePath},
                #{proxyUrl}, #{status}, #{timeoutMs},
                #{concurrencyLimit}, #{priority}, #{weight}, '{}'::jsonb
            )
            """)
    int insertChannel(AdminChannelRow row);

    @Update("""
            <script>
            UPDATE channels
               SET supplier_id = #{supplierId}, name = #{name}, provider_type = #{providerType},
                   operation_code = #{operationCode}, endpoint_type = #{endpointType},
                   request_method = #{requestMethod}, base_url = #{baseUrl},
                   health_probe_path = #{healthProbePath},
                   proxy_url = #{proxyUrl}, status = #{status}, timeout_ms = #{timeoutMs},
                   concurrency_limit = #{concurrencyLimit}, priority = #{priority}, weight = #{weight},
                   updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            </script>
            """)
    int updateChannel(AdminChannelRow row);

}
