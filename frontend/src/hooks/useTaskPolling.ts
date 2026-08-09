// 任务状态轮询 hook。预留 SSE/WebSocket 接入点。
// 当前实现：轮询。当后端提供 SSE 时，把 polling 替换成 EventSource 即可。

'use client';

import { useCallback, useEffect, useRef } from 'react';
import { videoApi } from '@/api/video';
import { useWorkbenchStore } from '@/store/workbench';
import type { TaskStatus } from '@/types/video';

const POLLING_INTERVAL = 2000;

/** 哪些状态算"进行中"，需要持续轮询 */
const ACTIVE: TaskStatus[] = ['queued', 'running'];

export function useTaskPolling() {
  const tasks = useWorkbenchStore((s) => s.tasks);
  const upsertTask = useWorkbenchStore((s) => s.upsertTask);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  // 用 useCallback 避免每次渲染创建新函数导致 useEffect 重跑
  const tick = useCallback(async () => {
    const currentTasks = useWorkbenchStore.getState().tasks;
    // 跳过无效 id（空字符串、"undefined"、"null"），避免轮询 /api/jobs/undefined
    const active = currentTasks.filter(
      (t) => ACTIVE.includes(t.status) && t.id && t.id !== 'undefined' && t.id !== 'null'
    );
    if (active.length === 0) return;

    // 并发请求所有活跃任务，单个失败不影响其他
    const results = await Promise.allSettled(
      active.map((t) => videoApi.getTask(t.id))
    );

    results.forEach((result, index) => {
      const taskId = active[index].id;
      if (result.status === 'fulfilled') {
        upsertTask(result.value);
      } else {
        const error = result.reason;
        // 只在首次失败时打印，避免刷屏
        console.warn('[useTaskPolling] poll failed for task', taskId,
          error instanceof Error ? error.message : error);
      }
    });
  }, [upsertTask]);

  useEffect(() => {
    // 立即执行一次
    tick();
    timer.current = setInterval(tick, POLLING_INTERVAL);
    return () => {
      if (timer.current) clearInterval(timer.current);
    };
  }, [tick]);
}
