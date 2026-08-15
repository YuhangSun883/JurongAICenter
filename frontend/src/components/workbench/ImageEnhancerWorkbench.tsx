'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  Check,
  ChevronDown,
  ChevronLeft,
  Film,
  Plus,
  Settings2,
  Sparkles,
  Upload,
  Video,
} from 'lucide-react';
import { useMediaPicker } from '@/contexts/MediaPickerContext';
import type { PickedMedia } from '@/components/common/MediaPickerDialog';
import { MediaPreviewDialog } from '@/components/common/MediaPreviewDialog';
import {
  submitEnhance,
  getEnhanceJob,
  type EnhancerJobResponse,
} from '@/api/enhancer.real';

type EnhancerVersion = '标准版' | '专业版';
type VideoSetting = '1080P · AIGC · 无' | '720P · AIGC · 无' | '1080P · 原画 · 有声';

interface QueueTask {
  id: string;
  name: string;
  status: 'queued' | 'running' | 'completed' | 'failed';
  /** 增强后的视频 URL（status=completed 时有值） */
  outputUrl?: string;
  /** 错误信息（status=failed 时有值） */
  errorMessage?: string;
  /** 源视频 URL（用于预览区回显） */
  sourceUrl?: string;
}

const VERSION_OPTIONS: EnhancerVersion[] = ['标准版', '专业版'];
const VIDEO_SETTING_OPTIONS: VideoSetting[] = [
  '1080P · AIGC · 无',
  '720P · AIGC · 无',
  '1080P · 原画 · 有声',
];

/** 终态:不再轮询 */
const TERMINAL_STATUSES = new Set(['completed', 'succeeded', 'success', 'failed', 'error', 'cancelled']);
/** 进行中:显示"生成中" */
const RUNNING_STATUSES = new Set(['running', 'processing', 'in_progress']);

