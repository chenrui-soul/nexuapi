-- 分组级渠道凭证已由“分组 × 供应商”凭证取代，当前运行时和管理端不再读取该表。
-- 仅删除历史兼容对象；V1/V10 等历史迁移保持不变，便于审计和新环境重放。
DROP TABLE IF EXISTS routing_group_channel_credentials;
