// 用户认证 - mock
import type { LoginRequest, LoginResponse, RegisterRequest, UserInfo } from '@/types/user';

const delay = <T>(v: T, ms = 200) => new Promise<T>((r) => setTimeout(() => r(v), ms));

export async function login(_req: LoginRequest): Promise<LoginResponse> {
  return delay({
    accessToken: 'mock_access_' + Math.random().toString(36).slice(2, 10),
    refreshToken: 'mock_refresh_' + Math.random().toString(36).slice(2, 10),
    userId: 1,
    email: 'demo@jurong.shop',
    role: 'USER',
  });
}

export async function register(_req: RegisterRequest): Promise<LoginResponse> {
  return delay({
    accessToken: 'mock_access_' + Math.random().toString(36).slice(2, 10),
    refreshToken: 'mock_refresh_' + Math.random().toString(36).slice(2, 10),
    userId: 2,
    email: _req.email,
    role: 'USER',
  });
}

export async function refresh(_refreshToken: string): Promise<LoginResponse> {
  return delay({
    accessToken: 'mock_access_' + Math.random().toString(36).slice(2, 10),
    refreshToken: 'mock_refresh_' + Math.random().toString(36).slice(2, 10),
    userId: 1,
    email: 'demo@jurong.shop',
    role: 'USER',
  });
}

export async function logout(): Promise<void> { /* mock */ }

export async function me(): Promise<UserInfo> {
  return delay({ id: 1, nickname: '体验用户', email: 'demo@jurong.shop', role: 'USER' });
}
