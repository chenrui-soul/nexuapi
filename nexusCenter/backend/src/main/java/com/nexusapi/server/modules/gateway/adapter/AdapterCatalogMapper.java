package com.nexusapi.server.modules.gateway.adapter;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
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

/** 适配器目录数据访问层；只保存可审计的目录配置，不保存凭证。 */
@Mapper
public interface AdapterCatalogMapper {
    String COLUMNS = """
            id, adapter_key, display_name, capability_type, implementation_key,
            description, status, built_in, created_at, updated_at, version
            """;

    @Select("SELECT " + COLUMNS + " FROM gateway_adapters WHERE id = #{id}")
    @Results(id = "adapterCatalogRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "adapter_key", property = "adapterKey"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "capability_type", property = "capabilityType"),
            @Result(column = "implementation_key", property = "implementationKey"),
            @Result(column = "description", property = "description"),
            @Result(column = "status", property = "status"),
            @Result(column = "built_in", property = "builtIn"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "version", property = "version")
    })
    AdapterCatalogRow findById(@Param("id") UUID id);

    @Select("SELECT " + COLUMNS + " FROM gateway_adapters WHERE adapter_key = #{adapterKey}")
    @ResultMap("adapterCatalogRow")
    AdapterCatalogRow findByKey(@Param("adapterKey") String adapterKey);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
              FROM gateway_adapters
             WHERE 1 = 1
               <if test="query != null">
                 AND (adapter_key ILIKE '%%' || #{query} || '%%'
                      OR display_name ILIKE '%%' || #{query} || '%%'
                      OR description ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">AND status = #{status}</if>
               <if test="capabilityType != null">AND capability_type = #{capabilityType}</if>
             ORDER BY built_in DESC, updated_at DESC, id
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ResultMap("adapterCatalogRow")
    List<AdapterCatalogRow> findPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("capabilityType") String capabilityType,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*) FROM gateway_adapters
             WHERE 1 = 1
               <if test="query != null">
                 AND (adapter_key ILIKE '%%' || #{query} || '%%'
                      OR display_name ILIKE '%%' || #{query} || '%%'
                      OR description ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">AND status = #{status}</if>
               <if test="capabilityType != null">AND capability_type = #{capabilityType}</if>
            </script>
            """)
    long countPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("capabilityType") String capabilityType
    );

    @Select("""
            <script>
            SELECT count(*) FROM gateway_adapters
             WHERE lower(adapter_key) = lower(#{adapterKey})
               <if test="excludedId != null">AND id &lt;&gt; #{excludedId}</if>
            </script>
            """)
    int countKey(@Param("adapterKey") String adapterKey, @Param("excludedId") UUID excludedId);

    @Insert("""
            INSERT INTO gateway_adapters (
                id, adapter_key, display_name, capability_type, implementation_key,
                description, status, built_in
            ) VALUES (
                #{id}, #{adapterKey}, #{displayName}, #{capabilityType}, #{implementationKey},
                #{description}, #{status}, #{builtIn}
            )
            """)
    int insert(AdapterCatalogRow row);

    @Update("""
            UPDATE gateway_adapters
               SET display_name = #{displayName}, capability_type = #{capabilityType},
                   implementation_key = #{implementationKey}, description = #{description},
                   status = #{status}, updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int update(AdapterCatalogRow row);
}
