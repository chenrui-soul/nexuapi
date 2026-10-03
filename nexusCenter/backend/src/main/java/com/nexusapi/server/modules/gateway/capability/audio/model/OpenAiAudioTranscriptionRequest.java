package com.nexusapi.server.modules.gateway.capability.audio.model;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OpenAI 音频转写请求的短生命周期载体。
 * 只保存本次上游调用所需字段，文件正文不写日志或数据库。
 */
public record OpenAiAudioTranscriptionRequest(
        String model,
        byte[] fileContent,
        String filename,
        MediaType contentType,
        String language,
        String prompt,
        String responseFormat,
        String temperature,
        List<String> timestampGranularities
) {
    private static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_PROMPT_CHARACTERS = 100_000;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "mp3", "mp4", "mpeg", "mpga", "m4a", "wav", "webm"
    );
    private static final Set<String> RESPONSE_FORMATS = Set.of(
            "json", "verbose_json", "text", "srt", "vtt"
    );
    private static final Set<String> TIMESTAMP_GRANULARITIES = Set.of("segment", "word");

    public OpenAiAudioTranscriptionRequest {
        fileContent = fileContent == null ? new byte[0] : fileContent.clone();
        timestampGranularities = timestampGranularities == null
                ? List.of() : List.copyOf(timestampGranularities);
    }

    @Override
    public byte[] fileContent() {
        return fileContent.clone();
    }

    /** 在进入鉴权、路由和计费前完成 multipart 字段与文件边界校验。 */
    public static OpenAiAudioTranscriptionRequest from(
            MultipartFile file,
            String model,
            String language,
            String prompt,
            String responseFormat,
            String temperature,
            List<String> timestampGranularities
    ) {
        String normalizedModel = requiredText(model, "model", 160);
        if (file == null || file.isEmpty()) throw validation("file 不能为空");
        if (file.getSize() > MAX_FILE_BYTES) throw validation("file 不能超过 20MB");
        String filename = safeFilename(file.getOriginalFilename());
        String extension = extension(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw validation("file 仅支持 mp3、mp4、mpeg、mpga、m4a、wav、webm");
        }
        MediaType contentType = safeContentType(file.getContentType(), extension);
        String normalizedLanguage = optionalText(language, 32, "language");
        if (normalizedLanguage != null && !normalizedLanguage.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{2,8})*")) {
            throw validation("language 格式无效");
        }
        String normalizedPrompt = optionalText(prompt, MAX_PROMPT_CHARACTERS, "prompt");
        String normalizedFormat = responseFormat == null || responseFormat.isBlank()
                ? "json" : responseFormat.strip().toLowerCase(Locale.ROOT);
        if (!RESPONSE_FORMATS.contains(normalizedFormat)) throw validation("response_format 不支持该值");
        String normalizedTemperature = normalizeTemperature(temperature);
        List<String> granularities = timestampGranularities == null ? List.of()
                : timestampGranularities.stream()
                .map(value -> value == null ? "" : value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (!TIMESTAMP_GRANULARITIES.containsAll(granularities)) {
            throw validation("timestamp_granularities[] 不支持该值");
        }
        try {
            return new OpenAiAudioTranscriptionRequest(
                    normalizedModel, file.getBytes(), filename, contentType,
                    normalizedLanguage, normalizedPrompt, normalizedFormat,
                    normalizedTemperature, granularities
            );
        } catch (IOException exception) {
            throw validation("file 读取失败");
        }
    }

    private static String normalizeTemperature(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal temperature = new BigDecimal(value.strip());
            if (temperature.compareTo(BigDecimal.ZERO) < 0 || temperature.compareTo(BigDecimal.ONE) > 0) {
                throw validation("temperature 必须在 0 到 1 之间");
            }
            return temperature.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            throw validation("temperature 格式无效");
        }
    }

    private static MediaType safeContentType(String raw, String extension) {
        try {
            if (raw != null && !raw.isBlank()) {
                MediaType parsed = MediaType.parseMediaType(raw);
                if ("audio".equalsIgnoreCase(parsed.getType())
                        || "video".equalsIgnoreCase(parsed.getType())
                        || MediaType.APPLICATION_OCTET_STREAM.includes(parsed)) {
                    return parsed;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 非法客户端 Content-Type 不向外透传，按受控扩展名使用安全默认值。
        }
        return switch (extension) {
            case "wav" -> MediaType.parseMediaType("audio/wav");
            case "webm" -> MediaType.parseMediaType("audio/webm");
            case "mp4", "m4a" -> MediaType.parseMediaType("audio/mp4");
            default -> MediaType.parseMediaType("audio/mpeg");
        };
    }

    private static String safeFilename(String raw) {
        String value = raw == null ? "audio" : raw.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_").strip();
        if (value.isEmpty() || value.length() > 160) throw validation("file 文件名格式无效");
        return value;
    }

    private static String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = optionalText(value, maxLength, field);
        if (normalized == null) throw validation(field + " 不能为空");
        return normalized;
    }

    private static String optionalText(String value, int maxLength, String field) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.codePointCount(0, normalized.length()) > maxLength) {
            throw validation(field + " 长度超过限制");
        }
        return normalized;
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
