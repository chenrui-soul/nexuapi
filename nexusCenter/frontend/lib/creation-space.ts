/** 创作空间请求层：Cookie Session + CSRF 调用控制台入口，禁止在浏览器生成隐藏 API 令牌。 */
import { apiDataRequest } from "./api.ts";

type CsrfResponse = { header: string; token: string };

async function csrfHeaders(): Promise<Record<string, string>> {
  const csrf = await apiDataRequest<CsrfResponse>("/api/v1/auth/csrf", { cache: "no-store" });
  return { [csrf.header]: csrf.token };
}

export async function createChat(input: {
  serviceGroupId: string;
  model: string;
  messages: { role: "user" | "assistant" | "system"; content: string }[];
}): Promise<unknown> {
  return apiDataRequest("/api/v1/creation-space/chat", {
    method: "POST",
    headers: await csrfHeaders(),
    body: JSON.stringify({
      service_group_id: input.serviceGroupId,
      model: input.model,
      messages: input.messages,
      stream: false,
    }),
  });
}

export async function createImage(input: {
  serviceGroupId: string;
  model: string;
  prompt: string;
  quantity: number;
  aspectRatio: string;
  quality: string;
  resolution: string;
}): Promise<unknown> {
  return apiDataRequest("/api/v1/creation-space/images", {
    method: "POST",
    headers: await csrfHeaders(),
    body: JSON.stringify({
      service_group_id: input.serviceGroupId,
      model: input.model,
      prompt: input.prompt,
      n: String(input.quantity),
      aspect_ratio: input.aspectRatio,
      quality: input.quality,
      resolution: input.resolution,
    }),
  });
}

export async function createVideo(input: {
  serviceGroupId: string;
  model: string;
  prompt: string;
  duration: number;
  aspectRatio: string;
  resolution: string;
}): Promise<unknown> {
  return apiDataRequest("/api/v1/creation-space/videos", {
    method: "POST",
    headers: await csrfHeaders(),
    body: JSON.stringify({
      service_group_id: input.serviceGroupId,
      model: input.model,
      prompt: input.prompt,
      duration: input.duration,
      aspect_ratio: input.aspectRatio,
      resolution: input.resolution,
      generate_audio: false,
    }),
  });
}

export function extractAssistantText(payload: unknown): string {
  const value = payload as { choices?: { message?: { content?: unknown } }[] } | null;
  const content = value?.choices?.[0]?.message?.content;
  if (typeof content === "string") return content;
  return JSON.stringify(payload, null, 2);
}

export function extractMediaUrls(payload: unknown): string[] {
  const data = (payload as { data?: unknown[] } | null)?.data;
  if (!Array.isArray(data)) return [];
  return data.flatMap((item) => {
    if (!item || typeof item !== "object") return [];
    const row = item as { url?: unknown; b64_json?: unknown };
    if (typeof row.url === "string") return [row.url];
    if (typeof row.b64_json === "string") return [`data:image/png;base64,${row.b64_json}`];
    return [];
  });
}

export function extractVideoReference(payload: unknown): string | null {
  if (!payload || typeof payload !== "object") return null;
  const row = payload as Record<string, unknown>;
  for (const key of ["url", "video_url", "task_id", "id"]) {
    if (typeof row[key] === "string") return row[key] as string;
  }
  return null;
}
