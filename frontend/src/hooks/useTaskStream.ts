// 预留：SSE 接入点。后端如果提供 /api/videos/stream 这种事件流，可以打开此 hook。
// 暂时未启用，等后端确认通信方式再启用。

'use client';

import { useEffect } from 'react';
import { useWorkbenchStore } from '@/store/workbench';
import type { VideoTask } from '@/types/video';

export function useTaskStream(enabled: boolean) {
  const upsertTask = useWorkbenchStore((s) => s.upsertTask);

  useEffect(() => {
    if (!enabled) return;
    const es = new EventSource('/api/videos/stream');
    es.addEventListener('task', (e) => {
      try {
        const data = JSON.parse((e as MessageEvent).data) as VideoTask;
        upsertTask(data);
      } catch { /* ignore */ }
    });
    return () => es.close();
  }, [enabled, upsertTask]);
}
