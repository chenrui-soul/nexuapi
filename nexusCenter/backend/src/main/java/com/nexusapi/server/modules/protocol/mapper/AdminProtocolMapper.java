package com.nexusapi.server.modules.protocol.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.protocol.entity.AdminProtocolRow;
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

/** 接口文档数据访问层；模型关系只允许由模型维护模块写入。 */
@Mapper
public interface AdminProtocolMapper {
    String COLUMNS = """
            id, interface_code, interface_name, interface_version, capability_type,
            transport_mode, http_method, public_path, request_content_type,
            request_schema::text AS request_schema_json,
            response_schema::text AS response_schema_json,
            description, status, created_at, updated_at, version
            """;

    @Select("SELECT " + COLUMNS + " FROM api_interfaces WHERE id = #{id}")
    @Results(id = "adminApiInterfaceRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "interface_code", property = "interfaceCode"),
            @Result(column = "interface_name", property = "interfaceName"),
            @Result(column = "interface_version", property = "interfaceVersion"),
            @Result(column = "capability_type", property = "capabilityType"),
            @Result(column = "transport_mode", property = "transportMode"),
            @Result(column = "http_method", property = "httpMethod"),
            @Result(column = "public_path", property = "publicPath"),
            @Result(column = "request_content_type", property = "requestContentType"),
            @Result(column = "request_schema_json", property = "requestSchemaJson"),
            @Result(column = "response_schema_json", property = "responseSchemaJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    AdminProtocolRow findById(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
              FROM api_interfaces
             WHERE 1 = 1
               <if test="query != null">
                 AND (interface_code ILIKE '%%' || #{query} || '%%'
                      OR interface_name ILIKE '%%' || #{query} || '%%'
                      OR description ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">AND status = #{status}</if>
               <if test="capabilityType != null">AND capability_type = #{capabilityType}</if>
             ORDER BY updated_at DESC, id
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ResultMap("adminApiInterfaceRow")
    List<AdminProtocolRow> findPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("capabilityType") String capabilityType,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*) FROM api_interfaces
             WHERE 1 = 1
               <if test="query != null">
                 AND (interface_code ILIKE '%%' || #{query} || '%%'
                      OR interface_name ILIKE '%%' || #{query} || '%%'
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
            SELECT count(*) FROM api_interfaces
             WHERE lower(interface_code) = lower(#{code})
               <if test="excludedId != null">AND id != #{excludedId}</if>
            </script>
            """)
    int countCode(@Param("code") String code, @Param("excludedId") UUID excludedId);

    @Insert("""
            INSERT INTO api_interfaces (
                id, interface_code, interface_name, interface_version, capability_type,
                transport_mode, http_method, public_path, request_content_type,
                request_schema, response_schema, description, status
            ) VALUES (
                #{id}, #{interfaceCode}, #{interfaceName}, #{interfaceVersion}, #{capabilityType},
                #{transportMode}, #{httpMethod}, #{publicPath}, #{requestContentType},
                CAST(#{requestSchemaJson} AS jsonb), CAST(#{responseSchemaJson} AS jsonb),
                #{description}, #{status}
            )
            """)
    int insert(AdminProtocolRow row);

    @Update("""
            UPDATE api_interfaces
               SET interface_code = #{interfaceCode}, interface_name = #{interfaceName},
                   interface_version = #{interfaceVersion}, capability_type = #{capabilityType},
                   transport_mode = #{transportMode}, http_method = #{httpMethod},
                   public_path = #{publicPath}, request_content_type = #{requestContentType},
                   request_schema = CAST(#{requestSchemaJson} AS jsonb),
                   response_schema = CAST(#{responseSchemaJson} AS jsonb),
                   description = #{description}, status = #{status},
                   updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int update(AdminProtocolRow row);
}
