// 任务状态轮询 hook。预留 SSE/WebSocket 接入点。
// 当前实现：轮询。当后端提供 SSE 时，把 polling 替换成 EventSource 即可。

'use client';

import { useEffect, useRef } from 'react';
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

  useEffect(() => {
    const tick = async () => {
      const active = tasks.filter((t) => ACTIVE.includes(t.status));
      if (active.length === 0) return;
      await Promise.all(
        active.map((t) =>
          videoApi.getTask(t.id).then(upsertTask).catch(() => undefined)
        )
      );
    };
    tick();
    timer.current = setInterval(tick, POLLING_INTERVAL);
    return () => {
      if (timer.current) clearInterval(timer.current);
    };
  }, [tasks, upsertTask]);
}
