package com.nexusapi.server.modules.devicekey.mapper;

import com.nexusapi.server.modules.devicekey.entity.DeviceActivationKeyRow;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 设备激活密钥持久化边界；设备绑定必须在数据库原子条件下完成。 */
@Mapper
public interface DeviceActivationKeyMapper {
    String COLUMNS = "id, name, application_code, key_prefix, key_suffix, encrypted_secret, secret_hash, device_code_hash, status, activated_at, expires_at, last_verified_at, created_at, updated_at, version";

    @Select("<script>SELECT " + COLUMNS + " FROM device_activation_keys "
            + "WHERE status &lt;&gt; 'deleted' "
            + "<if test=\"query != null and query != ''\">"
            + "AND (lower(name) LIKE concat('%', lower(#{query}), '%') "
            + "OR lower(application_code) LIKE concat('%', lower(#{query}), '%') "
            + "OR lower(key_prefix) LIKE concat('%', lower(#{query}), '%') "
            + "OR lower(key_suffix) LIKE concat('%', lower(#{query}), '%')) "
            + "</if> ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<DeviceActivationKeyRow> findPage(@Param("query") String query, @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
        <script>
        SELECT count(*) FROM device_activation_keys
         WHERE status &lt;&gt; 'deleted'
           <if test="query != null and query != ''">
             AND (lower(name) LIKE concat('%', lower(#{query}), '%')
                  OR lower(application_code) LIKE concat('%', lower(#{query}), '%')
                  OR lower(key_prefix) LIKE concat('%', lower(#{query}), '%')
                  OR lower(key_suffix) LIKE concat('%', lower(#{query}), '%'))
           </if>
        </script>
        """)
    long countPage(@Param("query") String query);

    @Select("SELECT " + COLUMNS + " FROM device_activation_keys WHERE id = #{id} LIMIT 1")
    DeviceActivationKeyRow findById(@Param("id") UUID id);

    @Select("SELECT " + COLUMNS + " FROM device_activation_keys WHERE secret_hash = #{secretHash} LIMIT 1")
    DeviceActivationKeyRow findBySecretHash(@Param("secretHash") byte[] secretHash);

    @Insert("""
        INSERT INTO device_activation_keys (id, name, application_code, key_prefix, key_suffix, encrypted_secret, secret_hash, expires_at)
        VALUES (#{id}, #{name}, #{applicationCode}, #{keyPrefix}, #{keySuffix}, #{encryptedSecret}, #{secretHash}, #{expiresAt})
        """)
    int insert(UUID id, String name, String applicationCode, String keyPrefix, String keySuffix,
               byte[] encryptedSecret, byte[] secretHash, Instant expiresAt);

    @Update("""
        UPDATE device_activation_keys
           SET device_code_hash = #{deviceCodeHash}, activated_at = COALESCE(activated_at, now()), updated_at = now(), version = version + 1
         WHERE id = #{id} AND status = 'active' AND device_code_hash IS NULL
           AND (expires_at IS NULL OR expires_at > now())
        """)
    int bindDevice(@Param("id") UUID id, @Param("deviceCodeHash") byte[] deviceCodeHash);

    @Update("UPDATE device_activation_keys SET last_verified_at = now(), updated_at = now() WHERE id = #{id}")
    int touchVerified(@Param("id") UUID id);

    @Update("UPDATE device_activation_keys SET status = #{status}, updated_at = now(), version = version + 1 WHERE id = #{id} AND status IN ('active','disabled') AND version = #{version}")
    int updateStatus(@Param("id") UUID id, @Param("status") String status, @Param("version") long version);

    @Update("UPDATE device_activation_keys SET status = 'deleted', updated_at = now(), version = version + 1 WHERE id = #{id} AND status <> 'deleted'")
    int markDeleted(@Param("id") UUID id);
}
