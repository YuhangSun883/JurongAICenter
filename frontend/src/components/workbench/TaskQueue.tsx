'use client';

import { ListChecks, Inbox } from 'lucide-react';
import { useWorkbenchStore } from '@/store/workbench';
import { videoApi } from '@/api/video';
import { cn, timeAgo } from '@/lib/utils';
import type { TaskStatus } from '@/types/video';

const STATUS_LABEL: Record<TaskStatus, string> = {
  queued: '排队中',
  running: '生成中',
  succeeded: '已完成',
  failed: '失败',
};

const STATUS_COLOR: Record<TaskStatus, string> = {
  queued: 'bg-zinc-500/20 text-zinc-300',
  running: 'bg-brand/20 text-brand-glow',
  succeeded: 'bg-emerald-500/20 text-emerald-300',
  failed: 'bg-red-500/20 text-red-300',
};

export function TaskQueue() {
  const tasks = useWorkbenchStore((s) => s.tasks);
  const select = useWorkbenchStore((s) => s.selectTask);
  const selectedId = useWorkbenchStore((s) => s.selectedTaskId);

  const queued = tasks.filter((t) => t.status === 'queued').length;
  const running = tasks.filter((t) => t.status === 'running').length;

  return (
    <div className="card">
      <div className="flex items-center justify-between border-b border-bg-line/80 px-4 py-2.5">
        <div className="flex items-center gap-2 text-sm text-fg-muted">
          <ListChecks className="h-4 w-4" /> 任务队列
        </div>
        <div className="flex items-center gap-1.5 text-xs">
          <span className="chip">排队中 {queued}</span>
          <span className="chip">生成中 {running}</span>
        </div>
      </div>

      <ul className="max-h-[360px] divide-y divide-bg-line/60 overflow-auto">
        {tasks.length === 0 && (
          <li className="grid place-items-center px-4 py-10 text-sm text-fg-subtle">
            <Inbox className="mb-2 h-6 w-6" />
            暂无任务
          </li>
        )}
        {tasks.map((t) => (
          <li
            key={t.id}
            onClick={() => select(t.id)}
            className={cn(
              'group flex cursor-pointer items-center gap-3 px-4 py-3 transition hover:bg-bg-soft/60',
              selectedId === t.id && 'bg-bg-soft/80'
            )}
          >
            <Thumb status={t.status} url={t.thumbnailUrl} progress={t.progress} />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 text-xs">
                <span className={cn('rounded-full px-2 py-0.5', STATUS_COLOR[t.status])}>
                  {STATUS_LABEL[t.status]}
                </span>
                <span className="text-fg-subtle">{t.request.model}</span>
                <span className="text-fg-subtle">· {t.request.duration}s</span>
              </div>
              <div className="mt-1 line-clamp-1 text-sm text-fg/90">
                {t.request.script || '(空脚本)'}
              </div>
              <div className="mt-0.5 text-[11px] text-fg-subtle">
                {timeAgo(t.createdAt)} · 预计消耗 {t.estimatedCredits} 积分
              </div>
            </div>
            {t.status !== 'succeeded' && t.status !== 'failed' && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  videoApi.cancel(t.id).then(() => videoApi.getTask(t.id))
                    .then((fresh) => useWorkbenchStore.getState().upsertTask(fresh))
                    .catch(() => undefined);
                }}
                className="btn-ghost opacity-0 transition group-hover:opacity-100"
              >
                取消
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function Thumb({ status, url, progress }: { status: TaskStatus; url?: string; progress: number }) {
  if (status === 'succeeded' && url) {
    return <img src={url} alt="" className="h-14 w-20 flex-none rounded-lg object-cover" />;
  }
  return (
    <div className="relative h-14 w-20 flex-none overflow-hidden rounded-lg border border-bg-line bg-bg-soft">
      <div className="absolute inset-0 grid place-items-center text-[10px] text-fg-subtle">
        {status === 'queued' ? '排队' : status === 'running' ? `${Math.round(progress)}%` : status === 'failed' ? '失败' : ''}
      </div>
      {(status === 'running' || status === 'queued') && (
        <div
          className="absolute bottom-0 left-0 h-0.5 bg-brand-glow"
          style={{ width: `${Math.min(100, progress)}%` }}
        />
      )}
    </div>
  );
}
