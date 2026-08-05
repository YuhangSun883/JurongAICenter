'use client';

import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Bot,
  Check,
  ChevronDown,
  ChevronRight,
  Coins,
  Image as ImageIcon,
  Layers3,
  List,
  Loader2,
  Maximize2,
  Menu,
  PanelRightClose,
  Play,
  Plus,
  Sparkles,
  Trash2,
} from 'lucide-react';
import { nanoid } from 'nanoid';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
import { cn } from '@/lib/utils';

const MAX_REFS = 9;
const MAX_PROMPT = 2000;

type ImageModel = '高级版 VIP' | '高级版' | '标准版';
type ImageRatio = '自适应' | '1:1' | '3:4' | '4:3' | '9:16' | '16:9';
type ImageResolution = '1K' | '2K' | '4K';
type ImageFormat = 'JPEG' | 'PNG';

const MODELS: ImageModel[] = ['高级版 VIP', '高级版', '标准版'];
const RATIOS: ImageRatio[] = ['自适应', '1:1', '3:4', '4:3', '9:16', '16:9'];
const RESOLUTIONS: ImageResolution[] = ['1K', '2K', '4K'];
const FORMATS: ImageFormat[] = ['JPEG', 'PNG'];

interface ImageTask {
  id: string;
  status: 'queued' | 'running' | 'succeeded' | 'failed';
  progress: number;
  prompt: string;
  referenceIds: string[];
  createdAt: number;
}

function mediaToPicked(file: File): GlobalMaterial {
  return {
    id: nanoid(10),
    type: file.type.startsWith('video') ? 'video' : file.type.startsWith('audio') ? 'audio' : 'image',
    url: URL.createObjectURL(file),
    name: file.name,
    size: file.size,
  };
}

