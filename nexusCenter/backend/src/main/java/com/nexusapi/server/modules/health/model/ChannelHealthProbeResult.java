package com.nexusapi.server.modules.health.model;

/** 不携带上游正文的探测结果，只允许固定分类和脱敏摘要流入数据库。 */
public record ChannelHealthProbeResult(
        Outcome outcome,
        int latencyMs,
        String category,
        String safeSummary
) {
    public static ChannelHealthProbeResult healthy(int latencyMs) {
        return new ChannelHealthProbeResult(Outcome.HEALTHY, latencyMs, null, null);
    }

    public static ChannelHealthProbeResult failure(int latencyMs, String category, String safeSummary) {
        return new ChannelHealthProbeResult(Outcome.FAILURE, latencyMs, category, safeSummary);
    }

    public static ChannelHealthProbeResult unconfigured(int latencyMs, String safeSummary) {
        return new ChannelHealthProbeResult(Outcome.UNCONFIGURED, latencyMs, null, safeSummary);
    }

    public enum Outcome {
        HEALTHY,
        FAILURE,
        UNCONFIGURED
    }
}