export function ImageEnhancerWorkbench() {
  const { openMediaPicker } = useMediaPicker();
  const [selectedVideo, setSelectedVideo] = useState<PickedMedia | null>(null);
  const [version, setVersion] = useState<EnhancerVersion>('标准版');
  const [videoSetting, setVideoSetting] = useState<VideoSetting>('1080P · AIGC · 无');
  const [tasks, setTasks] = useState<QueueTask[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [errorBanner, setErrorBanner] = useState<string | null>(null);
  /** 预览素材(成片) */
  const [preview, setPreview] = useState<PickedMedia | null>(null);

  // 记录每个任务的轮询定时器,组件卸载或任务取消时清理
  const pollTimersRef = useRef<Map<string, number>>(new Map());

  useEffect(() => {
    // 组件卸载时清理所有轮询定时器,避免内存泄漏
    return () => {
      pollTimersRef.current.forEach((timerId) => window.clearTimeout(timerId));
      pollTimersRef.current.clear();
    };
  }, []);

  function openVideoPicker() {
    openMediaPicker({
      initialTab: '视频',
      max: 1,
      title: '选择视频',
      subtitle: '上传或从资产库选择一段视频。',
      accept: 'video/*',
      onConfirm: (picked) => {
        const video = picked.find((item) => item.type === 'video');
        if (video) setSelectedVideo(video);
      },
    });
  }

  function resetWorkbench() {
    setSelectedVideo(null);
    setVersion('标准版');
    setVideoSetting('1080P · AIGC · 无');
    setErrorBanner(null);
  }

  function updateTask(taskId: string, patch: Partial<QueueTask>) {
    setTasks((current) => current.map((t) => (t.id === taskId ? { ...t, ...patch } : t)));
  }

  function scheduleNextPoll(taskId: string) {
    // 轮询间隔 4 秒,与后端约定一致
    const timerId = window.setTimeout(() => pollOnce(taskId), 4000);
    pollTimersRef.current.set(taskId, timerId);
  }

  async function pollOnce(taskId: string) {
    pollTimersRef.current.delete(taskId);
    try {
      const job: EnhancerJobResponse = await getEnhanceJob(taskId);
      const status = (job.status || '').toLowerCase();

      if (TERMINAL_STATUSES.has(status)) {
        if (status === 'completed' || status === 'succeeded' || status === 'success') {
          updateTask(taskId, {
            status: 'completed',
            outputUrl: job.outputUrl,
          });
        } else {
          updateTask(taskId, {
            status: 'failed',
            errorMessage: job.errorMessage || `任务失败 (status=${job.status})`,
          });
        }
        return;
      }

      if (RUNNING_STATUSES.has(status)) {
        updateTask(taskId, { status: 'running' });
      } else {
        // queued / pending / 其它中间态都视为排队
        updateTask(taskId, { status: 'queued' });
      }
      // 继续轮询
      scheduleNextPoll(taskId);
    } catch (err) {
      console.error('[Enhancer] 轮询失败', taskId, err);
      const message = err instanceof Error ? err.message : String(err);
      updateTask(taskId, {
        status: 'failed',
        errorMessage: `轮询失败: ${message}`,
      });
    }
  }

  async function startTask() {
    if (!selectedVideo || submitting) return;
    setErrorBanner(null);

    // 临时 ID,提交成功后会用后端返回的 taskId 替换
    const tempId = `pending-${Date.now()}`;
    const initialTask: QueueTask = {
      id: tempId,
      name: selectedVideo.name,
      status: 'queued',
      sourceUrl: selectedVideo.url,
    };
    setTasks((current) => [initialTask, ...current]);
    setSubmitting(true);

    try {
      const resp = await submitEnhance({
        videoUrl: selectedVideo.url,
        version,
        setting: videoSetting,
      });
      if (!resp || !resp.taskId) {
        throw new Error('后端未返回 taskId');
      }
      // 用真实 taskId 替换 tempId
      setTasks((current) =>
        current.map((t) => (t.id === tempId ? { ...t, id: resp.taskId } : t)),
      );
      // 立即开始轮询
      scheduleNextPoll(resp.taskId);
    } catch (err) {
      console.error('[Enhancer] 提交失败', err);
      const message = err instanceof Error ? err.message : String(err);
      // 移除临时任务
      setTasks((current) => current.filter((t) => t.id !== tempId));
      setErrorBanner(`提交失败: ${message}`);
    } finally {
      setSubmitting(false);
    }
  }

  const queuedCount = tasks.filter((task) => task.status === 'queued').length;
  const runningCount = tasks.filter((task) => task.status === 'running').length;
  const completedCount = tasks.filter((task) => task.status === 'completed').length;
  const failedCount = tasks.filter((task) => task.status === 'failed').length;
  const latestOutput = tasks.find((task) => task.status === 'completed')?.outputUrl;

  return (
    <div className="min-h-screen bg-[#f7f7f8] pl-[72px] text-[#1d222b]">
      <main className="min-h-screen overflow-auto px-4 py-5 sm:px-6">
        <div className="grid min-h-[calc(100vh-40px)] gap-3 md:grid-cols-[minmax(420px,540px)_minmax(0,1fr)_200px]">
          <section className="flex min-h-[720px] flex-col rounded-xl border border-[#e4e5e9] bg-white">
            <div className="flex h-14 items-center justify-between px-4">
              <div className="flex items-center gap-3">
                <Link
                  href="/"
                  aria-label="返回首页"
                  className="grid h-8 w-8 place-items-center rounded-md transition hover:bg-[#f3f4f6]"
                >
                  <ArrowLeft className="h-4 w-4" />
                </Link>
                <h1 className="text-sm font-semibold">画质增强</h1>
              </div>
              <button
                type="button"
                onClick={resetWorkbench}
                className="inline-flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium transition hover:bg-[#f3f4f6]"
              >
                <Plus className="h-3.5 w-3.5" />
                新建
              </button>
            </div>

            <div className="mx-3 flex flex-1 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc] p-2">
              <div className="relative flex min-h-[390px] flex-1 items-center justify-center overflow-hidden rounded-lg border border-dashed border-[#e4e5e9] bg-[#fafafa]">
                {selectedVideo ? (
                  <div className="relative max-h-full w-full overflow-hidden rounded-lg bg-black">
                    <video src={selectedVideo.url} controls className="block max-h-[430px] w-full object-contain" />
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={openVideoPicker}
                    className="flex cursor-pointer flex-col items-center justify-center text-center"
                  >
                    <Upload className="h-9 w-9 text-[#20242b]" strokeWidth={1.8} />
                    <span className="mt-3 text-sm font-semibold">上传视频</span>
                    <span className="mt-2 max-w-[280px] text-xs leading-5 text-[#737985]">
                      支持 5 分钟以内的 mp4 / mov / avi 等格式
                    </span>
                  </button>
                )}
              </div>
            </div>

            <div className="mx-3 mt-2 rounded-xl border border-[#e4e5e9] bg-white p-3 text-xs text-[#5c6470]">
              <h2 className="font-semibold text-[#252a33]">操作指引</h2>
              <ol className="mt-3 space-y-2.5">
                <GuideItem index="1" icon={<Film className="h-3.5 w-3.5" />} title="上传视频">
                  点击画面中央上传，支持 5 分钟以内的 mp4 / mov / avi 等格式。
                </GuideItem>
                <GuideItem index="2" icon={<Video className="h-3.5 w-3.5" />} title="选择目标画质">
                  根据原视频分辨率选择更合适的输出规格。
                </GuideItem>
                <GuideItem index="3" icon={<Sparkles className="h-3.5 w-3.5" />} title="开始增强">
                  确认积分后提交，提升视频清晰度和画面质感。
                </GuideItem>
              </ol>
            </div>

            <div className="mx-3 mt-2 grid grid-cols-2 gap-2">
              <CompactSelect
                label="版本"
                value={version}
                options={VERSION_OPTIONS}
                icon={<Sparkles className="h-4 w-4" />}
                onChange={setVersion}
              />
              <CompactSelect
                label="视频设置"
                value={videoSetting}
                options={VIDEO_SETTING_OPTIONS}
                icon={<Settings2 className="h-4 w-4" />}
                onChange={setVideoSetting}
              />
            </div>

            <div className="m-3 space-y-2">
              {errorBanner ? (
                <div className="rounded-lg border border-[#f3c5c5] bg-[#fdecec] px-3 py-2 text-xs text-[#b03434]">
                  {errorBanner}
                </div>
              ) : null}
              <button
                type="button"
                disabled={!selectedVideo || submitting}
                onClick={startTask}
                className="flex h-10 w-full items-center justify-center gap-3 rounded-lg bg-[#20242b] text-sm font-semibold text-white transition hover:bg-[#111318] disabled:bg-[#8b8b8b]"
              >
                <Sparkles className="h-4 w-4" />
                {submitting ? '提交中…' : '开始增强'}
                <span className="text-xs font-medium opacity-80">预计 -- 积分</span>
              </button>
            </div>
          </section>

          <section className="flex min-h-[720px] min-w-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
            <div className="flex h-12 items-center gap-4 px-5 text-xs font-semibold">
              <span>预览</span>
              <button type="button" className="text-[#a4aab5]" aria-label="收起预览">
                <ChevronLeft className="h-4 w-4" />
              </button>
            </div>
            <div className="grid min-h-0 flex-1 place-items-center p-8">
              {latestOutput ? (
                <div className="flex w-full flex-col items-center gap-3">
                  <button
                    type="button"
                    onClick={() => setPreview({ id: 'latest-output', type: 'video', url: latestOutput, name: '增强后成片' })}
                    className="block cursor-pointer transition hover:opacity-90"
                    title="点击全屏预览"
                  >
                    <video
                      src={latestOutput}
                      controls
                      className="max-h-full max-w-full rounded-xl bg-black shadow-sm"
                    />
                  </button>
                  <a
                    href={latestOutput}
                    target="_blank"
                    rel="noreferrer"
                    className="text-[11px] text-[#4f7cff] hover:underline"
                  >
                    在新窗口打开
                  </a>
                </div>
              ) : selectedVideo ? (
                <video
                  src={selectedVideo.url}
                  controls
                  className="max-h-full max-w-full rounded-xl bg-black shadow-sm"
                />
              ) : (
                <div className="text-center text-[#747b86]">
                  <Video className="mx-auto mb-6 h-10 w-10 stroke-[1.7]" />
                  <p className="text-sm">处理完成后会在这里预览增强后的视频。</p>
                </div>
              )}
            </div>
          </section>

          <aside className="flex min-h-[720px] flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
            <div className="flex h-12 items-center justify-between px-4 text-xs font-semibold">
              <span>任务队列</span>
              <button type="button" className="text-[#9ca2ad]">
                收起
              </button>
            </div>
            <div className="mx-3 grid grid-cols-4 rounded-lg border border-[#e4e5e9] bg-white py-2 text-center">
              <div>
                <div className="text-[10px] text-[#9ca2ad]">排队中</div>
                <div className="text-base font-semibold leading-6">{queuedCount}</div>
              </div>
              <div>
                <div className="text-[10px] text-[#9ca2ad]">生成中</div>
                <div className="text-base font-semibold leading-6">{runningCount}</div>
              </div>
              <div>
                <div className="text-[10px] text-[#9ca2ad]">已完成</div>
                <div className="text-base font-semibold leading-6 text-[#18a06b]">{completedCount}</div>
              </div>
              <div>
                <div className="text-[10px] text-[#9ca2ad]">失败</div>
                <div className="text-base font-semibold leading-6 text-[#b03434]">{failedCount}</div>
              </div>
            </div>
            <div className="mt-3 space-y-2 px-3">
              {tasks.map((task) => (
                <TaskCard
                  key={task.id}
                  task={task}
                  onPreview={(m) => setPreview(m)}
                />
              ))}
            </div>
          </aside>
        </div>
      </main>

      {/* 素材预览弹窗(成片) */}
      <MediaPreviewDialog media={preview} onClose={() => setPreview(null)} />
    </div>
  );
}

function GuideItem({
  index,
  icon,
  title,
  children,
}: {
  index: string;
  icon: React.ReactNode;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <li className="flex gap-2.5">
      <span className="grid h-5 w-5 flex-none place-items-center rounded-full bg-[#f0f2f6] text-[10px] font-semibold text-[#4f5663]">
        {index}
      </span>
      <span className="min-w-0">
        <span className="flex items-center gap-1.5 font-semibold text-[#3f4652]">
          {icon}
          {title}
        </span>
        <span className="mt-0.5 block text-[10px] leading-4 text-[#8b929d]">{children}</span>
      </span>
    </li>
  );
}

function TaskCard({ task, onPreview }: { task: QueueTask; onPreview: (m: PickedMedia) => void }) {
  // 不同状态显示不同颜色的小圆点 + 文案
  const statusMeta: Record<QueueTask['status'], { label: string; dot: string; text: string }> = {
    queued: { label: '排队中', dot: 'bg-[#a4aab5]', text: 'text-[#747b86]' },
    running: { label: '生成中', dot: 'bg-[#4f7cff]', text: 'text-[#4f7cff]' },
    completed: { label: '已完成', dot: 'bg-[#18a06b]', text: 'text-[#18a06b]' },
    failed: { label: '失败', dot: 'bg-[#b03434]', text: 'text-[#b03434]' },
  };
  const meta = statusMeta[task.status];

  return (
    <div className="rounded-lg border border-[#e4e5e9] bg-white p-2">
      <div className={`flex items-center gap-1.5 text-[10px] ${meta.text}`}>
        <span className={`h-1.5 w-1.5 rounded-full ${meta.dot}`} />
        {meta.label}
      </div>
      <p className="mt-1 truncate text-xs font-medium text-[#1d222b]">{task.name}</p>
      {task.status === 'failed' && task.errorMessage ? (
        <p className="mt-1 line-clamp-2 text-[10px] text-[#b03434]">{task.errorMessage}</p>
      ) : null}
      {task.status === 'completed' && task.outputUrl ? (
        <button
          type="button"
          onClick={() => onPreview({ id: task.id, type: 'video', url: task.outputUrl!, name: task.name })}
          className="mt-1 block truncate text-left text-[10px] text-[#4f7cff] hover:underline"
          title="点击全屏预览成片"
        >
          预览成片 ▶
        </button>
      ) : null}
    </div>
  );
}

function CompactSelect<T extends string>({
  label,
  value,
  options,
  icon,
  onChange,
}: {
  label: string;
  value: T;
  options: readonly T[];
  icon: React.ReactNode;
  onChange: (value: T) => void;
}) {
  const [open, setOpen] = useState(false);

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="flex h-[52px] w-full items-center gap-2 rounded-lg border border-[#e4e5e9] bg-[#fbfbfc] px-3 text-left transition hover:border-[#cdd2dc]"
        aria-expanded={open}
      >
        <span className="grid h-8 w-8 place-items-center rounded-md border border-[#eceef2] bg-white text-[#89909b]">
          {icon}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] text-[#707784]">{label}</span>
          <span className="block truncate text-xs font-semibold text-[#242832]">{value}</span>
        </span>
        <ChevronDown className={`h-4 w-4 text-[#9da3ad] transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open ? (
        <div className="absolute bottom-full left-0 z-50 mb-2 w-full min-w-[220px] rounded-xl border border-[#dfe2e8] bg-[#fbfbfc] p-2 shadow-[0_12px_28px_rgba(29,35,48,0.12)]">
          <div className="px-1 pb-1.5 text-[11px] text-[#707784]">选择{label}</div>
          <div className="space-y-1">
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
                  className="flex w-full items-center gap-2 rounded-lg border border-[#e7e9ed] bg-white px-2.5 py-2 text-left text-xs transition hover:border-[#cbd3e6]"
                >
                  <span className="min-w-0 flex-1 truncate">{option}</span>
                  {selected ? (
                    <span className="grid h-4 w-4 place-items-center rounded-full bg-[#20242b] text-white">
                      <Check className="h-2.5 w-2.5" strokeWidth={3} />
                    </span>
                  ) : null}
                </button>
              );
            })}
          </div>
        </div>
      ) : null}
    </div>
  );
}
