// 用户认证（占位）

export interface UserInfo {
  id: string | number;
  nickname: string;
  avatar?: string;
  email?: string;
  role?: 'USER' | 'ADMIN';
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName?: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  userId: number;
  email: string;
  role: 'USER' | 'ADMIN';
}
