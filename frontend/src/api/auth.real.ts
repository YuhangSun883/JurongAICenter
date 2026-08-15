// 用户认证 - 真实后端
// 后端 AuthResponse: {accessToken, refreshToken, userId, email, role}
// 这里保存双 token 到 auth-store，自动触发滑动窗口
import { request } from '@/lib/http';
import {
  setTokens, setUser, clearTokens, getUser,
  bootstrapTokens, silentRefresh
} from '@/lib/auth-store';
import type {
  LoginRequest, LoginResponse, RegisterRequest, UserInfo
} from '@/types/user';

// 后端真实响应结构
interface BackendAuthResponse {
  accessToken: string;
  refreshToken?: string;
  userId: number;
  email: string;
  role: string;
  createdAt?: string;
}

// 必须跟后端 application.yml 里 jwt.access-token-expiry 一致(目前是 2h)
// 这是前端预估的过期时间,真实过期以后端签发时间为准
const ACCESS_TOKEN_TTL_SEC = 2 * 60 * 60;

/** 后端响应 → 前端 LoginResponse */
function adapt(resp: BackendAuthResponse): LoginResponse {
  return {
    token: resp.accessToken,
    refreshToken: resp.refreshToken || '',
    expiresIn: ACCESS_TOKEN_TTL_SEC,
    user: {
      id: String(resp.userId),
      nickname: resp.email.split('@')[0],
      email: resp.email,
      createdAt: resp.createdAt,
      role: resp.role,
      channel: 'APP',
    },
  };
}

/** 持久化 tokens + user */
function persist(authResp: LoginResponse) {
  setTokens(authResp.token, authResp.refreshToken, authResp.expiresIn || ACCESS_TOKEN_TTL_SEC);
  setUser(authResp.user);
}

export async function login(req: LoginRequest): Promise<LoginResponse> {
  // 只发邮箱 + 密码两个字段(后端 LoginRequest 契约)
  const backendReq = {
    email: req.email,
    password: req.password,
  };

  const backendResp = await request<BackendAuthResponse>('/api/auth/login', {
    method: 'POST',
    body: backendReq,
  });
  const adapted = adapt(backendResp);
  persist(adapted);
  return adapted;
}

export async function register(req: RegisterRequest): Promise<LoginResponse> {
  const backendResp = await request<BackendAuthResponse>('/api/auth/register', {
    method: 'POST',
    body: {
      email: req.email,
      password: req.password,
      displayName: req.displayName,
    },
  });
  const adapted = adapt(backendResp);
  persist(adapted);
  return adapted;
}

export async function logout(): Promise<void> {
  const refreshToken = typeof window !== 'undefined' ? localStorage.getItem('refreshToken') : null;
  try {
    await request<void>('/api/auth/logout', {
      method: 'POST',
      body: refreshToken ? { refreshToken } : {},
    });
  } catch {
    // 即便后端 logout 失败，前端也要清理
  }
  clearTokens();
}

export async function me(): Promise<UserInfo> {
  // 后端 /api/auth/me 不存在，先用缓存的 user
  const cached = getUser<UserInfo>();
  if (cached) return cached;
  return {
    id: 'unknown',
    nickname: '当前用户',
    email: '',
  };
}

/**
 * 应用启动时调用：
 * 1. 从 localStorage 恢复 tokens 到内存
 * 2. 如果 token 接近过期，silent refresh
 * 3. 如果 refresh 失败，清除 tokens（用户回到登录页）
 */
export async function bootstrapAuth(): Promise<boolean> {
  bootstrapTokens();
  // 给一帧时间让 request() 完成初始化
  const { isLoggedIn, isAccessTokenExpired, isAccessTokenNearExpiry } = await import('@/lib/auth-store');
  if (!isLoggedIn()) return false;

  // 已过期或快过期（< 5 分钟）→ refresh
  if (isAccessTokenExpired() || isAccessTokenNearExpiry()) {
    const ok = await silentRefresh();
    return ok;
  }
  return true;
}
