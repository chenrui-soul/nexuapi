-- Wave 9B：把用户 API Key、服务分组和供应商资源形成显式关系。
-- 旧 channels/channel_models/group_routes 继续作为已验证的兼容执行层，不删除历史数据。

ALTER TABLE channels
    ADD COLUMN endpoint_type VARCHAR(24) NOT NULL DEFAULT 'text',
    ADD CONSTRAINT channels_endpoint_type_valid
        CHECK (endpoint_type IN ('text', 'image', 'video', 'audio', 'embedding', 'multimodal'));

-- 已有接口尽量根据现有供应模型能力推导类型；无法唯一判断时保持 text，等待管理员确认。
UPDATE channels c
   SET endpoint_type = inferred.capability_type
  FROM (
        SELECT cm.channel_id, min(m.capability_type) AS capability_type
          FROM channel_models cm
          JOIN ai_models m ON m.id = cm.model_id
         GROUP BY cm.channel_id
        HAVING count(DISTINCT m.capability_type) = 1
       ) inferred
 WHERE inferred.channel_id = c.id;

ALTER TABLE api_keys
    ADD COLUMN service_group_id UUID;

UPDATE api_keys
   SET service_group_id = default_group_id
 WHERE service_group_id IS NULL
   AND default_group_id IS NOT NULL;

UPDATE api_keys k
   SET service_group_id = (
       SELECT g.id
         FROM jsonb_array_elements_text(k.allowed_group_ids) WITH ORDINALITY allowed(value, position)
         JOIN routing_groups g ON g.id = allowed.value::uuid
        ORDER BY allowed.position
        LIMIT 1
   )
 WHERE k.service_group_id IS NULL
   AND jsonb_array_length(k.allowed_group_ids) > 0;

-- 只为仍未绑定的历史 Key 选择一个已有可路由分组；撤销 Key 允许继续为空以便保留历史审计。
UPDATE api_keys k
   SET service_group_id = candidate.group_id
  FROM LATERAL (
        SELECT g.id AS group_id
          FROM routing_groups g
         WHERE EXISTS (
               SELECT 1 FROM group_routes gr
                WHERE gr.group_id = g.id AND gr.status = 'active'
         )
         ORDER BY CASE WHEN g.status = 'active' THEN 0 ELSE 1 END,
                  CASE WHEN g.audience = 'all' THEN 0 WHEN g.audience = 'assigned' THEN 1 ELSE 2 END,
                  g.created_at
         LIMIT 1
       ) candidate
 WHERE k.service_group_id IS NULL
   AND k.status <> 'revoked';

ALTER TABLE api_keys
    ADD CONSTRAINT fk_api_keys_service_group
        FOREIGN KEY (service_group_id) REFERENCES routing_groups(id),
    ADD CONSTRAINT api_keys_service_group_required
        CHECK (status = 'revoked' OR service_group_id IS NOT NULL);

CREATE INDEX idx_api_keys_service_group_status
    ON api_keys (service_group_id, status)
    WHERE revoked_at IS NULL;

CREATE TABLE routing_group_suppliers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES routing_groups(id) ON DELETE CASCADE,
    supplier_id UUID NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
    priority INTEGER NOT NULL DEFAULT 100,
    weight INTEGER NOT NULL DEFAULT 100,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_routing_group_suppliers UNIQUE (group_id, supplier_id),
    CONSTRAINT routing_group_suppliers_priority_valid CHECK (priority >= 0),
    CONSTRAINT routing_group_suppliers_weight_valid CHECK (weight > 0),
    CONSTRAINT routing_group_suppliers_status_valid CHECK (status IN ('active', 'disabled'))
);

-- 根据现有分组路由无损建立分组与供应商关系，避免新版本切换后历史路由突然不可用。
INSERT INTO routing_group_suppliers (group_id, supplier_id, priority, weight, status)
SELECT gr.group_id,
       c.supplier_id,
       min(gr.priority + cm.priority + c.priority),
       greatest(1, max(gr.weight)),
       CASE WHEN bool_or(gr.status = 'active') THEN 'active' ELSE 'disabled' END
  FROM group_routes gr
  JOIN channel_models cm ON cm.id = gr.channel_model_id
  JOIN channels c ON c.id = cm.channel_id
 GROUP BY gr.group_id, c.supplier_id
ON CONFLICT (group_id, supplier_id) DO NOTHING;

CREATE INDEX idx_routing_group_suppliers_route
    ON routing_group_suppliers (group_id, status, priority, weight);

CREATE TABLE routing_group_supplier_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES routing_groups(id) ON DELETE CASCADE,
    supplier_id UUID NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
    encrypted_credential BYTEA NOT NULL,
    credential_key_version INTEGER NOT NULL,
    credential_fingerprint VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_routing_group_supplier_credentials UNIQUE (group_id, supplier_id),
    CONSTRAINT routing_group_supplier_credentials_status_valid CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_routing_group_supplier_credentials_lookup
    ON routing_group_supplier_credentials (group_id, supplier_id, status);

COMMENT ON COLUMN channels.endpoint_type IS '供应商上游接口能力类型：文本、图像、视频、音频、向量或多模态';
COMMENT ON COLUMN api_keys.service_group_id IS '用户创建 API Key 时自主选择且运行时唯一绑定的服务分组标识';

COMMENT ON TABLE routing_group_suppliers IS '服务分组与供应商资源关系，定义主备优先级、权重和可用状态';
COMMENT ON COLUMN routing_group_suppliers.id IS '服务分组供应商关系唯一标识';
COMMENT ON COLUMN routing_group_suppliers.group_id IS '服务分组标识';
COMMENT ON COLUMN routing_group_suppliers.supplier_id IS '供应商标识';
COMMENT ON COLUMN routing_group_suppliers.priority IS '供应商在该分组内的优先级，数值越小越优先';
COMMENT ON COLUMN routing_group_suppliers.weight IS '同优先级供应商在该分组内的路由权重';
COMMENT ON COLUMN routing_group_suppliers.status IS '关系状态：active 或 disabled';
COMMENT ON COLUMN routing_group_suppliers.created_at IS '关系创建时间';
COMMENT ON COLUMN routing_group_suppliers.updated_at IS '关系最后更新时间';
COMMENT ON COLUMN routing_group_suppliers.version IS '乐观锁版本号';

COMMENT ON TABLE routing_group_supplier_credentials IS '服务分组为供应商配置的独立上游凭证密文，不保存或回显明文';
COMMENT ON COLUMN routing_group_supplier_credentials.id IS '分组供应商凭证唯一标识';
COMMENT ON COLUMN routing_group_supplier_credentials.group_id IS '服务分组标识';
COMMENT ON COLUMN routing_group_supplier_credentials.supplier_id IS '供应商标识';
COMMENT ON COLUMN routing_group_supplier_credentials.encrypted_credential IS '使用 AES-256-GCM 加密后的上游凭证密文';
COMMENT ON COLUMN routing_group_supplier_credentials.credential_key_version IS '加密主密钥版本，用于安全轮换';
COMMENT ON COLUMN routing_group_supplier_credentials.credential_fingerprint IS '凭证不可逆短指纹，仅用于管理员识别是否已轮换';
COMMENT ON COLUMN routing_group_supplier_credentials.status IS '凭证状态：active 或 disabled';
COMMENT ON COLUMN routing_group_supplier_credentials.created_at IS '凭证首次创建时间';
COMMENT ON COLUMN routing_group_supplier_credentials.updated_at IS '凭证最后轮换或状态更新时间';
COMMENT ON COLUMN routing_group_supplier_credentials.version IS '乐观锁版本号';
