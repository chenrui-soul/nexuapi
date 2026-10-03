import assert from "node:assert/strict";
import test from "node:test";
import { ApiError } from "../lib/api.ts";
import { getRegistration, getPlatformSettings, savePlatformSettings, listAnnouncements, saveAnnouncement, transitionAnnouncement, getAnnouncementFeed, readAnnouncement, type Announcement } from "../lib/platform.ts";

test("public registration reads always bypass the cache and surface failure", async () => {
  const original = globalThis.fetch;
  try {
    let enabled = true;
    globalThis.fetch = async (url, init) => {
      assert.equal(String(url), "/api/v1/system/registration");
      assert.equal(init?.cache, "no-store");
      return Response.json({ success: true, data: { registration_enabled: enabled } });
    };
    assert.equal((await getRegistration()).registration_enabled, true);
    enabled = false;
    assert.equal((await getRegistration()).registration_enabled, false);
    globalThis.fetch = async () => Response.json({ success: false }, { status: 503 });
    await assert.rejects(getRegistration(), ApiError);
  } finally { globalThis.fetch = original; }
});

test("platform writes use CSRF and preserve versions, plain text, and user-scoped receipt paths", async () => {
  const original = globalThis.fetch;
  const writes: { path: string; method: string; body: unknown }[] = [];
  try {
    globalThis.fetch = async (url, init) => {
      const path = String(url);
      if (path === "/api/v1/auth/csrf") return Response.json({ success: true, data: { header: "X-CSRF-TOKEN", token: "test-csrf" } });
      assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "test-csrf");
      assert.equal(init?.credentials, "include");
      writes.push({ path, method: init!.method!, body: JSON.parse(init!.body as string) });
      return Response.json({ success: true, data: {} });
    };
    await savePlatformSettings({ registration_enabled: false, version: 5 });
    await saveAnnouncement({ title: "test", content: "line 1\n<script>untrusted</script>" });
    await saveAnnouncement({ title: "edited", content: "new", version: 2 }, "id-1");
    await transitionAnnouncement({ id: "id-1", version: 3 } as Announcement, "publish");
    await transitionAnnouncement({ id: "id-1", version: 4 } as Announcement, "withdraw");
    await readAnnouncement("id-1");
    assert.deepEqual(writes[0], { path: "/api/v1/admin/settings", method: "PUT", body: { registration_enabled: false, version: 5 } });
    assert.deepEqual(writes[1].body, { title: "test", content: "line 1\n<script>untrusted</script>" });
    assert.equal(writes[2].method, "PUT");
    assert.deepEqual(writes[3], { path: "/api/v1/admin/announcements/id-1/publish", method: "POST", body: { version: 3 } });
    assert.equal(writes[4].path, "/api/v1/admin/announcements/id-1/withdraw");
    assert.deepEqual(writes[5], { path: "/api/v1/announcements/id-1/read", method: "POST", body: {} });
  } finally { globalThis.fetch = original; }
});

test("admin and user announcement lists are separate uncached paged reads", async () => {
  const original = globalThis.fetch;
  const paths: string[] = [];
  try {
    globalThis.fetch = async (url, init) => {
      paths.push(String(url)); assert.equal(init?.cache, "no-store");
      return Response.json({ success: true, data: {} });
    };
    await getPlatformSettings(); await listAnnouncements(2); await getAnnouncementFeed(3);
    assert.deepEqual(paths, ["/api/v1/admin/settings", "/api/v1/admin/announcements?page=2&page_size=10", "/api/v1/announcements?page=3&page_size=10"]);
  } finally { globalThis.fetch = original; }
});
