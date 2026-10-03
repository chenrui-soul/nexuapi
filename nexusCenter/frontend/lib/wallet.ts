import { apiDataRequest } from "./api.ts";

export type WalletBalance = {
  permanent_credits: string;
  expiring_credits: string;
  frozen_credits: string;
  available_credits: string;
  total_credits: string;
  version: number;
  updated_at: string;
};

export type WalletLedgerItem = {
  id: string;
  entry_type: string;
  request_id: string | null;
  amount: string;
  permanent_delta: string;
  expiring_delta: string;
  frozen_delta: string;
  available_after: string;
  balance_after: string;
  source_type: string | null;
  source_id: string | null;
  created_at: string;
};

export type WalletLedgerPage = {
  items: WalletLedgerItem[];
  total: number;
  page: number;
  page_size: number;
};

export type RechargeOrder = {
  id: string;
  order_no: string;
  amount: string;
  currency: string;
  status: "pending" | "paid" | "failed" | "cancelled" | "refunded";
  payment_provider: string;
  provider_order_id: string | null;
  credited_points: string;
  paid_at: string | null;
  created_at: string;
};

type CsrfResponse = { header: string; token: string };

async function csrfHeaders(): Promise<Record<string, string>> {
  const csrf = await apiDataRequest<CsrfResponse>("/api/v1/auth/csrf", { cache: "no-store" });
  return { [csrf.header]: csrf.token };
}

export function getWalletBalance(): Promise<WalletBalance> {
  return apiDataRequest<WalletBalance>("/api/v1/wallet", { cache: "no-store" });
}

export function listWalletLedger(page = 1, pageSize = 20): Promise<WalletLedgerPage> {
  return apiDataRequest<WalletLedgerPage>(`/api/v1/wallet/ledger?page=${page}&page_size=${pageSize}`, { cache: "no-store" });
}

export async function createRechargeOrder(amount: string, idempotencyKey: string): Promise<RechargeOrder> {
  return apiDataRequest<RechargeOrder>("/api/v1/wallet/recharge-orders", {
    method: "POST",
    headers: { ...(await csrfHeaders()), "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ amount, payment_provider: "mock" }),
  });
}

export async function mockPayRechargeOrder(orderId: string): Promise<RechargeOrder> {
  return apiDataRequest<RechargeOrder>(`/api/v1/wallet/recharge-orders/${encodeURIComponent(orderId)}/mock-pay`, {
    method: "POST",
    headers: await csrfHeaders(),
  });
}
