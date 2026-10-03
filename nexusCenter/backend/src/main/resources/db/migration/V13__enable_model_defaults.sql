-- 模型目录统一默认开放；管理员仍可在模型维护页单独关闭任一能力或停用模型。
ALTER TABLE ai_models
    ALTER COLUMN supports_streaming SET DEFAULT true,
    ALTER COLUMN supports_tools SET DEFAULT true,
    ALTER COLUMN supports_structured_output SET DEFAULT true,
    ALTER COLUMN public_visible SET DEFAULT true,
    ALTER COLUMN status SET DEFAULT 'active';

-- 按产品要求将已有模型一次性统一为启用和公开状态。
UPDATE ai_models
   SET supports_streaming = true,
       supports_tools = true,
       supports_structured_output = true,
       public_visible = true,
       status = 'active',
       updated_at = now(),
       version = version + 1
 WHERE supports_streaming = false
    OR supports_tools = false
    OR supports_structured_output = false
    OR public_visible = false
    OR status <> 'active';
