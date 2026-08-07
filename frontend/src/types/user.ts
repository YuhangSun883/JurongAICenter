// 用户认证（占位）

export interface UserInfo {
  id: string;
  nickname: string;
  avatar?: string;
  email?: string;
}

export interface LoginRequest {
  account?: string;
  email?: string;
  phone?: string;
  code?: string;
  password?: string;
}

export interface LoginResponse {
  token: string;
  user: UserInfo;
}
