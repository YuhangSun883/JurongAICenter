// 用户认证

export interface UserInfo {
  id: string;
  nickname: string;
  avatar?: string;
  email?: string;
  phone?: string;
  createdAt?: string;
  role?: 'USER' | 'ADMIN' | string;
  channel?: 'APP' | 'CONSOLE' | string;
}

export interface LoginRequest {
  account?: string;
  email?: string;
  phone?: string;
  code?: string;
  password?: string;
}

/**
 * 前端统一的登录响应。
 * - token: 后端叫 accessToken（短命，30 分钟）
 * - refreshToken: 刷新用（长命，7 天，可滑动延长）
 * - expiresIn: access token 的有效秒数（默认 1800 = 30 分钟）
 */
export interface LoginResponse {
  token: string;
  refreshToken: string;
  expiresIn?: number;
  user: UserInfo;
}

/** 注册请求 — 后端契约：{ email, password, displayName? } */
export interface RegisterRequest {
  email: string;
  password: string;
  displayName?: string;
}
