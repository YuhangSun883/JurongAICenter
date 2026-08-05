'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  ArrowLeft,
  ChevronLeft,
  Eraser,
  Film,
  Plus,
  Sparkles,
  Upload,
  Video,
  X,
} from 'lucide-react';
import { useMediaPicker } from '@/contexts/MediaPickerContext';
import type { PickedMedia } from '@/components/common/MediaPickerDialog';

interface WatermarkBox {
  id: string;
  label: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

interface QueueTask {
  id: string;
  name: string;
  status: 'queued' | 'running';
}

const DEFAULT_BOX: WatermarkBox = {
  id: 'watermark-1',
  label: '水印区 1',
  x: 78.4,
  y: 14,
  width: 14,
  height: 12,
};

export function WatermarkRemoverWorkbench() {
  const { openMediaPicker } = useMediaPicker();
  const [selectedVideo, setSelectedVideo] = useState<{ id: string; url: string; name: string } | null>(null);
  const [boxes, setBoxes] = useState<WatermarkBox[]>([]);
  const [selectedBoxId, setSelectedBoxId] = useState<string | null>(null);
  const [tasks, setTasks] = useState<QueueTask[]>([]);
  const videoUrl = selectedVideo?.url ?? '';

  function openVideoPicker() {
    openMediaPicker({
      initialTab: '视频',
      max: 1,
      title: '选择视频',
      subtitle: '上传或从资产库选择一段视频。',
      accept: 'video/*',
      onConfirm: handleConfirmPicked,
    });
  }

  function handleConfirmPicked(picked: PickedMedia[]) {
    const video = picked.find((item) => item.type === 'video');
    if (!video) return;

    setSelectedVideo(video);
    setBoxes([{ ...DEFAULT_BOX }]);
    setSelectedBoxId(DEFAULT_BOX.id);
  }

  function resetWorkbench() {
    setSelectedVideo(null);
    setBoxes([]);
    setSelectedBoxId(null);
  }

  function addWatermarkBox() {
    const nextIndex = boxes.length + 1;
    const nextBox: WatermarkBox = {
      ...DEFAULT_BOX,
      id: `watermark-${nextIndex}`,
      label: `水印区 ${nextIndex}`,
      x: Math.max(8, DEFAULT_BOX.x - nextIndex * 8),
      y: Math.min(70, DEFAULT_BOX.y + nextIndex * 8),
    };
    setBoxes((current) => [...current, nextBox]);
    setSelectedBoxId(nextBox.id);
  }

  function removeWatermarkBox(id: string) {
    setBoxes((current) => current.filter((box) => box.id !== id));
    setSelectedBoxId((current) => (current === id ? null : current));
  }

  function startTask() {
    if (!selectedVideo || boxes.length === 0) return;
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
        <div className="grid min-h-[calc(100vh-40px)] gap-3 lg:grid-cols-[minmax(420px,526px)_minmax(0,1fr)_200px]">
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
                <h1 className="text-sm font-semibold">视频去水印</h1>
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
                {videoUrl ? (
                  <div className="relative max-h-full w-full overflow-hidden rounded-lg bg-black">
                    <video src={videoUrl} controls className="block max-h-[430px] w-full object-contain" />
                    {boxes.map((box) => (
                      <button
                        type="button"
                        key={box.id}
                        aria-label={`选择${box.label}`}
                        onClick={() => setSelectedBoxId(box.id)}
                        className={`absolute border-2 ${
                          selectedBoxId === box.id
                            ? 'border-[#4f7cff] bg-[#4f7cff]/15'
                            : 'border-white/80 bg-white/10'
                        }`}
                        style={{
                          left: `${box.x}%`,
                          top: `${box.y}%`,
                          width: `${box.width}%`,
                          height: `${box.height}%`,
                        }}
                      >
                        {selectedBoxId === box.id ? (
                          <span className="absolute -top-6 left-0 whitespace-nowrap rounded bg-[#4f7cff] px-1.5 py-0.5 text-[10px] text-white">
                            {box.label}
                          </span>
                        ) : null}
                      </button>
                    ))}
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
                      支持 30 秒以内的 mp4 / mov / avi 等格式；超过 2K 的视频将自动压缩至 2K 后处理
                    </span>
                  </button>
                )}
              </div>

              <div className="mt-2 flex min-h-[70px] items-center gap-2 rounded-xl border border-[#e4e5e9] bg-white px-3 py-2">
                {boxes.map((box) => (
                  <button
                    type="button"
                    key={box.id}
                    onClick={() => setSelectedBoxId(box.id)}
                    className={`relative flex h-12 min-w-[118px] items-center justify-between rounded-lg border px-2 text-left transition ${
                      selectedBoxId === box.id
                        ? 'border-[#4f7cff] bg-[#f5f7ff]'
                        : 'border-[#e4e5e9] bg-white'
                    }`}
                  >
                    <span>
                      <span className="block text-xs font-semibold">{box.label}</span>
                      <span className="block text-[10px] text-[#9ca2ad]">
                        {box.x.toFixed(1)} / {box.y.toFixed(0)}, {box.width.toFixed(0)}
                      </span>
                    </span>
                    <span
                      role="button"
                      tabIndex={0}
                      aria-label={`删除${box.label}`}
                      onClick={(event) => {
                        event.stopPropagation();
                        removeWatermarkBox(box.id);
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          event.stopPropagation();
                          removeWatermarkBox(box.id);
                        }
                      }}
                      className="grid h-6 w-6 place-items-center rounded-md text-[#747b86] hover:bg-[#f3f4f6]"
                    >
                      <X className="h-3.5 w-3.5" />
                    </span>
                  </button>
                ))}
                <button
                  type="button"
                  onClick={addWatermarkBox}
                  className="grid h-12 w-28 place-items-center rounded-lg border border-dashed border-[#dfe2e8] text-[#9ca2ad] transition hover:border-[#9aaef8] hover:text-[#4f7cff]"
                  aria-label="添加水印区域"
                >
                  <Plus className="h-4 w-4" />
                </button>
              </div>
            </div>

            <div className="mx-3 mt-2 rounded-xl border border-[#e4e5e9] bg-white p-3 text-xs text-[#5c6470]">
              <h2 className="font-semibold text-[#252a33]">操作指引</h2>
              <ol className="mt-3 space-y-2.5">
                <GuideItem index="1" icon={<Film className="h-3.5 w-3.5" />} title="上传视频">
                  点击画面中央上传，支持 30 秒以内的视频格式。
                </GuideItem>
                <GuideItem index="2" icon={<Sparkles className="h-3.5 w-3.5" />} title="框选水印">
                  在视频预览上添加需要处理的水印区域。
                </GuideItem>
                <GuideItem index="3" icon={<Eraser className="h-3.5 w-3.5" />} title="确认区域">
                  可添加多个水印区域，点击卡片切换当前区域。
                </GuideItem>
              </ol>
            </div>

            <div className="m-3">
              <button
                type="button"
                disabled={!selectedVideo || boxes.length === 0}
                onClick={startTask}
                className="flex h-10 w-full items-center justify-center gap-3 rounded-lg bg-[#20242b] text-sm font-semibold text-white transition hover:bg-[#111318] disabled:bg-[#8b8b8b]"
              >
                <Sparkles className="h-4 w-4" />
                开始去水印
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
              {videoUrl ? (
                <video src={videoUrl} controls className="max-h-full max-w-full rounded-xl bg-black shadow-sm" />
              ) : (
                <div className="text-center text-[#747b86]">
                  <Video className="mx-auto mb-6 h-10 w-10 stroke-[1.7]" />
                  <p className="text-sm">处理完成后会在这里预览去水印后的视频。</p>
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
