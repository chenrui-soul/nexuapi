package com.nexusapi.server.modules.gateway.capability.image.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OpenAI 图片生成与编辑请求的已校验快照。
 *
 * <p>只保留当前接口文档确认的字段；multipart 上传文件在构造时读取为受限字节数组，
 * JSON 引用图只接受 HTTP/HTTPS URL，避免异步客户端访问失效文件或转发其他 URI 类型。</p>
 */
public record OpenAiImageGenerationRequest(
        String model,
        String prompt,
        int quantity,
        String aspectRatio,
        String quality,
        String resolution,
        List<OpenAiUpstreamClient.UploadPart> images,
        List<String> imageUrls
) {
    private static final int MAX_IMAGES = 5;
    private static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_TOTAL_IMAGE_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_PROMPT_CHARACTERS = 100_000;
    private static final int MAX_IMAGE_URL_CHARACTERS = 2_048;
    private static final Set<String> ASPECT_RATIOS = Set.of(
            "auto", "1:1", "3:4", "4:3", "9:16", "16:9", "2:3", "3:2",
            "9:19.5", "19.5:9", "9:20", "20:9", "1:2", "2:1"
    );
    private static final Set<String> QUALITIES = Set.of("low", "medium");
    private static final Set<String> RESOLUTIONS = Set.of("1k", "2k");
    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp"
    );

    public OpenAiImageGenerationRequest {
        images = images == null ? List.of() : List.copyOf(images);
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
    }

    /** 将各类常见 multipart 嵌套字段写法归一化为同一个 resolution。 */
    public static OpenAiImageGenerationRequest create(
            ObjectMapper objectMapper,
            String model,
            String prompt,
            String rawQuantity,
            String rawAspectRatio,
            String rawQuality,
            String extraParamsJson,
            String dottedResolution,
            String bracketResolution,
            String directResolution,
            List<MultipartFile> imageParts,
            List<MultipartFile> bracketImageParts
    ) {
        String normalizedModel = requiredText(model, "model", 160, false);
        String normalizedPrompt = requiredText(prompt, "prompt", MAX_PROMPT_CHARACTERS, true);
        int quantity = parseQuantity(rawQuantity);
        // 上游要求明确的宽高比，不能发送 auto；未提供时统一使用 1:1。
        String aspectRatio = enumValue(rawAspectRatio, "aspect_ratio", "1:1", ASPECT_RATIOS);
        String quality = enumValue(rawQuality, "quality", "medium", QUALITIES);
        String resolution = resolveResolution(
                objectMapper, extraParamsJson, dottedResolution, bracketResolution, directResolution
        );
        List<MultipartFile> combinedImages = new ArrayList<>();
        if (imageParts != null) combinedImages.addAll(imageParts);
        if (bracketImageParts != null) combinedImages.addAll(bracketImageParts);
        List<OpenAiUpstreamClient.UploadPart> uploads = validateImages(combinedImages);
        return new OpenAiImageGenerationRequest(
                normalizedModel, normalizedPrompt, quantity, aspectRatio, quality, resolution, uploads, List.of()
        );
    }

    /** 校验 application/json 图片生成或 URL 引用图编辑请求。 */
    public static OpenAiImageGenerationRequest createJson(JsonNode body) {
        if (body == null || !body.isObject()) throw validation("请求体必须是 JSON 对象");
        String model = requiredJsonText(body, "model", 160, false);
        String prompt = requiredJsonText(body, "prompt", MAX_PROMPT_CHARACTERS, true);
        int quantity = parseJsonQuantity(body.get("n"));
        String aspectRatio = enumValue(optionalJsonText(body, "aspect_ratio"),
                "aspect_ratio", "1:1", ASPECT_RATIOS);
        String quality = enumValue(optionalJsonText(body, "quality"),
                "quality", "medium", QUALITIES);
        String resolution = resolveJsonResolution(body);
        List<String> imageUrls = validateImageUrls(body.get("images"));
        return new OpenAiImageGenerationRequest(
                model, prompt, quantity, aspectRatio, quality, resolution, List.of(), imageUrls
        );
    }

    /** 计费规则未显式收到 resolution 时按上游默认 1K 匹配，但不会擅自改写用户请求。 */
    public String pricingResolution() {
        return resolution == null ? "1k" : resolution;
    }

    private static int parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) return 1;
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < 1 || value > 10) throw validation("n 必须在 1 到 10 之间");
            return value;
        } catch (NumberFormatException exception) {
            throw validation("n 必须是 1 到 10 之间的整数");
        }
    }

    private static int parseJsonQuantity(JsonNode node) {
        if (node == null || node.isNull()) return 1;
        if (node.isIntegralNumber()) {
            int value = node.canConvertToInt() ? node.intValue() : -1;
            if (value < 1 || value > 10) throw validation("n 必须在 1 到 10 之间");
            return value;
        }
        if (node.isTextual()) return parseQuantity(node.asText());
        throw validation("n 必须是 1 到 10 之间的整数");
    }

    private static String enumValue(String raw, String field, String defaultValue, Set<String> allowed) {
        String value = raw == null || raw.isBlank() ? defaultValue : raw.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) throw validation(field + " 不在支持范围内");
        return value;
    }

    private static String resolveResolution(
            ObjectMapper objectMapper,
            String extraParamsJson,
            String dottedResolution,
            String bracketResolution,
            String directResolution
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addResolution(values, dottedResolution);
        addResolution(values, bracketResolution);
        addResolution(values, directResolution);
        if (extraParamsJson != null && !extraParamsJson.isBlank()) {
            try {
                JsonNode extra = objectMapper.readTree(extraParamsJson);
                if (extra == null || !extra.isObject()) throw validation("extra_params 必须是 JSON 对象");
                extra.fieldNames().forEachRemaining(name -> {
                    if (!"resolution".equals(name)) throw validation("extra_params 只支持 resolution");
                });
                JsonNode node = extra.get("resolution");
                if (node != null && !node.isNull()) {
                    if (!node.isTextual()) throw validation("extra_params.resolution 必须是字符串");
                    addResolution(values, node.asText());
                }
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception exception) {
                throw validation("extra_params 必须是合法 JSON 对象");
            }
        }
        if (values.size() > 1) throw validation("resolution 存在相互冲突的重复值");
        return values.stream().findFirst().orElse(null);
    }

    private static String resolveJsonResolution(JsonNode body) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addResolution(values, optionalJsonText(body, "resolution"));
        JsonNode extra = body.get("extra_params");
        if (extra != null && !extra.isNull()) {
            if (!extra.isObject()) throw validation("extra_params 必须是 JSON 对象");
            extra.fieldNames().forEachRemaining(name -> {
                if (!"resolution".equals(name)) throw validation("extra_params 只支持 resolution");
            });
            addResolution(values, optionalJsonText(extra, "resolution"));
        }
        if (values.size() > 1) throw validation("resolution 存在相互冲突的重复值");
        return values.stream().findFirst().orElse(null);
    }

    private static List<String> validateImageUrls(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw validation("images 必须是 URL 字符串数组");
        if (node.size() > MAX_IMAGES) throw validation("images 最多包含 5 张图片");
        List<String> urls = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) throw validation("images 必须是 URL 字符串数组");
            String value = item.asText().strip();
            if (value.isEmpty()) throw validation("images 不能包含空 URL");
            if (value.length() > MAX_IMAGE_URL_CHARACTERS) throw validation("images URL 长度超过限制");
            if (value.codePoints().anyMatch(Character::isISOControl)) {
                throw validation("images URL 包含无效控制字符");
            }
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!("http".equals(scheme) || "https".equals(scheme))
                        || !uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw validation("images 只支持不含用户信息的绝对 HTTP/HTTPS URL");
                }
            } catch (URISyntaxException exception) {
                throw validation("images 包含无效 URL");
            }
            urls.add(value);
        }
        return List.copyOf(urls);
    }

    private static void addResolution(Set<String> values, String raw) {
        if (raw == null || raw.isBlank()) return;
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (!RESOLUTIONS.contains(value)) throw validation("resolution 只支持 1k 或 2k");
        values.add(value);
    }

    private static List<OpenAiUpstreamClient.UploadPart> validateImages(List<MultipartFile> files) {
        if (files.size() > MAX_IMAGES) throw validation("images 最多上传 5 张图片");
        List<OpenAiUpstreamClient.UploadPart> uploads = new ArrayList<>(files.size());
        long totalBytes = 0L;
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            if (file == null || file.isEmpty()) throw validation("images 不能包含空文件");
            if (file.getSize() > MAX_IMAGE_BYTES) throw validation("单张图片不能超过 20MB");
            totalBytes += file.getSize();
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) throw validation("图片文件总大小不能超过 50MB");
            String contentType = file.getContentType() == null
                    ? "" : file.getContentType().strip().toLowerCase(Locale.ROOT);
            if (!IMAGE_MEDIA_TYPES.contains(contentType)) {
                throw validation("images 只支持 JPEG、PNG 或 WebP 图片");
            }
            try {
                byte[] bytes = file.getBytes();
                if (!matchesSignature(bytes, contentType)) {
                    throw validation("图片文件内容与声明类型不一致");
                }
                uploads.add(new OpenAiUpstreamClient.UploadPart(
                        bytes, safeFilename(file.getOriginalFilename(), contentType, index),
                        MediaType.parseMediaType(contentType)
                ));
            } catch (IOException exception) {
                throw validation("图片文件读取失败");
            }
        }
        return List.copyOf(uploads);
    }

    private static boolean matchesSignature(byte[] bytes, String contentType) {
        if (MediaType.IMAGE_JPEG_VALUE.equals(contentType)) {
            return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        }
        if (MediaType.IMAGE_PNG_VALUE.equals(contentType)) {
            byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            if (bytes.length < signature.length) return false;
            for (int index = 0; index < signature.length; index++) {
                if (bytes[index] != signature[index]) return false;
            }
            return true;
        }
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static String safeFilename(String raw, String contentType, int index) {
        String value = raw == null ? "" : raw.replace('\\', '/');
        int separator = value.lastIndexOf('/');
        if (separator >= 0) value = value.substring(separator + 1);
        value = value.replaceAll("[\\p{Cntrl}\\\";]", "_").strip();
        if (value.isBlank() || value.equals(".") || value.equals("..") || value.length() > 180) {
            String extension = switch (contentType) {
                case MediaType.IMAGE_JPEG_VALUE -> ".jpg";
                case MediaType.IMAGE_PNG_VALUE -> ".png";
                default -> ".webp";
            };
            return "image-" + (index + 1) + extension;
        }
        return value;
    }

    private static String requiredText(String raw, String field, int maxLength, boolean allowNewlines) {
        if (raw == null || raw.isBlank()) throw validation(field + " 不能为空");
        if (raw.length() > maxLength) throw validation(field + " 长度超过限制");
        boolean invalidControl = raw.codePoints().anyMatch(value -> Character.isISOControl(value)
                && !(allowNewlines && (value == '\n' || value == '\r' || value == '\t')));
        if (invalidControl) throw validation(field + " 包含无效控制字符");
        return "model".equals(field) ? raw.strip() : raw;
    }

    private static String requiredJsonText(
            JsonNode body, String field, int maxLength, boolean allowNewlines
    ) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual()) throw validation(field + " 必须是字符串");
        return requiredText(node.asText(), field, maxLength, allowNewlines);
    }

    private static String optionalJsonText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw validation(field + " 必须是字符串");
        return node.asText();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
