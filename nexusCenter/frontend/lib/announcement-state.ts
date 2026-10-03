import type { AnnouncementFeed } from "./platform.ts";

// Used for immediate feedback after a successful receipt; next pending comes from the server.
export function acknowledgeAnnouncement(feed: AnnouncementFeed | null, id: string, now: string = new Date().toISOString()): AnnouncementFeed | null {
  if (!feed) return feed;
  const wasUnread = feed.next_unread?.id === id || feed.announcements.items.some(item => item.id === id && !item.read_at);
  return { ...feed, unread_count: Math.max(0, feed.unread_count - (wasUnread ? 1 : 0)),
    next_unread: feed.next_unread?.id === id ? null : feed.next_unread,
    announcements: { ...feed.announcements, items: feed.announcements.items.map(item => item.id === id ? { ...item, read_at: now } : item) } };
}

