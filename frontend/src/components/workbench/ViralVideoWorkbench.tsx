'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  ArrowLeft,
  AudioLines,
  Check,
  ChevronLeft,
  Download,
  Film,
  ListVideo,
  Plus,
  Scissors,
  Trash2,
  Upload,
  Video,
  X,
} from 'lucide-react';
import { useMediaPicker } from '@/contexts/MediaPickerContext';
import type { PickedMedia } from '@/components/common/MediaPickerDialog';

interface QueueTask {
  id: string;
  name: string;
  status: 'queued' | 'running';
}

interface Segment {
  id: string;
  name: string;
  sourceUrl: string;
}

export function ViralVideoWorkbench() {
  const { openMediaPicker } = useMediaPicker();
  const [videos, setVideos] = useState<PickedMedia[]>([]);
  const [segments, setSegments] = useState<Segment[]>([]);
  const [audio, setAudio] = useState<PickedMedia | null>(null);
  const [selectedResultIds, setSelectedResultIds] = useState<string[]>([]);
  const [tasks, setTasks] = useState<QueueTask[]>([]);

  function openVideoPicker() {
    openMediaPicker({
      initialTab: '视频',
      max: 3,
      title: '选择视频',
      subtitle: '上传或从资产库选择最多 3 条视频。',
      accept: 'video/*',
      onConfirm: (picked) => {
        const fresh = picked.filter((item) => item.type === 'video').slice(0, 3);
        setVideos(fresh);
        setSegments([]);
      },
    });
  }

  function openAudioPicker() {
    openMediaPicker({
      initialTab: '音频',
      max: 1,
      title: '选择音频',
      subtitle: '上传或从资产库选择一段音频。',
      accept: 'audio/*',
      onConfirm: (picked) => {
        const selected = picked.find((item) => item.type === 'audio');
        if (selected) setAudio(selected);
      },
    });
  }

  function splitVideos() {
    if (videos.length === 0) return;
    const nextSegments = videos.flatMap((video, videoIndex) =>
      [1, 2, 3].map((part) => ({
        id: `${video.id}-${part}`,
        name: `${video.name} · 片段 ${videoIndex * 3 + part}`,
        sourceUrl: video.url,
      }))
    );
    setSegments(nextSegments);
  }

  function addSegment() {
    if (videos.length === 0) {
      openVideoPicker();
      return;
    }
    const source = videos[segments.length % videos.length];
    const nextIndex = segments.length + 1;
    setSegments((current) => [
      ...current,
      {
        id: `${source.id}-manual-${nextIndex}`,
        name: `${source.name} · 片段 ${nextIndex}`,
        sourceUrl: source.url,
      },
    ]);
  }

  function resetWorkbench() {
    setVideos([]);
    setSegments([]);
    setAudio(null);
    setSelectedResultIds([]);
  }

  function startTask() {
    if (videos.length === 0) return;
    const task: QueueTask = {
      id: `${Date.now()}`,
      name: videos[0].name,
      status: 'queued',
    };
    setTasks((current) => [task, ...current]);
    window.setTimeout(() => {
      setTasks((current) =>
        current.map((item) => (item.id === task.id ? { ...item, status: 'running' } : item))
      );
    }, 500);
  }

  const queuedCount = tasks.filter((task) => task.status === 'queued').length;
  const runningCount = tasks.filter((task) => task.status === 'running').length;
  const canSubmit = videos.length > 0;

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
                <h1 className="text-sm font-semibold">爆款裂变</h1>
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

            <div className="flex flex-1 flex-col gap-5 px-3 pb-3">
              <StepSection index="1" title="上传视频">
                <button
                  type="button"
                  onClick={openVideoPicker}
                  className="flex h-[84px] w-full flex-col items-center justify-center rounded-lg border border-[#e4e5e9] bg-[#fbfbfc] text-[#737985] transition hover:border-[#9aaef8] hover:text-[#4f7cff]"
                >
                  <Upload className="h-5 w-5" strokeWidth={1.8} />
                  <span className="mt-2 text-xs font-semibold">支持 mp4 / mov / avi，最多 3 条</span>
                </button>
                {videos.length > 0 ? (
                  <div className="mt-2 space-y-1.5">
                    {videos.map((video) => (
                      <div key={video.id} className="flex items-center gap-2 rounded-lg border border-[#e4e5e9] bg-white px-2.5 py-1.5">
                        <Video className="h-3.5 w-3.5 flex-none text-[#7b8cff]" />
                        <span className="min-w-0 flex-1 truncate text-[11px] font-medium">{video.name}</span>
                        <button
                          type="button"
                          onClick={() => setVideos((current) => current.filter((item) => item.id !== video.id))}
                          className="grid h-5 w-5 place-items-center rounded text-[#9ca2ad] hover:bg-[#f3f4f6]"
                          aria-label={`移除 ${video.name}`}
                        >
                          <X className="h-3 w-3" />
                        </button>
                      </div>
                    ))}
                  </div>
                ) : null}
              </StepSection>

              <StepSection
                index="2"
                title="视频片段"
                actions={
                  <>
                    <button
                      type="button"
                      onClick={splitVideos}
                      disabled={!canSubmit}
                      className="inline-flex h-7 items-center gap-1 rounded-md border border-[#e4e5e9] px-2 text-[11px] transition hover:border-[#9aaef8] disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      <Scissors className="h-3 w-3" />
                      自动拆分
                    </button>
                    <button
                      type="button"
                      onClick={addSegment}
                      className="inline-flex h-7 items-center gap-1 rounded-md border border-[#e4e5e9] px-2 text-[11px] transition hover:border-[#9aaef8]"
                    >
                      <Plus className="h-3 w-3" />
                      添加片段
                    </button>
                  </>
                }
              >
                <div className="flex min-h-[112px] items-center justify-center rounded-lg border border-[#e4e5e9] bg-[#fbfbfc] px-3">
                  {segments.length > 0 ? (
                    <div className="grid w-full grid-cols-2 gap-1.5">
                      {segments.map((segment) => (
                        <div key={segment.id} className="flex items-center gap-1.5 rounded-md border border-[#e8ebf1] bg-white px-2 py-2 text-[10px]">
                          <Film className="h-3 w-3 flex-none text-[#7b8cff]" />
                          <span className="min-w-0 truncate">{segment.name}</span>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="text-center text-xs font-medium text-[#747b86]">
                      上传视频后，点击自动拆分或添加片段生成可参与重组的片段。
                    </p>
                  )}
                </div>
              </StepSection>

              <StepSection
                index="3"
                title="音频"
                actions={
                  <button
                    type="button"
                    onClick={openAudioPicker}
                    className="inline-flex h-7 items-center gap-1 rounded-md border border-[#e4e5e9] px-2 text-[11px] transition hover:border-[#9aaef8]"
                  >
                    <Plus className="h-3 w-3" />
                    上传音频
                  </button>
                }
              >
                <div className="flex min-h-[56px] items-center justify-center rounded-lg border border-[#e4e5e9] bg-[#fbfbfc] px-3">
                  {audio ? (
                    <div className="flex w-full items-center gap-2 rounded-md bg-white px-2.5 py-2 text-xs">
                      <AudioLines className="h-4 w-4 flex-none text-[#7b8cff]" />
                      <span className="min-w-0 flex-1 truncate font-medium">{audio.name}</span>
                      <button
                        type="button"
                        onClick={() => setAudio(null)}
                        className="grid h-5 w-5 place-items-center rounded text-[#9ca2ad] hover:bg-[#f3f4f6]"
                        aria-label="移除音频"
                      >
                        <X className="h-3 w-3" />
                      </button>
                    </div>
                  ) : (
                    <p className="text-center text-xs font-medium text-[#747b86]">
                      上传视频或音频后可选择音轨。
                    </p>
                  )}
                </div>
              </StepSection>
            </div>

            <div className="m-3">
              <button
                type="button"
                onClick={startTask}
                disabled={!canSubmit}
                className="flex h-10 w-full items-center justify-center gap-3 rounded-lg bg-[#20242b] text-sm font-semibold text-white transition hover:bg-[#111318] disabled:bg-[#8b8b8b]"
              >
                <ListVideo className="h-4 w-4" />
                开始裂变
                <span className="text-xs font-medium opacity-80">预计 -- 积分</span>
              </button>
            </div>
          </section>

          <section className="flex min-h-[720px] min-w-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
            <div className="flex h-14 items-start justify-between px-5 pt-4">
              <div>
                <h2 className="text-sm font-semibold">裂变视频</h2>
                <p className="mt-1 text-[11px] text-[#7a818c]">提交任务后，这里会展示裂变生成的视频。</p>
              </div>
              <div className="flex items-center gap-2">
                <ToolbarButton icon={<Check className="h-3.5 w-3.5" />} label="全选" disabled={segments.length === 0} onClick={() => setSelectedResultIds(segments.map((item) => item.id))} />
                <ToolbarButton icon={<Download className="h-3.5 w-3.5" />} label="下载" disabled={selectedResultIds.length === 0} />
                <ToolbarButton icon={<Trash2 className="h-3.5 w-3.5" />} label="删除" disabled={selectedResultIds.length === 0} onClick={() => setSelectedResultIds([])} />
              </div>
            </div>
            <div className="grid min-h-0 flex-1 place-items-center p-8">
              <div className="text-center text-[#747b86]">
                <Film className="mx-auto mb-6 h-10 w-10 stroke-[1.7]" />
                <p className="text-sm">暂无裂变视频</p>
              </div>
            </div>
            <div className="px-5 pb-4 text-xs text-[#747b86]">{selectedResultIds.length} 条结果</div>
          </section>

          <aside className="flex min-h-[720px] flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
            <div className="flex h-12 items-center justify-between px-4 text-xs font-semibold">
              <span>任务队列</span>
              <button type="button" className="text-[#9ca2ad]">
                收起
              </button>
            </div>
            <div className="mx-3 grid grid-cols-2 rounded-lg border border-[#e4e5e9] bg-white py-2 text-center">
              <div>
                <div className="text-[10px] text-[#9ca2ad]">排队中</div>
                <div className="text-xl font-semibold leading-6">{queuedCount}</div>
              </div>
              <div>
                <div className="text-[10px] text-[#9ca2ad]">生成中</div>
                <div className="text-xl font-semibold leading-6">{runningCount}</div>
              </div>
            </div>
            <div className="mt-3 space-y-2 px-3">
              {tasks.map((task) => (
                <div key={task.id} className="rounded-lg border border-[#e4e5e9] bg-white p-2">
                  <div className="flex items-center gap-1.5 text-[10px] text-[#747b86]">
                    <span className="h-1.5 w-1.5 rounded-full bg-[#4f7cff]" />
                    {task.status === 'queued' ? '排队中' : '生成中'}
                  </div>
                  <p className="mt-1 truncate text-xs font-medium">{task.name}</p>
                </div>
              ))}
            </div>
            <div className="mt-auto flex justify-center pb-4 text-[#a4aab5]">
              <ChevronLeft className="h-4 w-4" />
            </div>
          </aside>
        </div>
      </main>
    </div>
  );
}

function StepSection({
  index,
  title,
  actions,
  children,
}: {
  index: string;
  title: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-xs font-semibold">
          {index}. {title}
        </h2>
        <div className="flex items-center gap-1.5">{actions}</div>
      </div>
      {children}
    </section>
  );
}

function ToolbarButton({
  icon,
  label,
  disabled,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  disabled?: boolean;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-[#e4e5e9] px-3 text-[11px] text-[#707784] transition hover:border-[#cbd3e6] disabled:cursor-not-allowed disabled:opacity-45"
    >
      {icon}
      {label}
    </button>
  );
}
