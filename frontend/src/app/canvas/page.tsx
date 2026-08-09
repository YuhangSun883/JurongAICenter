'use client';

import { ArrowRight, Check, ChevronUp, CirclePlay, ImageIcon, Pencil, Plus, Trash2, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Sidebar } from '@/components/home/Sidebar';
import { canvasApi, type CanvasListItem } from '@/api/canvas';
import { cn } from '@/lib/utils';

const UNTITLED_CANVAS = '\u672a\u547d\u540d\u753b\u5e03';
const DEFAULT_CANVAS_NAMES = new Set(['\u9ed8\u8ba4\u753b\u5e03', '\u699b\u6a3f\ue17b\u9422\u8bf2\u7af7']);

const LABELS = {
  tutorialTitle: '\u753b\u5e03\u6559\u7a0b',
  collapseTutorial: '\u6536\u8d77\u753b\u5e03\u6559\u7a0b',
  myCreation: '\u6211\u7684\u521b\u4f5c',
  createCanvas: '\u65b0\u5efa\u753b\u5e03',
  creating: '\u521b\u5efa\u4e2d...',
  create: '\u65b0\u5efa',
  loading: '\u52a0\u8f7d\u4e2d...',
  emptyHistory: '\u6682\u65e0\u5386\u53f2\u753b\u5e03\uff0c\u70b9\u51fb\u65b0\u5efa\u5f00\u59cb\u521b\u4f5c',
  renameFailed: '\u753b\u5e03\u540d\u79f0\u4fee\u6539\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5',
  deleteFailed: '\u753b\u5e03\u5220\u9664\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5',
  closeTip: '\u5173\u95ed\u63d0\u793a',
  noPreview: '\u6682\u65e0\u9884\u89c8',
  rename: '\u4fee\u6539\u540d\u79f0',
  deleteCanvas: '\u5220\u9664\u753b\u5e03',
  canvasName: '\u753b\u5e03\u540d\u79f0',
  saveName: '\u4fdd\u5b58\u540d\u79f0',
  cancelRename: '\u53d6\u6d88\u4fee\u6539',
  justNow: '\u521a\u521a',
  minuteAgo: '\u5206\u949f\u524d',
  hourAgo: '\u5c0f\u65f6\u524d',
  nodeUnit: '\u4e2a\u8282\u70b9',
};

