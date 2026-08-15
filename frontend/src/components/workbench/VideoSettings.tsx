'use client';

import { useMemo } from 'react';
import { Coins, Loader2, Play, ChevronDown } from 'lucide-react';
import { useWorkbenchStore, parseReferences } from '@/store/workbench';
import { videoApi } from '@/api/video';
import type { AspectRatio, Duration, Resolution, VideoModel } from '@/types/video';

const MODELS: VideoModel[] = [
  'Seedance-2.0-VIP',
  'Seedance-2.0-Fast-VIP',
  'Seedance-2.0-Mini-VIP',
];
const ASPECTS: AspectRatio[] = ['21:9', '16:9', '4:3', '1:1', '3:4', '9:16'];
const RES: Resolution[] = ['480p', '720p', '1080p'];
const DURATIONS: Duration[] = [5, 10, 15, 30];

export function VideoSettings() {
  const {
    script, model, setModel, aspectRatio, setAspectRatio,
    resolution, setResolution, duration, setDuration,
    audioMode,
    references, isSubmitting, setSubmitting,
  } = useWorkbenchStore();
  const upsertTask = useWorkbenchStore((s) => s.upsertTask);

  const estimated = useMemo(() => {
    const base = duration * 2;
    return model.includes('VIP') ? Math.round(base * 1.5) : base;
  }, [duration, model]);

  const canSubmit = script.trim().length > 0 && !isSubmitting;

  async function submit() {
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      const { taskId } = await videoApi.create({
        script,
        model,
        aspectRatio,
        resolution,
        duration,
        audioMode,
        referenceIds: parseReferences(script, references),
      });
      const t = await videoApi.getTask(taskId);
      upsertTask(t);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="card overflow-hidden">
      {/* 上下两行：模型 + 设置 */}
      <div className="grid grid-cols-1 gap-4 p-4 md:grid-cols-2">
        <Field label="视频模型" icon="📊">
          <Select value={model} onChange={(v) => setModel(v as VideoModel)} options={MODELS} />
        </Field>

        <Field label="视频设置" icon="🎞️">
          <div className="flex items-center gap-2">
            <Select value={aspectRatio} onChange={(v) => setAspectRatio(v as AspectRatio)} options={ASPECTS} />
            <Select value={resolution} onChange={(v) => setResolution(v as Resolution)} options={RES} />
            <Select
              value={String(duration)}
              onChange={(v) => setDuration(Number(v) as Duration)}
              options={DURATIONS.map((d) => String(d))}
              formatter={(v) => `${v}s`}
            />
          </div>
        </Field>
      </div>

      {/* 底部：消耗 + 立即生成 */}
      <div className="flex items-center justify-between border-t border-bg-line/60 px-4 py-3">
        <div className="flex items-center gap-2 text-sm text-fg-muted">
          <Coins className="h-4 w-4 text-brand" />
          预计消耗 <span className="text-fg">{estimated}</span> 积分
        </div>
        <button
          onClick={submit}
          disabled={!canSubmit}
          className="flex items-center gap-2 rounded-xl bg-brand px-5 py-2 text-sm font-medium text-white shadow-glow transition hover:brightness-110 disabled:opacity-40"
        >
          {isSubmitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
          立即生成视频
        </button>
      </div>
    </div>
  );
}

function Field({ label, icon, children }: { label: string; icon?: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="mb-1.5 flex items-center gap-1.5 text-xs text-fg-muted">
        {icon && <span className="text-fg-subtle">{icon}</span>}
        {label}
      </div>
      {children}
    </div>
  );
}

function Select<T extends string>({
  value, onChange, options, formatter,
}: {
  value: T;
  onChange: (v: T) => void;
  options: readonly T[];
  formatter?: (v: T) => string;
}) {
  return (
    <div className="relative flex-1">
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as T)}
        className="w-full appearance-none rounded-xl border border-bg-line bg-bg-soft/60 px-3 py-2 pr-8 text-sm text-fg outline-none focus:border-brand/60 focus:ring-2 focus:ring-brand/20"
      >
        {options.map((o) => (
          <option key={o} value={o}>{formatter ? formatter(o) : o}</option>
        ))}
      </select>
      <ChevronDown className="pointer-events-none absolute right-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-fg-subtle" />
    </div>
  );
}
