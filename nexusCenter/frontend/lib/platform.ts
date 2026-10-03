import { apiDataRequest } from "./api.ts";
import type { AdminPage } from "./admin.ts";

export type PlatformSettings = { registration_enabled: boolean; manual_recharge_enabled?: boolean; version: number };
export type Announcement = {
  id: string; title: string; content: string; status: "draft" | "published" | "withdrawn";
  version: number; created_at: string; published_at?: string; read_at?: string;
};
export type AnnouncementFeed = { announcements: AdminPage<Announcement>; unread_count: number; next_unread?: Announcement | null };

async function write<T>(path: string, method: "POST" | "PUT", body: unknown): Promise<T> {
  const csrf = await apiDataRequest<{ header: string; token: string }>("/api/v1/auth/csrf", { cache: "no-store" });
  return apiDataRequest<T>(path, { method, headers: { [csrf.header]: csrf.token }, body: JSON.stringify(body) });
}
export const getRegistration = () => apiDataRequest<{ registration_enabled: boolean }>("/api/v1/system/registration", { cache: "no-store" });
export const getPlatformSettings = () => apiDataRequest<PlatformSettings>("/api/v1/admin/settings", { cache: "no-store" });
export const savePlatformSettings = (settings: PlatformSettings) => write<PlatformSettings>("/api/v1/admin/settings", "PUT", settings);
export const listAnnouncements = (page = 1) => apiDataRequest<AdminPage<Announcement>>(`/api/v1/admin/announcements?page=${page}&page_size=10`, { cache: "no-store" });
export const saveAnnouncement = (input: { title: string; content: string; version?: number }, id?: string) =>
  write<Announcement>(id ? `/api/v1/admin/announcements/${encodeURIComponent(id)}` : "/api/v1/admin/announcements", id ? "PUT" : "POST", input);
export const transitionAnnouncement = (item: Announcement, action: "publish" | "withdraw") =>
  write<Announcement>(`/api/v1/admin/announcements/${encodeURIComponent(item.id)}/${action}`, "POST", { version: item.version });
export const getAnnouncementFeed = (page = 1) => apiDataRequest<AnnouncementFeed>(`/api/v1/announcements?page=${page}&page_size=10`, { cache: "no-store" });
export const readAnnouncement = (id: string) => write<{ read: boolean }>(`/api/v1/announcements/${encodeURIComponent(id)}/read`, "POST", {});

export type RechargeInput = { request_id: string; credits: string; reason: string };
export type RechargeReceipt = { request_id: string; ledger_id: string; credits: string; available_credits: string; replayed: boolean };
export const getAdminWallet = (id: string) => apiDataRequest<import("./wallet.ts").WalletBalance>(`/api/v1/admin/users/${encodeURIComponent(id)}/wallet`, { cache: "no-store" });
export const rechargeUser = (id: string, input: RechargeInput) => write<RechargeReceipt>(`/api/v1/admin/users/${encodeURIComponent(id)}/recharge`, "POST", input);
