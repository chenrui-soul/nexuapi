-- Transport options do not grant model access; service groups own routing membership.
-- Refuse ambiguous legacy data rather than silently select a different upstream model.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM channel_models WHERE status = 'active'
        GROUP BY channel_id, model_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Resolve duplicate active channel/model options before retiring channel_models';
    END IF;
    IF EXISTS (SELECT 1 FROM channels WHERE metadata ? 'upstream_models') THEN
        RAISE EXCEPTION 'channels.metadata.upstream_models already exists; review before migration';
    END IF;
END $$;

UPDATE channels c
SET metadata = c.metadata || jsonb_build_object('upstream_models', options.models)
FROM (
    SELECT cm.channel_id, jsonb_object_agg(cm.model_id::text, jsonb_build_object(
        'upstream_model', cm.upstream_model,
        'config', cm.config,
        'cost_input_price', cm.cost_input_price,
        'cost_cached_input_price', cm.cost_cached_input_price,
        'cost_output_price', cm.cost_output_price
    )) AS models
    FROM channel_models cm
    JOIN ai_models m ON m.id = cm.model_id
    WHERE cm.status = 'active'
      AND (cm.upstream_model <> m.public_name OR cm.config <> '{}'::jsonb
           OR cm.cost_input_price <> 0 OR cm.cost_cached_input_price <> 0 OR cm.cost_output_price <> 0)
    GROUP BY cm.channel_id
) options
WHERE c.id = options.channel_id;

-- Retain historical UUIDs and financial facts without a dependency on the retired table.
ALTER TABLE request_logs DROP CONSTRAINT request_logs_channel_model_id_fkey;
ALTER TABLE upstream_attempt_logs DROP CONSTRAINT upstream_attempt_logs_channel_model_id_fkey;
DROP TABLE channel_models;

COMMENT ON COLUMN request_logs.channel_model_id IS 'Historical mapping UUID only; new requests leave this null.';
COMMENT ON COLUMN upstream_attempt_logs.channel_model_id IS 'Historical mapping UUID only; new attempts leave this null.';
COMMENT ON COLUMN channels.metadata IS 'Channel metadata; upstream_models holds optional per-model transport aliases, request config and supplier costs. It never grants routing access.';
COMMENT ON COLUMN ai_models.adapter_key IS 'Platform model protocol adapter; channel transport options do not replace an explicitly selected model adapter.';
