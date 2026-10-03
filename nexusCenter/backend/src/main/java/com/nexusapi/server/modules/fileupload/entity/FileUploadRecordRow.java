package com.nexusapi.server.modules.fileupload.entity;

import java.util.UUID;

/** 独立文件上传记录；只持久化元数据和上游结果，不关联用户或任何 API Key。 */
public record FileUploadRecordRow(
        /** 上传记录主键。 */ UUID id,
        /** 本次 HTTP 请求标识。 */ String requestId,
        /** 清理路径和控制字符后的原始文件名。 */ String originalFilename,
        /** 转发给上游的 MIME 类型。 */ String contentType,
        /** 文件正文大小，单位字节。 */ long fileSizeBytes,
        /** 文件正文 SHA-256 十六进制摘要。 */ String contentSha256,
        /** 固定上游提供方编码。 */ String upstreamProvider
) {
}
