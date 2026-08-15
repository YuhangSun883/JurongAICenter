import {
  clearConsoleTokens,
  clearTokens,
  getAccessToken,
  getConsoleAccessToken,
  silentRefresh,
} from './auth-store';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  constructor(public status: number, message: string, public payload?: unknown) {
    super(message);
    this.name = 'ApiError';
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
  /** 明确指定本次请求用哪套登录态，避免前台/后台账号串用。 */
  authScope?: 'app' | 'console' | 'none';
  __isRefresh?: boolean;
}

function buildUrl(path: string, query?: RequestOptions['query']) {
  const url = new URL(path, API_BASE);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null) {
        url.searchParams.set(key, String(value));
      }
    }
  }
  return url.toString();
}

type AuthFailureHandler = () => void;
const authFailureHandlers = new Set<AuthFailureHandler>();

export function onAuthFailure(handler: AuthFailureHandler): () => void {
  authFailureHandlers.add(handler);
  return () => authFailureHandlers.delete(handler);
}

function getToken(scope: RequestOptions['authScope']) {
  if (scope === 'console') return getConsoleAccessToken();
  if (scope === 'none') return null;
  return getAccessToken();
}

function notifyAuthFailure(scope: RequestOptions['authScope']) {
  if (scope === 'console') {
    clearConsoleTokens();
  } else if (scope === 'app') {
    clearTokens();
  }

  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event('auth-changed'));
  }

  authFailureHandlers.forEach((handler) => {
    try {
      handler();
    } catch {
      /* ignore */
    }
  });
}

async function readErrorPayload(res: Response) {
  try {
    return await res.json();
  } catch {
    return undefined;
  }
}

async function requestInner<T>(path: string, opts: RequestOptions = {}, isRetry = false): Promise<T> {
  const { method = 'GET', body, query, signal, authScope = 'app' } = opts;
  const token = getToken(authScope);
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(buildUrl(path, query), {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal,
    cache: 'no-store',
  });

  if (res.status === 401 && !isRetry) {
    if (authScope === 'app') {
      const refreshed = await silentRefresh();
      if (refreshed) {
        return requestInner<T>(path, opts, true);
      }
    }

    notifyAuthFailure(authScope);
    throw new ApiError(res.status, `HTTP ${res.status}`, await readErrorPayload(res));
  }

  if (!res.ok) {
    const payload = await readErrorPayload(res);
    console.error('[http] non-OK response', { url: buildUrl(path, query), status: res.status, payload });
    throw new ApiError(res.status, `HTTP ${res.status}`, payload);
  }

  const text = await res.text();
  if (!text || text.trim() === '') return undefined as T;

  const json = JSON.parse(text) as any;
  if (json && typeof json === 'object' && 'code' in json && 'data' in json) {
    if (json.code !== 0) {
      if (json.code === 9401) {
        notifyAuthFailure(authScope);
      }
      throw new ApiError(json.code ?? res.status, json.message ?? `HTTP ${res.status}`, json);
    }
    return json.data as T;
  }

  return json as T;
}

export async function request<T = unknown>(path: string, opts: RequestOptions = {}): Promise<T> {
  return requestInner<T>(path, opts, false);
}
