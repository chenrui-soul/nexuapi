package com.nexusapi.server.modules.gateway.support;

/**
 * OpenAI 兼容响应的标准化 Token 用量。
 *
 * <p>缓存命中、缓存写入、音频输入属于输入 Token 的互斥子集，音频输出属于输出 Token 子集；
 * 计费时会从普通输入/输出中扣除这些子集，防止同一 Token 重复扣费。</p>
 */
public record OpenAiUsage(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long cacheWrite5mInputTokens,
        long cacheWrite1hInputTokens,
        long audioInputTokens,
        long audioOutputTokens
) {
    /** 兼容现有文本调用方；未报告的细分 Token 默认为 0。 */
    public OpenAiUsage(long inputTokens, long outputTokens, long cachedInputTokens) {
        this(inputTokens, outputTokens, cachedInputTokens, 0L, 0L, 0L, 0L);
    }

    public OpenAiUsage {
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        cachedInputTokens = Math.min(inputTokens, Math.max(0L, cachedInputTokens));
        long remainingInput = Math.max(0L, inputTokens - cachedInputTokens);
        cacheWrite5mInputTokens = Math.min(remainingInput, Math.max(0L, cacheWrite5mInputTokens));
        remainingInput -= cacheWrite5mInputTokens;
        cacheWrite1hInputTokens = Math.min(remainingInput, Math.max(0L, cacheWrite1hInputTokens));
        remainingInput -= cacheWrite1hInputTokens;
        audioInputTokens = Math.min(remainingInput, Math.max(0L, audioInputTokens));
        audioOutputTokens = Math.min(outputTokens, Math.max(0L, audioOutputTokens));
    }

    public long ordinaryInputTokens() {
        return inputTokens - cachedInputTokens - cacheWrite5mInputTokens - cacheWrite1hInputTokens - audioInputTokens;
    }

    public long ordinaryOutputTokens() {
        return outputTokens - audioOutputTokens;
    }

    public long totalTokens() {
        if (Long.MAX_VALUE - inputTokens < outputTokens) {
            return Long.MAX_VALUE;
        }
        return inputTokens + outputTokens;
    }
}
