'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  ArrowLeft,
  ChevronLeft,
  Film,
  Move,
  Plus,
  Sparkles,
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

export function SubtitleRemoverWorkbench() {
  const { openMediaPicker } = useMediaPicker();
  const [selectedVideo, setSelectedVideo] = useState<PickedMedia | null>(null);
  const [subtitleArea, setSubtitleArea] = useState({ x: 17, y: 78, width: 66, height: 12 });
  const [tasks, setTasks] = useState<QueueTask[]>([]);

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
    setSubtitleArea({ x: 17, y: 78, width: 66, height: 12 });
  }

  function startTask() {
    if (!selectedVideo) return;

    const task: QueueTask = {
      id: `${Date.now()}`,
      name: selectedVideo.name,
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

  return (
    <div className="min-h-screen bg-[#f7f7f8] pl-[72px] text-[#1d222b]">
      <main className="min-h-screen overflow-auto px-4 py-5 sm:px-6">
        <div className="grid min-h-[calc(100vh-40px)] gap-3 md:grid-cols-[minmax(420px,526px)_minmax(0,1fr)_200px]">
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
                <h1 className="text-sm font-semibold">视频字幕擦除</h1>
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
                    <button
                      type="button"
                      aria-label="调整字幕区域"
                      className="absolute rounded border-2 border-[#4f7cff] bg-[#4f7cff]/15"
                      style={{
                        left: `${subtitleArea.x}%`,
                        top: `${subtitleArea.y}%`,
                        width: `${subtitleArea.width}%`,
                        height: `${subtitleArea.height}%`,
                      }}
                    >
                      <span className="absolute -top-6 left-0 whitespace-nowrap rounded bg-[#4f7cff] px-1.5 py-0.5 text-[10px] text-white">
                        字幕区域
                      </span>
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={openVideoPicker}
                    className="flex cursor-pointer flex-col items-center justify-center text-center"
                  >
                    <Upload className="h-9 w-9 text-[#20242b]" strokeWidth={1.8} />
                    <span className="mt-3 text-sm font-semibold">上传视频</span>
                    <span className="mt-2 max-w-[260px] text-xs leading-5 text-[#737985]">
                      支持 30 秒以内的 mp4 / mov / avi 等格式
                    </span>
                  </button>
                )}
              </div>

              <div className="mt-2 flex min-h-[70px] items-center gap-2 rounded-xl border border-[#e4e5e9] bg-white px-3 py-2">
                {selectedVideo ? (
                  <div className="relative flex h-12 min-w-[150px] items-center gap-2 rounded-lg border border-[#4f7cff] bg-[#f5f7ff] px-2">
                    <Video className="h-4 w-4 text-[#4f7cff]" />
                    <span className="min-w-0 truncate text-xs font-semibold">{selectedVideo.name}</span>
                    <button
                      type="button"
                      onClick={resetWorkbench}
                      className="ml-auto grid h-6 w-6 place-items-center rounded-md text-[#747b86] hover:bg-[#e7eaff]"
                      aria-label="移除视频"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={openVideoPicker}
                    className="flex h-12 min-w-[150px] items-center gap-2 rounded-lg border border-dashed border-[#dfe2e8] px-3 text-xs text-[#8b929d] transition hover:border-[#9aaef8] hover:text-[#4f7cff]"
                  >
                    <Plus className="h-4 w-4" />
                    添加视频
                  </button>
                )}
                <div className="ml-auto flex items-center gap-1.5 text-[11px] text-[#9ca2ad]">
                  <Move className="h-3.5 w-3.5" />
                  可拖动调整字幕区域
                </div>
              </div>
            </div>

            <div className="mx-3 mt-2 rounded-xl border border-[#e4e5e9] bg-white p-3 text-xs text-[#5c6470]">
              <h2 className="font-semibold text-[#252a33]">操作指引</h2>
              <ol className="mt-3 space-y-2.5">
                <GuideItem index="1" icon={<Film className="h-3.5 w-3.5" />} title="上传视频">
                  点击画面中央上传，支持常见视频格式。
                </GuideItem>
                <GuideItem index="2" icon={<Sparkles className="h-3.5 w-3.5" />} title="框选字幕">
                  在视频预览中框选需要擦除的字幕区域。
                </GuideItem>
                <GuideItem index="3" icon={<Move className="h-3.5 w-3.5" />} title="微调区域">
                  拖动字幕框位置和大小，覆盖完整字幕内容。
                </GuideItem>
                <GuideItem index="4" icon={<Sparkles className="h-3.5 w-3.5" />} title="开始擦除">
                  确认视频和区域后，提交任务进行处理。
                </GuideItem>
              </ol>
            </div>

            <div className="m-3">
              <button
                type="button"
                disabled={!selectedVideo}
                onClick={startTask}
                className="flex h-10 w-full items-center justify-center gap-3 rounded-lg bg-[#20242b] text-sm font-semibold text-white transition hover:bg-[#111318] disabled:bg-[#8b8b8b]"
              >
                <Sparkles className="h-4 w-4" />
                开始擦除
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
              {selectedVideo ? (
                <video src={selectedVideo.url} controls className="max-h-full max-w-full rounded-xl bg-black shadow-sm" />
              ) : (
                <div className="text-center text-[#747b86]">
                  <Video className="mx-auto mb-6 h-10 w-10 stroke-[1.7]" />
                  <p className="text-sm">处理完成后会在这里预览擦除字幕后的视频。</p>
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
          </aside>
        </div>
      </main>
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
