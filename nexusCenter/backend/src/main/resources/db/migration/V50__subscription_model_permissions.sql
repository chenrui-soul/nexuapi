-- 订阅套餐模型权限与用户订阅模型快照。
-- 服务分组权限和模型权限相互独立配置，Gateway 运行时同时校验两者。

CREATE TABLE plan_models (
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    model_id UUID NOT NULL REFERENCES ai_models(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plan_id, model_id)
);

CREATE INDEX idx_plan_models_model
    ON plan_models (model_id, plan_id);

CREATE TABLE subscription_models (
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    model_id UUID NOT NULL REFERENCES ai_models(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (subscription_id, model_id)
);

CREATE INDEX idx_subscription_models_model
    ON subscription_models (model_id, subscription_id);

-- 兼容回填只用于避免升级后既有套餐突然失去模型权限。
-- 正式销售前仍需由管理员按照每个套餐的真实权益收窄模型范围。
INSERT INTO plan_models (plan_id, model_id)
SELECT p.id, m.id
  FROM plans p
 CROSS JOIN ai_models m
 WHERE p.status = 'active'
   AND m.status = 'active'
   AND m.public_visible = true
ON CONFLICT DO NOTHING;

-- 已售活动订阅复制套餐模型权限，后续套餐修改不追溯改变该订阅快照。
INSERT INTO subscription_models (subscription_id, model_id)
SELECT s.id, pm.model_id
  FROM subscriptions s
  JOIN plan_models pm ON pm.plan_id = s.plan_id
 WHERE s.status = 'active'
ON CONFLICT DO NOTHING;

COMMENT ON TABLE plan_models IS '订阅套餐允许使用的模型配置；与套餐服务分组配置共同构成套餐调用权限。';
COMMENT ON COLUMN plan_models.plan_id IS '订阅套餐标识。';
COMMENT ON COLUMN plan_models.model_id IS '套餐允许用户使用的模型标识。';
COMMENT ON COLUMN plan_models.created_at IS '套餐与模型关系创建时间。';

COMMENT ON TABLE subscription_models IS '用户开通订阅时复制的模型权限快照，套餐后续修改不追溯影响已售订阅。';
COMMENT ON COLUMN subscription_models.subscription_id IS '用户订阅记录标识。';
COMMENT ON COLUMN subscription_models.model_id IS '该订阅周期允许使用的模型标识。';
COMMENT ON COLUMN subscription_models.created_at IS '订阅模型权限快照创建时间。';
