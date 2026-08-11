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
  Minimize2,
  Package,
  PanelRightClose,
  Play,
  Plus,
  RefreshCw,
  Search,
  Sparkles,
  Star,
  Trash2,
  UserRound,
  Video,
  X,
  XCircle,
} from 'lucide-react';
import { nanoid } from 'nanoid';
import { useWorkbenchStore } from '@/store/workbench';
import { useTaskPolling } from '@/hooks/useTaskPolling';
import { videoApi } from '@/api/video';
import { mediaApi } from '@/api/media';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
import { cn } from '@/lib/utils';
import { promptApi, type UserPromptResult } from '@/api/prompt';
import { listFavorites, addFavorite, removeFavorite, isFavorited, type FavoriteVideo } from '@/lib/favorites-storage';
import type { AspectRatio, AudioMode, Duration, ReferenceMedia, Resolution, VideoModel, VideoTask } from '@/types/video';

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
  // 三个图标按钮状态
  const [showSavePopover, setShowSavePopover] = useState(false);
  const [savePromptName, setSavePromptName] = useState('');
  const [showPromptsDialog, setShowPromptsDialog] = useState(false);
  const [savedPrompts, setSavedPrompts] = useState<UserPromptResult[]>([]);
  const [promptSearch, setPromptSearch] = useState('');
  const [isExpanded, setIsExpanded] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [previewTab, setPreviewTab] = useState<'preview' | 'favorites'>('preview');
  const [favorites, setFavorites] = useState<FavoriteVideo[]>([]);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const savePopoverRef = useRef<HTMLDivElement>(null);
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

  /**
   * Agent 模块跳转过来的预填：
   *   URL 参数：
   *     prefill=true
   *     prompt=xxx
   *     attachmentIds=assetId1,assetId2
   *
   * 行为：
   *   1. 自动填入 prompt（即 script）
   *   2. 自动把 attachmentIds 加到 selectedReferences
   */
  const prefillAppliedRef = useRef(false);
  useEffect(() => {
    if (prefillAppliedRef.current) return;
    if (typeof window === 'undefined') return;
    const params = new URLSearchParams(window.location.search);
    if (params.get('prefill') !== 'true') {
      prefillAppliedRef.current = true;
      return;
    }
    // 1) 填 prompt
    const p = params.get('prompt');
    if (p) {
      useWorkbenchStore.getState().setScript(p);
    }
    // 2) 加素材到 references
    const idsStr = params.get('attachmentIds');
    if (idsStr) {
      const ids = idsStr.split(',').filter(Boolean);
      if (ids.length > 0) {
        (async () => {
          const picked: ReferenceMedia[] = [];
          for (const id of ids) {
            try {
              // getAsset 期望 number，但 URL 里来的是 string
              const numericId = Number(id);
              if (!Number.isFinite(numericId)) continue;
              const asset = await mediaApi.getAsset(numericId);
              if (asset) {
                picked.push({
                  id: String(asset.id),
                  url: asset.url,
                  type: (asset.type as 'image' | 'video' | 'audio') || 'image',
                  name: asset.name,
                  // ReferenceMedia 需要 token 字段，placeholder
                  token: '',
                });
              }
            } catch (e) {
              console.warn('[Workbench] failed to load prefill asset', id, e);
            }
          }
          if (picked.length > 0) {
            setSelectedReferences((prev) => [...prev, ...picked]);
            addMaterials(picked.map((p) => ({
              id: p.id,
              url: p.url,
              type: p.type,
              name: p.name,
            })));
          }
        })();
      }
    }
    // 清掉 URL 参数
    if (typeof window.history?.replaceState === 'function') {
      window.history.replaceState({}, '', window.location.pathname);
    }
    prefillAppliedRef.current = true;
  }, [addMaterials]);

  const selectedTask = tasks.find((task) => task.id === selectedTaskId) ?? null;
  const queued = tasks.filter((task) => task.status === 'queued').length;
  const running = tasks.filter((task) => task.status === 'running').length;
  const estimatedCredits = useMemo(() => {
    const base = duration * 2;
    return model.includes('VIP') ? Math.round(base * 1.5) : base;
  }, [duration, model]);

  const canSubmit = script.trim().length > 0 && !isSubmitting;
  const remainingReferenceSlots = Math.max(0, AI_VIDEO_MAX_REFS - selectedReferences.length);

  // ── 三个图标按钮的功能 ──

  /** 保存当前提示词 */
  function handleOpenSavePopover() {
    if (!script.trim()) return;
    setSavePromptName(script.trim().slice(0, 20));
    setShowSavePopover(true);
  }

  async function handleConfirmSave() {
    const name = savePromptName.trim();
    if (!name || !script.trim()) return;
    try {
      await promptApi.savePrompt({ title: name, prompt: script });
    } catch { /* 接口异常不阻塞UI */ }
    setShowSavePopover(false);
    setSavePromptName('');
  }

  /** 打开「我的提示词」 */
  async function handleOpenPromptsDialog() {
    setPromptSearch('');
    setShowPromptsDialog(true);
    try {
      setSavedPrompts(await promptApi.listPrompts());
    } catch {
      setSavedPrompts([]);
    }
  }

  function handleLoadPrompt(p: UserPromptResult) {
    setScript(p.prompt);
    promptApi.usePrompt(p.id).catch(() => {});
    setShowPromptsDialog(false);
  }

  async function handleDeletePrompt(id: number) {
    try {
      await promptApi.deletePrompt(id);
      setSavedPrompts((prev) => prev.filter((p) => p.id !== id));
    } catch { /* ignore */ }
  }

  /** 展开编辑器：Escape 键关闭 */
  useEffect(() => {
    if (!isExpanded) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setIsExpanded(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [isExpanded]);

  // 点击外部关闭保存 popover
  useEffect(() => {
    if (!showSavePopover) return;
    const onPointerDown = (e: PointerEvent) => {
      if (e.target instanceof Node && !savePopoverRef.current?.contains(e.target)) {
        setShowSavePopover(false);
      }
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [showSavePopover]);

  async function submit() {
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      // 有图片素材时走图生视频接口（POST /api/video/image-to-video）
      const imageRef = selectedReferences.find((r) => r.type === 'image');
      let taskId: string;

      if (imageRef) {
        const result = await videoApi.createImageToVideo(
          imageRef.url,
          script,
          duration,
          resolution
        );
        taskId = result.taskId;
      } else {
        const result = await videoApi.create({
          script,
          model,
          aspectRatio,
          resolution,
          duration,
          audioMode,
          referenceIds: selectedReferences.map((reference) => reference.id),
        });
        taskId = result.taskId;
      }

      // 立即添加一个占位任务（PENDING 状态，初始进度 5%），让用户看到反馈
      const placeholderTask: VideoTask = {
        id: taskId,
        status: 'queued',
        progress: 5,
        request: {
          script,
          model,
          aspectRatio,
          resolution,
          duration,
          audioMode,
          referenceIds: selectedReferences.map((reference) => reference.id),
        },
        estimatedCredits,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      useWorkbenchStore.getState().upsertTask(placeholderTask);
      selectTask(taskId);

      // 立即获取真实数据（可能仍是 PENDING，但会带 createdAt 用于进度模拟）
      const fresh = await videoApi.getTask(taskId);
      useWorkbenchStore.getState().upsertTask(fresh);
      setSubmitError(null);
    } catch (e) {
      setSubmitError(e instanceof Error ? e.message : '提交失败，请稍后重试');
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
      <div className={cn('grid h-full min-h-0 gap-3', sidebarCollapsed ? 'grid-cols-[minmax(420px,610px)_minmax(0,1fr)_52px]' : 'grid-cols-[minmax(420px,610px)_minmax(0,1fr)_100px]')}>
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
              <div className="relative flex items-center gap-3 text-[#a4aab5]" ref={savePopoverRef}>
                <span title="保存当前提示词" className="contents"><Package className="h-4 w-4 cursor-pointer hover:text-[#4d73ff]" onClick={handleOpenSavePopover} /></span>
                {showSavePopover && (
                  <div className="absolute right-0 top-6 z-50 w-[600px] rounded-xl border border-[#dfe2e8] bg-white p-4 shadow-[0_12px_28px_rgba(29,35,48,0.14)]">
                    <div className="mb-1.5 flex items-center justify-between text-[11px] font-medium text-[#707784]">
                      <span>设置标题</span>
                      <span className="font-normal text-[#a4aab5]">{savePromptName.length}/50</span>
                    </div>
                    <input
                      autoFocus
                      value={savePromptName}
                      onChange={(e) => setSavePromptName(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Escape') setShowSavePopover(false); }}
                      maxLength={50}
                      placeholder="输入提示词名称"
                      className="w-full rounded-lg border border-[#e4e5e9] bg-[#f9fafb] px-2.5 py-1.5 text-xs text-[#242832] outline-none focus:border-[#4d73ff] placeholder:text-[#b8bdc7]"
                    />
                    <div className="mb-1.5 mt-3 flex items-center justify-between text-[11px] font-medium text-[#707784]">
                      <span>提示词内容</span>
                      <span className="font-normal text-[#a4aab5]">{script.length}/10000</span>
                    </div>
                    <textarea
                      value={script}
                      readOnly
                      rows={12}
                      className="w-full resize-none rounded-lg border border-[#e4e5e9] bg-[#f9fafb] px-2.5 py-1.5 text-xs leading-relaxed text-[#737985] outline-none"
                    />
                    <div className="mt-3 flex items-center justify-end">
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => setShowSavePopover(false)}
                          className="rounded-md px-2.5 py-1 text-[11px] text-[#707784] hover:bg-[#f3f4f6]"
                        >
                          取消
                        </button>
                        <button
                          type="button"
                          onClick={handleConfirmSave}
                          disabled={!savePromptName.trim()}
                          className="rounded-md bg-[#1f232b] px-3 py-1 text-[11px] font-medium text-white hover:bg-[#111318] disabled:opacity-40"
                        >
                          保存
                        </button>
                      </div>
                    </div>
                  </div>
                )}
                <span title="我的提示词" className="contents"><List className="h-4 w-4 cursor-pointer hover:text-[#4d73ff]" onClick={handleOpenPromptsDialog} /></span>
                {isExpanded ? (
                  <span title="收起" className="contents"><Minimize2 className="h-4 w-4 cursor-pointer hover:text-[#4d73ff]" onClick={() => setIsExpanded(false)} /></span>
                ) : (
                  <span title="展开编辑" className="contents"><Maximize2 className="h-4 w-4 cursor-pointer hover:text-[#4d73ff]" onClick={() => setIsExpanded(true)} /></span>
                )}
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
            {submitError && (
              <div className="mb-2 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-600">
                <XCircle className="mt-0.5 h-3.5 w-3.5 flex-none" />
                <span>{submitError}</span>
              </div>
            )}
            <button
              type="button"
              disabled={!canSubmit}
              onClick={submit}
              className="flex h-10 w-full items-center justify-center gap-5 rounded-lg bg-[#1f232b] text-sm font-semibold text-white transition hover:bg-[#111318] disabled:bg-[#8b8b8b] disabled:hover:bg-[#8b8b8b]"
            >
              {isSubmitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              立即生成视频
              <span className="text-xs font-medium opacity-90">预计 {estimatedCredits} 积分</span>
            </button>
          </div>
        </section>

        <section className="flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
          <div className="flex h-12 items-center gap-7 px-5 text-xs font-semibold">
            <button
              onClick={() => setPreviewTab('preview')}
              className={cn(
                'relative h-12',
                previewTab === 'preview'
                  ? 'text-[#1d222b] after:absolute after:bottom-0 after:left-1/2 after:h-0.5 after:w-4 after:-translate-x-1/2 after:rounded-full after:bg-[#1d222b]'
                  : 'text-[#575e69]'
              )}
            >
              预览
            </button>
            <button
              onClick={() => { setFavorites(listFavorites()); setPreviewTab('favorites'); }}
              className={cn(
                'relative h-12',
                previewTab === 'favorites'
                  ? 'text-[#1d222b] after:absolute after:bottom-0 after:left-1/2 after:h-0.5 after:w-4 after:-translate-x-1/2 after:rounded-full after:bg-[#1d222b]'
                  : 'text-[#575e69]'
              )}
            >
              收藏
            </button>
          </div>

          {previewTab === 'preview' ? (
            <div className="flex min-h-0 flex-1 flex-col px-8 pb-8">
              <div className="grid flex-1 place-items-center">
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
                    {/* FAILED 时显示"补刀"按钮：检查 NewAPI 是否其实已完成 */}
                    {selectedTask.status === 'failed' && (
                      <button
                        onClick={async () => {
                          try {
                            const r: any = await videoApi.retry(selectedTask.id);
                            if (r?.recovered) {
                              // 补刀成功 → 重新加载任务状态
                              const fresh = await videoApi.getTask(selectedTask.id);
                              useWorkbenchStore.getState().upsertTask(fresh);
                            } else {
                              alert(`NewAPI 上任务尚未完成：${r?.reason ?? ''}`);
                              // 仍然刷新一下，可能状态变了
                              const fresh = await videoApi.getTask(selectedTask.id);
                              useWorkbenchStore.getState().upsertTask(fresh);
                            }
                          } catch (e) {
                            alert('补刀失败：' + (e instanceof Error ? e.message : String(e)));
                          }
                        }}
                        className="mt-4 inline-flex items-center gap-1.5 rounded-full bg-[#4f7cff] px-4 py-1.5 text-xs font-medium text-white hover:bg-[#3d6ce5]"
                      >
                        <RefreshCw className="h-3.5 w-3.5" /> 检查并补刀
                      </button>
                    )}
                  </div>
                ) : (
                  <div className="text-center text-[#747b86]">
                    <Video className="mx-auto mb-7 h-10 w-10 stroke-[1.7]" />
                    <p className="text-sm">点击右侧队列中带成片的任务即可预览。</p>
                  </div>
                )}
              </div>
              {selectedTask?.status === 'succeeded' && selectedTask.resultUrl && (
                <div className="flex justify-center pt-4">
                  <button
                    type="button"
                    onClick={() => {
                      if (isFavorited(selectedTask.id)) {
                        removeFavorite(selectedTask.id);
                      } else {
                        addFavorite({
                          id: `fav_${Date.now()}`,
                          taskId: selectedTask.id,
                          resultUrl: selectedTask.resultUrl!,
                          thumbnailUrl: selectedTask.thumbnailUrl,
                          script,
                          model,
                          duration,
                          createdAt: Date.now(),
                        });
                      }
                      setFavorites(listFavorites());
                    }}
                    className={cn(
                      'inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs transition',
                      isFavorited(selectedTask.id)
                        ? 'border-[#ffb700] bg-[#fff9eb] text-[#b8860b]'
                        : 'border-[#e4e5e9] bg-white text-[#707784] hover:border-[#ffb700] hover:text-[#b8860b]'
                    )}
                  >
                    <Star className={cn('h-3.5 w-3.5', isFavorited(selectedTask.id) && 'fill-[#ffb700] text-[#ffb700]')} />
                    {isFavorited(selectedTask.id) ? '已收藏' : '收藏'}
                  </button>
                </div>
              )}
            </div>
          ) : (
            <div className="flex-1 overflow-auto px-5 pb-4">
              {favorites.length === 0 ? (
                <div className="grid place-items-center py-14 text-center">
                  <Star className="mb-3 h-8 w-8 text-[#d0d4dc]" />
                  <p className="text-xs text-[#a4aab5]">暂无收藏的视频</p>
                  <p className="mt-1 text-[10px] text-[#c2c6cf]">生成视频后点击下方的收藏按钮即可保存</p>
                </div>
              ) : (
                <div className="grid grid-cols-2 gap-3">
                  {favorites.map((fav) => (
                    <div key={fav.id} className="group overflow-hidden rounded-xl border border-[#e7e9ed] bg-white shadow-sm">
                      <div className="relative aspect-video bg-black">
                        {fav.thumbnailUrl ? (
                          <img src={fav.thumbnailUrl} alt="" className="h-full w-full object-cover" />
                        ) : (
                          <video src={fav.resultUrl} className="h-full w-full object-cover" muted />
                        )}
                        <button
                          type="button"
                          onClick={() => {
                            removeFavorite(fav.taskId);
                            setFavorites(listFavorites());
                          }}
                          className="absolute right-2 top-2 grid h-6 w-6 place-items-center rounded-full bg-black/50 text-white opacity-0 transition group-hover:opacity-100 hover:bg-red-500"
                          title="取消收藏"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      </div>
                      <div className="p-2.5">
                        <div className="line-clamp-2 text-[11px] leading-relaxed text-[#3f4652]">{fav.script || '(空脚本)'}</div>
                        <div className="mt-1.5 flex items-center gap-2 text-[10px] text-[#a4aab5]">
                          <span>{fav.model}</span>
                          <span>·</span>
                          <span>{fav.duration}s</span>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </section>

        <aside className="flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc] py-4">
          {sidebarCollapsed ? (
            <button
              type="button"
              onClick={() => setSidebarCollapsed(false)}
              className="mx-auto grid h-7 w-7 place-items-center rounded-md text-[#a6abb4] hover:bg-[#e8ecf1] hover:text-[#3f4652]"
              title="展开侧栏"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          ) : (
            <div className="flex items-center justify-between px-3">
              <span className="text-xs font-semibold text-[#3f4652]">任务队列</span>
              <button
                type="button"
                onClick={() => setSidebarCollapsed(true)}
                className="grid h-6 w-6 place-items-center rounded-md text-[#a6abb4] hover:bg-[#e8ecf1] hover:text-[#3f4652]"
                title="收起侧栏"
              >
                <PanelRightClose className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
          {!sidebarCollapsed && (
            <>
              <div className="mt-5 flex gap-2 rounded-lg border border-[#eceef2] bg-white px-2.5 py-2.5 text-center shadow-sm">
                <div className="flex-1">
                  <div className="text-[10px] text-[#9ca2ad]">排队中</div>
                  <div className="text-base font-semibold leading-5">{queued}</div>
                </div>
                <div className="w-px bg-[#eceef2]" />
                <div className="flex-1">
                  <div className="text-[10px] text-[#9ca2ad]">生成中</div>
                  <div className="text-base font-semibold leading-5">{running}</div>
                </div>
              </div>
              <div className="mt-3 flex min-h-0 flex-1 flex-col items-center gap-2 overflow-auto px-1.5">
                {tasks.length === 0 ? (
                  <p className="pt-8 text-[10px] text-[#c2c6cf]">暂无任务</p>
                ) : (
                  tasks.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => selectTask(t.id)}
                      className={cn(
                        'relative w-[76px] flex-none overflow-hidden rounded-lg border shadow-sm transition',
                        selectedTaskId === t.id
                          ? 'border-[#4f7cff] ring-1 ring-[#4f7cff]/30'
                          : 'border-[#e7e9ed] hover:border-[#cbd3e6]'
                      )}
                    >
                      {t.status === 'succeeded' && t.thumbnailUrl ? (
                        <img src={t.thumbnailUrl} alt="" className="aspect-video w-full object-cover" />
                      ) : t.status === 'succeeded' && t.resultUrl ? (
                        <video src={t.resultUrl} className="aspect-video w-full object-cover" muted />
                      ) : (
                        <div className="flex aspect-video w-full flex-col items-center justify-center bg-[#f4f5f7] text-[#9ca2ad]">
                          <Loader2 className={cn('h-4 w-4', t.status === 'running' && 'animate-spin text-[#4f7cff]')} />
                          <span className="mt-0.5 text-[9px] text-[#a4aab5]">
                            {t.status === 'queued' ? '排队' : t.status === 'running' ? `${Math.round(t.progress)}%` : '失败'}
                          </span>
                        </div>
                      )}
                      {t.status === 'running' && (
                        <div className="absolute bottom-0 left-0 h-0.5 bg-[#4f7cff]" style={{ width: `${Math.min(100, t.progress)}%` }} />
                      )}
                    </button>
                  ))
                )}
              </div>
              <div className="mt-auto pb-2" />
            </>
          )}
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

      {/* 我的提示词弹窗 */}
      {showPromptsDialog && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/30" onClick={() => setShowPromptsDialog(false)}>
          <div className="flex max-h-[480px] w-[400px] max-w-[90vw] flex-col rounded-2xl border border-[#e4e5e9] bg-white shadow-[0_20px_50px_rgba(0,0,0,0.18)]" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between border-b border-[#eff0f3] px-5 py-3.5">
              <h3 className="text-sm font-semibold text-[#1d222b]">我的提示词</h3>
              <button type="button" onClick={() => setShowPromptsDialog(false)} className="grid h-7 w-7 place-items-center rounded-md text-[#a4aab5] hover:bg-[#f3f4f6] hover:text-[#3f4652]"><X className="h-4 w-4" /></button>
            </div>
            <div className="flex items-center gap-2 border-b border-[#eff0f3] px-5 py-2.5">
              <Search className="h-3.5 w-3.5 text-[#a4aab5]" />
              <input value={promptSearch} onChange={(e) => setPromptSearch(e.target.value)} placeholder="搜索已保存的提示词..." className="flex-1 bg-transparent text-xs text-[#242832] outline-none placeholder:text-[#b8bdc7]" />
            </div>
            <div className="flex-1 overflow-auto">
              {savedPrompts.length === 0 ? (
                <div className="grid place-items-center px-4 py-14 text-xs text-[#a4aab5]">暂无保存的提示词</div>
              ) : (() => {
                const filtered = promptSearch.trim() ? savedPrompts.filter((p) => p.prompt.includes(promptSearch.trim())) : savedPrompts;
                if (filtered.length === 0) return <div className="grid place-items-center px-4 py-14 text-xs text-[#a4aab5]">没有匹配的提示词</div>;
                return filtered.map((p) => (
                  <div key={p.id} className="group flex items-start gap-3 border-b border-[#f3f4f6] px-5 py-3 last:border-b-0 hover:bg-[#f8f9fb]">
                    <div className="min-w-0 flex-1 cursor-pointer" onClick={() => handleLoadPrompt(p)}>
                      <div className="text-xs font-medium text-[#242832]">{p.title || p.prompt.slice(0, 20)}</div>
                      <div className="mt-0.5 line-clamp-2 text-[11px] leading-relaxed text-[#9ca2ad]">{p.prompt}</div>
                      <div className="mt-1 flex items-center gap-2 text-[10px] text-[#c2c6cf]">
                        <span>{new Date(p.createdAt).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>
                        <span>使用 {p.useCount} 次</span>
                      </div>
                    </div>
                    <button type="button" onClick={() => handleDeletePrompt(p.id)} className="mt-0.5 grid h-6 w-6 flex-none place-items-center rounded opacity-0 transition group-hover:opacity-100 hover:bg-[#fef0ef] hover:text-[#e5484d]" title="删除"><Trash2 className="h-3.5 w-3.5" /></button>
                  </div>
                ));
              })()}
            </div>
            <div className="border-t border-[#eff0f3] px-5 py-2 text-[10px] text-[#c2c6cf]">共 {savedPrompts.length} 条 · 点击条目加载到编辑器</div>
          </div>
        </div>
      )}

      {/* 展开编辑全屏 */}
      {isExpanded && (
        <div className="fixed inset-0 z-[100] flex flex-col bg-[#f7f7f8]">
          <div className="flex h-14 items-center justify-between border-b border-[#e4e5e9] bg-white px-5">
            <h2 className="text-sm font-semibold text-[#1d222b]">编辑提示词</h2>
            <div className="flex items-center gap-3">
              <span className="text-xs text-[#a4aab5]">{script.length} / 10000</span>
              <button type="button" onClick={() => setIsExpanded(false)} className="inline-flex items-center gap-1.5 rounded-lg border border-[#e4e5e9] bg-white px-3 py-1.5 text-xs text-[#707784] hover:bg-[#f3f4f6]"><Minimize2 className="h-3.5 w-3.5" /> 收起</button>
            </div>
          </div>
          <div className="flex flex-1 justify-center overflow-hidden px-6 py-6">
            <textarea autoFocus value={script} onChange={(e) => setScript(e.target.value)} maxLength={10000} placeholder="输入视频脚本，描述你想要的画面、节奏、风格..." className="h-full w-full max-w-[900px] resize-none rounded-xl border border-[#e4e5e9] bg-white p-6 text-sm leading-7 text-[#242832] shadow-sm outline-none placeholder:text-[#b8bdc7] focus:border-[#c4c9d4]" />
          </div>
        </div>
      )}
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
