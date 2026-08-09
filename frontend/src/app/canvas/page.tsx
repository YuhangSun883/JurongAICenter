'use client';

import { ArrowRight, ChevronUp, CirclePlay, Plus, ImageIcon } from 'lucide-react';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Sidebar } from '@/components/home/Sidebar';
import { canvasApi, type CanvasListItem } from '@/api/canvas';
import { cn } from '@/lib/utils';

const TUTORIALS = [
  {
    step: '01',
    title: '画布组件',
    desc: '批量运行&连线逻辑',
    tone: 'from-[#eaf3ff] to-white',
    kind: 'flow',
  },
  {
    step: '02',
    title: '智能编导',
    desc: '图文视频分析',
    tone: 'from-[#e7f1ff] to-white',
    kind: 'director',
  },
  {
    step: '03',
    title: '图片创作',
    desc: '文生图、图生图、替换元素',
    tone: 'from-[#edf5ff] to-white',
    kind: 'image',
  },
  {
    step: '04',
    title: '视频创作',
    desc: '文生视频、全能参考生视频',
    tone: 'from-[#eef5ff] to-white',
    kind: 'video',
  },
] as const;

const COPY_WIDTH: Record<(typeof TUTORIALS)[number]['kind'], string> = {
  flow: 'w-[132px]',
  director: 'w-[136px]',
  image: 'w-[132px]',
  video: 'w-[132px]',
};

