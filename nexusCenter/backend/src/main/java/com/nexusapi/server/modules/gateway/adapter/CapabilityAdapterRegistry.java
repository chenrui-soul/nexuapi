package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 业务层使用的适配器注册中心。
 *
 * <p>适配器键由 ai_models.adapter_key 统一维护；旧路由对象仍允许从历史 config 回退，迁移后正式数据不再依赖渠道映射。</p>
 */
@Component
public class CapabilityAdapterRegistry {
    private final ObjectMapper objectMapper;
    private final AdapterCatalogMapper catalogMapper;
    private final Map<String, TextCapabilityAdapter> textAdapters;
    private final Map<String, ImageCapabilityAdapter> imageAdapters;
    private final Map<String, VideoCapabilityAdapter> videoAdapters;

    public CapabilityAdapterRegistry(
            ObjectMapper objectMapper,
            AdapterCatalogMapper catalogMapper,
            List<TextCapabilityAdapter> textAdapters,
            List<ImageCapabilityAdapter> imageAdapters,
            List<VideoCapabilityAdapter> videoAdapters
    ) {
        this.objectMapper = objectMapper;
        this.catalogMapper = catalogMapper;
        this.textAdapters = textAdapters.stream().collect(Collectors.toUnmodifiableMap(TextCapabilityAdapter::key, Function.identity()));
        this.imageAdapters = imageAdapters.stream().collect(Collectors.toUnmodifiableMap(ImageCapabilityAdapter::key, Function.identity()));
        this.videoAdapters = videoAdapters.stream().collect(Collectors.toUnmodifiableMap(VideoCapabilityAdapter::key, Function.identity()));
    }

    public TextCapabilityAdapter text(RuntimeRouteRow route) {
        return required(textAdapters, resolveImplementationKey(adapterKey(route, "text"), "text"));
    }

    public ImageCapabilityAdapter image(RuntimeRouteRow route) {
        return required(imageAdapters, resolveImplementationKey(adapterKey(route, "image"), "image"));
    }

    public VideoCapabilityAdapter video(RuntimeRouteRow route) {
        return required(videoAdapters, resolveImplementationKey(adapterKey(route, "video"), "video"));
    }

    /** 管理员目录可以创建别名，但别名必须绑定到已注册的真实代码实现。 */
    public boolean supportsConfiguredAdapter(String configuredKey, String capability) {
        if (configuredKey == null || configuredKey.isBlank()) return false;
        String normalized = configuredKey.strip().toLowerCase(java.util.Locale.ROOT);
        AdapterCatalogRow configured = catalogMapper.findByKey(normalized);
        if (configured != null) {
            if (!"active".equalsIgnoreCase(configured.getStatus())) return false;
            normalized = configured.getImplementationKey();
        }
        return supportsImplementation(normalized, capability);
    }

    public boolean supportsImplementation(String implementationKey, String capability) {
        if (implementationKey == null || capability == null) return false;
        return switch (capability.toLowerCase(java.util.Locale.ROOT)) {
            case "image" -> imageAdapters.containsKey(implementationKey);
            case "video" -> videoAdapters.containsKey(implementationKey);
            default -> textAdapters.containsKey(implementationKey);
        };
    }

    public String implementationLabel(String implementationKey) {
        return switch (implementationKey) {
            case "openai_compatible_text" -> "OpenAI 兼容文本实现";
            case "openai_compatible_image" -> "OpenAI 兼容图片实现";
            case "openai_compatible_video" -> "OpenAI 兼容视频实现";
            case "jimeng_video" -> "即梦视频实现";
            case "grok_video" -> "Grok 视频实现";
            case "minimax_h3_video" -> "MiniMax H3 视频实现";
            default -> implementationKey;
        };
    }

    private String resolveImplementationKey(String configuredKey, String capability) {
        AdapterCatalogRow configured = catalogMapper.findByKey(configuredKey);
        if (configured == null) return configuredKey;
        if (!"active".equalsIgnoreCase(configured.getStatus())) {
            throw new IllegalStateException("适配器已停用: " + configuredKey);
        }
        return configured.getImplementationKey();
    }

    private String adapterKey(RuntimeRouteRow route, String capability) {
        if (route != null && route.getAdapterKey() != null && !route.getAdapterKey().isBlank()) {
            return route.getAdapterKey().trim();
        }
        if (route == null || route.getConfigJson() == null || route.getConfigJson().isBlank()) {
            return "openai_compatible_" + capability;
        }
        try {
            JsonNode config = objectMapper.readTree(route.getConfigJson());
            String configured = config.path("adapter_key").asText("").trim();
            return configured.isBlank() ? "openai_compatible_" + capability : configured;
        } catch (Exception ignored) {
            return "openai_compatible_" + capability;
        }
    }

    private <T> T required(Map<String, T> adapters, String key) {
        T adapter = adapters.get(key);
        if (adapter == null) throw new IllegalStateException("未注册能力适配器: " + key);
        return adapter;
    }
}
