package com.nexusapi.server.modules.model.pricing.time.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.model.pricing.time.entity.TimePricingRuleModelRow;
import com.nexusapi.server.modules.model.pricing.time.entity.TimePricingRuleRow;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 模型时段倍率的管理端写入和 Gateway 运行时只读访问层。 */
@Mapper
public interface TimePricingMapper {
    String RULE_COLUMNS = """
            r.id, r.name, r.multiplier, array_to_string(r.days_of_week, ',') AS days_of_week_csv,
            r.start_time, r.end_time, r.enabled, r.created_by, r.created_at, r.updated_at, r.version
            """;

    @Select("SELECT " + RULE_COLUMNS + " FROM billing_time_rules r ORDER BY r.enabled DESC, r.updated_at DESC, r.id")
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "multiplier", javaType = java.math.BigDecimal.class),
            @Arg(column = "days_of_week_csv", javaType = String.class),
            @Arg(column = "start_time", javaType = java.time.LocalTime.class),
            @Arg(column = "end_time", javaType = java.time.LocalTime.class),
            @Arg(column = "enabled", javaType = boolean.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class),
            @Arg(column = "updated_at", javaType = java.time.Instant.class),
            @Arg(column = "version", javaType = long.class)
    })
    List<TimePricingRuleRow> findAllRules();

    @Select("SELECT " + RULE_COLUMNS + " FROM billing_time_rules r WHERE r.id = #{id}")
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "multiplier", javaType = java.math.BigDecimal.class),
            @Arg(column = "days_of_week_csv", javaType = String.class),
            @Arg(column = "start_time", javaType = java.time.LocalTime.class),
            @Arg(column = "end_time", javaType = java.time.LocalTime.class),
            @Arg(column = "enabled", javaType = boolean.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class),
            @Arg(column = "updated_at", javaType = java.time.Instant.class),
            @Arg(column = "version", javaType = long.class)
    })
    TimePricingRuleRow findRule(@Param("id") UUID id);

    @Select("SELECT " + RULE_COLUMNS + " FROM billing_time_rules r WHERE r.id = #{id} FOR UPDATE")
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "multiplier", javaType = java.math.BigDecimal.class),
            @Arg(column = "days_of_week_csv", javaType = String.class),
            @Arg(column = "start_time", javaType = java.time.LocalTime.class),
            @Arg(column = "end_time", javaType = java.time.LocalTime.class),
            @Arg(column = "enabled", javaType = boolean.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class),
            @Arg(column = "updated_at", javaType = java.time.Instant.class),
            @Arg(column = "version", javaType = long.class)
    })
    TimePricingRuleRow lockRule(@Param("id") UUID id);

    /** 运行时只读取启用规则；具体星期和时段匹配由 Java 使用 Asia/Shanghai 计算。 */
    @Select("""
            SELECT
            """ + RULE_COLUMNS + """
              FROM billing_time_rules r
              JOIN billing_time_rule_models rm ON rm.rule_id = r.id
             WHERE rm.model_id = #{modelId}
               AND r.enabled = true
             ORDER BY r.id
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "multiplier", javaType = java.math.BigDecimal.class),
            @Arg(column = "days_of_week_csv", javaType = String.class),
            @Arg(column = "start_time", javaType = java.time.LocalTime.class),
            @Arg(column = "end_time", javaType = java.time.LocalTime.class),
            @Arg(column = "enabled", javaType = boolean.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class),
            @Arg(column = "updated_at", javaType = java.time.Instant.class),
            @Arg(column = "version", javaType = long.class)
    })
    List<TimePricingRuleRow> findEnabledRulesForModel(@Param("modelId") UUID modelId);

    /** 保存或启用规则前读取关联模型的其他启用规则，用于跨天重叠校验。 */
    @Select("""
            <script>
            SELECT DISTINCT
            """ + RULE_COLUMNS + """
              FROM billing_time_rules r
              JOIN billing_time_rule_models rm ON rm.rule_id = r.id
             WHERE r.enabled = true
               AND rm.model_id IN
               <foreach collection="modelIds" item="modelId" open="(" separator="," close=")">#{modelId}</foreach>
               <if test="excludeRuleId != null">AND r.id &lt;&gt; #{excludeRuleId}</if>
             ORDER BY r.id
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "multiplier", javaType = java.math.BigDecimal.class),
            @Arg(column = "days_of_week_csv", javaType = String.class),
            @Arg(column = "start_time", javaType = java.time.LocalTime.class),
            @Arg(column = "end_time", javaType = java.time.LocalTime.class),
            @Arg(column = "enabled", javaType = boolean.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class),
            @Arg(column = "updated_at", javaType = java.time.Instant.class),
            @Arg(column = "version", javaType = long.class)
    })
    List<TimePricingRuleRow> findEnabledRulesForModels(
            @Param("modelIds") List<UUID> modelIds,
            @Param("excludeRuleId") UUID excludeRuleId
    );

    @Select("""
            SELECT rm.rule_id, m.id AS model_id, m.public_name, m.display_name, m.capability_type
              FROM billing_time_rule_models rm
              JOIN ai_models m ON m.id = rm.model_id
             WHERE rm.rule_id = #{ruleId}
             ORDER BY m.display_name, m.public_name, m.id
            """)
    @ConstructorArgs({
            @Arg(column = "rule_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "model_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "public_name", javaType = String.class),
            @Arg(column = "display_name", javaType = String.class),
            @Arg(column = "capability_type", javaType = String.class)
    })
    List<TimePricingRuleModelRow> findRuleModels(@Param("ruleId") UUID ruleId);

    @Select("""
            <script>
            SELECT id
              FROM ai_models
             WHERE id IN
             <foreach collection="modelIds" item="modelId" open="(" separator="," close=")">#{modelId}</foreach>
             ORDER BY id
             FOR UPDATE
            </script>
            """)
    List<UUID> lockModels(@Param("modelIds") List<UUID> modelIds);

    @Insert("""
            INSERT INTO billing_time_rules (
                id, name, multiplier, days_of_week, start_time, end_time, enabled, created_by
            ) VALUES (
                #{id}, #{name}, #{multiplier}, string_to_array(#{daysOfWeekCsv}, ',')::smallint[],
                #{startTime}, #{endTime}, #{enabled}, #{createdBy}
            )
            """)
    int insertRule(TimePricingRuleRow row);

    @Update("""
            UPDATE billing_time_rules
               SET name = #{name},
                   multiplier = #{multiplier},
                   days_of_week = string_to_array(#{daysOfWeekCsv}, ',')::smallint[],
                   start_time = #{startTime},
                   end_time = #{endTime},
                   enabled = #{enabled},
                   updated_at = now(),
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
            """)
    int updateRule(TimePricingRuleRow row);

    @Update("""
            UPDATE billing_time_rules
               SET enabled = #{enabled}, updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int updateStatus(@Param("id") UUID id, @Param("enabled") boolean enabled, @Param("version") long version);

    @Delete("DELETE FROM billing_time_rule_models WHERE rule_id = #{ruleId}")
    int deleteRuleModels(@Param("ruleId") UUID ruleId);

    @Insert("""
            INSERT INTO billing_time_rule_models (rule_id, model_id)
            VALUES (#{ruleId}, #{modelId})
            """)
    int insertRuleModel(@Param("ruleId") UUID ruleId, @Param("modelId") UUID modelId);

    @Delete("DELETE FROM billing_time_rules WHERE id = #{id} AND version = #{version}")
    int deleteRule(@Param("id") UUID id, @Param("version") long version);
}
