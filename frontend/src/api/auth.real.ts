// 用户认证 - 真实后端
import { request } from '@/lib/http';
import type { LoginRequest, LoginResponse, RegisterRequest, UserInfo } from '@/types/user';

export async function login(req: LoginRequest): Promise<LoginResponse> {
  return request<LoginResponse>('/api/auth/login', { method: 'POST', body: req });
}

export async function register(req: RegisterRequest): Promise<LoginResponse> {
  return request<LoginResponse>('/api/auth/register', { method: 'POST', body: req });
}

export async function refresh(refreshToken: string): Promise<LoginResponse> {
  return request<LoginResponse>('/api/auth/refresh', {
    method: 'POST',
    body: { refreshToken },
  });
}

export async function logout(): Promise<void> {
  const refreshToken = typeof window !== 'undefined' ? localStorage.getItem('refreshToken') : null;
  // 先调后端撤销 refresh token（即使失败也不影响前端清理）
  try {
    await request<{ code: number; message: string }>('/api/auth/logout', {
      method: 'POST',
      body: refreshToken ? { refreshToken } : {},
      skipRefresh: true,
    });
  } catch {
    // 后端失败不影响登出流程（前端清理是最终一致性）
  }
  // 前端清理 localStorage
  if (typeof window !== 'undefined') {
    localStorage.removeItem('token');
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('user');
    window.dispatchEvent(new Event('auth-changed'));
  }
}

export async function me(): Promise<UserInfo> {
  return request<UserInfo>('/api/auth/me');
}