const TUTORIALS = [
  {
    step: '01',
    title: '\u811a\u672c\u62c6\u89e3',
    desc: '\u628a\u6587\u6848\u62c6\u6210\u53ef\u751f\u4ea7\u7684\u955c\u5934\u8282\u70b9',
    tone: 'from-[#eaf3ff] to-white',
    kind: 'flow',
  },
  {
    step: '02',
    title: '\u5bfc\u6f14\u5de5\u4f5c\u53f0',
    desc: '\u7edf\u4e00\u7ba1\u7406\u56fe\u7247\u3001\u89c6\u9891\u548c\u89d2\u8272\u7d20\u6750',
    tone: 'from-[#e7f1ff] to-white',
    kind: 'director',
  },
  {
    step: '03',
    title: '\u56fe\u7247\u751f\u6210',
    desc: '\u8282\u70b9\u4e32\u8054\u540e\u5feb\u901f\u751f\u6210\u5206\u955c\u56fe\u7247',
    tone: 'from-[#edf5ff] to-white',
    kind: 'image',
  },
  {
    step: '04',
    title: '\u89c6\u9891\u751f\u6210',
    desc: '\u4ece\u5206\u955c\u7ee7\u7eed\u751f\u6210\u89c6\u9891\u7d20\u6750',
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

function isUserCanvas(item: CanvasListItem) {
  return item.id !== 'mock_canvas_default' && !DEFAULT_CANVAS_NAMES.has(item.name);
}

export default function Page() {
  const router = useRouter();
  const [canvasHistory, setCanvasHistory] = useState<CanvasListItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [actionError, setActionError] = useState('');

  useEffect(() => {
    setHistoryLoading(true);
    canvasApi.listCanvases(1, 50)
      .then((list) => setCanvasHistory(list.filter(isUserCanvas)))
      .catch((err) => console.warn('[canvas-home] listCanvases failed:', err))
      .finally(() => setHistoryLoading(false));
  }, []);

  const handleNewCanvas = async () => {
    if (creating) return;
    setCreating(true);
    try {
      const item = await canvasApi.createCanvas({ name: UNTITLED_CANVAS });
      router.push(`/canvas/new?canvasId=${encodeURIComponent(item.id)}`);
    } catch (err) {
      console.warn('[canvas-home] createCanvas failed, fallback to /canvas/new:', err);
      router.push('/canvas/new');
    } finally {
      setCreating(false);
    }
  };

  const handleRenameCanvas = async (canvasId: string, name: string) => {
    const nextName = name.trim();
    if (!nextName) return;
    try {
      const updated = await canvasApi.updateCanvas(canvasId, { name: nextName });
      setCanvasHistory((current) =>
        current.map((item) => (item.id === canvasId ? { ...item, ...updated, name: updated.name || nextName } : item)),
      );
      setActionError('');
    } catch (err) {
      console.warn('[canvas-home] rename canvas failed:', err);
      setActionError(LABELS.renameFailed);
    }
  };

  const handleDeleteCanvas = async (item: CanvasListItem) => {
    if (!window.confirm(`\u786e\u5b9a\u5220\u9664\u300c${item.name || UNTITLED_CANVAS}\u300d\u5417\uff1f`)) return;
    try {
      await canvasApi.deleteCanvas(item.id);
      setCanvasHistory((current) => current.filter((canvas) => canvas.id !== item.id));
      setActionError('');
    } catch (err) {
      console.warn('[canvas-home] delete canvas failed:', err);
      setActionError(LABELS.deleteFailed);
    }
  };

  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar />
      <main className="min-h-screen bg-[#f7f7f8] px-8 py-10 text-[#111318]">
        <div className="mx-auto w-full max-w-[1440px]">
          <section>
            <div className="mb-5 flex items-center gap-2">
              <h1 className="text-xl font-bold">{LABELS.tutorialTitle}</h1>
              <button
                type="button"
                className="grid h-6 w-6 place-items-center rounded-full bg-[#e9ebef] text-[#8a909b]"
                aria-label={LABELS.collapseTutorial}
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
            <h2 className="text-xl font-bold">{LABELS.myCreation}</h2>
            <div className="mt-5 flex flex-wrap gap-5">
              <div>
                <button
                  type="button"
                  onClick={handleNewCanvas}
                  disabled={creating}
                  className="group flex h-[230px] w-[230px] flex-col items-center justify-center rounded-lg border border-[#e0e2e7] bg-white text-[#16181d] shadow-sm transition hover:border-[#c8d2ff] hover:shadow-[0_14px_30px_rgba(24,31,45,0.08)] disabled:cursor-wait disabled:opacity-60"
                  title={LABELS.createCanvas}
                  aria-label={LABELS.createCanvas}
                >
                  <Plus className="h-8 w-8 text-[#8b909b] transition group-hover:text-[#4778ff]" />
                  <span className="mt-5 text-sm font-semibold">{creating ? LABELS.creating : LABELS.create}</span>
                </button>
                <div className="mt-3 pl-2 text-sm font-semibold">{LABELS.createCanvas}</div>
              </div>

              {historyLoading ? (
                <div className="grid h-[230px] w-[230px] place-items-center rounded-lg border border-dashed border-[#e0e2e7] bg-white/60 text-xs text-[#a8b0bd]">
                  {LABELS.loading}
                </div>
              ) : canvasHistory.length === 0 ? (
                <div className="grid h-[230px] w-[230px] place-items-center rounded-lg border border-dashed border-[#e0e2e7] bg-white/60 px-6 text-center text-xs leading-5 text-[#a8b0bd]">
                  {LABELS.emptyHistory}
                </div>
              ) : (
                canvasHistory.map((c) => (
                  <CanvasCardWithActions
                    key={c.id}
                    item={c}
                    onClick={() => router.push(`/canvas/new?canvasId=${encodeURIComponent(c.id)}`)}
                    onRename={handleRenameCanvas}
                    onDelete={handleDeleteCanvas}
                  />
                ))
              )}
            </div>
            {actionError && (
              <div className="mt-3 flex items-center gap-2 text-xs text-red-500">
                <span>{actionError}</span>
                <button type="button" onClick={() => setActionError('')} aria-label={LABELS.closeTip}>
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            )}
          </section>
        </div>
      </main>
    </div>
  );
}

function CanvasCardWithActions({
  item,
  onClick,
  onRename,
  onDelete,
}: {
  item: CanvasListItem;
  onClick: () => void;
  onRename: (canvasId: string, name: string) => Promise<void>;
  onDelete: (item: CanvasListItem) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(item.name || UNTITLED_CANVAS);
  const [saving, setSaving] = useState(false);
  const displayName = item.name || UNTITLED_CANVAS;

  useEffect(() => {
    if (!editing) setDraft(displayName);
  }, [displayName, editing]);

  const saveName = async () => {
    const nextName = draft.trim();
    if (!nextName || saving) return;
    setSaving(true);
    try {
      await onRename(item.id, nextName);
      setEditing(false);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') onClick();
      }}
      className="group flex w-[230px] flex-col rounded-lg border border-[#e0e2e7] bg-white text-left shadow-sm transition hover:border-[#c8d2ff] hover:shadow-[0_14px_30px_rgba(24,31,45,0.08)]"
      title={`\u6253\u5f00\u753b\u5e03\uff1a${displayName}`}
    >
      <div className="relative h-[160px] w-full overflow-hidden rounded-t-lg bg-[#f3f5f8]">
        {item.thumbnail ? (
          <img src={item.thumbnail} alt={displayName} className="h-full w-full object-cover transition group-hover:scale-[1.02]" />
        ) : (
          <div className="grid h-full w-full place-items-center text-[#c8d2dd]">
            <ImageIcon className="h-10 w-10" />
            <span className="mt-1 text-[11px]">{LABELS.noPreview}</span>
          </div>
        )}
        <div className="pointer-events-none absolute inset-x-0 bottom-0 flex justify-end bg-gradient-to-t from-black/55 to-transparent p-2 opacity-0 transition-opacity group-hover:opacity-100">
          <div className="pointer-events-auto flex items-center gap-1">
            <button
              type="button"
              onClick={(event) => {
                event.stopPropagation();
                setDraft(displayName);
                setEditing(true);
              }}
              className="grid h-7 w-7 place-items-center rounded-md text-white transition hover:bg-white/20"
              title={LABELS.rename}
              aria-label={LABELS.rename}
            >
              <Pencil className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={(event) => {
                event.stopPropagation();
                void onDelete(item);
              }}
              className="grid h-7 w-7 place-items-center rounded-md text-white transition hover:bg-red-500/80"
              title={LABELS.deleteCanvas}
              aria-label={LABELS.deleteCanvas}
            >
              <Trash2 className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>
      <div className="flex flex-col gap-1 p-3">
        {editing ? (
          <div className="flex items-center gap-1" onClick={(event) => event.stopPropagation()}>
            <input
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void saveName();
                if (event.key === 'Escape') {
                  setDraft(displayName);
                  setEditing(false);
                }
              }}
              autoFocus
              className="min-w-0 flex-1 rounded border border-[#c8d2ff] px-2 py-1 text-sm font-semibold text-[#16181d] outline-none"
              aria-label={LABELS.canvasName}
            />
            <button type="button" onClick={() => void saveName()} disabled={saving} className="grid h-7 w-7 shrink-0 place-items-center rounded text-[#3978ff] hover:bg-[#eef3ff]" title={LABELS.saveName} aria-label={LABELS.saveName}>
              <Check className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={() => {
                setDraft(displayName);
                setEditing(false);
              }}
              className="grid h-7 w-7 shrink-0 place-items-center rounded text-[#8a909b] hover:bg-[#f1f3f6]"
              title={LABELS.cancelRename}
              aria-label={LABELS.cancelRename}
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        ) : (
          <div className="truncate text-sm font-semibold text-[#16181d]">{displayName}</div>
        )}
        <div className="text-[11px] text-[#8a909b]">
          {formatRelativeTime(item.updatedAt)}
          {item.nodeCount != null && <> ? {item.nodeCount} {LABELS.nodeUnit}</>}
        </div>
      </div>
    </div>
  );
}

