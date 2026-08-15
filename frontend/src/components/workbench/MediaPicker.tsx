'use client';

import { useRef, useState } from 'react';
import { ImagePlus, Music, Video as VideoIcon, X } from 'lucide-react';
import { useWorkbenchStore } from '@/store/workbench';
import { nanoid } from 'nanoid';
import type { ReferenceMedia } from '@/types/video';
import { cn } from '@/lib/utils';
import { MediaPreviewDialog } from '@/components/common/MediaPreviewDialog';
import { ReferenceMediaThumbnail } from '@/components/common/ReferenceMediaThumbnail';

const TABS = [
  { key: 'video', label: '视频', icon: VideoIcon },
  { key: 'image', label: '图片', icon: ImagePlus },
  { key: 'audio', label: '音频', icon: Music },
] as const;

type TabKey = (typeof TABS)[number]['key'];

export function MediaPicker() {
  const references = useWorkbenchStore((s) => s.references);
  const add = useWorkbenchStore((s) => s.addReference);
  const remove = useWorkbenchStore((s) => s.removeReference);
  const inputRef = useRef<HTMLInputElement>(null);
  const [tab, setTab] = useState<TabKey>('video');
  const [preview, setPreview] = useState<ReferenceMedia | null>(null);
  const limit = 3;
  const reached = references.length >= limit;

  function handleFiles(files: FileList | null) {
    if (!files) return;
    Array.from(files).forEach((file) => {
      if (references.length >= limit) return;
      const kind: ReferenceMedia['type'] = file.type.startsWith('video')
        ? 'video'
        : file.type.startsWith('audio')
        ? 'audio'
        : 'image';
      const url = URL.createObjectURL(file);
      const token = file.name.replace(/\.[^.]+$/, '').toLowerCase().slice(0, 16) || nanoid(6);
      add({
        id: nanoid(8),
        type: kind,
        url,
        name: file.name,
        token,
      });
    });
  }

  return (
    <div className="card p-4">
      <div className="mb-2 flex items-center justify-between">
        <div className="label">添加参考素材</div>
        <div className="text-[11px] text-fg-subtle">
          视频/音频/图片 · 最多 {limit} 个 · 已添加 {references.length}
        </div>
      </div>

      <div className="mb-3 flex gap-1">
        {TABS.map((t) => {
          const Icon = t.icon;
          return (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={cn(
                'btn-ghost h-8 px-3 py-1 text-xs',
                tab === t.key && 'border-brand/50 text-fg'
              )}
            >
              <Icon className="h-3.5 w-3.5" /> {t.label}
            </button>
          );
        })}
      </div>

      <label
        className={cn(
          'flex h-28 cursor-pointer items-center justify-center rounded-xl border border-dashed border-bg-line bg-bg-soft/40 text-sm text-fg-muted transition hover:border-brand/50',
          reached && 'pointer-events-none opacity-50'
        )}
      >
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={tab === 'video' ? 'video/*' : tab === 'audio' ? 'audio/*' : 'image/*'}
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />
        {reached ? '已达上限' : '点击或拖入文件（本地预览，不会上传）'}
      </label>

      {references.length > 0 && (
        <ul className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-3">
          {references.map((r) => (
            <li
              key={r.id}
              onClick={() => setPreview(r)}
              className="group relative cursor-pointer overflow-hidden rounded-xl border border-bg-line bg-bg-soft/60 transition hover:ring-2 hover:ring-brand/40"
            >
              <ReferenceMediaThumbnail media={r} />
              <div className="px-2 py-1.5">
                <div className="truncate text-xs text-fg">@{r.token}</div>
                <div className="truncate text-[10px] text-fg-subtle">{r.name}</div>
              </div>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  remove(r.id);
                }}
                className="absolute right-1.5 top-1.5 grid h-6 w-6 place-items-center rounded-full bg-black/60 text-white opacity-0 transition group-hover:opacity-100"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <MediaPreviewDialog media={preview} onClose={() => setPreview(null)} />
    </div>
  );
}
