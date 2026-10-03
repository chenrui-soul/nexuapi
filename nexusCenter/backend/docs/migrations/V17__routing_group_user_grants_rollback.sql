-- 回滚前确认没有仍依赖特殊分组授权的用户或 API Key。
DROP TABLE IF EXISTS routing_group_user_grants;
