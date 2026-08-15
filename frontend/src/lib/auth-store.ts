// auth-store.ts — token 状态管理 + 滑动窗口 + silent refresh
// 行业标准：
//   - access token 30 分钟，每次 API 响应检查过期时间，< 5 分钟时静默 refresh
//   - refresh token 7 天，每次成功 refresh 延长 7 天
//   - 用户活跃（API 调用 / 鼠标键盘活动）→ 滑动延长 session

const ACCESS_TOKEN_KEY = 'accessToken';
const REFRESH_TOKEN_KEY = 'refreshToken';
const USER_KEY = 'user';
const ACCESS_TOKEN_EXPIRY_KEY = 'accessTokenExpiry';

const CONSOLE_ACCESS_TOKEN_KEY = 'consoleAccessToken';
const CONSOLE_REFRESH_TOKEN_KEY = 'consoleRefreshToken';
const CONSOLE_USER_KEY = 'consoleUser';
const CONSOLE_ACCESS_TOKEN_EXPIRY_KEY = 'consoleAccessTokenExpiry';

// ===== 内存中的 token（避免反复读 localStorage） =====
let accessTokenInMemory: string | null = null;
let refreshTokenInMemory: string | null = null;
let accessTokenExpiryInMemory: number | null = null;
let consoleAccessTokenInMemory: string | null = null;
let consoleRefreshTokenInMemory: string | null = null;
let consoleAccessTokenExpiryInMemory: number | null = null;

// ===== 单例 Promise（防止并发触发多次 refresh） =====
let refreshingPromise: Promise<boolean> | null = null;

// ===== 通知回调（auth 状态变化时通知 UI） =====
type AuthChangeHandler = (event: 'login' | 'logout' | 'refresh') => void;
const authChangeHandlers = new Set<AuthChangeHandler>();

export function onAuthChange(handler: AuthChangeHandler): () => void {
  authChangeHandlers.add(handler);
  return () => authChangeHandlers.delete(handler);
}

function notifyAuthChange(event: 'login' | 'logout' | 'refresh') {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent('auth-state-change', { detail: event }));
  authChangeHandlers.forEach((h) => {
    try { h(event); } catch { /* ignore */ }
  });
}

// ===== 读取 token（启动时从 localStorage 同步到内存） =====
export function bootstrapTokens(): void {
  if (typeof window === 'undefined') return;
  const rawAccess = localStorage.getItem(ACCESS_TOKEN_KEY);
  const rawRefresh = localStorage.getItem(REFRESH_TOKEN_KEY);
  const rawUser = localStorage.getItem(USER_KEY);
  if (rawUser) {
    try {
      const user = JSON.parse(rawUser) as { channel?: string };
      if (user?.channel === 'CONSOLE') {
        clearTokens();
        return;
      }
    } catch {
      localStorage.removeItem(USER_KEY);
    }
  }
  // 校验 token 格式（JWT 格式: xxx.yyy.zzz），清除无效 token
  const jwtRe = /^[A-Za-z0-9\-_]+?\.[A-Za-z0-9\-_]+?\.[A-Za-z0-9\-_]+$/;
  if (rawAccess && !jwtRe.test(rawAccess)) {
    console.warn('[auth] invalid access token format detected, clearing');
    clearTokens();
    return;
  }
  if (rawRefresh && !jwtRe.test(rawRefresh)) {
    console.warn('[auth] invalid refresh token format detected, clearing');
    clearTokens();
    return;
  }
  accessTokenInMemory = rawAccess;
  refreshTokenInMemory = rawRefresh;
  const expiry = localStorage.getItem(ACCESS_TOKEN_EXPIRY_KEY);
  accessTokenExpiryInMemory = expiry ? parseInt(expiry, 10) : null;
}

