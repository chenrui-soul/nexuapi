import assert from "node:assert/strict";
import test from "node:test";
import {
  createChat,
  createImage,
  createVideo,
  extractAssistantText,
  extractMediaUrls,
  extractVideoReference,
} from "../lib/creation-space.ts";

function ok<T>(data: T): Response {
  return Response.json({ success: true, data, request_id: "req-creation" });
}

test("creation space uses Cookie Session and CSRF without creating a hidden API key", async () => {
  const originalFetch = globalThis.fetch;
  const writes: Array<{ path: string; body: unknown }> = [];
  globalThis.fetch = async (request, init) => {
    const path = String(request);
    if (path === "/api/v1/auth/csrf") return ok({ header: "X-CSRF-TOKEN", token: "csrf-value" });
    assert.equal(init?.method, "POST");
    assert.equal(init?.credentials, "include");
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf-value");
    writes.push({ path, body: JSON.parse(String(init?.body)) });
    return ok({ id: "result-1" });
  };
  try {
    await createChat({
      serviceGroupId: "group-1",
      model: "gpt-5.4-mini",
      messages: [{ role: "user", content: "你好" }],
    });
    await createImage({
      serviceGroupId: "group-1", model: "gpt-image-2", prompt: "蓝色小球",
      quantity: 1, aspectRatio: "1:1", quality: "medium", resolution: "1k",
    });
    await createVideo({
      serviceGroupId: "group-1", model: "seedance-2.0", prompt: "蓝色小球滚动",
      duration: 5, aspectRatio: "1:1", resolution: "480p",
    });

    assert.deepEqual(writes, [
      { path: "/api/v1/creation-space/chat", body: {
        service_group_id: "group-1", model: "gpt-5.4-mini",
        messages: [{ role: "user", content: "你好" }], stream: false,
      } },
      { path: "/api/v1/creation-space/images", body: {
        service_group_id: "group-1", model: "gpt-image-2", prompt: "蓝色小球",
        n: "1", aspect_ratio: "1:1", quality: "medium", resolution: "1k",
      } },
      { path: "/api/v1/creation-space/videos", body: {
        service_group_id: "group-1", model: "seedance-2.0", prompt: "蓝色小球滚动",
        duration: 5, aspect_ratio: "1:1", resolution: "480p", generate_audio: false,
      } },
    ]);
    assert.equal(writes.some(({ path }) => path.includes("api-keys")), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("creation response helpers support text, image and video contracts", () => {
  assert.equal(extractAssistantText({ choices: [{ message: { content: "回答" } }] }), "回答");
  assert.deepEqual(extractMediaUrls({ data: [{ url: "https://example.test/image.png" }, { b64_json: "abc" }] }), [
    "https://example.test/image.png", "data:image/png;base64,abc",
  ]);
  assert.equal(extractVideoReference({ task_id: "video-task-1" }), "video-task-1");
});