export default function Page() {
  const router = useRouter();
  const [canvasHistory, setCanvasHistory] = useState<CanvasListItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [creating, setCreating] = useState(false);

  // 进入页面时拉画布列表
  useEffect(() => {
    setHistoryLoading(true);
    canvasApi.listCanvases(1, 50)
      .then((list) => setCanvasHistory(list))
      .catch((err) => console.warn('[canvas-home] listCanvases failed:', err))
      .finally(() => setHistoryLoading(false));
  }, []);

  // 「+ 新建」:每次点击都创建一个新画布(后端) → 跳到 /canvas/new 并带上 canvasId
  const handleNewCanvas = async () => {
    if (creating) return;
    setCreating(true);
    try {
      const item = await canvasApi.createCanvas({ name: '未命名画布' });
      // 带上 canvasId 让 /canvas/new 加载这个画布
      router.push(`/canvas/new?canvasId=${encodeURIComponent(item.id)}`);
    } catch (err) {
      console.warn('[canvas-home] createCanvas failed, fallback to /canvas/new:', err);
      router.push('/canvas/new');
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar />
      <main className="min-h-screen bg-[#f7f7f8] px-8 py-10 text-[#111318]">
        <div className="mx-auto w-full max-w-[1440px]">
          <section>
            <div className="mb-5 flex items-center gap-2">
              <h1 className="text-xl font-bold">新手教程</h1>
              <button
                type="button"
                className="grid h-6 w-6 place-items-center rounded-full bg-[#e9ebef] text-[#8a909b]"
                aria-label="收起新手教程"
              >
                <ChevronUp className="h-4 w-4" />
              </button>
            </div>
            <div className="grid grid-cols-[1fr_1.14fr_1fr_1fr] gap-4">
              {TUTORIALS.map((item) => (
                <TutorialCard key={item.step} item={item} />
              ))}
            </div>
          </section>

          <section className="mt-10">
            <h2 className="text-xl font-bold">我的创作</h2>
            <div className="mt-5 flex flex-wrap gap-5">
              {/* "+ 新建" 卡片 - 每次点击都创建新画布 */}
              <div>
                <button
                  type="button"
                  onClick={handleNewCanvas}
                  disabled={creating}
                  className="group flex h-[230px] w-[230px] flex-col items-center justify-center rounded-lg border border-[#e0e2e7] bg-white text-[#16181d] shadow-sm transition hover:border-[#c8d2ff] hover:shadow-[0_14px_30px_rgba(24,31,45,0.08)] disabled:cursor-wait disabled:opacity-60"
                  title="新建画布（每次点击都创建一张新画布）"
                  aria-label="新建画布"
                >
                  <Plus className="h-8 w-8 text-[#8b909b] transition group-hover:text-[#4778ff]" />
                  <span className="mt-5 text-sm font-semibold">{creating ? '创建中...' : '新建'}</span>
                </button>
                <div className="mt-3 pl-2 text-sm font-semibold">开始创作</div>
              </div>

              {/* 已有画布列表(按创建时间倒序,最新在最前) */}
              {historyLoading ? (
                <div className="grid h-[230px] w-[230px] place-items-center rounded-lg border border-dashed border-[#e0e2e7] bg-white/60 text-xs text-[#a8b0bd]">
                  加载中…
                </div>
              ) : canvasHistory.length === 0 ? (
                <div className="grid h-[230px] w-[230px] place-items-center rounded-lg border border-dashed border-[#e0e2e7] bg-white/60 text-xs text-[#a8b0bd]">
                  还没有创作，去点上方 + 新建一张吧
                </div>
              ) : (
                canvasHistory.map((c) => (
                  <CanvasCard
                    key={c.id}
                    item={c}
                    onClick={() => router.push(`/canvas/new?canvasId=${encodeURIComponent(c.id)}`)}
                  />
                ))
              )}
            </div>
          </section>
        </div>
      </main>
    </div>
  );
}

/**
 * 单个画布卡片(我的创作列表用)
 * - 缩略图优先用 item.thumbnail(后端有就给);没有就用占位
 * - 标题:后端的 item.name 或 "未命名画布"
 * - 时间:相对时间(刚刚/N分钟前/N小时前/yyyy-MM-dd)
 */
function CanvasCard({ item, onClick }: { item: CanvasListItem; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group flex w-[230px] flex-col rounded-lg border border-[#e0e2e7] bg-white text-left shadow-sm transition hover:border-[#c8d2ff] hover:shadow-[0_14px_30px_rgba(24,31,45,0.08)]"
      title={`打开画布: ${item.name || '未命名画布'}`}
    >
      <div className="relative h-[160px] w-full overflow-hidden rounded-t-lg bg-[#f3f5f8]">
        {item.thumbnail ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={item.thumbnail}
            alt={item.name || '未命名画布'}
            className="h-full w-full object-cover transition group-hover:scale-[1.02]"
          />
        ) : (
          <div className="grid h-full w-full place-items-center text-[#c8d2dd]">
            <ImageIcon className="h-10 w-10" />
            <span className="mt-1 text-[11px]">无预览</span>
          </div>
        )}
      </div>
      <div className="flex flex-col gap-1 p-3">
        <div className="truncate text-sm font-semibold text-[#16181d]">
          {item.name || '未命名画布'}
        </div>
        <div className="text-[11px] text-[#8a909b]">
          {formatRelativeTime(item.updatedAt)}
          {item.nodeCount != null && <> · {item.nodeCount} 个节点</>}
        </div>
      </div>
    </button>
  );
}

/**
 * 把毫秒时间戳格式化为 "刚刚/N分钟前/N小时前/yyyy-MM-dd"
 */
function formatRelativeTime(ms: number): string {
  const now = Date.now();
  const diff = now - ms;
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function TutorialCard({ item }: { item: (typeof TUTORIALS)[number] }) {
  return (
    <article className="group relative h-[174px] min-w-0 overflow-hidden rounded-xl border border-[#edf0f4] bg-white shadow-[0_10px_24px_rgba(25,31,45,0.05)] transition hover:-translate-y-0.5 hover:shadow-[0_16px_34px_rgba(25,31,45,0.08)]">
      <div className={cn('absolute inset-0 bg-gradient-to-r', item.tone)} />
      <div className="relative flex h-full">
        <div className={cn('flex flex-none flex-col p-5', COPY_WIDTH[item.kind])}>
          <div className="mb-6 grid h-9 w-12 place-items-center rounded-br-[22px] rounded-tl-xl bg-[#dfeaff] text-lg font-semibold text-[#006cff]">
            {item.step}
          </div>
          <h3 className={cn('font-bold tracking-tight', item.kind === 'director' ? 'text-[18px]' : 'text-xl')}>
            {item.title}
          </h3>
          <p className={cn('mt-3 text-xs leading-[18px] text-[#5f6876]', item.kind === 'director' ? 'max-w-[110px]' : 'max-w-[150px]')}>
            {item.desc}
          </p>
          <span className="mt-auto grid h-7 w-7 place-items-center rounded-full bg-[#d9e8ff] text-[#1673ff] transition group-hover:bg-[#1673ff] group-hover:text-white">
            <ArrowRight className="h-4 w-4" />
          </span>
        </div>
        <div className="relative flex min-w-0 flex-1 items-center justify-center pr-4">
          <TutorialVisual kind={item.kind} />
        </div>
      </div>
    </article>
  );
}

function TutorialVisual({ kind }: { kind: (typeof TUTORIALS)[number]['kind'] }) {
  if (kind === 'flow') {
    return (
      <div className="relative h-[140px] w-[190px]">
        <MiniDoc className="left-0 top-1" label="文字描述" />
        <MiniImage className="left-5 top-[62px]" seed="bag-a" label="参考图片" />
        <MiniAvatar className="left-6 bottom-0" label="组件/素材" />
        <Connector className="left-[68px] top-[54px] h-[54px]" />
        <Bubble className="left-[83px] top-[52px]" label="批量生成" />
        <MiniImage className="right-2 top-2" seed="bag-b" />
        <MiniImage className="right-4 top-[62px]" seed="bag-c" />
        <MiniImage className="right-0 bottom-0" seed="bag-d" />
      </div>
    );
  }

  if (kind === 'director') {
    return (
      <div className="relative h-[140px] w-[194px]">
        <StackCard className="left-0 top-0" label="文本" seed="doc-blue" wide />
        <StackCard className="left-2 top-[39px]" label="视频" seed="video-room" wide />
        <StackCard className="left-0 top-[78px]" label="图片" seed="green-product" wide />
        <StackCard className="left-2 bottom-0" label="改写" seed="shoe" wide />
        <div className="absolute left-[118px] top-[62px] flex items-center text-[#5b9aff]">
          <span className="h-px w-5 bg-[#82b2ff]" />
          <span className="mx-0.5 text-lg leading-none">✦</span>
          <span className="h-px w-4 bg-[#82b2ff]" />
        </div>
        <div className="absolute right-0 top-3 flex h-[124px] w-8 flex-col items-center justify-around rounded-lg bg-white shadow-sm">
          <DirectorAction label="分析" icon="✣" />
          <DirectorAction label="建议" icon="▣" />
          <DirectorAction label="执行" icon="✎" />
        </div>
      </div>
    );
  }

  if (kind === 'image') {
    return (
      <div className="grid w-[205px] grid-cols-[60px_1fr] gap-2">
        <div className="space-y-2">
          <MiniDoc label="文字描述" />
          <MiniImage seed="perfume-a" label="参考图" />
          <MiniImage seed="flower-a" label="原图" />
        </div>
        <div className="grid grid-cols-2 gap-2">
          {['perfume-b', 'perfume-c', 'bag-e', 'bag-f', 'flower-b', 'flower-c'].map((seed) => (
            <MiniImage key={seed} seed={seed} />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="relative h-[138px] w-[210px]">
      <div className="absolute left-0 top-0 h-[92px] w-[135px] overflow-hidden rounded-lg bg-[#d9e4f4] shadow-sm">
        <img
          src="https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=360&auto=format&fit=crop"
          alt=""
          className="h-full w-full object-cover"
        />
        <CirclePlay className="absolute left-1/2 top-1/2 h-9 w-9 -translate-x-1/2 -translate-y-1/2 fill-black/60 text-white" />
      </div>
      <MiniImage className="right-0 top-0" seed="mountain-a" />
      <MiniImage className="right-0 top-[48px]" seed="mountain-b" />
      <MiniDoc className="left-4 bottom-0" label="文本" />
      <MiniImage className="left-[70px] bottom-0" seed="video-ref" label="图片" />
      <MiniImage className="right-10 bottom-0" seed="video-ref-b" label="视频" />
      <MiniAudio className="right-0 bottom-0" />
    </div>
  );
}

function MiniDoc({ className, label }: { className?: string; label?: string }) {
  return (
    <div className={cn('absolute h-12 w-12 rounded-md bg-white shadow-sm', className)}>
      <div className="m-2 space-y-1">
        <div className="h-1 w-7 rounded bg-[#b5bfce]" />
        <div className="h-1 w-6 rounded bg-[#ccd3df]" />
        <div className="h-1 w-8 rounded bg-[#ccd3df]" />
      </div>
      {label && <span className="absolute -bottom-3 left-1 rounded bg-white px-1 text-[9px] shadow-sm">{label}</span>}
    </div>
  );
}

function MiniImage({ className, seed, label }: { className?: string; seed: string; label?: string }) {
  return (
    <div className={cn('absolute h-12 w-12 overflow-hidden rounded-md bg-white shadow-sm', className)}>
      <img
        src={`https://picsum.photos/seed/canvas-${seed}/120/120`}
        alt=""
        className="h-full w-full object-cover"
      />
      {label && <span className="absolute bottom-0 left-0 right-0 bg-white/90 text-center text-[9px]">{label}</span>}
    </div>
  );
}

function MiniAvatar({ className, label }: { className?: string; label?: string }) {
  return (
    <div className={cn('absolute grid h-12 w-12 place-items-center rounded-md bg-white shadow-sm', className)}>
      <div className="h-7 w-7 rounded-full bg-[#cfd7e7]" />
      {label && <span className="absolute -bottom-3 left-0 rounded bg-white px-1 text-[9px] shadow-sm">{label}</span>}
    </div>
  );
}

function Bubble({ className, label }: { className?: string; label: string }) {
  return (
    <div className={cn('absolute grid h-11 w-11 place-items-center rounded-full border border-[#4c8dff] bg-white text-[10px] leading-3 text-[#1a6fff] shadow-sm', className)}>
      {label}
    </div>
  );
}

function Connector({ className }: { className?: string }) {
  return <div className={cn('absolute w-px bg-[#77a7ff]', className)} />;
}

function StackCard({
  className,
  label,
  seed,
  wide,
}: {
  className?: string;
  label: string;
  seed?: string;
  wide?: boolean;
}) {
  return (
    <div className={cn('absolute flex h-8 items-center gap-2 rounded-md bg-white p-1 shadow-sm', wide ? 'w-[124px]' : 'w-[118px]', className)}>
      <div className="h-6 w-8 overflow-hidden rounded bg-[#e6edf8]">
        {seed && <img src={`https://picsum.photos/seed/canvas-${seed}/90/70`} alt="" className="h-full w-full object-cover" />}
      </div>
      <span className="text-[9px] leading-none">{label}</span>
    </div>
  );
}

function DirectorAction({ label, icon }: { label: string; icon: string }) {
  return (
    <div className="flex flex-col items-center gap-0.5">
      <span className="text-[13px] leading-none text-[#2f79ff]">{icon}</span>
      <span className="text-[10px] leading-none text-[#2f79ff]">{label}</span>
    </div>
  );
}

function MiniAudio({ className }: { className?: string }) {
  return (
    <div className={cn('absolute flex h-10 w-12 items-center justify-center gap-0.5 rounded-md bg-white shadow-sm', className)}>
      {[8, 14, 22, 12].map((height, index) => (
        <span key={index} className="w-1 rounded-full bg-[#6ea2ff]" style={{ height }} />
      ))}
    </div>
  );
}