function formatRelativeTime(ms: number): string {
  const now = Date.now();
  const diff = now - ms;
  if (diff < 60_000) return LABELS.justNow;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} ${LABELS.minuteAgo}`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} ${LABELS.hourAgo}`;
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
        <MiniDoc className="left-0 top-1" label="\u811a\u672c" />
        <MiniImage className="left-5 top-[62px]" seed="bag-a" label="\u7d20\u6750" />
        <MiniAvatar className="left-6 bottom-0" label="\u89d2\u8272" />
        <Connector className="left-[68px] top-[54px] h-[54px]" />
        <Bubble className="left-[83px] top-[52px]" label="\u751f\u6210" />
        <MiniImage className="right-2 top-2" seed="bag-b" />
        <MiniImage className="right-4 top-[62px]" seed="bag-c" />
        <MiniImage className="right-0 bottom-0" seed="bag-d" />
      </div>
    );
  }

  if (kind === 'director') {
    return (
      <div className="relative h-[140px] w-[194px]">
        <StackCard className="left-0 top-0" label="\u811a\u672c" seed="doc-blue" wide />
        <StackCard className="left-2 top-[39px]" label="\u89c6\u9891" seed="video-room" wide />
        <StackCard className="left-0 top-[78px]" label="\u56fe\u7247" seed="green-product" wide />
        <StackCard className="left-2 bottom-0" label="\u89d2\u8272" seed="shoe" wide />
        <div className="absolute left-[118px] top-[62px] flex items-center text-[#5b9aff]">
          <span className="h-px w-5 bg-[#82b2ff]" />
          <span className="mx-0.5 text-lg leading-none">-&gt;</span>
          <span className="h-px w-4 bg-[#82b2ff]" />
        </div>
        <div className="absolute right-0 top-3 flex h-[124px] w-8 flex-col items-center justify-around rounded-lg bg-white shadow-sm">
          <DirectorAction label="\u62c6\u89e3" icon="*" />
          <DirectorAction label="\u62bd\u5e27" icon="[]" />
          <DirectorAction label="\u751f\u6210" icon=">" />
        </div>
      </div>
    );
  }

  if (kind === 'image') {
    return (
      <div className="grid w-[205px] grid-cols-[60px_1fr] gap-2">
        <div className="space-y-2">
          <MiniDoc label="\u63d0\u793a\u8bcd" />
          <MiniImage seed="perfume-a" label="\u53c2\u8003" />
          <MiniImage seed="flower-a" label="\u6210\u56fe" />
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
      <MiniDoc className="left-4 bottom-0" label="\u811a\u672c" />
      <MiniImage className="left-[70px] bottom-0" seed="video-ref" label="\u56fe\u7247" />
      <MiniImage className="right-10 bottom-0" seed="video-ref-b" label="\u89c6\u9891" />
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
      <img src={`https://picsum.photos/seed/canvas-${seed}/120/120`} alt="" className="h-full w-full object-cover" />
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
