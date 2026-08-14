import { request } from '@/lib/http';
import { setTokens, setUser } from '@/lib/auth-store';
import type { LoginRequest, LoginResponse } from '@/types/user';

export interface ConsolePage<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ConsoleJobItem {
  id: number;
  userId: number;
  userEmail?: string;
  templateId?: string;
  taskId?: string;
  status: string;
  creditsCost?: number;
  durationMs?: number;
  errorMessage?: string;
  createdAt?: string;
  completedAt?: string;
}

export interface ConsoleOverview {
  totalUsers: number;
  activeUsers: number;
  disabledUsers: number;
  adminUsers: number;
  totalJobs: number;
  todayJobs: number;
  runningJobs: number;
  failedJobs: number;
  totalAssets: number;
  todayAssets: number;
  totalCredits: number;
  recentJobs: ConsoleJobItem[];
}

export interface ConsoleUserItem {
  id: number;
  email: string;
  displayName?: string;
  role: string;
  disabled?: number;
  credits?: number;
  monthlyQuota?: number;
  quotaUsed?: number;
  plan?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ConsoleAdminItem {
  id: number;
  email: string;
  displayName?: string;
  role: string;
  disabled?: number;
  lastLoginAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ConsoleAssetItem {
  id: number;
  userId: number;
  userEmail?: string;
  type?: string;
  source?: string;
  name?: string;
  mimeType?: string;
  sizeBytes?: number;
  sourceTool?: string;
  sourceTaskId?: string;
  createdAt?: string;
  deleted?: number;
}

export interface ConsoleAuditItem {
  id: number;
  adminId: number;
  adminEmail?: string;
  action: string;
  targetType?: string;
  targetId?: number;
  detail?: string;
  createdAt?: string;
}

export interface ConsoleFinanceOrderItem {
  orderNo: string;
  userId: number;
  userEmail?: string;
  source: string;
  status: string;
  amount?: number;
  credits?: number;
  paymentId?: string;
  paidAt?: string;
}

export interface ConsoleBillingItem {
  id: number;
  userId: number;
  userEmail?: string;
  jobId?: number;
  type: string;
  creditsDelta: number;
  balanceAfter: number;
  description?: string;
  paymentId?: string;
  createdAt?: string;
}

export interface ConsolePricingRuleItem {
  key: string;
  scene: string;
  baseCredits: number;
  billingLogic: string;
  enabled: string;
  note: string;
}

export interface ConsoleSettingItem {
  group: string;
  key: string;
  value: string;
  note: string;
}

export interface ConsoleUserDetail {
  user: ConsoleUserItem;
  recentJobs: ConsoleJobItem[];
  recentBillings: ConsoleBillingItem[];
  recentAssets: ConsoleAssetItem[];
}

export const consoleApi = {
  login: async (body: LoginRequest): Promise<LoginResponse> => {
    const resp = await request<{
      accessToken: string;
      refreshToken: string;
      userId: number;
      email: string;
      role: string;
    }>('/api/console/auth/login', {
      method: 'POST',
      body: { email: body.email, password: body.password },
    });
    const auth = {
      token: resp.accessToken,
      refreshToken: resp.refreshToken,
      expiresIn: 2 * 60 * 60,
      user: {
        id: String(resp.userId),
        nickname: resp.email.split('@')[0],
        email: resp.email,
        role: resp.role,
        channel: 'CONSOLE',
      },
    };
    setTokens(auth.token, auth.refreshToken, auth.expiresIn);
    setUser(auth.user);
    return auth;
  },
  overview: () => request<ConsoleOverview>('/api/console/overview'),
  users: (query?: Record<string, string | number | boolean | undefined>) =>
    request<ConsolePage<ConsoleUserItem>>('/api/console/users', { query }),
  userDetail: (id: number) =>
    request<ConsoleUserDetail>(`/api/console/users/${id}`),
  patchUser: (id: number, body: { role?: string; disabled?: boolean }) =>
    request<ConsoleUserItem>(`/api/console/users/${id}`, { method: 'PATCH', body }),
  patchUserPlan: (id: number, body: { displayName?: string; plan?: string; monthlyQuota?: number }) =>
    request<ConsoleUserItem>(`/api/console/users/${id}/plan`, { method: 'PATCH', body }),
  resetUserPassword: (id: number, body: { password: string }) =>
    request<ConsoleUserItem>(`/api/console/users/${id}/password`, { method: 'PATCH', body }),
  adjustCredits: (id: number, body: { delta: number; reason?: string }) =>
    request<ConsoleUserItem>(`/api/console/users/${id}/credits`, { method: 'PATCH', body }),
  admins: (query?: Record<string, string | number | boolean | undefined>) =>
    request<ConsolePage<ConsoleAdminItem>>('/api/console/admins', { query }),
  createAdmin: (body: { email: string; password: string; displayName?: string; role?: string }) =>
    request<ConsoleAdminItem>('/api/console/admins', { method: 'POST', body }),
  patchAdmin: (id: number, body: { displayName?: string; role?: string; disabled?: boolean }) =>
    request<ConsoleAdminItem>(`/api/console/admins/${id}`, { method: 'PATCH', body }),
  resetAdminPassword: (id: number, body: { password: string }) =>
    request<ConsoleAdminItem>(`/api/console/admins/${id}/password`, { method: 'PATCH', body }),
  deleteAdmin: (id: number) =>
    request<void>(`/api/console/admins/${id}`, { method: 'DELETE' }),
  jobs: (query?: Record<string, string | number | boolean | undefined>) =>
    request<ConsolePage<ConsoleJobItem>>('/api/console/jobs', { query }),
  patchJob: (id: number, body: { status: string; reason?: string }) =>
    request<ConsoleJobItem>(`/api/console/jobs/${id}`, { method: 'PATCH', body }),
  assets: (query?: Record<string, string | number | boolean | undefined>) =>
    request<ConsolePage<ConsoleAssetItem>>('/api/console/assets', { query }),
  deleteAsset: (id: number) =>
    request<void>(`/api/console/assets/${id}`, { method: 'DELETE' }),
  restoreAsset: (id: number) =>
    request<ConsoleAssetItem>(`/api/console/assets/${id}/restore`, { method: 'PATCH' }),
  audits: (query?: Record<string, string | number | boolean | undefined>) =>
    request<ConsolePage<ConsoleAuditItem>>('/api/console/audits', { query }),
  orders: (query?: Record<string, string | number | boolean | undefined>) =>
    request<ConsolePage<ConsoleFinanceOrderItem>>('/api/console/orders', { query }),
  billings: (query?: Record<string, string | number | boolean | undefined>) =>
    request<ConsolePage<ConsoleBillingItem>>('/api/console/billings', { query }),
  pricing: () => request<ConsolePricingRuleItem[]>('/api/console/pricing'),
  settings: () => request<ConsoleSettingItem[]>('/api/console/settings'),
};
