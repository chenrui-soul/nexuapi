package com.nexusapi.server.modules.auth.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.auth.entity.UserAuthRow;
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

@Mapper
public interface AuthUserMapper {

    @Select("""
            SELECT id, display_name, email_ciphertext, password_hash, status, created_at,
                   email_verified_at, last_login_at, password_changed_at
              FROM users
             WHERE email_lookup_hash = #{emailHash}
               AND deleted_at IS NULL
             LIMIT 1
            """)
    @Results(id = "authUserRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "email_ciphertext", property = "emailCiphertext"),
            @Result(column = "password_hash", property = "passwordHash"),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "email_verified_at", property = "emailVerifiedAt"),
            @Result(column = "last_login_at", property = "lastLoginAt"),
            @Result(column = "password_changed_at", property = "passwordChangedAt")
    })
    UserAuthRow findByEmailHash(@Param("emailHash") byte[] emailHash);

    @Select("""
            SELECT id, display_name, email_ciphertext, password_hash, status, created_at,
                   email_verified_at, last_login_at, password_changed_at
              FROM users
             WHERE id = #{id}
               AND deleted_at IS NULL
             LIMIT 1
            """)
    @ResultMap("authUserRow")
    UserAuthRow findById(@Param("id") UUID id);

    @Select("SELECT role_code FROM user_roles WHERE user_id = #{userId} ORDER BY role_code")
    List<String> findRoles(@Param("userId") UUID userId);

    @Insert("""
            INSERT INTO users (
                id, display_name, email_ciphertext, email_lookup_hash, email_key_version,
                password_hash, password_changed_at, status, registered_ip, registered_user_agent_hash
            ) VALUES (
                #{id}, #{displayName}, #{emailCiphertext}, #{emailHash}, 1,
                #{passwordHash}, now(), 'active', CAST(#{registeredIp} AS inet), #{userAgentHash}
            )
            """)
    int insertUser(
            @Param("id") UUID id,
            @Param("displayName") String displayName,
            @Param("emailCiphertext") byte[] emailCiphertext,
            @Param("emailHash") byte[] emailHash,
            @Param("passwordHash") String passwordHash,
            @Param("registeredIp") String registeredIp,
            @Param("userAgentHash") String userAgentHash
    );

    @Insert("INSERT INTO user_roles (user_id, role_code) VALUES (#{userId}, 'user')")
    int insertDefaultRole(@Param("userId") UUID userId);

    @Insert("INSERT INTO wallet_accounts (user_id) VALUES (#{userId})")
    int insertWallet(@Param("userId") UUID userId);

    @Update("UPDATE users SET last_login_at = now() WHERE id = #{userId}")
    int markLastLogin(@Param("userId") UUID userId);

    /** 登录态修改密码，只更新密码元数据，不改变邮箱验证状态。 */
    @Update("""
            UPDATE users
               SET password_hash = #{passwordHash},
                   password_changed_at = now(),
                   updated_at = now()
             WHERE id = #{userId}
               AND status = 'active'
               AND deleted_at IS NULL
            """)
    int updatePassword(@Param("userId") UUID userId, @Param("passwordHash") String passwordHash);

    /** 邮件找回密码已经证明邮箱所有权，可同时补记邮箱验证时间。 */
    @Update("""
            UPDATE users
               SET password_hash = #{passwordHash},
                   password_changed_at = now(),
                   email_verified_at = COALESCE(email_verified_at, now()),
                   updated_at = now()
             WHERE id = #{userId}
               AND status = 'active'
               AND deleted_at IS NULL
            """)
    int resetPassword(@Param("userId") UUID userId, @Param("passwordHash") String passwordHash);
}
