package com.nexusapi.server.testing;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/** Optional transport settings; callers must separately configure service-group membership. */
public final class TestChannelOptions {
    private TestChannelOptions() { }

    public static void put(JdbcTemplate jdbc, UUID channelId, UUID modelId, String upstreamModel,
                           String inputCost, String cachedCost, String outputCost) {
        jdbc.update("""
                UPDATE channels SET metadata = metadata || jsonb_build_object('upstream_models',
                    coalesce(metadata->'upstream_models', '{}'::jsonb) || jsonb_build_object(CAST(? AS text),
                        jsonb_build_object('upstream_model', CAST(? AS text), 'config', '{}'::jsonb,
                            'cost_input_price', CAST(? AS numeric), 'cost_cached_input_price', CAST(? AS numeric),
                            'cost_output_price', CAST(? AS numeric))))
                WHERE id = ?
                """, modelId.toString(), upstreamModel, new BigDecimal(inputCost), new BigDecimal(cachedCost),
                new BigDecimal(outputCost), channelId);
    }
}
