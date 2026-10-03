package com.nexusapi.server.modules.routing.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.routing.vo.PublicServiceGroupResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 用户侧服务分组目录只按启用状态和用户可见性过滤，不依赖运行时路由健康。 */
@Mapper
public interface ServiceGroupCatalogMapper {

    @Select("""
            <script>
            SELECT g.id, g.code, g.name, g.description, g.price_multiplier,
                   count(DISTINCT m.id) AS model_count
              FROM routing_groups g
              LEFT JOIN routing_group_models rgm
                ON rgm.group_id = g.id AND rgm.source_status = 'active'
              LEFT JOIN ai_models m
                ON m.id = rgm.model_id AND m.status = 'active' AND m.public_visible = true
             WHERE g.status = 'active'
               AND (
                    g.audience = 'all'
                    <if test="userId != null">
                    OR (g.audience = 'assigned' AND EXISTS (
                        SELECT 1
                          FROM routing_group_user_grants grant_row
                          JOIN users authorized_user ON authorized_user.id = grant_row.user_id
                         WHERE grant_row.group_id = g.id
                           AND grant_row.user_id = #{userId}
                           AND grant_row.status = 'active'
                           AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
                           AND authorized_user.status = 'active'
                           AND authorized_user.deleted_at IS NULL
                    ))
                    </if>
               )
             GROUP BY g.id, g.code, g.name, g.description, g.price_multiplier
             ORDER BY g.price_multiplier, g.name, g.id
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "code", javaType = String.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "description", javaType = String.class),
            @Arg(column = "price_multiplier", javaType = java.math.BigDecimal.class),
            @Arg(column = "model_count", javaType = long.class)
    })
    List<PublicServiceGroupResponse> findSelectableGroups(@Param("userId") UUID userId);
}
