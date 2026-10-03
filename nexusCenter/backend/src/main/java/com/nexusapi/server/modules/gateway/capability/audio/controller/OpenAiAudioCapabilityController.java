package com.nexusapi.server.modules.gateway.capability.audio.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.apikey.security.NexusApiKeyPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.gateway.capability.audio.model.OpenAiAudioTranscriptionRequest;
import com.nexusapi.server.modules.gateway.service.OpenAiGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 音频能力的统一对外入口；语音合成与音频转写使用不同接口契约。 */
@RestController
@RequestMapping("/v1/audio")
public class OpenAiAudioCapabilityController {
    private static final int MAX_SPEECH_CHARACTERS = 100_000;
    private static final Set<String> SPEECH_FORMATS = Set.of("mp3", "opus", "aac", "flac", "wav", "pcm");

    private final OpenAiGatewayService service;

    public OpenAiAudioCapabilityController(OpenAiGatewayService service) {
        this.service = service;
    }

    /** TTS：JSON 文本输入，返回上游生成的二进制音频，按输入字符数计费。 */
    @PostMapping(value = "/speech", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> speech(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestBody ObjectNode body,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        String model = requiredText(body, "model", 160);
        requiredText(body, "input", MAX_SPEECH_CHARACTERS);
        requiredText(body, "voice", 120);
        String format = body.path("response_format").asText("mp3").strip().toLowerCase(Locale.ROOT);
        if (!SPEECH_FORMATS.contains(format)) throw validation("response_format 不支持该值");
        body.put("response_format", format);
        OpenAiGatewayService.BinaryCapabilityResult result = service.audioSpeech(
                principal, body, model, groupSelector, ClientRequestMetadata.from(servletRequest)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_TYPE, result.contentType().toString())
                .body(result.body());
    }

    /** Whisper：multipart 音频输入，返回指定格式的转写结果，按上游实际音频秒数计费。 */
    @PostMapping(value = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<byte[]> transcriptions(
            @AuthenticationPrincipal NexusApiKeyPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam("model") String model,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestParam(value = "response_format", required = false) String responseFormat,
            @RequestParam(value = "temperature", required = false) String temperature,
            @RequestParam(value = "timestamp_granularities[]", required = false)
            List<String> timestampGranularities,
            @RequestHeader(value = "X-Nexus-Group", required = false) String groupSelector,
            HttpServletRequest servletRequest
    ) {
        OpenAiAudioTranscriptionRequest request = OpenAiAudioTranscriptionRequest.from(
                file, model, language, prompt, responseFormat, temperature, timestampGranularities
        );
        OpenAiGatewayService.BinaryCapabilityResult result = service.audioTranscription(
                principal, request, groupSelector, ClientRequestMetadata.from(servletRequest)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_TYPE, result.contentType().toString())
                .body(result.body());
    }

    private String requiredText(ObjectNode body, String field, int maxCharacters) {
        if (body == null || !body.path(field).isTextual()) throw validation(field + " 不能为空");
        String value = body.path(field).asText().strip();
        if (value.isEmpty()) throw validation(field + " 不能为空");
        if (value.codePointCount(0, value.length()) > maxCharacters) {
            throw validation(field + " 长度超过限制");
        }
        body.put(field, value);
        return value;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
