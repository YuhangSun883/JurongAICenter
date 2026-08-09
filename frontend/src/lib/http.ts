// 统一 HTTP 客户端。后端就绪后，只需要把 NEXT_PUBLIC_USE_MOCK 改成 false 即可。
// 这里刻意写得简单，后端同学按 REST 风格提供接口即可。

import { getAccessToken, silentRefresh, clearTokens } from './auth-store';

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
  /** 标记当前请求是 silent refresh（避免内部循环） */
  __isRefresh?: boolean;
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

/** 全局鉴权失败监听器（由 LoginGate 等组件注册） */
type AuthFailureHandler = () => void;
const authFailureHandlers = new Set<AuthFailureHandler>();
export function onAuthFailure(handler: AuthFailureHandler): () => void {
  authFailureHandlers.add(handler);
  return () => authFailureHandlers.delete(handler);
}

function notifyAuthFailure() {
  clearTokens();
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event('auth-changed'));
  }
  authFailureHandlers.forEach((h) => {
    try { h(); } catch { /* ignore */ }
  });
}

/**
 * 核心请求函数：自动注入 access token；401 时 silent refresh + 重试；
 * refresh 失败则清 token + 通知上层（LoginGate redirect 登录页）
 *
 * 后端返回格式：
 *   - 成功：直接返回业务对象（如 JobResponse、GenerateResponse）
 *   - 业务异常：HTTP 200 + {code, message, data}（由 GlobalExceptionHandler 统一封装）
 *   - 系统异常：HTTP 4xx/5xx + {code, message, data}
 */
async function requestInner<T>(path: string, opts: RequestOptions = {}, isRetry = false): Promise<T> {
  const { method = 'GET', body, query, signal } = opts;

  const token = getAccessToken();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(buildUrl(path, query), {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal,
    cache: 'no-store',
  });

  // 401：尝试 silent refresh + 重试一次
  if (res.status === 401 && !isRetry) {
    const refreshed = await silentRefresh();
    if (refreshed) {
      // refresh 成功 → 重试原请求
      return requestInner<T>(path, opts, true);
    }
    // refresh 失败 → 清 token + 通知
    notifyAuthFailure();
    let payload: unknown = undefined;
    try { payload = await res.json(); } catch { /* ignore */ }
    throw new ApiError(res.status, `HTTP ${res.status}`, payload);
  }

  // 非 OK 状态（4xx/5xx）→ 直接抛错
  if (!res.ok) {
    let payload: unknown = undefined;
    try { payload = await res.json(); } catch { /* ignore */ }
    throw new ApiError(res.status, `HTTP ${res.status}`, payload);
  }

  // 解析响应体（200/204 等 OK 状态）
  let json: any;
  try {
    const text = await res.text();
    if (!text || text.trim() === '') return undefined as T;
    json = JSON.parse(text);
  } catch {
    return undefined as T;
  }

  // 检查后端统一响应包装：{code, message, data}
  // GlobalExceptionHandler 会把业务异常包装成 HTTP 200 + {code, message, data}
  if (json && typeof json === 'object' && 'code' in json) {
    // 如果同时有 data 字段，说明是统一包装格式
    if ('data' in json) {
      if (json.code !== 0) {
        // 业务码 9401 (UNAUTHORIZED) → 通知上层
        if (json.code === 9401) {
          notifyAuthFailure();
        }
        throw new ApiError(json.code ?? res.status, json.message ?? `HTTP ${res.status}`, json);
      }
      // code=0 表示成功，返回 data 字段
      return (json.data ?? null) as T;
    }
    // 只有 code 没有 data，且 code != 0 → 业务异常
    if (json.code !== 0) {
      if (json.code === 9401) {
        notifyAuthFailure();
      }
      throw new ApiError(json.code ?? res.status, json.message ?? `HTTP ${res.status}`, json);
    }
  }

  // 非包装格式，直接返回
  return json as T;
}

export async function request<T = unknown>(path: string, opts: RequestOptions = {}): Promise<T> {
  return requestInner<T>(path, opts, false);
}