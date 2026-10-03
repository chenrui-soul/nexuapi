package com.nexusapi.server.modules.fileupload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.fileupload.client.CaicaiFileUploadClient;
import com.nexusapi.server.modules.fileupload.entity.FileUploadRecordRow;
import com.nexusapi.server.modules.fileupload.mapper.FileUploadRecordMapper;
import com.nexusapi.server.modules.fileupload.model.FileUploadRequest;
import com.nexusapi.server.modules.gateway.upstream.UpstreamCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 独立文件上传编排：先创建审计记录，再调用上游并收口成功或失败状态。 */
@Service
public class FileUploadService {
    private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

    private final CaicaiFileUploadClient client;
    private final FileUploadRecordMapper mapper;

    public FileUploadService(CaicaiFileUploadClient client, FileUploadRecordMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public JsonNode upload(FileUploadRequest upload, ClientRequestMetadata metadata) {
        UUID recordId = UUID.randomUUID();
        byte[] content = upload.content();
        FileUploadRecordRow row = new FileUploadRecordRow(
                recordId, metadata.requestId(), upload.filename(), upload.contentType().toString(),
                content.length, sha256(content), "caicai"
        );
        if (mapper.insert(row) != 1) throw new IllegalStateException("Failed to create file upload record");
        try {
            CaicaiFileUploadClient.UploadResponse response = client.upload(upload);
            JsonNode body = response.body();
            int updated = mapper.markCompleted(
                    recordId, response.statusCode(), firstText(body, "id", "file_id"),
                    firstText(body, "url", "file_url", "download_url"), body.toString(), Instant.now()
            );
            if (updated != 1) throw new IllegalStateException("Failed to complete file upload record");
            return body;
        } catch (UpstreamCallException exception) {
            markFailed(recordId, exception.upstreamStatus(), exception.clientCode(), exception.safeSummary());
            throw exception;
        } catch (BusinessException exception) {
            markFailed(recordId, null, exception.errorCode().name().toLowerCase(java.util.Locale.ROOT),
                    "file_upload_not_configured");
            throw exception;
        } catch (RuntimeException exception) {
            markFailed(recordId, null, "internal_error", "file_upload_internal_error");
            throw exception;
        }
    }

    private void markFailed(UUID recordId, Integer upstreamStatus, String errorCode, String errorSummary) {
        try {
            mapper.markFailed(recordId, upstreamStatus, limit(errorCode, 64), limit(errorSummary, 255), Instant.now());
        } catch (RuntimeException persistenceFailure) {
            log.error("Failed to persist file upload failure, recordId={}", recordId);
        }
    }

    private String firstText(JsonNode body, String... fields) {
        for (String field : fields) {
            String direct = text(body.path(field));
            if (direct != null) return limit(direct, field.equals("id") || field.equals("file_id") ? 255 : 4096);
            String nested = text(body.path("data").path(field));
            if (nested != null) return limit(nested, field.equals("id") || field.equals("file_id") ? 255 : 4096);
        }
        return null;
    }

    private String text(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        String normalized = value.asText().strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