export function bootstrapConsoleTokens(): void {
  if (typeof window === 'undefined') return;
  const rawAccess = localStorage.getItem(CONSOLE_ACCESS_TOKEN_KEY);
  const rawRefresh = localStorage.getItem(CONSOLE_REFRESH_TOKEN_KEY);
  const jwtRe = /^[A-Za-z0-9\-_]+?\.[A-Za-z0-9\-_]+?\.[A-Za-z0-9\-_]+$/;
  if (rawAccess && !jwtRe.test(rawAccess)) {
    console.warn('[auth] invalid console access token format detected, clearing');
    clearConsoleTokens();
    return;
  }
  if (rawRefresh && !jwtRe.test(rawRefresh)) {
    console.warn('[auth] invalid console refresh token format detected, clearing');
    clearConsoleTokens();
    return;
  }
  consoleAccessTokenInMemory = rawAccess;
  consoleRefreshTokenInMemory = rawRefresh;
  const expiry = localStorage.getItem(CONSOLE_ACCESS_TOKEN_EXPIRY_KEY);
  consoleAccessTokenExpiryInMemory = expiry ? parseInt(expiry, 10) : null;
}

// ===== 写入 token =====
export function setTokens(accessToken: string, refreshToken: string, expiresInSec: number): void {
  if (typeof window === 'undefined') return;
  accessTokenInMemory = accessToken;
  refreshTokenInMemory = refreshToken;
  // expiresInSec 是相对秒数，转为绝对时间戳
  accessTokenExpiryInMemory = Date.now() + expiresInSec * 1000;
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  localStorage.setItem(ACCESS_TOKEN_EXPIRY_KEY, String(accessTokenExpiryInMemory));
  notifyAuthChange('login');
}

export function setUser(user: object): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function setConsoleTokens(accessToken: string, refreshToken: string, expiresInSec: number): void {
  if (typeof window === 'undefined') return;
  consoleAccessTokenInMemory = accessToken;
  consoleRefreshTokenInMemory = refreshToken;
  consoleAccessTokenExpiryInMemory = Date.now() + expiresInSec * 1000;
  localStorage.setItem(CONSOLE_ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(CONSOLE_REFRESH_TOKEN_KEY, refreshToken);
  localStorage.setItem(CONSOLE_ACCESS_TOKEN_EXPIRY_KEY, String(consoleAccessTokenExpiryInMemory));
  notifyAuthChange('login');
}

export function setConsoleUser(user: object): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(CONSOLE_USER_KEY, JSON.stringify(user));
}

export function getUser<T = unknown>(): T | null {
  if (typeof window === 'undefined') return null;
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try { return JSON.parse(raw) as T; } catch { return null; }
}

export function getConsoleUser<T = unknown>(): T | null {
  if (typeof window === 'undefined') return null;
  const raw = localStorage.getItem(CONSOLE_USER_KEY);
  if (!raw) return null;
  try { return JSON.parse(raw) as T; } catch { return null; }
}

// ===== 清除 token =====
export function clearTokens(): void {
  accessTokenInMemory = null;
  refreshTokenInMemory = null;
  accessTokenExpiryInMemory = null;
  if (typeof window === 'undefined') return;
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(ACCESS_TOKEN_EXPIRY_KEY);
  localStorage.removeItem(USER_KEY);
  notifyAuthChange('logout');
}

export function clearConsoleTokens(): void {
  consoleAccessTokenInMemory = null;
  consoleRefreshTokenInMemory = null;
  consoleAccessTokenExpiryInMemory = null;
  if (typeof window === 'undefined') return;
  localStorage.removeItem(CONSOLE_ACCESS_TOKEN_KEY);
  localStorage.removeItem(CONSOLE_REFRESH_TOKEN_KEY);
  localStorage.removeItem(CONSOLE_ACCESS_TOKEN_EXPIRY_KEY);
  localStorage.removeItem(CONSOLE_USER_KEY);
  notifyAuthChange('logout');
}

// ===== 查询 token =====
export function getAccessToken(): string | null {
  return accessTokenInMemory;
}

export function getConsoleAccessToken(): string | null {
  return consoleAccessTokenInMemory;
}

export function getRefreshToken(): string | null {
  return refreshTokenInMemory;
}

export function isLoggedIn(): boolean {
  return !!accessTokenInMemory && !!refreshTokenInMemory;
}

export function isConsoleLoggedIn(): boolean {
  if (!consoleAccessTokenInMemory || !consoleRefreshTokenInMemory) return false;
  if (!consoleAccessTokenExpiryInMemory) return true;
  return consoleAccessTokenExpiryInMemory - Date.now() > 0;
}

