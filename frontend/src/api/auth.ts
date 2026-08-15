// 用户认证 - 业务侧入口
import { USE_MOCK } from './config';
import * as real from './auth.real';
import * as mock from './auth.mock';
import type { LoginRequest, RegisterRequest } from '@/types/user';

export const authApi = {
  login: (req: Parameters<typeof real.login>[0]) =>
    USE_MOCK ? mock.login(req) : real.login(req),

  register: (req: RegisterRequest) =>
    USE_MOCK ? mock.register(req) : real.register(req),

  logout: () =>
    USE_MOCK ? mock.logout() : real.logout(),

  me: () =>
    USE_MOCK ? mock.me() : real.me(),
};