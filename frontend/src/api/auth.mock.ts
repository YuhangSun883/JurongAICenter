// 用户认证 - mock
import type {
  LoginRequest, LoginResponse, RegisterRequest, UserInfo
} from '@/types/user';

const delay = <T>(v: T, ms = 200) => new Promise<T>((r) => setTimeout(() => r(v), ms));
const mockJwt = () => `mock.${Math.random().toString(36).slice(2, 10)}.token`;

export async function login(_req: LoginRequest): Promise<LoginResponse> {
  return delay({
    token: mockJwt(),
    refreshToken: mockJwt(),
    expiresIn: 30 * 60,
    user: {
      id: 'u_1',
      nickname: '体验用户',
      email: 'demo@jurong.shop',
    },
  });
}

export async function register(_req: RegisterRequest): Promise<LoginResponse> {
  // mock：直接返回登录成功（自动登录）
  return delay({
    token: mockJwt(),
    refreshToken: mockJwt(),
    expiresIn: 30 * 60,
    user: {
      id: 'u_' + Math.floor(Math.random() * 1000),
      nickname: _req.displayName || _req.email.split('@')[0],
      email: _req.email,
    },
  });
}

export async function logout(): Promise<void> { /* mock */ }

export async function me(): Promise<UserInfo> {
  return delay({
    id: 'u_1',
    nickname: '体验用户',
    email: 'demo@jurong.shop',
  });
}
