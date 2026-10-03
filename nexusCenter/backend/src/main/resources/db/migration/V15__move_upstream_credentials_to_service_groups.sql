-- 上游密钥只由“服务分组 × 供应商”关系维护。
-- 旧渠道密钥列和历史密文暂时保留用于回滚，但新接口不再写入，Gateway 也不再读取。

ALTER TABLE channels
    ALTER COLUMN encrypted_credential DROP NOT NULL,
    ALTER COLUMN credential_key_version DROP NOT NULL;

COMMENT ON COLUMN channels.encrypted_credential IS
    '旧渠道凭证密文，仅用于迁移回滚；新上游密钥必须写入 routing_group_supplier_credentials';
COMMENT ON COLUMN channels.credential_key_version IS
    '旧渠道凭证密钥版本，仅用于迁移回滚；运行时不再读取';