/**
 * 检查 access token 是否快过期（< 5 分钟）
 * 用于滑动窗口：每次 API 响应后调用
 */
export function isAccessTokenNearExpiry(): boolean {
  if (!accessTokenExpiryInMemory) return false;
  const FIVE_MINUTES = 5 * 60 * 1000;
  return accessTokenExpiryInMemory - Date.now() < FIVE_MINUTES;
}

/**
 * 检查 access token 是否已过期
 */
export function isAccessTokenExpired(): boolean {
  if (!accessTokenExpiryInMemory) return false;
  return accessTokenExpiryInMemory - Date.now() < 0;
}

// ===== Silent refresh =====

/**
 * 单例 refresh：多次调用共享一个 Promise
 * 成功返回 true；失败（refresh token 也过期/被撤销）返回 false
 */
export async function silentRefresh(): Promise<boolean> {
  if (refreshingPromise) return refreshingPromise;

  if (!refreshTokenInMemory) {
    return Promise.resolve(false);
  }

  refreshingPromise = (async () => {
    try {
      const resp = await fetch(buildApiUrl('/api/auth/refresh'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: refreshTokenInMemory }),
      });
      if (!resp.ok) return false;

      const json = await resp.json();
      // 后端 GlobalExceptionHandler 包了 {code, data}，前端解包
      const payload = json && typeof json === 'object' && 'data' in json ? json.data : json;
      if (!payload?.accessToken) return false;

      // 后端目前 refresh 后保持 refreshToken 不变，但保险起见更新
      const newAccess = payload.accessToken;
      const newRefresh = payload.refreshToken || refreshTokenInMemory!;
      // 假设 access token 还是 2 小时(与后端 jwt.access-token-expiry 一致)
      setTokens(newAccess, newRefresh, 2 * 60 * 60);
      notifyAuthChange('refresh');
      console.log('[auth] silent refresh succeeded');
      return true;
    } catch (e) {
      console.warn('[auth] silent refresh failed:', e);
      return false;
    } finally {
      // 释放单例（无论成功失败都允许下次重试）
      setTimeout(() => { refreshingPromise = null; }, 0);
    }
  })();

  return refreshingPromise;
}

function buildApiUrl(path: string): string {
  const base = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
  return new URL(path, base).toString();
}

// ===== 周期性检查（app 启动时调用一次） =====
let periodicTimerId: ReturnType<typeof setInterval> | null = null;

/**
 * 启动周期性检查：每 60 秒检查一次
 * 如果 access token 快过期（< 5 分钟），静默 refresh
 */
export function startAuthWatchdog(): void {
  if (periodicTimerId || typeof window === 'undefined') return;
  periodicTimerId = setInterval(() => {
    if (!isLoggedIn()) return;
    if (isAccessTokenNearExpiry()) {
      console.log('[auth] watchdog: access token near expiry, refreshing...');
      silentRefresh().then((ok) => {
        if (!ok) {
          console.warn('[auth] watchdog: refresh failed, clearing tokens');
          clearTokens();
        }
      });
    }
  }, 60 * 1000);
}

export function stopAuthWatchdog(): void {
  if (periodicTimerId) {
    clearInterval(periodicTimerId);
    periodicTimerId = null;
  }
}

// ===== 滑动窗口：用户活动时触发 refresh =====

let activityDebounceId: ReturnType<typeof setTimeout> | null = null;

/**
 * 监听用户活动（鼠标点击、键盘输入、滚动）。
 * 在活动时如果 access token 快过期，触发 refresh。
 * 防抖：300ms 内多次活动只触发一次。
 */
export function bindActivityRefresh(): () => void {
  if (typeof window === 'undefined') return () => {};

  const handler = () => {
    if (activityDebounceId) clearTimeout(activityDebounceId);
    activityDebounceId = setTimeout(() => {
      if (!isLoggedIn()) return;
      if (isAccessTokenNearExpiry()) {
        silentRefresh();
      }
    }, 300);
  };

  window.addEventListener('click', handler, { passive: true });
  window.addEventListener('keydown', handler, { passive: true });
  window.addEventListener('scroll', handler, { passive: true });

  return () => {
    window.removeEventListener('click', handler);
    window.removeEventListener('keydown', handler);
    window.removeEventListener('scroll', handler);
  };
}
