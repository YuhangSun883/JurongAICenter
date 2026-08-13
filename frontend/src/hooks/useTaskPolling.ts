// 任务状态轮询 hook。预留 SSE/WebSocket 接入点。
// 当前实现：轮询。当后端提供 SSE 时，把 polling 替换成 EventSource 即可。

'use client';

import { useEffect, useRef } from 'react';
import { videoApi } from '@/api/video';
import { useWorkbenchStore } from '@/store/workbench';
import type { TaskStatus, VideoTask } from '@/types/video';
import { ApiError } from '@/lib/http';

const POLLING_INTERVAL = 2000;

/**
 * 前端"自动补刀"等待窗口（毫秒）
 * 说明：用户点击"立即生成"后，若后端先把任务误标 FAILED（NewAPI 状态回调延迟），
 * 前端在窗口期内会持续调 /retry 让后端重新查 NewAPI 拉视频，
 * 而不是直接给用户展示"生成失败"。窗口结束后才真正展示失败。
 */
const AUTO_RETRY_WINDOW_MS = 5 * 60 * 1000;

/** 哪些状态算"进行中"，需要持续轮询 */
const ACTIVE: TaskStatus[] = ['queued', 'running'];

interface RetryResponse {
  recovered?: boolean;
  reason?: string;
  currentStatus?: string;
  jobId?: number | string;
}

export function useTaskPolling() {
  const tasks = useWorkbenchStore((s) => s.tasks);
  const upsertTask = useWorkbenchStore((s) => s.upsertTask);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  // 标记任务是否已经触发过自动补刀，避免每次轮询都重复打 retry
  const autoRetriedRef = useRef<Set<string>>(new Set());
  // 标记任务已经"放弃补刀"（NewAPI 主动失败 / 出错），永远不再重试
  const abandonedRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    const tick = async () => {
      const now = Date.now();

      // 1) 主动轮询"进行中"任务（queued / running）
      const active = tasks.filter((t) => ACTIVE.includes(t.status));
      if (active.length > 0) {
        await Promise.all(
          active.map((t) =>
            videoApi.getTask(t.id).then(upsertTask).catch(() => undefined)
          )
        );
      }

      // 2) 自动补刀：把"刚失败 5 分钟内"的任务也纳入轮询，调一次 /retry 让后端重新查 NewAPI
      const recentlyFailed = tasks.filter(
        (t) =>
          t.status === 'failed' &&
          now - t.createdAt < AUTO_RETRY_WINDOW_MS &&
          !autoRetriedRef.current.has(t.id) &&
          !abandonedRef.current.has(t.id)  // 已放弃补刀的任务不再纳入
      );
      if (recentlyFailed.length === 0) return;

      await Promise.all(
        recentlyFailed.map(async (t) => {
          autoRetriedRef.current.add(t.id);
          try {
            // 标识"自动补刀"请求，让后端区分日志（自动补刀不打 VIDEO-RETRY-MANUAL）
            const resp = (await retryAuto(t.id)) as unknown as RetryResponse;

            // 补刀成功 → 重新加载任务状态
            const fresh = await videoApi.getTask(t.id).catch(() => null);
            if (fresh) {
              upsertTask(fresh);
              if (fresh.status === 'succeeded') {
                autoRetriedRef.current.delete(t.id);
              }
            }
            // 补刀未恢复（NewAPI 上任务尚未完成 / queued / in_progress）—— 5 分钟窗口期内
            // 会自动被再次纳入继续尝试
            if (resp?.recovered === false) {
              console.debug(
                '[useTaskPolling] job %s 自动补刀未恢复，等待下次轮询: %s',
                t.id,
                resp.reason ?? '(no reason)'
              );
              // 把标记移除，让下一轮继续尝试
              autoRetriedRef.current.delete(t.id);
            }
          } catch (e) {
            // ✅ 关键：补刀抛异常时（NewAPI 主动失败 / 上游报具体错误码），
            // 把后端 message 写入 task.error，加入 abandoned 集合停止重试。
            const apiErr = e as ApiError;
            const payload = apiErr?.payload as { message?: string; code?: number } | undefined;
            const backendMsg = payload?.message || apiErr?.message || '自动补刀异常';
            const backendCode = payload?.code;
            console.warn('[useTaskPolling] 自动补刀检测到上游失败，停止重试',
              t.id, backendMsg, backendCode ? `code=${backendCode}` : '');
            upsertTask({
              ...t,
              status: 'failed',
              error: backendCode
                ? `${backendMsg}（code=${backendCode}）`
                : backendMsg,
              updatedAt: Date.now(),
            });
            // 加入 abandoned 集合 → 永远不再触发自动补刀
            abandonedRef.current.add(t.id);
            autoRetriedRef.current.delete(t.id);
          }
        })
      );
    };
    tick();
    timer.current = setInterval(tick, POLLING_INTERVAL);
    return () => {
      if (timer.current) clearInterval(timer.current);
    };
  }, [tasks, upsertTask]);
}

/**
 * 自动补刀调用 retry 接口 + 自动标记 X-Auto-Retry header
 * （区别于用户手动点击"检查并补刀"按钮）
 */
async function retryAuto(id: string): Promise<unknown> {
  const { getAccessToken } = await import('@/lib/auth-store');
  const token = getAccessToken();
  const headers: Record<string, string> = {
    'X-Auto-Retry': 'true',
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`/api/videos/${id}/retry`, {
    method: 'POST',
    headers,
  });
  let payload: unknown = undefined;
  try { payload = await res.json(); } catch { /* ignore */ }
  if (!res.ok) {
    // 后端抛 BusinessException → GlobalExceptionHandler 包成 {code, message, data: null}
    // request 模式下 http.ts 会自动解包 ApiError
    throw new ApiError(res.status, (payload as any)?.message ?? `HTTP ${res.status}`, payload);
  }
  // 业务错误（如 NewAPI 主动失败，controller 也用 BusinessException）走相同解包路径
  if (payload && typeof payload === 'object' && 'code' in payload && (payload as any).code !== 0) {
    throw new ApiError(
      (payload as any).code ?? res.status,
      (payload as any).message ?? `HTTP ${res.status}`,
      payload
    );
  }
  return (payload as any).data ?? payload;
}

/** 给 UI 组件用的工具：判断任务是否还在"自动补刀窗口期"内 */
export function isInAutoRetryWindow(task: VideoTask, now: number = Date.now()): boolean {
  return task.status === 'failed' && now - task.createdAt < AUTO_RETRY_WINDOW_MS;
}