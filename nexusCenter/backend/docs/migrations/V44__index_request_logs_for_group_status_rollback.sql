-- 回滚 V44：删除服务分组最近请求查询索引；不影响任何业务数据。
DROP INDEX IF EXISTS idx_request_logs_group_time;
