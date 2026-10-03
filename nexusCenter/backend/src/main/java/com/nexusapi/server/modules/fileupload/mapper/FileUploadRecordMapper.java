package com.nexusapi.server.modules.fileupload.mapper;

import com.nexusapi.server.modules.fileupload.entity.FileUploadRecordRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

/** 文件上传审计记录持久化边界。 */
@Mapper
public interface FileUploadRecordMapper {
    @Insert("""
        INSERT INTO file_upload_records (
            id, request_id, original_filename, content_type,
            file_size_bytes, content_sha256, upstream_provider, status
        ) VALUES (
            #{row.id}, #{row.requestId}, #{row.originalFilename}, #{row.contentType},
            #{row.fileSizeBytes}, #{row.contentSha256}, #{row.upstreamProvider}, 'uploading'
        )
        """)
    int insert(@Param("row") FileUploadRecordRow row);

    @Update("""
        UPDATE file_upload_records
           SET status = 'completed', upstream_status = #{upstreamStatus},
               upstream_file_id = #{upstreamFileId}, upstream_file_url = #{upstreamFileUrl},
               response_body = CAST(#{responseBodyJson} AS jsonb), completed_at = #{completedAt}
         WHERE id = #{id} AND status = 'uploading'
        """)
    int markCompleted(
            @Param("id") UUID id,
            @Param("upstreamStatus") int upstreamStatus,
            @Param("upstreamFileId") String upstreamFileId,
            @Param("upstreamFileUrl") String upstreamFileUrl,
            @Param("responseBodyJson") String responseBodyJson,
            @Param("completedAt") Instant completedAt
    );

    @Update("""
        UPDATE file_upload_records
           SET status = 'failed', upstream_status = #{upstreamStatus},
               error_code = #{errorCode}, error_summary = #{errorSummary}, completed_at = #{completedAt}
         WHERE id = #{id} AND status = 'uploading'
        """)
    int markFailed(
            @Param("id") UUID id,
            @Param("upstreamStatus") Integer upstreamStatus,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("completedAt") Instant completedAt
    );
}
