// 统一 HTTP 客户端。后端就绪后，只需要把 NEXT_PUBLIC_USE_MOCK 改成 false 即可。
// 这里刻意写得简单，后端同学按 REST 风格提供接口即可。

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:4000';

export class ApiError extends Error {
  constructor(public status: number, message: string, public payload?: unknown) {
    super(message);
    this.name = 'ApiError';
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
  /** 标记不需要自动 refresh 的接口（如登录、refresh 本身） */
  skipRefresh?: boolean;
  /** 内部重试标记，防止无限递归 */
  _retried?: boolean;
}

function buildUrl(path: string, query?: RequestOptions['query']) {
  const url = new URL(path, API_BASE);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null) url.searchParams.set(k, String(v));
    }
  }
  return url.toString();
}

function getAccessToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem('token') || localStorage.getItem('accessToken');
}

function getRefreshToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem('refreshToken');
}

function clearAuth() {
  if (typeof window === 'undefined') return;
  localStorage.removeItem('token');
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('user');
  window.dispatchEvent(new Event('auth-changed'));
}

/** 尝试用 refresh token 换取新 access token；返回是否成功 */
async function tryRefresh(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;
  // refresh 路径不能再次走拦截器（否则递归）
  try {
    const res = await fetch(buildUrl('/api/auth/refresh'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
      cache: 'no-store',
    });
    if (!res.ok) {
      clearAuth();
      return false;
    }
    const json = await res.json();
    const data = json?.data ?? json;
    if (data?.accessToken) {
      localStorage.setItem('token', data.accessToken);
      if (data.refreshToken) localStorage.setItem('refreshToken', data.refreshToken);
      window.dispatchEvent(new Event('auth-changed'));
      return true;
    }
    clearAuth();
    return false;
  } catch {
    clearAuth();
    return false;
  }
}

export async function request<T = unknown>(path: string, opts: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, signal, skipRefresh } = opts;

  const doFetch = (extraHeaders: Record<string, string> = {}) =>
    fetch(buildUrl(path, query), {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(getAccessToken() ? { Authorization: `Bearer ${getAccessToken()}` } : {}),
        ...extraHeaders,
      },
      body: body ? JSON.stringify(body) : undefined,
      signal,
      cache: 'no-store',
    });

  let res = await doFetch();

  // 401 自动 refresh + 重试（只对非登录/refresh 接口）
  if (res.status === 401 && !skipRefresh && !opts._retried) {
    const ok = await tryRefresh();
    if (ok) {
      res = await doFetch({ 'X-Refreshed': '1' });
      // 用 _retried 标记以防异常分支再触发一次
      (opts as { _retried?: boolean })._retried = true;
    } else {
      // refresh 失败：清掉 auth 状态，让 LoginGate 重新弹登录
      clearAuth();
    }
  }

  if (!res.ok) {
    let payload: unknown = undefined;
    try { payload = await res.json(); } catch { /* ignore */ }
    throw new ApiError(res.status, `HTTP ${res.status}`, payload);
  }

  // 204 等空响应
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}
