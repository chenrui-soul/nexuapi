-- 用户分组状态按 group_id 读取最近 60 次真实业务请求，增加时间倒序索引避免逐组扫描日志分区。
CREATE INDEX idx_request_logs_group_time
    ON request_logs (group_id, created_at DESC)
    WHERE group_id IS NOT NULL;

COMMENT ON INDEX idx_request_logs_group_time IS
    '服务分组状态按分组和时间倒序读取最近真实请求的查询索引';
