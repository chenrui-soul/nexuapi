package com.nexusapi.server.modules.gateway.capability;

import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.Optional;

/**
 * 程序固定的对外能力入口与上游地址匹配规则。
 *
 * <p>该枚举属于真实调用契约，不读取 {@code api_interfaces} 或 {@code model_interfaces}。
 * 管理员维护的接口文档只负责展示请求和返回字段，不能改变 Gateway 选路。</p>
 */
public enum PublicGatewayOperation {
    CHAT_COMPLETIONS("chat_completions", "对话补全", "text", HttpMethod.POST, "/v1/chat/completions", true),
    RESPONSES("responses", "Responses", "text", HttpMethod.POST, "/v1/responses", false),
    IMAGE_GENERATIONS("image_generations", "图片生成", "image", HttpMethod.POST, "/v1/images/generations", true),
    IMAGE_TASK_CREATE("image_task_create", "异步图片任务创建", "image", HttpMethod.POST, "/v1/images/tasks", false),
    IMAGE_TASK_DETAIL("image_task_detail", "异步图片任务详情", "image", HttpMethod.GET, "/v1/images/tasks/{id}", false),
    VIDEO_CREATE("video_create", "创建视频", "video", HttpMethod.POST, "/v1/videos", false),
    VIDEO_LIST("video_list", "视频列表", "video", HttpMethod.GET, "/v1/videos", false),
    VIDEO_DETAIL("video_detail", "视频详情", "video", HttpMethod.GET, "/v1/videos/{taskId}", false),
    AUDIO_SPEECH("audio_speech", "语音合成", "audio", HttpMethod.POST, "/v1/audio/speech", false),
    AUDIO_TRANSCRIPTION("audio_transcriptions", "音频转写", "audio", HttpMethod.POST, "/v1/audio/transcriptions", false),
    EMBEDDINGS("embeddings", "向量生成", "embedding", HttpMethod.POST, "/v1/embeddings", false);

    private final String operationCode;
    private final String displayName;
    private final String capabilityType;
    private final HttpMethod httpMethod;
    private final String publicPath;
    private final boolean apiVersionRootAllowed;

    PublicGatewayOperation(
            String operationCode,
            String displayName,
            String capabilityType,
            HttpMethod httpMethod,
            String publicPath,
            boolean apiVersionRootAllowed
    ) {
        this.operationCode = operationCode;
        this.displayName = displayName;
        this.capabilityType = capabilityType;
        this.httpMethod = httpMethod;
        this.publicPath = publicPath;
        this.apiVersionRootAllowed = apiVersionRootAllowed;
    }

    public String operationCode() { return operationCode; }
    public String displayName() { return displayName; }
    public String capabilityType() { return capabilityType; }
    public HttpMethod httpMethod() { return httpMethod; }
    public String publicPath() { return publicPath; }
    public boolean apiVersionRootAllowed() { return apiVersionRootAllowed; }

    /** 管理端只能绑定平台注册过的能力编码，不能通过数据库创造未知执行入口。 */
    public static Optional<PublicGatewayOperation> fromOperationCode(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(operation -> operation.operationCode.equals(normalized))
                .findFirst();
    }
}
