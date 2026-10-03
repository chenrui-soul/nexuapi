import type { AvailableModel } from "./api-keys.ts";

/** 将英文逗号、中文逗号或换行输入统一解析为去重后的 API 模型名。 */
export function parseModelWhitelist(value: string): string[] {
  const seen = new Set<string>();
  return value
    .split(/[,，\r\n]+/)
    .map((name) => name.trim())
    .filter((name) => {
      const normalized = name.toLowerCase();
      if (!normalized || seen.has(normalized)) return false;
      seen.add(normalized);
      return true;
    });
}

/**
 * 用户输入的是公开 API 模型名，保存前再映射为后端现有的模型 ID。
 * 未识别名称不会静默丢弃，调用方应阻止保存并明确提示用户。
 */
export function resolveModelWhitelist(value: string, models: AvailableModel[]) {
  const names = parseModelWhitelist(value);
  const modelByName = new Map(models.map((model) => [model.publicName.toLowerCase(), model]));
  const modelIds: string[] = [];
  const canonicalNames: string[] = [];
  const unknownNames: string[] = [];

  names.forEach((name) => {
    const model = modelByName.get(name.toLowerCase());
    if (!model) {
      unknownNames.push(name);
      return;
    }
    modelIds.push(model.id);
    canonicalNames.push(model.publicName);
  });
  return { names, modelIds, canonicalNames, unknownNames };
}

/** 编辑令牌时把后端保存的模型 ID 还原为用户可读的公开 API 模型名。 */
export function modelWhitelistText(modelIds: string[], models: AvailableModel[]) {
  const modelById = new Map(models.map((model) => [model.id, model.publicName]));
  const names: string[] = [];
  const unknownIds: string[] = [];
  modelIds.forEach((id) => {
    const name = modelById.get(id);
    if (name) names.push(name);
    else unknownIds.push(id);
  });
  return { value: names.join(", "), unknownIds };
}
