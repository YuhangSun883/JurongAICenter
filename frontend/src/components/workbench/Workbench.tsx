'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  BarChart3,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Image as ImageIcon,
  List,
  Loader2,
  Maximize2,
  Mic2,
  Package,
  PanelRightClose,
  Play,
  Plus,
  Sparkles,
  UserRound,
  Video,
  XCircle,
} from 'lucide-react';
import { nanoid } from 'nanoid';
import { useWorkbenchStore } from '@/store/workbench';
import { useTaskPolling } from '@/hooks/useTaskPolling';
import { videoApi } from '@/api/video';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
import { cn } from '@/lib/utils';
import type { AspectRatio, AudioMode, Duration, ReferenceMedia, Resolution, VideoModel } from '@/types/video';

const MODELS: VideoModel[] = [
  'Seedance-2.0-VIP',
  'Seedance-2.0-Fast-VIP',
  'Seedance-2.0-Mini-VIP',
];
const MODEL_META: Record<VideoModel, { description: string }> = {
  'Seedance-2.0-VIP': { description: '正式发布首选' },
  'Seedance-2.0-Fast-VIP': { description: '快速迭代验证' },
  'Seedance-2.0-Mini-VIP': { description: '低成本测试' },
};
const ASPECTS: AspectRatio[] = ['21:9', '16:9', '4:3', '1:1', '3:4', '9:16'];
const RESOLUTIONS: Resolution[] = ['480p', '720p', '1080p'];
const DURATIONS: Duration[] = [5, 10, 15, 30];
const AI_VIDEO_MAX_REFS = 15;

const SLOT_META = [
  { kind: 'product', label: '添加商品', optional: false, icon: Package },
  { kind: 'model', label: '添加模特', optional: true, icon: UserRound },
  { kind: 'scene', label: '添加场景', optional: true, icon: ImageIcon },
  { kind: 'voice', label: '添加声音', optional: true, icon: Mic2 },
] as const;

type SlotKind = (typeof SLOT_META)[number]['kind'];

const SLOT_GUIDES: Record<
  SlotKind,
  {
    title: string;
    good: Array<{ label: string; seed: string }>;
    bad: Array<{ label: string; seed: string }>;
  }
> = {
  product: {
    title: '商品图注意事项',
    good: [
      { label: '真实干净单品', seed: 'jrai-product-dress-clean' },
      { label: '模特使用商品', seed: 'jrai-product-model-use' },
      { label: '多个角度', seed: 'jrai-product-angles' },
      { label: '特写', seed: 'jrai-product-detail' },
    ],
    bad: [
      { label: '不含图中图', seed: 'jrai-product-screen' },
      { label: '不含拼贴图', seed: 'jrai-product-collage' },
      { label: '不含文字底图', seed: 'jrai-product-text' },
    ],
  },
  model: {
    title: '模特图注意事项',
    good: [
      { label: '正脸', seed: 'jrai-model-face' },
      { label: '全身', seed: 'jrai-model-full-body' },
      { label: '侧脸', seed: 'jrai-model-side' },
      { label: '三视图', seed: 'jrai-model-views' },
    ],
    bad: [
      { label: '模糊不清', seed: 'jrai-model-blur' },
      { label: '多人合照', seed: 'jrai-model-group' },
      { label: '背景混乱', seed: 'jrai-model-background' },
    ],
  },
  scene: {
    title: '场景图注意事项',
    good: [
      { label: '干净整洁', seed: 'jrai-scene-clean' },
      { label: '自然光', seed: 'jrai-scene-light' },
      { label: '场景完整', seed: 'jrai-scene-wide' },
      { label: '风格统一', seed: 'jrai-scene-style' },
    ],
    bad: [
      { label: '背景杂乱', seed: 'jrai-scene-messy' },
      { label: '光线过暗', seed: 'jrai-scene-dark' },
      { label: '模糊不清', seed: 'jrai-scene-blur' },
    ],
  },
  voice: {
    title: '声音注意事项',
    good: [
      { label: '人声清晰干净', seed: 'jrai-voice-mic' },
      { label: '单人说话', seed: 'jrai-voice-single' },
      { label: '音量稳定', seed: 'jrai-voice-level' },
      { label: '完整连续录音', seed: 'jrai-voice-wave' },
    ],
    bad: [
      { label: '背景噪音大', seed: 'jrai-voice-noisy' },
      { label: '多人同时说话', seed: 'jrai-voice-group' },
      { label: '爆音或断续', seed: 'jrai-voice-clipped' },
    ],
  },
};

