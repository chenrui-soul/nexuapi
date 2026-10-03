DROP TABLE IF EXISTS health_alerts;

ALTER TABLE channels
    DROP CONSTRAINT IF EXISTS channels_health_probe_path_safe,
    DROP COLUMN IF EXISTS health_probe_path;