export function ImageWorkbench() {
  const { materials, addMaterials, removeMaterial } = useMaterials();
  const [prompt, setPrompt] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [references, setReferences] = useState<PickedMedia[]>([]);
  const [model, setModel] = useState<ImageModel>('高级版 VIP');
  const [ratio, setRatio] = useState<ImageRatio>('自适应');
  const [resolution, setResolution] = useState<ImageResolution>('1K');
  const [format, setFormat] = useState<ImageFormat>('JPEG');
  const [tasks, setTasks] = useState<ImageTask[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const remainingRefs = Math.max(0, MAX_REFS - references.length);
  const queued = tasks.filter((task) => task.status === 'queued').length;
  const running = tasks.filter((task) => task.status === 'running').length;
  const selectedTask = tasks.find((task) => task.id === selectedTaskId) ?? null;
  const estimatedCredits = useMemo(() => (model.includes('VIP') ? 1.18 : model === '高级版' ? 0.88 : 0.58), [model]);
  const canSubmit = prompt.trim().length > 0 && !submitting;

  function handleUploadFiles(files: FileList | null): PickedMedia[] {
    if (!files) return [];
    const existingFingerprints = new Set(materials.map((material) => `${material.name}_${material.size ?? material.url.length}`));
    const fresh: GlobalMaterial[] = [];

    Array.from(files).forEach((file) => {
      const fingerprint = `${file.name}_${file.size}`;
      if (existingFingerprints.has(fingerprint)) return;
      existingFingerprints.add(fingerprint);
      fresh.push(mediaToPicked(file));
    });

    addMaterials(fresh);
    return fresh;
  }

  function handleConfirmPicked(picked: PickedMedia[]) {
    setReferences((current) => {
      const existingIds = new Set(current.map((item) => item.id));
      const existingFingerprints = new Set(current.map((item) => `${item.name}_${item.url.length}`));
      const fresh = picked.filter((item) => {
        const fingerprint = `${item.name}_${item.url.length}`;
        if (existingIds.has(item.id) || existingFingerprints.has(fingerprint)) return false;
        existingIds.add(item.id);
        existingFingerprints.add(fingerprint);
        return true;
      });
      return [...current, ...fresh].slice(0, MAX_REFS);
    });
  }

  function submit() {
    if (!canSubmit) return;
    setSubmitting(true);
    const task: ImageTask = {
      id: `img_${nanoid(8)}`,
      status: 'queued',
      progress: 0,
      prompt,
      referenceIds: references.map((reference) => reference.id),
      createdAt: Date.now(),
    };
    setTasks((current) => [task, ...current]);
    setSelectedTaskId(task.id);
    window.setTimeout(() => setSubmitting(false), 300);
  }

  return (
    <main className="h-screen overflow-hidden bg-[#f7f7f8] px-4 py-5 text-[#16181d] sm:px-6">
      <div className="grid h-full min-h-0 grid-cols-[minmax(420px,610px)_minmax(0,1fr)_76px] gap-3">
        <section className="flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-white">
          <div className="flex h-14 items-center justify-between px-4">
            <h1 className="text-sm font-semibold">AI 生图</h1>
            <button
              type="button"
              onClick={() => {
                setPrompt('');
                setReferences([]);
                setSelectedTaskId(null);
              }}
              className="inline-flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium hover:bg-[#f3f4f6]"
            >
              <Plus className="h-3.5 w-3.5" />
              新建
            </button>
          </div>

          <div className="mx-3 flex min-h-0 flex-1 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
            <div className="flex items-center justify-between px-4 pt-4 text-xs text-[#737985]">
              <span>{references.length} / {MAX_REFS}</span>
              <div className="flex items-center gap-3 text-[#a4aab5]">
                <ImageIcon className="h-4 w-4" />
                <List className="h-4 w-4" />
                <Maximize2 className="h-4 w-4" />
              </div>
            </div>

            <div className="mt-4 flex min-h-[92px] items-start gap-3 px-4">
              {references.length > 0 && (
                <div className="flex min-w-0 flex-1 gap-2 overflow-x-auto pb-1">
                  {references.map((reference, index) => (
                    <div
                      key={reference.id}
                      className="group/ref relative h-[60px] w-[60px] flex-none overflow-hidden rounded-lg bg-white shadow-[0_8px_20px_rgba(21,25,36,0.06)] ring-1 ring-[#eff0f3]"
                      title={reference.name}
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={reference.url} alt={reference.name} className="h-full w-full object-cover" />
                      <span className="absolute left-1 top-1 grid h-4 min-w-4 place-items-center rounded-full bg-black/65 px-1 text-[10px] font-semibold leading-4 text-white">
                        {index + 1}
                      </span>
                      <button
                        type="button"
                        onClick={() => setReferences((current) => current.filter((item) => item.id !== reference.id))}
                        className="absolute right-1 top-1 hidden h-4 w-4 place-items-center rounded-full bg-black/55 text-[10px] leading-none text-white group-hover/ref:grid"
                        aria-label={`移除 ${reference.name}`}
                      >
                        x
                      </button>
                    </div>
                  ))}
                </div>
              )}
              <AddMaterialCard
                disabled={remainingRefs === 0}
                onClick={() => setPickerOpen(true)}
                label="点击添加"
                className="ml-auto h-[72px] w-[72px] flex-none rounded-lg border-[#eceef2] bg-[#f9fafb] text-[10px] text-[#747b87] hover:border-[#d7defc] hover:text-[#4d73ff]"
                iconClassName="h-5 w-5"
                labelClassName="mt-1 text-[10px]"
              />
            </div>

            <div className="mt-2 px-4 text-sm text-[#6e7580]">
              请输入图片内容，输入 / 选择技能包，输入 @ 引用上方参考图片。
            </div>

            <div className="relative mt-1 flex min-h-0 flex-1 px-4 pb-4">
              <textarea
                value={prompt}
                onChange={(event) => setPrompt(event.target.value)}
                maxLength={MAX_PROMPT}
                className="h-full min-h-[420px] w-full resize-none bg-transparent pb-8 pt-2 text-sm leading-7 text-[#242832] outline-none placeholder:text-[#b8bdc7]"
              />
              <div className="pointer-events-none absolute bottom-5 left-5 text-xs text-[#555b66]">
                {prompt.length} / {MAX_PROMPT}
              </div>
            </div>
          </div>

          <div className="mx-3 mt-2 grid grid-cols-2 gap-2">
            <SelectCard
              icon={<Sparkles className="h-4 w-4" />}
              label="模型"
              value={model}
              subValue={model.includes('VIP') ? '更快更稳' : undefined}
              options={MODELS}
              onChange={(value) => setModel(value as ImageModel)}
            />
            <ImageSettingsCard
              ratio={ratio}
              setRatio={setRatio}
              resolution={resolution}
              setResolution={setResolution}
              format={format}
              setFormat={setFormat}
            />
          </div>

          <div className="m-3 mt-2">
            <button
              type="button"
              disabled={!canSubmit}
              onClick={submit}
              className="flex h-10 w-full items-center justify-center gap-5 rounded-lg bg-[#8f8f92] text-sm font-semibold text-white transition hover:bg-[#797a7d] disabled:bg-[#8b8b8b] disabled:hover:bg-[#8b8b8b]"
            >
              {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              立即生成图片
              <span className="text-xs font-medium opacity-90">预计 {estimatedCredits.toFixed(2)} 积分</span>
            </button>
          </div>
        </section>

        <section className="flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
          <div className="flex h-12 items-center gap-7 px-5 text-xs font-semibold">
            <button className="h-12 text-[#575e69]">预览</button>
            <button className="relative h-12 text-[#1d222b] after:absolute after:bottom-0 after:left-1/2 after:h-0.5 after:w-4 after:-translate-x-1/2 after:rounded-full after:bg-[#1d222b]">
              收藏
            </button>
          </div>

          <div className="grid flex-1 place-items-center px-8 pb-8">
            {selectedTask ? (
              <div className="w-full max-w-[520px] text-center">
                <div className="mx-auto mb-5 grid h-16 w-16 place-items-center rounded-full bg-white shadow-sm">
                  <ImageIcon className="h-8 w-8 text-[#757b86]" />
                </div>
                <div className="text-sm font-medium text-[#303642]">
                  {selectedTask.status === 'queued' ? '排队中' : '生成中'}
                  <span className="ml-2 text-[#6f7682]">{selectedTask.progress}%</span>
                </div>
              </div>
            ) : (
              <div className="text-center text-[#747b86]">
                <ImageIcon className="mx-auto mb-7 h-12 w-12 stroke-[1.5] text-[#c3c7cf]" />
                <p className="text-sm">还没有收藏的图片任务。收藏右侧任务后会出现在这里。</p>
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
          <button
            type="button"
            className="mt-6 grid h-10 w-10 place-items-center rounded-lg border border-[#cfd3dc] bg-[#eef0f4] text-[#8c929e]"
            aria-label="编辑"
          >
            <Layers3 className="h-4 w-4" />
          </button>
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
          setReferences((current) => current.filter((reference) => reference.id !== id));
        }}
        uploadedFiles={materials.filter((material) => material.type === 'image')}
        showMockAssets={false}
        max={remainingRefs}
      />
    </main>
  );
}

export function ImageSidebarBottom() {
  return (
    <>
      <button
        type="button"
        className="flex w-12 flex-col items-center gap-0.5 rounded-xl py-1.5 text-fg-muted hover:text-brand"
        title="积分"
      >
        <span className="grid h-9 w-9 place-items-center rounded-xl border border-bg-line bg-bg-soft">
          <Coins className="h-4 w-4 text-brand" />
        </span>
        <span className="text-[10px]">0</span>
      </button>
      <button
        type="button"
        className="grid h-9 w-9 place-items-center rounded-full bg-brand-50 text-brand shadow-soft"
        title="助手"
      >
        <Bot className="h-4 w-4" />
      </button>
      <button
        type="button"
        className="grid h-9 w-9 place-items-center rounded-xl text-fg-muted hover:bg-bg-soft hover:text-fg"
        title="菜单"
      >
        <Menu className="h-4 w-4" />
      </button>
    </>
  );
}

function SelectCard<T extends string>({
  icon,
  label,
  value,
  subValue,
  options,
  onChange,
}: {
  icon: ReactNode;
  label: string;
  value: T;
  subValue?: string;
  options: readonly T[];
  onChange: (value: T) => void;
}) {
  const [open, setOpen] = useState(false);

  return (
    <div className="relative">
      {open && (
        <div className="absolute bottom-full left-0 z-50 mb-2 w-full min-w-[260px] rounded-xl border border-[#dfe2e8] bg-[#fbfbfc] p-2 shadow-[0_12px_28px_rgba(29,35,48,0.12)]">
          {options.map((option) => {
            const selected = option === value;
            return (
              <button
                key={option}
                type="button"
                onClick={() => {
                  onChange(option);
                  setOpen(false);
                }}
                className={cn(
                  'flex w-full items-center justify-between rounded-lg px-2 py-2 text-left text-xs transition',
                  selected ? 'bg-white font-semibold text-[#20242b] shadow-sm' : 'text-[#68707c] hover:bg-white'
                )}
              >
                {option}
                {selected && <Check className="h-3.5 w-3.5" />}
              </button>
            );
          })}
        </div>
      )}
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="flex h-[52px] w-full items-center gap-3 rounded-lg border border-[#e4e5e9] bg-[#fbfbfc] px-3 text-left transition hover:border-[#cdd2dc]"
      >
        <span className="grid h-8 w-8 place-items-center rounded-md border border-[#eceef2] bg-white text-[#89909b]">
          {icon}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] text-[#707784]">{label}</span>
          <span className="block truncate text-xs font-semibold text-[#242832]">
            {value}
            {subValue && <span className="ml-1 text-[10px] font-medium text-[#707784]">{subValue}</span>}
          </span>
        </span>
        <ChevronDown className={cn('h-4 w-4 text-[#9da3ad] transition-transform', open && 'rotate-180')} />
      </button>
    </div>
  );
}

function ImageSettingsCard({
  ratio,
  setRatio,
  resolution,
  setResolution,
  format,
  setFormat,
}: {
  ratio: ImageRatio;
  setRatio: (value: ImageRatio) => void;
  resolution: ImageResolution;
  setResolution: (value: ImageResolution) => void;
  format: ImageFormat;
  setFormat: (value: ImageFormat) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      {open && (
        <div className="absolute bottom-full left-0 z-50 mb-2 w-full min-w-[360px] rounded-xl border border-[#dfe2e8] bg-[#fbfbfc] p-3 shadow-[0_12px_28px_rgba(29,35,48,0.12)]">
          <SettingLabel>图片比例</SettingLabel>
          <div className="grid grid-cols-6 gap-2">
            {RATIOS.map((option) => (
              <OptionButton key={option} selected={option === ratio} onClick={() => setRatio(option)}>
                {option}
              </OptionButton>
            ))}
          </div>
          <SettingLabel className="mt-3">清晰度</SettingLabel>
          <div className="flex gap-2">
            {RESOLUTIONS.map((option) => (
              <OptionButton key={option} selected={option === resolution} onClick={() => setResolution(option)}>
                {option}
              </OptionButton>
            ))}
          </div>
          <SettingLabel className="mt-3">格式</SettingLabel>
          <div className="flex gap-2">
            {FORMATS.map((option) => (
              <OptionButton key={option} selected={option === format} onClick={() => setFormat(option)}>
                {option}
              </OptionButton>
            ))}
          </div>
        </div>
      )}
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="flex h-[52px] w-full items-center gap-3 rounded-lg border border-[#e4e5e9] bg-[#fbfbfc] px-3 text-left transition hover:border-[#cdd2dc]"
      >
        <span className="grid h-8 w-8 place-items-center rounded-md border border-[#eceef2] bg-white text-[#89909b]">
          <Layers3 className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] text-[#707784]">图片设置</span>
          <span className="block truncate text-xs font-semibold text-[#242832]">
            {ratio} · {resolution} · {format}
          </span>
        </span>
        <ChevronDown className={cn('h-4 w-4 text-[#9da3ad] transition-transform', open && 'rotate-180')} />
      </button>
    </div>
  );
}

function SettingLabel({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('mb-1.5 text-[10px] font-medium text-[#707784]', className)}>{children}</div>;
}

function OptionButton({
  selected,
  onClick,
  children,
}: {
  selected: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'relative flex h-8 flex-1 items-center justify-center rounded-lg border bg-white px-2 text-[11px] transition',
        selected
          ? 'border-[#20242b] font-semibold text-[#20242b]'
          : 'border-[#e7e9ed] text-[#68707c] hover:border-[#cbd3e6]'
      )}
    >
      {children}
    </button>
  );
}
