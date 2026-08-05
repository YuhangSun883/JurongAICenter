// 用户认证 - 真实后端
import { request } from '@/lib/http';
import type { LoginRequest, LoginResponse, UserInfo } from '@/types/user';

export async function login(req: LoginRequest): Promise<LoginResponse> {
  return request<LoginResponse>('/api/auth/login', { method: 'POST', body: req });
}

export async function logout(): Promise<void> {
  await request<void>('/api/auth/logout', { method: 'POST' });
}

export async function me(): Promise<UserInfo> {
  return request<UserInfo>('/api/auth/me');
}
