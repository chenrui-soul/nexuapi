package com.nexusapi.server.modules.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 单次模型市场同步的脱敏结果，不包含上游响应正文或接口地址参数。 */
public record ModelSyncRunResponse(
        UUID id,
        @JsonProperty("trigger_type") String triggerType,
        String status,
        @JsonProperty("upstream_total") Integer upstreamTotal,
        @JsonProperty("fetched_count") int fetchedCount,
        @JsonProperty("inserted_count") int insertedCount,
        @JsonProperty("updated_count") int updatedCount,
        @JsonProperty("unchanged_count") int unchangedCount,
        @JsonProperty("skipped_count") int skippedCount,
        @JsonProperty("group_total") int groupTotal,
        @JsonProperty("group_inserted_count") int groupInsertedCount,
        @JsonProperty("group_updated_count") int groupUpdatedCount,
        @JsonProperty("group_unchanged_count") int groupUnchangedCount,
        @JsonProperty("group_stale_count") int groupStaleCount,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_summary") String errorSummary,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt
) {
}
