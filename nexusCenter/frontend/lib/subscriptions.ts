/** 订阅计划真实接口请求层。 */
import { apiDataRequest } from "./api.ts";

export type SubscriptionPlan = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  billingCycle: string;
  price: string;
  includedCredits: string;
  concurrencyLimit: number | null;
  features: string[];
  featured: boolean;
};

export type CurrentSubscription = {
  id: string;
  planId: string;
  planName: string;
  billingCycle: string;
  status: string;
  startsAt: string;
  expiresAt: string;
  autoRenew: boolean;
  cancelledAt: string | null;
  includedCredits: string;
  usedCredits: string;
  remainingCredits: string;
  expiredCredits: string;
  refundRequestedAt: string | null;
  refundCompletedAt: string | null;
  refundAmount: string;
  refundableCredits: string;
  features: string[];
  version: number;
};

export type SubscriptionOverview = {
  plans: SubscriptionPlan[];
  current: CurrentSubscription | null;
  mockPurchaseEnabled: boolean;
};

type BackendOverview = {
  plans: Array<{
    id: string; code: string; name: string; description: string | null; billing_cycle: string;
    price: string | number; included_credits: string | number; concurrency_limit: number | null;
    features: string[]; featured: boolean;
  }>;
  current_subscription: null | {
    id: string; planId?: string; plan_id?: string; planName?: string; plan_name?: string;
    billingCycle?: string; billing_cycle?: string; status: string; startsAt?: string; starts_at?: string;
    expiresAt?: string; expires_at?: string; autoRenew?: boolean; auto_renew?: boolean;
    cancelledAt?: string | null; cancelled_at?: string | null; includedCredits?: string | number;
    included_credits?: string | number; usedCredits?: string | number; used_credits?: string | number;
    remainingCredits?: string | number; remaining_credits?: string | number; features: string[]; version: number;
    expiredCredits?: string | number; expired_credits?: string | number;
    refundRequestedAt?: string | null; refund_requested_at?: string | null;
    refundCompletedAt?: string | null; refund_completed_at?: string | null;
    refundAmount?: string | number; refund_amount?: string | number;
    refundableCredits?: string | number; refundable_credits?: string | number;
  };
  mock_purchase_enabled: boolean;
};

function map(data: BackendOverview): SubscriptionOverview {
  const current = data.current_subscription;
  return {
    plans: data.plans.map((plan) => ({
      id: plan.id, code: plan.code, name: plan.name, description: plan.description,
      billingCycle: plan.billing_cycle, price: String(plan.price),
      includedCredits: String(plan.included_credits), concurrencyLimit: plan.concurrency_limit,
      features: [...plan.features], featured: plan.featured,
    })),
    current: current ? {
      id: current.id,
      planId: current.plan_id ?? current.planId ?? "",
      planName: current.plan_name ?? current.planName ?? "",
      billingCycle: current.billing_cycle ?? current.billingCycle ?? "monthly",
      status: current.status,
      startsAt: current.starts_at ?? current.startsAt ?? "",
      expiresAt: current.expires_at ?? current.expiresAt ?? "",
      autoRenew: current.auto_renew ?? current.autoRenew ?? false,
      cancelledAt: current.cancelled_at ?? current.cancelledAt ?? null,
      includedCredits: String(current.included_credits ?? current.includedCredits ?? 0),
      usedCredits: String(current.used_credits ?? current.usedCredits ?? 0),
      remainingCredits: String(current.remaining_credits ?? current.remainingCredits ?? 0),
      expiredCredits: String(current.expired_credits ?? current.expiredCredits ?? 0),
      refundRequestedAt: current.refund_requested_at ?? current.refundRequestedAt ?? null,
      refundCompletedAt: current.refund_completed_at ?? current.refundCompletedAt ?? null,
      refundAmount: String(current.refund_amount ?? current.refundAmount ?? 0),
      refundableCredits: String(current.refundable_credits ?? current.refundableCredits ?? 0),
      features: [...current.features], version: current.version,
    } : null,
    mockPurchaseEnabled: data.mock_purchase_enabled,
  };
}

export async function getSubscriptionOverview(): Promise<SubscriptionOverview> {
  return map(await apiDataRequest<BackendOverview>("/api/v1/subscriptions", { cache: "no-store" }));
}

export async function activateMockSubscription(planId: string): Promise<SubscriptionOverview> {
  const csrf = await apiDataRequest<{ header: string; token: string }>("/api/v1/auth/csrf", { cache: "no-store" });
  return map(await apiDataRequest<BackendOverview>(`/api/v1/subscriptions/plans/${encodeURIComponent(planId)}/mock-activate`, {
    method: "POST",
    headers: { [csrf.header]: csrf.token },
  }));
}

export async function cancelCurrentSubscription(version: number): Promise<{
  subscriptionId: string;
  status: string;
  refundAmount: string;
  refundableCredits: string;
  frozenCredits: string;
  refundPending: boolean;
  completedAt: string | null;
}> {
  const csrf = await apiDataRequest<{ header: string; token: string }>("/api/v1/auth/csrf", { cache: "no-store" });
  const data = await apiDataRequest<{
    subscription_id: string;
    status: string;
    refund_amount: string | number;
    refundable_credits: string | number;
    frozen_credits: string | number;
    refund_pending: boolean;
    completed_at: string | null;
  }>("/api/v1/subscriptions/current/cancel", {
    method: "POST",
    headers: { [csrf.header]: csrf.token, "Content-Type": "application/json" },
    body: JSON.stringify({ version }),
  });
  return {
    subscriptionId: data.subscription_id,
    status: data.status,
    refundAmount: String(data.refund_amount),
    refundableCredits: String(data.refundable_credits),
    frozenCredits: String(data.frozen_credits),
    refundPending: data.refund_pending,
    completedAt: data.completed_at,
  };
}
