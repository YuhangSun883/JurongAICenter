'use client';

import { Eye, Download, RefreshCw, X } from 'lucide-react';
import { useWorkbenchStore } from '@/store/workbench';
import { videoApi } from '@/api/video';
import { cn, timeAgo } from '@/lib/utils';

export function PreviewPanel() {
  const tasks = useWorkbenchStore((s) => s.tasks);
  const selectedId = useWorkbenchStore((s) => s.selectedTaskId);
  const select = useWorkbenchStore((s) => s.selectTask);

  const selected = tasks.find((t) => t.id === selectedId) ?? null;

  return (
    <div className="card overflow-hidden">
      <div className="flex items-center justify-between border-b border-bg-line/80 px-4 py-2.5">
        <div className="flex items-center gap-2 text-sm text-fg-muted">
          <Eye className="h-4 w-4" /> 预览
        </div>
        {selected && (
          <div className="flex items-center gap-1.5">
            <button
              className="btn-ghost"
              onClick={async () => {
                const t = await videoApi.retry(selected.id);
                const fresh = await videoApi.getTask(t.taskId);
                useWorkbenchStore.getState().upsertTask(fresh);
                select(fresh.id);
              }}
            >
              <RefreshCw className="h-3.5 w-3.5" /> 重生成
            </button>
            {selected.resultUrl && (
              <a className="btn-ghost" href={selected.resultUrl} download>
                <Download className="h-3.5 w-3.5" /> 下载
              </a>
            )}
            <button className="btn-ghost" onClick={() => select(null)}>
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        )}
      </div>

      <div className="aspect-video w-full bg-black/60">
        {!selected && <EmptyState />}
        {selected?.status === 'succeeded' && selected.resultUrl && (
          <video
            key={selected.id}
            src={selected.resultUrl}
            poster={selected.thumbnailUrl}
            controls
            className="h-full w-full"
          />
        )}
        {selected && selected.status !== 'succeeded' && (
          <RunningState status={selected.status} progress={selected.progress} error={selected.error} />
        )}
      </div>

      {selected && (
        <div className="border-t border-bg-line/80 px-4 py-2 text-xs text-fg-muted">
          <span className="chip mr-2">{selected.request.model}</span>
          <span className="chip mr-2">{selected.request.aspectRatio}</span>
          <span className="chip mr-2">{selected.request.resolution}</span>
          <span className="chip mr-2">{selected.request.duration}s</span>
          <span className="text-fg-subtle">· {timeAgo(selected.createdAt)}</span>
        </div>
      )}
    </div>
  );
}

function EmptyState() {
  return (
    <div className="grid h-full place-items-center text-center text-fg-subtle">
      <div>
        <div className="mx-auto mb-2 h-12 w-12 rounded-2xl border border-bg-line bg-bg-soft/60" />
        提交任务后，结果会在这里预览
      </div>
    </div>
  );
}

function RunningState({
  status,
  progress,
  error,
}: {
  status: 'queued' | 'running' | 'failed';
  progress: number;
  error?: string;
}) {
  if (status === 'failed') {
    return (
      <div className="grid h-full place-items-center text-sm text-red-300">
        生成失败{error ? `：${error}` : ''}
      </div>
    );
  }
  const pct = Math.round(progress);
  return (
    <div className="grid h-full place-items-center text-fg-muted">
      <div className="w-2/3 text-center">
        <div className="mx-auto mb-3 h-10 w-10 animate-pulse-glow rounded-full bg-brand/60" />
        <div className="text-sm">
          {status === 'queued' ? '排队中…' : '生成中…'} <span className="text-fg">{pct}%</span>
        </div>
        <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-bg-soft">
          <div
            className={cn('h-full rounded-full bg-gradient-to-r from-brand to-brand-glow transition-all')}
            style={{ width: `${pct}%` }}
          />
        </div>
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-transparent">
          <div className="shimmer h-full w-full rounded-full" />
        </div>
      </div>
    </div>
  );
}
