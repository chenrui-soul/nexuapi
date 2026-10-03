import assert from "node:assert/strict";
import test from "node:test";
import { PageReadCache, readPageData, setPageReadScope } from "../lib/page-read-cache.ts";
import { apiDataRequest, pageDataRequest } from "../lib/api.ts";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(yes => { resolve = yes; });
  return { promise, resolve };
}

test("concurrent page mounts deduplicate; fresh reads reuse; manual refresh bypasses", async () => {
  const cache = new PageReadCache(); cache.setScope("alice");
  const pending = deferred<number>(); let calls = 0;
  const loader = () => { calls++; return pending.promise; };
  const a = cache.read("overview?today", loader, 30_000);
  const b = cache.read("overview?today", loader, 30_000);
  pending.resolve(1);
  assert.deepEqual(await Promise.all([a,b]), [1,1]); assert.equal(calls,1);
  assert.equal(await cache.read("overview?today", loader, 30_000),1); assert.equal(calls,1);
  assert.equal(await cache.read("overview?today", async () => 2, 30_000, true),2);
  assert.equal(await cache.read("overview?today", loader, 30_000),2);
});

test("expired data and distinct filters must load independently", async () => {
  const cache = new PageReadCache(); cache.setScope("alice");
  await cache.read("models?page=1", async () => 1, 0);
  assert.equal(await cache.read("models?page=1", async () => 2, 60_000),2);
  assert.equal(await cache.read("models?page=2", async () => 3, 60_000),3);
});

test("logout, user change and mutations reject pending stale reads, including deduplicated readers", async () => {
  for (const invalidate of [(c: PageReadCache) => c.setScope(null), (c: PageReadCache) => c.setScope("bob"), (c: PageReadCache) => c.clear()]) {
    const cache = new PageReadCache(); cache.setScope("alice");
    const pending = deferred<string>();
    const a = cache.read("overview", () => pending.promise, 30_000);
    const b = cache.read("overview", () => pending.promise, 30_000);
    const rejected = Promise.all([assert.rejects(a,/已失效/),assert.rejects(b,/已失效/)]);
    invalidate(cache); pending.resolve("alice-private"); await rejected;
    cache.setScope("bob");
    assert.equal(await cache.read("overview", async () => "bob-private",30_000),"bob-private");
  }
});

test("late pre-refresh request cannot overwrite forced refresh", async () => {
  const cache = new PageReadCache(); cache.setScope("alice");
  const pending = deferred<number>();
  const old = cache.read("status", () => pending.promise,15_000);
  assert.equal(await cache.read("status", async () => 2,15_000,true),2);
  pending.resolve(1); await old;
  assert.equal(await cache.read("status", async () => 3,15_000),2);
});

test("failed requests can retry; anonymous and SSR calls never reuse data", async () => {
  const cache = new PageReadCache(); cache.setScope("alice");
  await assert.rejects(cache.read("x",async () => { throw new Error("offline"); },30_000),/offline/);
  assert.equal(await cache.read("x",async () => 2,30_000),2);
  cache.setScope(null); let calls = 0;
  await cache.read("x",async () => ++calls,30_000);
  await cache.read("x",async () => ++calls,30_000);
  setPageReadScope("alice");
  await readPageData("x",async () => ++calls,30_000);
  await readPageData("x",async () => ++calls,30_000);
  assert.equal(calls,4);
});

test("browser API mutations invalidate opted-in reads; ordinary reads stay uncached", async () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis,"window");
  const originalFetch = globalThis.fetch;
  Object.defineProperty(globalThis,"window",{ value: {}, configurable: true });
  setPageReadScope("alice"); let reads = 0;
  globalThis.fetch = async (_url,init) => Response.json({success:true,data:init?.method === "POST" ? "written" : ++reads});
  try {
    assert.equal(await pageDataRequest("/overview",30_000),1);
    assert.equal(await pageDataRequest("/overview",30_000),1);
    await apiDataRequest("/update",{method:"POST"});
    assert.equal(await pageDataRequest("/overview",30_000),2);
    await apiDataRequest("/wallet"); await apiDataRequest("/wallet"); assert.equal(reads,4);
  } finally {
    setPageReadScope(null); globalThis.fetch = originalFetch;
    if(descriptor)Object.defineProperty(globalThis,"window",descriptor); else Reflect.deleteProperty(globalThis,"window");
  }
});
