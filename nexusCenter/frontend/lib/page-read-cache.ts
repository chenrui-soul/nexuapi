/** Short-lived, opt-in browser memory cache. Never stores data during SSR. */
export class PageReadCache {
  private scope: string | null = null;
  private generation = 0;
  private entries = new Map<string, { expires: number; value?: unknown; pending?: Promise<unknown> }>();

  setScope(scope: string | null) {
    if (scope !== this.scope) { this.clear(); this.scope = scope; }
  }

  clear() { this.generation++; this.entries.clear(); }

  async read<T>(key: string, loader: () => Promise<T>, ttl: number, force = false): Promise<T> {
    if (this.scope === null) return loader();
    const generation = this.generation;
    const previous = this.entries.get(key);
    if (!force && previous && (previous.pending || previous.expires > Date.now())) {
      const value = await (previous.pending ?? previous.value);
      if (generation !== this.generation) throw new Error("页面数据已失效，请重新加载");
      return value as T;
    }
    const entry: { expires: number; value?: T; pending?: Promise<T> } = { expires: 0 };
    this.entries.set(key, entry);
    // Bound memory even when users try many combinations of filters.
    if (this.entries.size > 80) this.entries.delete(this.entries.keys().next().value!);
    entry.pending = Promise.resolve().then(loader).then(value => {
      if (generation !== this.generation) throw new Error("页面数据已失效，请重新加载");
      entry.value = value; entry.expires = Date.now() + ttl;
      return value;
    }).catch(error => {
      if (this.entries.get(key) === entry) this.entries.delete(key);
      throw error;
    }).finally(() => { entry.pending = undefined; });
    return entry.pending;
  }
}

const browserCache = new PageReadCache();
export function setPageReadScope(userId: string | null) {
  if (typeof window !== "undefined") browserCache.setScope(userId);
}
export function invalidatePageReads() {
  if (typeof window !== "undefined") browserCache.clear();
}
export function readPageData<T>(key: string, loader: () => Promise<T>, ttl: number, force = false) {
  return typeof window === "undefined" ? loader() : browserCache.read(key, loader, ttl, force);
}
