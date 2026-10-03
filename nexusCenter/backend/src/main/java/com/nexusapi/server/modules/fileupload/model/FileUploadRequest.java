package com.nexusapi.server.modules.fileupload.model;

import com.nexusapi.server.common.config.FileUploadProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 已完成边界校验的文件上传请求；文件正文仅存在于当前请求内存中。 */
public record FileUploadRequest(
        /** 文件正文。 */ byte[] content,
        /** 安全化后的文件名。 */ String filename,
        /** 受控 MIME 类型。 */ MediaType contentType
) {
    public FileUploadRequest {
        content = content == null ? new byte[0] : content.clone();
        filename = filename == null || filename.isBlank() ? "upload.bin" : filename;
        contentType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType;
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    /** 读取并校验单个用户文件，不限制业务文件类型。 */
    public static FileUploadRequest from(MultipartFile file, FileUploadProperties properties) {
        if (file == null || file.isEmpty()) throw validation("file 不能为空");
        if (file.getSize() > properties.maxFileBytes()) {
            throw validation("file 不能超过 " + (properties.maxFileBytes() / 1024L / 1024L) + "MB");
        }
        try {
            return new FileUploadRequest(
                    file.getBytes(), safeFilename(file.getOriginalFilename()), safeContentType(file.getContentType())
            );
        } catch (IOException exception) {
            throw validation("file 读取失败");
        }
    }

    private static String safeFilename(String raw) {
        String value = raw == null ? "" : raw.replace('\\', '/');
        int separator = value.lastIndexOf('/');
        if (separator >= 0) value = value.substring(separator + 1);
        value = value.replaceAll("[\\p{Cntrl}\\\";]", "_").strip();
        if (value.isBlank() || value.equals(".") || value.equals("..")) return "upload.bin";
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private static MediaType safeContentType(String raw) {
        if (raw == null || raw.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            MediaType mediaType = MediaType.parseMediaType(raw);
            return mediaType.getType().equals("*") || mediaType.getSubtype().equals("*")
                    ? MediaType.APPLICATION_OCTET_STREAM : mediaType;
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