export function Workbench() {
  useTaskPolling();

  const [isWriting, setIsWriting] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [selectedReferences, setSelectedReferences] = useState<ReferenceMedia[]>([]);
  const { materials, addMaterials, removeMaterial } = useMaterials();
  const {
    script,
    setScript,
    model,
    setModel,
    aspectRatio,
    setAspectRatio,
    resolution,
    setResolution,
    duration,
    setDuration,
    audioMode,
    setAudioMode,
    tasks,
    selectedTaskId,
    selectTask,
    isSubmitting,
    setSubmitting,
  } = useWorkbenchStore();

  useEffect(() => {
    videoApi
      .listTasks({ pageSize: 20 })
      .then((res) => useWorkbenchStore.getState().setTasks(res.items))
      .catch(() => undefined);
  }, []);

  const selectedTask = tasks.find((task) => task.id === selectedTaskId) ?? null;
  const queued = tasks.filter((task) => task.status === 'queued').length;
  const running = tasks.filter((task) => task.status === 'running').length;
  const estimatedCredits = useMemo(() => {
    const base = duration * 2;
    return model.includes('VIP') ? Math.round(base * 1.5) : base;
  }, [duration, model]);

  const canSubmit = script.trim().length > 0 && !isSubmitting;
  const remainingReferenceSlots = Math.max(0, AI_VIDEO_MAX_REFS - selectedReferences.length);

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
        referenceIds: selectedReferences.map((reference) => reference.id),
      });
      const fresh = await videoApi.getTask(taskId);
      useWorkbenchStore.getState().upsertTask(fresh);
      selectTask(fresh.id);
    } finally {
      setSubmitting(false);
    }
  }

  function mediaToReference(media: PickedMedia): ReferenceMedia {
    return {
      id: media.id,
      type: media.type,
      url: media.url,
      name: media.name,
      token: media.name.replace(/\.[^/.]+$/, '').slice(0, 8) || nanoid(6),
    };
  }

  function handleUploadFiles(files: FileList | null): PickedMedia[] {
    if (!files) return [];
    const existingFingerprints = new Set(materials.map((material) => `${material.name}_${material.size ?? material.url.length}`));
    const items: GlobalMaterial[] = [];

    Array.from(files).forEach((file) => {
      const fingerprint = `${file.name}_${file.size}`;
      if (existingFingerprints.has(fingerprint)) return;
      existingFingerprints.add(fingerprint);

      const type: GlobalMaterial['type'] = file.type.startsWith('video')
        ? 'video'
        : file.type.startsWith('audio')
          ? 'audio'
          : 'image';

      items.push({
        id: nanoid(10),
        type,
        url: URL.createObjectURL(file),
        name: file.name,
        size: file.size,
      });
    });

    addMaterials(items);
    return items;
  }

  function handleConfirmPicked(picked: PickedMedia[]) {
    if (picked.length === 0) return;

    setSelectedReferences((current) => {
      const existingIds = new Set(current.map((reference) => reference.id));
      const existingFingerprints = new Set(current.map((reference) => `${reference.name}_${reference.url.length}`));
      const fresh = picked
        .filter((media) => {
          const fingerprint = `${media.name}_${media.url.length}`;
          if (existingIds.has(media.id) || existingFingerprints.has(fingerprint)) return false;
          existingIds.add(media.id);
          existingFingerprints.add(fingerprint);
          return true;
        })
        .slice(0, Math.max(0, AI_VIDEO_MAX_REFS - current.length))
        .map(mediaToReference);

      return [...current, ...fresh];
    });
  }

  async function writeForMe() {
    if (isWriting) return;
    setIsWriting(true);
    try {
      const result = await videoApi.generateScript({
        brief: script.trim() || undefined,
        model,
        aspectRatio,
        duration,
        audioMode,
        referenceIds: selectedReferences.map((reference) => reference.id),
      });
      setScript(result.script);
    } finally {
      setIsWriting(false);
    }
  }

  return (
    <main className="h-screen overflow-hidden bg-[#f7f7f8] px-4 py-5 text-[#16181d] sm:px-6">
      <div className="grid h-full min-h-0 grid-cols-[minmax(420px,610px)_minmax(0,1fr)_76px] gap-3">
        <section className="flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-white">
          <div className="flex h-14 items-center justify-between px-4">
            <h1 className="text-sm font-semibold">AI 视频</h1>
            <button
              type="button"
              onClick={() => {
                setScript('');
                setSelectedReferences([]);
                selectTask(null);
              }}
              className="inline-flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium hover:bg-[#f3f4f6]"
            >
              <Plus className="h-3.5 w-3.5" />
              新建
            </button>
          </div>

          <div className="mx-3 flex flex-1 min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
            <div className="flex items-center justify-between px-4 pt-4 text-xs text-[#737985]">
              <span>{selectedReferences.length} / {AI_VIDEO_MAX_REFS}</span>
              <div className="flex items-center gap-3 text-[#a4aab5]">
                <Package className="h-4 w-4" />
                <List className="h-4 w-4" />
                <Maximize2 className="h-4 w-4" />
              </div>
            </div>

            <div
              className={cn(
                'mt-5 flex items-center gap-3 px-4',
                selectedReferences.length === 0 ? 'overflow-visible pb-0' : 'overflow-x-auto pb-1'
              )}
            >
              {selectedReferences.length === 0 ? (
                SLOT_META.map((slot) => {
                  const Icon = slot.icon;
                  return (
                    <div
                      key={slot.kind}
                      className="group/slot relative z-10 flex-none"
                    >
                      <button
                        type="button"
                        onClick={() => setPickerOpen(true)}
                        className="group relative flex h-[60px] w-[84px] flex-col items-center justify-center overflow-hidden rounded-lg bg-white text-[10px] text-[#3f4652] shadow-[0_8px_20px_rgba(21,25,36,0.06)] ring-1 ring-[#eff0f3] transition group-hover/slot:ring-[#20242b]"
                      >
                        <Icon className="mb-1 h-4 w-4 text-[#a8afba]" />
                        <span>{slot.label}</span>
                        {slot.optional && <span className="absolute right-1 top-1 text-[9px] text-[#c2c6cf]">可选</span>}
                      </button>
                      <SlotGuidePopover
                        guide={SLOT_GUIDES[slot.kind]}
                        align={slot.kind === 'product' ? 'left' : slot.kind === 'voice' ? 'right' : 'center'}
                      />
                    </div>
                  );
                })
              ) : (
                selectedReferences.map((reference, index) => (
                  <div
                    key={reference.id}
                    className="group/ref relative h-[60px] w-[60px] flex-none overflow-hidden rounded-lg bg-white shadow-[0_8px_20px_rgba(21,25,36,0.06)] ring-1 ring-[#eff0f3]"
                    title={reference.name}
                  >
                    {reference.type === 'audio' ? (
                      <div className="grid h-full w-full place-items-center bg-[#f3f5ff]">
                        <Mic2 className="h-5 w-5 text-[#7892ff]" />
                      </div>
                    ) : (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={reference.url} alt={reference.name} className="h-full w-full object-cover" />
                    )}
                    <span className="absolute left-1 top-1 grid h-4 min-w-4 place-items-center rounded-full bg-black/65 px-1 text-[10px] font-semibold leading-4 text-white">
                      {index + 1}
                    </span>
                    <button
                      type="button"
                      onClick={() => setSelectedReferences((current) => current.filter((item) => item.id !== reference.id))}
                      className="absolute right-1 top-1 hidden h-4 w-4 place-items-center rounded-full bg-black/55 text-[10px] leading-none text-white group-hover/ref:grid"
                      aria-label={`移除 ${reference.name}`}
                    >
                      x
                    </button>
                  </div>
                ))
              )}
              <AddMaterialCard
                disabled={remainingReferenceSlots === 0}
                onClick={() => setPickerOpen(true)}
                label="点击添加"
                className="ml-auto h-[60px] w-[60px] rounded-lg border-[#eceef2] bg-[#f9fafb] text-[10px] text-[#747b87] hover:border-[#d7defc] hover:text-[#4d73ff]"
                iconClassName="h-5 w-5"
                labelClassName="mt-1 text-[10px]"
              />
            </div>

            <div className="mt-4 px-4 text-sm text-[#6e7580]">
              输入视频脚本，使用 <span className="text-[#9298a3]">@</span> 指定参考素材，或
              <button
                type="button"
                onClick={writeForMe}
                disabled={isWriting}
                className="ml-2 font-medium text-[#3677ff] disabled:cursor-wait disabled:opacity-60"
              >
                「帮我写」
              </button>
            </div>

            <div className="relative mt-1 flex min-h-0 flex-1 px-4 pb-4">
              <textarea
                value={script}
                onChange={(event) => setScript(event.target.value)}
                maxLength={10000}
                className="h-full min-h-[420px] w-full resize-none bg-transparent pb-8 pt-2 text-sm leading-7 text-[#242832] outline-none placeholder:text-[#b8bdc7]"
              />
              <div className="pointer-events-none absolute bottom-5 left-5 text-xs text-[#555b66]">
                {script.length} / 10000
              </div>
              <button
                type="button"
                onClick={writeForMe}
                disabled={isWriting}
                className="absolute bottom-5 right-7 inline-flex items-center gap-1 text-xs font-medium text-[#2f78ff] disabled:cursor-wait disabled:opacity-60"
              >
                <Sparkles className={cn('h-3.5 w-3.5', isWriting && 'animate-pulse')} />
                {isWriting ? '生成中...' : '帮我写'}
              </button>
            </div>
          </div>

          <div className="mx-3 mt-2 grid grid-cols-2 gap-2">
            <ModelSelectCard
              icon={<BarChart3 className="h-4 w-4" />}
              label="模型"
              value={model}
              options={MODELS}
              optionMeta={MODEL_META}
              onChange={(value) => setModel(value as VideoModel)}
            />
            <SettingsCard
              aspectRatio={aspectRatio}
              setAspectRatio={setAspectRatio}
              resolution={resolution}
              setResolution={setResolution}
              duration={duration}
              setDuration={setDuration}
              audioMode={audioMode}
              setAudioMode={setAudioMode}
            />
          </div>

          <div className="m-3 mt-2">
            <button
              type="button"
              disabled={!canSubmit}
              onClick={submit}
              className="flex h-10 w-full items-center justify-center gap-5 rounded-lg bg-[#1f232b] text-sm font-semibold text-white transition hover:bg-[#111318] disabled:bg-[#8b8b8b] disabled:hover:bg-[#8b8b8b]"
            >
              {isSubmitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              立即生成视频
              <span className="text-xs font-medium opacity-90">预计 {estimatedCredits.toFixed(2)} 积分</span>
            </button>
          </div>
        </section>

        <section className="flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
          <div className="flex h-12 items-center gap-7 px-5 text-xs font-semibold">
            <button className="relative h-12 text-[#1d222b] after:absolute after:bottom-0 after:left-1/2 after:h-0.5 after:w-4 after:-translate-x-1/2 after:rounded-full after:bg-[#1d222b]">
              预览
            </button>
            <button className="h-12 text-[#575e69]">收藏</button>
          </div>

          <div className="grid flex-1 place-items-center px-8 pb-8">
            {selectedTask?.status === 'succeeded' && selectedTask.resultUrl ? (
              <video
                key={selectedTask.id}
                src={selectedTask.resultUrl}
                poster={selectedTask.thumbnailUrl}
                controls
                className="max-h-full max-w-full rounded-xl bg-black shadow-sm"
              />
            ) : selectedTask ? (
              <div className="w-full max-w-[520px] text-center">
                <div className="mx-auto mb-5 grid h-16 w-16 place-items-center rounded-full bg-white shadow-sm">
                  <Video className="h-8 w-8 text-[#757b86]" />
                </div>
                <div className="text-sm font-medium text-[#303642]">
                  {selectedTask.status === 'failed' ? '生成失败' : selectedTask.status === 'queued' ? '排队中' : '生成中'}
                  <span className="ml-2 text-[#6f7682]">{Math.round(selectedTask.progress)}%</span>
                </div>
                <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-[#ebeef3]">
                  <div className="h-full rounded-full bg-[#4f7cff]" style={{ width: `${Math.min(100, selectedTask.progress)}%` }} />
                </div>
              </div>
            ) : (
              <div className="text-center text-[#747b86]">
                <Video className="mx-auto mb-7 h-10 w-10 stroke-[1.7]" />
                <p className="text-sm">点击右侧队列中带成片的任务即可预览。</p>
              </div>
            )}
          </div>
        </section>

        <aside className="flex min-h-0 flex-col items-center rounded-xl border border-[#e4e5e9] bg-[#fbfbfc] py-5">
          <PanelRightClose className="h-4 w-4 text-[#a6abb4]" />
          <div className="mt-8 w-14 rounded-lg border border-[#eceef2] bg-white py-3 text-center shadow-sm">
            <div className="text-xs text-[#9ca2ad]">排队中</div>
            <div className="text-2xl font-semibold leading-7">{queued}</div>
            <div className="mt-2 text-xs text-[#9ca2ad]">生成中</div>
            <div className="text-2xl font-semibold leading-7">{running}</div>
          </div>
          <div className="mt-auto pb-2">
            <ChevronRight className="h-4 w-4 text-[#a6abb4]" />
          </div>
        </aside>
      </div>
      <MediaPickerDialog
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        onConfirm={handleConfirmPicked}
        onUploadFiles={handleUploadFiles}
        onRemoveUploaded={(id) => {
          removeMaterial(id);
          setSelectedReferences((current) => current.filter((reference) => reference.id !== id));
        }}
        uploadedFiles={materials}
        showMockAssets={false}
        max={remainingReferenceSlots}
      />
    </main>
  );
}

function ModelSelectCard<T extends string>({
  icon,
  label,
  value,
  options,
  optionMeta,
  onChange,
}: {
  icon: React.ReactNode;
  label: string;
  value: T;
  options: readonly T[];
  optionMeta: Record<T, { description: string }>;
  onChange: (value: T) => void;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && !containerRef.current?.contains(event.target)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [open]);

  return (
    <div ref={containerRef} className="relative">
      {open && (
        <div
          role="listbox"
          aria-label="选择模型"
          className="absolute bottom-full left-0 z-50 mb-2 w-full min-w-[280px] rounded-xl border border-[#dfe2e8] bg-[#fbfbfc] p-2 shadow-[0_12px_28px_rgba(29,35,48,0.12)]"
        >
          <div className="px-1 pb-1.5 text-[11px] text-[#707784]">选择模型</div>
          <div className="space-y-1.5">
            {options.map((option) => {
              const selected = option === value;
              return (
                <button
                  key={option}
                  type="button"
                  role="option"
                  aria-selected={selected}
                  onClick={() => {
                    onChange(option);
                    setOpen(false);
                  }}
                  className={cn(
                    'flex w-full items-center gap-2 rounded-xl border px-2 py-2 text-left transition',
                    selected
                      ? 'border-[#20242b] bg-white shadow-[0_2px_8px_rgba(20,24,32,0.08)]'
                      : 'border-[#e7e9ed] bg-white/70 hover:border-[#cbd3e6] hover:bg-white'
                  )}
                >
                  <span className="grid h-8 w-8 flex-none place-items-center rounded-md border border-[#e5e8ed] bg-[#f8f9fb] text-[#89909b]">
                    {icon}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-xs font-semibold text-[#272b33]">
                      {option}
                    </span>
                    <span className="mt-0.5 block text-[10px] text-[#818792]">
                      {optionMeta[option].description}
                    </span>
                  </span>
                  {selected && (
                    <span className="grid h-4 w-4 flex-none place-items-center rounded-full bg-[#20242b] text-white">
                      <Check className="h-2.5 w-2.5" strokeWidth={3} />
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}

      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className={cn(
          'flex h-[52px] w-full items-center gap-3 rounded-lg border bg-[#fbfbfc] px-3 text-left transition',
          open
            ? 'border-[#b9bdc5] bg-white shadow-[0_0_0_2px_rgba(31,35,43,0.14)]'
            : 'border-[#e4e5e9] hover:border-[#cdd2dc]'
        )}
      >
        <span className="grid h-8 w-8 place-items-center rounded-md border border-[#eceef2] bg-white text-[#89909b]">
          {icon}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] text-[#707784]">{label}</span>
          <span className="block truncate text-xs font-semibold text-[#242832]">{value}</span>
        </span>
        <ChevronDown
          className={cn('h-4 w-4 text-[#9da3ad] transition-transform', open && 'rotate-180')}
        />
      </button>
    </div>
  );
}

function SlotGuidePopover({
  guide,
  align,
}: {
  guide: (typeof SLOT_GUIDES)[SlotKind];
  align: 'left' | 'center' | 'right';
}) {
  return (
    <div
      className={cn(
        'pointer-events-none absolute top-[70px] z-50 w-[360px] rounded-xl border border-[#eef0f4] bg-white p-2.5 opacity-0 shadow-[0_18px_36px_rgba(33,38,52,0.18)] transition duration-150 group-hover/slot:opacity-100 group-focus-within/slot:opacity-100',
        align === 'left' && 'left-0',
        align === 'center' && 'left-1/2 -translate-x-1/2',
        align === 'right' && 'right-0'
      )}
    >
      <div className="mb-2 flex items-center justify-center gap-2 text-[22px] font-bold tracking-tight text-[#1f232b]">
        <span className="h-px w-7 bg-[#dfe2e8]" />
        {guide.title}
        <span className="h-px w-7 bg-[#dfe2e8]" />
      </div>

      <GuideGroup tone="good" title="推荐" items={guide.good} />
      <GuideGroup tone="bad" title="不推荐" items={guide.bad} />
    </div>
  );
}

function GuideGroup({
  tone,
  title,
  items,
}: {
  tone: 'good' | 'bad';
  title: string;
  items: Array<{ label: string; seed: string }>;
}) {
  const Icon = tone === 'good' ? CheckCircle2 : XCircle;
  const color = tone === 'good' ? 'text-[#29a24a]' : 'text-[#df3d35]';
  return (
    <div className="mb-2 last:mb-0">
      <div className={cn('mb-1 flex items-center gap-1 text-[10px] font-semibold', color)}>
        <Icon className="h-3 w-3" />
        {title}
      </div>
      <div className={cn('grid gap-1.5', items.length === 4 ? 'grid-cols-4' : 'grid-cols-3')}>
        {items.map((item) => (
          <div key={item.seed} className="min-w-0">
            <div className="overflow-hidden rounded-md border border-[#edf0f4] bg-[#f4f5f7]">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={`https://picsum.photos/seed/${item.seed}/120/86`}
                alt={item.label}
                className="h-[58px] w-full object-cover"
              />
            </div>
            <div className={cn('mt-1 flex items-center gap-0.5 text-[9px] font-medium', color)}>
              <Icon className="h-2.5 w-2.5 flex-none" />
              <span className="truncate text-[#3f4652]">{item.label}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function SettingsCard({
  aspectRatio,
  setAspectRatio,
  resolution,
  setResolution,
  duration,
  setDuration,
  audioMode,
  setAudioMode,
}: {
  aspectRatio: AspectRatio;
  setAspectRatio: (value: AspectRatio) => void;
  resolution: Resolution;
  setResolution: (value: Resolution) => void;
  duration: Duration;
  setDuration: (value: Duration) => void;
  audioMode: AudioMode;
  setAudioMode: (value: AudioMode) => void;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && !containerRef.current?.contains(event.target)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [open]);

  return (
    <div ref={containerRef} className="relative">
      {open && (
        <div className="absolute bottom-full left-0 z-50 mb-2 w-full min-w-[360px] rounded-xl border border-[#dfe2e8] bg-[#fbfbfc] p-3 shadow-[0_12px_28px_rgba(29,35,48,0.12)]">
          <SettingsSectionLabel>视频比例</SettingsSectionLabel>
          <div className="grid grid-cols-6 gap-2">
            {ASPECTS.map((option) => {
              const selected = option === aspectRatio;
              return (
                <button
                  key={option}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => setAspectRatio(option)}
                  className={cn(
                    'relative flex h-[68px] flex-col items-center justify-center gap-1 rounded-lg border bg-white text-[10px] font-semibold text-[#68707c] transition',
                    selected
                      ? 'border-[#20242b] text-[#20242b] shadow-[0_2px_7px_rgba(20,24,32,0.08)]'
                      : 'border-[#e7e9ed] hover:border-[#cbd3e6]'
                  )}
                >
                  <RatioGlyph value={option} />
                  {option}
                  {selected && <SelectionMark />}
                </button>
              );
            })}
          </div>

          <SettingsSectionLabel className="mt-3">分辨率</SettingsSectionLabel>
          <div className="flex gap-2">
            {[
              ['标清', '480p'],
              ['高清', '720p'],
              ['超清', '1080p'],
            ].map(([name, value]) => {
              const selected = value === resolution;
              return (
                <button
                  key={value}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => setResolution(value as Resolution)}
                  className={cn(
                    'flex h-8 flex-1 items-center justify-center gap-1 rounded-lg border bg-white text-[11px] transition',
                    selected
                      ? 'border-[#20242b] font-semibold text-[#20242b]'
                      : 'border-[#e7e9ed] text-[#68707c] hover:border-[#cbd3e6]'
                  )}
                >
                  <span>{name}</span>
                  <span className="font-semibold">{value}</span>
                  {selected && <SelectionMark />}
                </button>
              );
            })}
          </div>

          <SettingsSectionLabel className="mt-3">音频</SettingsSectionLabel>
          <div className="flex gap-2">
            {(['with-audio', 'mute'] as const).map((value) => {
              const selected = value === audioMode;
              const label = value === 'with-audio' ? '含音频' : '静音';
              return (
                <button
                  key={value}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => setAudioMode(value)}
                  className={cn(
                    'flex h-8 flex-1 items-center justify-center rounded-lg border bg-white text-[11px] transition',
                    selected
                      ? 'border-[#20242b] font-semibold text-[#20242b]'
                      : 'border-[#e7e9ed] text-[#68707c] hover:border-[#cbd3e6]'
                  )}
                >
                  {label}
                  {selected && <SelectionMark />}
                </button>
              );
            })}
          </div>

          <SettingsSectionLabel className="mt-3">时长</SettingsSectionLabel>
          <div className="px-3 pt-1">
            <input
              aria-label="视频时长"
              type="range"
              min={4}
              max={30}
              step={1}
              value={duration}
              onChange={(event) => setDuration(Number(event.target.value) as Duration)}
              className="h-4 w-full accent-[#20242b]"
            />
            <div className="mt-1 flex items-center justify-between text-[10px] text-[#707784]">
              <span>4s</span>
              <span className="rounded-md bg-[#e3e6eb] px-1.5 py-0.5 font-semibold text-[#3f4652]">
                {duration}s
              </span>
            </div>
          </div>
        </div>
      )}

      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className={cn(
          'flex h-[52px] w-full items-center gap-3 rounded-lg border bg-[#fbfbfc] px-3 text-left transition',
          open
            ? 'border-[#b9bdc5] bg-white shadow-[0_0_0_2px_rgba(31,35,43,0.14)]'
            : 'border-[#e4e5e9] hover:border-[#cdd2dc]'
        )}
      >
        <span className="grid h-8 w-8 place-items-center rounded-md border border-[#eceef2] bg-white text-[#89909b]">
          <Video className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] text-[#707784]">视频设置</span>
          <span className="block truncate text-xs font-semibold text-[#242832]">
            {aspectRatio} · {resolution} · {duration}s
          </span>
        </span>
        <ChevronDown
          className={cn('h-4 w-4 text-[#9da3ad] transition-transform', open && 'rotate-180')}
        />
      </button>
    </div>
  );
}

function SettingsSectionLabel({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return <div className={cn('mb-1.5 text-[10px] font-medium text-[#707784]', className)}>{children}</div>;
}

function SelectionMark() {
  return (
    <span className="absolute right-1.5 top-1.5 grid h-4 w-4 place-items-center rounded-full bg-[#20242b] text-white">
      <Check className="h-2.5 w-2.5" strokeWidth={3} />
    </span>
  );
}

function RatioGlyph({ value }: { value: AspectRatio }) {
  const [width, height] = value.split(':').map(Number);
  return (
    <span
      className="block rounded-md border border-[#616975]"
      style={{
        width: `${Math.max(20, Math.min(34, 34 * (width / height)))}px`,
        height: `${Math.max(20, Math.min(34, 34 * (height / width)))}px`,
      }}
    />
  );
}
