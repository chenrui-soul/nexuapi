package com.nexusapi.server.modules.subscription.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.subscription.entity.PlanRow;
import com.nexusapi.server.modules.subscription.vo.AdminSubscriptionPlanOptionsResponse;
import com.nexusapi.server.modules.subscription.vo.AdminSubscriptionPlanResponse;
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

import java.util.List;
import java.util.UUID;

/** 管理员套餐配置数据访问层；所有外部值均通过 MyBatis 参数绑定。 */
@Mapper
public interface AdminSubscriptionPlanMapper {
    String PLAN_COLUMNS = "p.id, p.code, p.name, p.description, p.billing_cycle, p.price, "
            + "p.included_credits, p.concurrency_limit, p.entitlements::text AS entitlements_json, "
            + "p.status, p.display_order, p.featured, p.version, p.created_at, p.updated_at";

    @Select("SELECT " + PLAN_COLUMNS + " FROM plans p "
            + "ORDER BY CASE p.status WHEN 'active' THEN 0 WHEN 'draft' THEN 1 ELSE 2 END, "
            + "p.display_order, p.price, p.id")
    @Results(id = "adminSubscriptionPlanRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "entitlements_json", property = "entitlementsJson")
    })
    List<PlanRow> findAllPlans();

    @Select("SELECT " + PLAN_COLUMNS + " FROM plans p WHERE p.id = #{id}")
    @ResultMap("adminSubscriptionPlanRow")
    PlanRow findPlan(@Param("id") UUID id);

    @Select("SELECT " + PLAN_COLUMNS + " FROM plans p WHERE p.id = #{id} FOR UPDATE")
    @ResultMap("adminSubscriptionPlanRow")
    PlanRow lockPlan(@Param("id") UUID id);

    @Select("SELECT id FROM plans WHERE lower(code) = lower(#{code}) "
            + "AND (#{excludedId}::uuid IS NULL OR id != #{excludedId}) LIMIT 1")
    UUID findPlanIdByCode(@Param("code") String code, @Param("excludedId") UUID excludedId);

    @Insert("""
            INSERT INTO plans (
                id, code, name, description, billing_cycle, price, included_credits,
                concurrency_limit, entitlements, status, display_order, featured, version
            ) VALUES (
                #{id}, #{code}, #{name}, #{description}, #{billingCycle}, #{price}, #{includedCredits},
                #{concurrencyLimit}, CAST(#{entitlementsJson} AS jsonb), #{status}, #{displayOrder}, #{featured}, 0
            )
            """)
    int insertPlan(PlanRow row);

    @Update("""
            UPDATE plans
               SET code = #{code}, name = #{name}, description = #{description},
                   billing_cycle = #{billingCycle}, price = #{price}, included_credits = #{includedCredits},
                   concurrency_limit = #{concurrencyLimit}, entitlements = CAST(#{entitlementsJson} AS jsonb),
                   status = #{status}, display_order = #{displayOrder}, featured = #{featured},
                   updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int updatePlan(PlanRow row);

    @Update("""
            UPDATE plans
               SET status = 'archived', featured = false, updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int archivePlan(@Param("id") UUID id, @Param("version") long version);

    @Select("""
            SELECT g.id, g.code, g.name
              FROM plan_service_groups relation
              JOIN routing_groups g ON g.id = relation.service_group_id
             WHERE relation.plan_id = #{planId}
             ORDER BY g.name, g.id
            """)
    List<AdminSubscriptionPlanResponse.ServiceGroupSummary> findPlanServiceGroups(@Param("planId") UUID planId);

    @Select("""
            SELECT m.id, m.public_name, m.display_name, m.provider, m.capability_type
              FROM plan_models relation
              JOIN ai_models m ON m.id = relation.model_id
             WHERE relation.plan_id = #{planId}
             ORDER BY m.capability_type, m.display_name, m.id
            """)
    List<AdminSubscriptionPlanResponse.ModelSummary> findPlanModels(@Param("planId") UUID planId);

    @Select("""
            SELECT id, code, name, audience, price_multiplier
              FROM routing_groups
             WHERE status = 'active' AND audience != 'internal'
             ORDER BY price_multiplier, name, id
            """)
    List<AdminSubscriptionPlanOptionsResponse.ServiceGroupOption> findServiceGroupOptions();

    @Select("""
            SELECT id, public_name, display_name, provider, capability_type
              FROM ai_models
             WHERE status = 'active' AND public_visible = true
             ORDER BY capability_type, display_name, id
            """)
    List<AdminSubscriptionPlanOptionsResponse.ModelOption> findModelOptions();

    @Select("""
            <script>
            SELECT id FROM routing_groups
             WHERE status = 'active' AND audience != 'internal' AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY id FOR UPDATE
            </script>
            """)
    List<UUID> lockSellableServiceGroups(@Param("ids") List<UUID> ids);

    @Select("""
            <script>
            SELECT id FROM ai_models
             WHERE status = 'active' AND public_visible = true AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY id FOR UPDATE
            </script>
            """)
    List<UUID> lockSellableModels(@Param("ids") List<UUID> ids);

    @Delete("DELETE FROM plan_service_groups WHERE plan_id = #{planId}")
    int deletePlanServiceGroups(@Param("planId") UUID planId);

    @Insert("""
            <script>
            INSERT INTO plan_service_groups (plan_id, service_group_id) VALUES
            <foreach collection="ids" item="id" separator=",">(#{planId}, #{id})</foreach>
            </script>
            """)
    int insertPlanServiceGroups(@Param("planId") UUID planId, @Param("ids") List<UUID> ids);

    @Delete("DELETE FROM plan_models WHERE plan_id = #{planId}")
    int deletePlanModels(@Param("planId") UUID planId);

    @Insert("""
            <script>
            INSERT INTO plan_models (plan_id, model_id) VALUES
            <foreach collection="ids" item="id" separator=",">(#{planId}, #{id})</foreach>
            </script>
            """)
    int insertPlanModels(@Param("planId") UUID planId, @Param("ids") List<UUID> ids);
}
