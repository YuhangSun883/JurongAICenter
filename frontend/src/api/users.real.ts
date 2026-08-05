import { request } from '@/lib/http';

export interface UserMeResponse {
  id: number;
  email: string;
  displayName: string;
  role: 'USER' | 'ADMIN';
  credits: number;
  monthlyQuota: number;
  quotaUsed: number;
  plan: string;
}

export interface QuotaResponse {
  credits: number;
  monthlyQuota: number;
  quotaUsed: number;
  plan: string;
}

export interface UserGroupResponse {
  id: number;
  name: string;
  description?: string;
  color?: string;
}

export interface UpdateUserRequest {
  displayName?: string;
  password?: string;
}

/** 查询当前用户信息 — 后端直接返回 UserResponse 对象（无 code/data 包装） */
export async function getMe(): Promise<UserMeResponse> {
  return request<UserMeResponse>('/api/users/me');
}

/** 修改当前用户信息（昵称 / 密码） */
export async function updateMe(body: UpdateUserRequest): Promise<UserMeResponse> {
  const res = await request<UserMeResponse>('/api/users/me', {
    method: 'PATCH',
    body,
  });
  // 更新 localStorage 中的 user 信息
  if (typeof window !== 'undefined') {
    const stored = localStorage.getItem('user');
    if (stored) {
      try {
        const obj = JSON.parse(stored);
        localStorage.setItem('user', JSON.stringify({ ...obj, ...res }));
      } catch { /* ignore */ }
    }
  }
  return res;
}

/** 查询当前用户配额 */
export async function getMyQuota(): Promise<QuotaResponse> {
  return request<QuotaResponse>('/api/users/me/quota');
}

/** 查询当前用户分组 */
export async function getMyGroups(): Promise<UserGroupResponse[]> {
  return request<UserGroupResponse[]>('/api/users/me/groups');
}