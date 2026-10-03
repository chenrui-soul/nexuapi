package com.nexusapi.server.modules.model.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.model.entity.ModelSyncRunRow;
import com.nexusapi.server.modules.model.entity.ModelSyncSettingsRow;
import com.nexusapi.server.modules.model.entity.ModelSyncTargetRow;
import com.nexusapi.server.modules.model.entity.RoutingGroupSyncTargetRow;
import com.nexusapi.server.modules.model.entity.SyncedGroupModelRow;
import com.nexusapi.server.modules.model.entity.SyncedModelRow;
import com.nexusapi.server.modules.model.entity.SyncedRoutingGroupRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 模型市场同步的配置、运行历史和模型幂等写入数据访问层。 */
@Mapper
public interface ModelSyncMapper {

    @Select("""
            SELECT id, enabled, interval_minutes, next_run_at, updated_by, version, created_at, updated_at
              FROM model_sync_settings
             WHERE id = 1
            """)
    @Results(id = "modelSyncSettingsRow", value = {
            @Result(column = "interval_minutes", property = "intervalMinutes"),
            @Result(column = "next_run_at", property = "nextRunAt"),
            @Result(column = "updated_by", property = "updatedBy", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ModelSyncSettingsRow findSettings();

    @Update("""
            UPDATE model_sync_settings
               SET enabled = #{enabled},
                   interval_minutes = #{intervalMinutes},
                   next_run_at = CASE
                       WHEN #{enabled} THEN now() + make_interval(mins => #{intervalMinutes})
                       ELSE NULL
                   END,
                   updated_by = #{actorUserId},
                   updated_at = now(),
                   version = version + 1
             WHERE id = 1
               AND version = #{version}
            """)
    int updateSettings(
            @Param("actorUserId") UUID actorUserId,
            @Param("enabled") boolean enabled,
            @Param("intervalMinutes") int intervalMinutes,
            @Param("version") long version
    );

    /** 原子领取已到期的计划时间，多实例只有一个实例能把 next_run_at 推进到下一周期。 */
    @Update("""
            UPDATE model_sync_settings
               SET next_run_at = now() + make_interval(mins => interval_minutes)
             WHERE id = 1
               AND enabled = true
               AND next_run_at IS NOT NULL
               AND next_run_at <= now()
            """)
    int claimDueSchedule();

    @Insert("""
            INSERT INTO model_sync_runs (id, trigger_type, actor_user_id, status)
            VALUES (#{id}, #{triggerType}, #{actorUserId}, 'running')
            """)
    int insertRun(
            @Param("id") UUID id,
            @Param("triggerType") String triggerType,
            @Param("actorUserId") UUID actorUserId
    );

    @Update("""
            UPDATE model_sync_runs
               SET status = 'failed', error_code = 'worker_interrupted',
                   error_summary = '上一次同步进程异常中断', completed_at = now()
             WHERE status = 'running'
               AND started_at < #{cutoff}
            """)
    int failStaleRuns(@Param("cutoff") Instant cutoff);

    @Update("""
            UPDATE model_sync_runs
               SET status = 'succeeded', upstream_total = #{upstreamTotal}, fetched_count = #{fetchedCount},
                   inserted_count = #{insertedCount}, updated_count = #{updatedCount},
                   unchanged_count = #{unchangedCount}, skipped_count = #{skippedCount},
                   group_total = #{groupTotal}, group_inserted_count = #{groupInsertedCount},
                   group_updated_count = #{groupUpdatedCount}, group_unchanged_count = #{groupUnchangedCount},
                   group_stale_count = #{groupStaleCount},
                   completed_at = now()
             WHERE id = #{id} AND status = 'running'
            """)
    int completeRun(
            @Param("id") UUID id,
            @Param("upstreamTotal") int upstreamTotal,
            @Param("fetchedCount") int fetchedCount,
            @Param("insertedCount") int insertedCount,
            @Param("updatedCount") int updatedCount,
            @Param("unchangedCount") int unchangedCount,
            @Param("skippedCount") int skippedCount,
            @Param("groupTotal") int groupTotal,
            @Param("groupInsertedCount") int groupInsertedCount,
            @Param("groupUpdatedCount") int groupUpdatedCount,
            @Param("groupUnchangedCount") int groupUnchangedCount,
            @Param("groupStaleCount") int groupStaleCount
    );

    @Update("""
            UPDATE model_sync_runs
               SET status = 'failed', error_code = #{errorCode}, error_summary = #{errorSummary}, completed_at = now()
             WHERE id = #{id} AND status = 'running'
            """)
    int failRun(
            @Param("id") UUID id,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary
    );

    @Select("""
            SELECT id, trigger_type, actor_user_id, status, upstream_total, fetched_count,
                   inserted_count, updated_count, unchanged_count, skipped_count,
                   group_total, group_inserted_count, group_updated_count, group_unchanged_count, group_stale_count,
                   error_code, error_summary, started_at, completed_at
              FROM model_sync_runs
             WHERE id = #{id}
            """)
    @Results(id = "modelSyncRunRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "trigger_type", property = "triggerType"),
            @Result(column = "actor_user_id", property = "actorUserId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "upstream_total", property = "upstreamTotal"),
            @Result(column = "fetched_count", property = "fetchedCount"),
            @Result(column = "inserted_count", property = "insertedCount"),
            @Result(column = "updated_count", property = "updatedCount"),
            @Result(column = "unchanged_count", property = "unchangedCount"),
            @Result(column = "skipped_count", property = "skippedCount"),
            @Result(column = "group_total", property = "groupTotal"),
            @Result(column = "group_inserted_count", property = "groupInsertedCount"),
            @Result(column = "group_updated_count", property = "groupUpdatedCount"),
            @Result(column = "group_unchanged_count", property = "groupUnchangedCount"),
            @Result(column = "group_stale_count", property = "groupStaleCount"),
            @Result(column = "error_code", property = "errorCode"),
            @Result(column = "error_summary", property = "errorSummary"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "completed_at", property = "completedAt")
    })
    ModelSyncRunRow findRun(@Param("id") UUID id);

    @Select("""
            SELECT id, trigger_type, actor_user_id, status, upstream_total, fetched_count,
                   inserted_count, updated_count, unchanged_count, skipped_count,
                   group_total, group_inserted_count, group_updated_count, group_unchanged_count, group_stale_count,
                   error_code, error_summary, started_at, completed_at
              FROM model_sync_runs
             ORDER BY started_at DESC, id DESC
             LIMIT 1
            """)
    @ResultMap("modelSyncRunRow")
    ModelSyncRunRow findLatestRun();

    /** 同步来源必须绑定已建档的商业供应商，禁止用模型厂商或协议类型替代。 */
    @Select("SELECT id FROM suppliers WHERE lower(code) = lower(#{code})")
    UUID findSupplierIdByCode(@Param("code") String code);

    @Select("""
            SELECT id, name, source_group_id, source_payload_hash, source_status
              FROM routing_groups
             WHERE source_supplier_id = #{supplierId}
               AND source_group_id = #{sourceGroupId}
            """)
    @Results(id = "routingGroupSyncTargetRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "source_group_id", property = "sourceGroupId"),
            @Result(column = "source_payload_hash", property = "sourcePayloadHash"),
            @Result(column = "source_status", property = "sourceStatus")
    })
    RoutingGroupSyncTargetRow findRoutingGroupBySource(
            @Param("supplierId") UUID supplierId,
            @Param("sourceGroupId") String sourceGroupId
    );

    /** 只有唯一的同名未绑定分组才允许自动承接上游身份，避免误绑定。 */
    @Select("""
            SELECT id, name, source_group_id, source_payload_hash, source_status
              FROM routing_groups
             WHERE source_group_id IS NULL
               AND lower(name) = lower(#{name})
             ORDER BY created_at, id
             LIMIT 2
            """)
    @ResultMap("routingGroupSyncTargetRow")
    List<RoutingGroupSyncTargetRow> findUnboundRoutingGroupsByName(@Param("name") String name);

    @Insert("""
            INSERT INTO routing_groups (
                id, code, name, description, price_multiplier, audience, status,
                source_supplier_id, source_group_id, source_group_name, sync_source, source_status,
                source_rate, source_billing_type, source_metadata, source_payload_hash,
                source_last_seen_at, source_synced_at, source_managed
            ) VALUES (
                #{group.id}, #{group.localCode}, #{group.sourceGroupName},
                '从上游同步的普通服务分组，需管理员确认售价和路由后启用。',
                1.000000, 'all', 'disabled', #{supplierId}, #{group.sourceGroupId},
                #{group.sourceGroupName}, #{source}, 'active', #{group.sourceRate},
                #{group.sourceBillingType}, CAST(#{group.sourceMetadataJson} AS jsonb),
                #{group.sourcePayloadHash}, #{group.seenAt}, #{group.seenAt}, true
            )
            """)
    int insertRoutingGroupFromSource(
            @Param("supplierId") UUID supplierId,
            @Param("source") String source,
            @Param("group") SyncedRoutingGroupRow group
    );

    @Update("""
            UPDATE routing_groups
               SET source_supplier_id = #{supplierId}, source_group_id = #{group.sourceGroupId},
                   source_group_name = #{group.sourceGroupName}, sync_source = #{source}, source_status = 'active',
                   source_rate = #{group.sourceRate}, source_billing_type = #{group.sourceBillingType},
                   source_metadata = CAST(#{group.sourceMetadataJson} AS jsonb),
                   source_payload_hash = #{group.sourcePayloadHash}, source_last_seen_at = #{group.seenAt},
                   source_synced_at = #{group.seenAt}, source_managed = true
             WHERE id = #{id} AND source_group_id IS NULL
            """)
    int bindRoutingGroupSource(
            @Param("id") UUID id,
            @Param("supplierId") UUID supplierId,
            @Param("source") String source,
            @Param("group") SyncedRoutingGroupRow group
    );

    @Update("""
            UPDATE routing_groups
               SET source_group_name = #{group.sourceGroupName}, source_status = 'active',
                   source_rate = #{group.sourceRate}, source_billing_type = #{group.sourceBillingType},
                   source_metadata = CASE WHEN #{changed} THEN CAST(#{group.sourceMetadataJson} AS jsonb) ELSE source_metadata END,
                   source_payload_hash = CASE WHEN #{changed} THEN #{group.sourcePayloadHash} ELSE source_payload_hash END,
                   source_last_seen_at = #{group.seenAt},
                   source_synced_at = CASE WHEN #{changed} THEN #{group.seenAt} ELSE source_synced_at END,
                   source_managed = true
             WHERE id = #{id}
               AND source_supplier_id = #{supplierId}
               AND source_group_id = #{group.sourceGroupId}
            """)
    int updateRoutingGroupSource(
            @Param("id") UUID id,
            @Param("supplierId") UUID supplierId,
            @Param("group") SyncedRoutingGroupRow group,
            @Param("changed") boolean changed
    );

    @Select("""
            SELECT id, name, source_group_id, source_payload_hash, source_status
              FROM routing_groups
             WHERE source_supplier_id = #{supplierId} AND sync_source = #{source}
            """)
    @ResultMap("routingGroupSyncTargetRow")
    List<RoutingGroupSyncTargetRow> findRoutingGroupsBySource(
            @Param("supplierId") UUID supplierId,
            @Param("source") String source
    );

    @Update("""
            UPDATE routing_groups
               SET source_status = 'stale'
             WHERE source_supplier_id = #{supplierId}
               AND sync_source = #{source}
               AND source_status = 'active'
               AND (source_last_seen_at IS NULL OR source_last_seen_at < #{seenAt})
            """)
    int markMissingRoutingGroupsStale(
            @Param("supplierId") UUID supplierId,
            @Param("source") String source,
            @Param("seenAt") Instant seenAt
    );

    @Select("""
            <script>
            SELECT id, public_name, sync_source, source_model_key, source_managed, source_payload_hash,
                   active_pricing_version_id, pricing_source_managed, pricing_source_hash,
                   pricing_source_synced_at
              FROM ai_models
             WHERE lower(public_name) IN
             <foreach collection="normalizedNames" item="name" open="(" separator="," close=")">
                 #{name}
             </foreach>
            </script>
            """)
    @Results(id = "modelSyncTargetRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "public_name", property = "publicName"),
            @Result(column = "sync_source", property = "syncSource"),
            @Result(column = "source_model_key", property = "sourceModelKey"),
            @Result(column = "source_managed", property = "sourceManaged"),
            @Result(column = "source_payload_hash", property = "sourcePayloadHash"),
            @Result(column = "active_pricing_version_id", property = "activePricingVersionId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "pricing_source_managed", property = "pricingSourceManaged"),
            @Result(column = "pricing_source_hash", property = "pricingSourceHash"),
            @Result(column = "pricing_source_synced_at", property = "pricingSourceSyncedAt")
    })
    List<ModelSyncTargetRow> findTargets(@Param("normalizedNames") List<String> normalizedNames);

    @Insert("""
            <script>
            INSERT INTO ai_models (
                id, public_name, display_name, provider, capability_type,
                input_modalities, output_modalities, context_window, max_output_tokens,
                supports_streaming, supports_tools, supports_structured_output,
                input_price, output_price, cached_input_price, price_unit, billing_type,
                public_visible, status, metadata, sync_source, source_model_key, source_managed,
                source_metadata, source_payload_hash, source_last_seen_at, source_synced_at,
                pricing_source_managed
            ) VALUES
            <foreach collection="rows" item="row" separator=",">
                (
                    #{row.id}, #{row.publicName}, #{row.displayName}, 'unknown', #{row.capabilityType},
                    CAST(#{row.inputModalitiesJson} AS jsonb), CAST(#{row.outputModalitiesJson} AS jsonb),
                    #{row.contextWindow}, NULL, #{row.supportsStreaming}, #{row.supportsTools},
                    #{row.supportsStructuredOutput}, 0, 0, 0, #{row.priceUnit},
                    CASE
                        WHEN #{row.capabilityType} = 'audio' AND #{row.priceUnit} = 'second' THEN 6
                        WHEN #{row.capabilityType} = 'audio' AND #{row.priceUnit} != 'character' THEN 5
                        WHEN #{row.priceUnit} = 'request' THEN 1
                        WHEN #{row.priceUnit} = 'image' THEN 2
                        WHEN #{row.priceUnit} = 'second' THEN 3
                        WHEN #{row.priceUnit} = 'character' THEN 5
                        ELSE 4
                    END,
                    true, 'active', '{}'::jsonb, #{row.syncSource}, #{row.sourceModelKey}, true,
                    CAST(#{row.sourceMetadataJson} AS jsonb), #{row.sourcePayloadHash}, #{row.seenAt}, #{row.seenAt},
                    true
                )
            </foreach>
            </script>
            """)
    int insertModels(@Param("rows") List<SyncedModelRow> rows);

    @Update("""
            UPDATE ai_models
               SET sync_source = #{model.syncSource},
                   source_model_key = #{model.sourceModelKey},
                   source_metadata = CASE WHEN #{changed} THEN CAST(#{model.sourceMetadataJson} AS jsonb) ELSE source_metadata END,
                   source_payload_hash = CASE WHEN #{changed} THEN #{model.sourcePayloadHash} ELSE source_payload_hash END,
                   source_last_seen_at = #{model.seenAt},
                   source_synced_at = CASE WHEN #{changed} THEN #{model.seenAt} ELSE source_synced_at END,
                   display_name = CASE WHEN source_managed AND #{changed} THEN #{model.displayName} ELSE display_name END,
                    capability_type = CASE WHEN source_managed AND #{changed} THEN #{model.capabilityType} ELSE capability_type END,
                    billing_type = CASE
                        WHEN source_managed AND active_pricing_version_id IS NULL AND #{changed} THEN
                            CASE
                                WHEN #{model.capabilityType} = 'audio' AND #{model.priceUnit} = 'second' THEN 6
                                WHEN #{model.capabilityType} = 'audio' AND #{model.priceUnit} != 'character' THEN 5
                                WHEN #{model.priceUnit} = 'request' THEN 1
                                WHEN #{model.priceUnit} = 'image' THEN 2
                                WHEN #{model.priceUnit} = 'second' THEN 3
                                WHEN #{model.priceUnit} = 'character' THEN 5
                                ELSE 4
                            END
                        ELSE billing_type
                    END,
                   input_modalities = CASE WHEN source_managed AND #{changed} THEN CAST(#{model.inputModalitiesJson} AS jsonb) ELSE input_modalities END,
                   output_modalities = CASE WHEN source_managed AND #{changed} THEN CAST(#{model.outputModalitiesJson} AS jsonb) ELSE output_modalities END,
                   context_window = CASE WHEN source_managed AND #{changed} THEN #{model.contextWindow} ELSE context_window END,
                   supports_streaming = CASE WHEN source_managed AND #{changed} THEN #{model.supportsStreaming} ELSE supports_streaming END,
                   supports_tools = CASE WHEN source_managed AND #{changed} THEN #{model.supportsTools} ELSE supports_tools END,
                   supports_structured_output = CASE WHEN source_managed AND #{changed} THEN #{model.supportsStructuredOutput} ELSE supports_structured_output END,
                   updated_at = CASE WHEN source_managed AND #{changed} THEN now() ELSE updated_at END,
                   version = CASE WHEN source_managed AND #{changed} THEN version + 1 ELSE version END
             WHERE id = #{id}
            """)
    int updateModelSnapshot(
            @Param("id") UUID id,
            @Param("model") SyncedModelRow model,
            @Param("changed") boolean changed
    );

    @Insert("""
            <script>
            INSERT INTO routing_group_models (
                id, group_id, model_id, source_type, source_status,
                upstream_last_status, upstream_success_rate, upstream_consecutive_failures,
                upstream_health_history, upstream_last_checked_at, upstream_last_success_at,
                source_last_seen_at
            ) VALUES
            <foreach collection="rows" item="row" separator=",">
                (
                    #{row.id}, #{row.groupId}, #{row.modelId}, 'caicai_market', 'active',
                    #{row.upstreamLastStatus}, #{row.upstreamSuccessRate},
                    #{row.upstreamConsecutiveFailures}, CAST(#{row.upstreamHealthHistoryJson} AS jsonb),
                    #{row.upstreamLastCheckedAt}, #{row.upstreamLastSuccessAt}, #{row.seenAt}
                )
            </foreach>
            ON CONFLICT (group_id, model_id) DO UPDATE
               SET source_type = CASE WHEN routing_group_models.source_type = 'manual' THEN routing_group_models.source_type ELSE 'caicai_market' END,
                   source_status = CASE WHEN routing_group_models.source_type = 'manual' THEN routing_group_models.source_status ELSE 'active' END,
                   upstream_last_status = EXCLUDED.upstream_last_status,
                   upstream_success_rate = EXCLUDED.upstream_success_rate,
                   upstream_consecutive_failures = EXCLUDED.upstream_consecutive_failures,
                   upstream_health_history = EXCLUDED.upstream_health_history,
                   upstream_last_checked_at = EXCLUDED.upstream_last_checked_at,
                   upstream_last_success_at = EXCLUDED.upstream_last_success_at,
                   source_last_seen_at = EXCLUDED.source_last_seen_at,
                   updated_at = now(), version = routing_group_models.version + 1
            </script>
            """)
    int upsertRoutingGroupModels(@Param("rows") List<SyncedGroupModelRow> rows);

    @Update("""
            UPDATE routing_group_models rgm
               SET source_status = 'stale', updated_at = now(), version = rgm.version + 1
              FROM routing_groups rg
             WHERE rg.id = rgm.group_id
               AND rg.source_supplier_id = #{supplierId}
               AND rg.sync_source = #{source}
               AND rgm.source_type = 'caicai_market'
               AND rgm.source_status = 'active'
               AND (rgm.source_last_seen_at IS NULL OR rgm.source_last_seen_at < #{seenAt})
            """)
    int markMissingRoutingGroupModelsStale(
            @Param("supplierId") UUID supplierId,
            @Param("source") String source,
            @Param("seenAt") Instant seenAt
    );
}
