package com.nexusapi.server.modules.user.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.user.entity.AdminUserRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.Set;
import java.util.UUID;
import java.util.List;

@Mapper
public interface AdminUserMapper {
    String BASE_SELECT = """
            SELECT u.id, u.display_name, u.email_ciphertext, u.status, u.email_verified_at,
                   u.last_login_at, u.created_at, u.updated_at, u.version,
                   COALESCE(string_agg(ur.role_code, ',' ORDER BY ur.role_code), '') AS roles_csv
              FROM users u
              LEFT JOIN user_roles ur ON ur.user_id = u.id
            """;

    @Select("""
            <script>
            """ + BASE_SELECT + """
             WHERE u.deleted_at IS NULL
               <if test="query != null">
                 AND (u.display_name ILIKE '%' || #{query} || '%' OR CAST(u.id AS text) ILIKE '%' || #{query} || '%')
               </if>
               <if test="emailHash != null">AND u.email_lookup_hash = #{emailHash}</if>
               <if test="status != null">AND u.status = #{status}</if>
               <if test="role != null">
                 AND EXISTS (SELECT 1 FROM user_roles filter_role WHERE filter_role.user_id = u.id AND filter_role.role_code = #{role})
               </if>
             GROUP BY u.id
             ORDER BY u.created_at DESC, u.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results(id = "adminUserRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "email_ciphertext", property = "emailCiphertext"),
            @Result(column = "status", property = "status"),
            @Result(column = "email_verified_at", property = "emailVerifiedAt"),
            @Result(column = "last_login_at", property = "lastLoginAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "version", property = "version"),
            @Result(column = "roles_csv", property = "rolesCsv")
    })
    List<AdminUserRow> findPage(
            @Param("query") String query,
            @Param("emailHash") byte[] emailHash,
            @Param("status") String status,
            @Param("role") String role,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*) FROM users u
             WHERE u.deleted_at IS NULL
               <if test="query != null">
                 AND (u.display_name ILIKE '%' || #{query} || '%' OR CAST(u.id AS text) ILIKE '%' || #{query} || '%')
               </if>
               <if test="emailHash != null">AND u.email_lookup_hash = #{emailHash}</if>
               <if test="status != null">AND u.status = #{status}</if>
               <if test="role != null">
                 AND EXISTS (SELECT 1 FROM user_roles filter_role WHERE filter_role.user_id = u.id AND filter_role.role_code = #{role})
               </if>
            </script>
            """)
    long countUsers(
            @Param("query") String query,
            @Param("emailHash") byte[] emailHash,
            @Param("status") String status,
            @Param("role") String role
    );

    @Select(BASE_SELECT + " WHERE u.id = #{id} AND u.deleted_at IS NULL GROUP BY u.id")
    @ResultMap("adminUserRow")
    AdminUserRow findById(@Param("id") UUID id);

    @Update("""
            UPDATE users
               SET display_name = #{displayName}, status = #{status}, updated_at = now(), version = version + 1
             WHERE id = #{id} AND deleted_at IS NULL AND version = #{version}
            """)
    int updateUser(
            @Param("id") UUID id,
            @Param("displayName") String displayName,
            @Param("status") String status,
            @Param("version") long version
    );

    @Delete("DELETE FROM user_roles WHERE user_id = #{userId}")
    int deleteRoles(@Param("userId") UUID userId);

    @Insert("""
            <script>
            INSERT INTO user_roles (user_id, role_code, granted_by)
            VALUES
            <foreach collection="roles" item="role" separator=",">
                (#{userId}, #{role}, #{grantedBy})
            </foreach>
            </script>
            """)
    int insertRoles(
            @Param("userId") UUID userId,
            @Param("roles") Set<String> roles,
            @Param("grantedBy") UUID grantedBy
    );
}
