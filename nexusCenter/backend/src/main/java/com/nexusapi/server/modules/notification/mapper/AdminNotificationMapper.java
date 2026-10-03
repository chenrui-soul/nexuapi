package com.nexusapi.server.modules.notification.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 管理员站内通知数据访问层，只按角色选择有效管理员，不读取或返回用户敏感资料。 */
@Mapper
public interface AdminNotificationMapper {

    @Insert("""
            INSERT INTO notifications (id, user_id, type, title, content, action_url)
            SELECT gen_random_uuid(), u.id, #{type}, #{title}, #{content}, #{actionUrl}
              FROM users u
              JOIN user_roles ur ON ur.user_id = u.id AND ur.role_code = 'admin'
             WHERE u.status = 'active' AND u.deleted_at IS NULL
            """)
    int insertForActiveAdmins(
            @Param("type") String type,
            @Param("title") String title,
            @Param("content") String content,
            @Param("actionUrl") String actionUrl
    );
}
