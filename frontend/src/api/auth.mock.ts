// 用户认证 - mock
import type { LoginRequest, LoginResponse, UserInfo } from '@/types/user';

const delay = <T>(v: T, ms = 200) => new Promise<T>((r) => setTimeout(() => r(v), ms));

export async function login(_req: LoginRequest): Promise<LoginResponse> {
  return delay({
    token: 'mock_token_' + Math.random().toString(36).slice(2, 10),
    user: { id: 'u_1', nickname: '体验用户', email: 'demo@jurong.shop' },
  });
}

export async function logout(): Promise<void> { /* mock */ }

export async function me(): Promise<UserInfo> {
  return delay({ id: 'u_1', nickname: '体验用户', email: 'demo@jurong.shop' });
}
