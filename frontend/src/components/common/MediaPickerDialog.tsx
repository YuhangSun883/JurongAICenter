'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Upload, X, ChevronDown, Search,
  Check, ShieldCheck, User, Trash2,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { AddMaterialCard } from './AddMaterialCard';
import { RoleCategorySelect, type RoleCategory } from './RoleCategorySelect';
import { ROLES as MOCK_ROLES } from './MediaPickerDialog.mocks';

/** 弹窗内可选的素材 */
export interface PickedMedia {
  id: string;
  type: 'image' | 'video' | 'audio';
  url: string;
  name: string;
}

interface MediaPickerDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm?: (picked: PickedMedia[]) => void;
  /** 外部上传回调：传入文件，父组件处理（显示进度、保存文件等）。返回已上传的 PickedMedia[] */
  onUploadFiles?: (files: FileList | null) => PickedMedia[];
  /** 删除已上传素材回调 */
  onRemoveUploaded?: (id: string) => void;
  max?: number;
  /** 已上传的素材（由父组件持有，关闭弹窗后保留） */
  uploadedFiles?: PickedMedia[];
  /** Whether to show built-in demo assets. Keep true for legacy pages, disable on pure-upload flows. */
  showMockAssets?: boolean;
  initialTab?: typeof TABS[number];
  title?: string;
  subtitle?: string;
  accept?: string;
}

const TABS = ['图片', '视频', '音频'] as const;
const SOURCES = ['全部', '我上传的', 'AI生成的'] as const;

/** 角色库分类（细到"都市蓝领"这种） */
const ROLE_CATEGORIES: RoleCategory[] = [
  { key: 'face', label: '逼真人脸' },
  { key: 'urban-blue', label: '都市蓝领' },
  { key: 'urban-silver', label: '都市银发' },
  { key: 'kids', label: '儿童' },
  { key: 'mom', label: '精致妈妈' },
  { key: 'town-young', label: '小镇青年' },
  { key: 'town-mid', label: '小镇中老年' },
  { key: 'fantasy', label: '二次元' },
  { key: 'chinese', label: '国风' },
  { key: 'fashion', label: '时尚模特' },
  { key: 'animal', label: '动物' },
];

interface RoleItem {
  id: string;
  name: string;
  url: string;
  date: string;
  size: string;
}

const ROLES = MOCK_ROLES;

export function MediaPickerDialog({
  open, onClose, onConfirm, max = 12, uploadedFiles: propUploadedFiles, onUploadFiles, onRemoveUploaded, showMockAssets = true,
  initialTab = '图片', title = '选择参考素材', subtitle = '支持图片、视频和音频参考。',
  accept = 'image/*,video/*,audio/*',
}: MediaPickerDialogProps) {
  const [tab, setTab] = useState<typeof TABS[number]>(initialTab);
  const [source, setSource] = useState<typeof SOURCES[number]>('全部');
  const [keyword, setKeyword] = useState('');
  const [activeTopTab, setActiveTopTab] = useState<'assets' | 'roles'>('assets');
  const [pickedIds, setPickedIds] = useState<Set<string>>(new Set());
  const [mounted, setMounted] = useState(false);
  const [roleCategory, setRoleCategory] = useState<string>(ROLE_CATEGORIES[0].key);
  /** 待删除的素材 id（弹确认弹窗用） */
  const [pendingRemoveId, setPendingRemoveId] = useState<string | null>(null);
  /** 已上传素材：完全由父组件持有（prop 模式） */
  const uploadedFiles = propUploadedFiles ?? [];
  /** 上传中（带进度条）：暂时展示为 PickedMedia 占位，url 用 blob */
  const [uploading, setUploading] = useState<{ id: string; name: string; progress: number; type: 'image' | 'video' | 'audio' }[]>([]);

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [open, onClose]);

  useEffect(() => {
    if (open) setTab(initialTab);
    setPickedIds(new Set());
  }, [open, initialTab]);

  // 派生：根据当前 source/tab 实际可见的选中数
  const tabToType: Record<typeof TABS[number], string> = { '图片': 'image', '视频': 'video', '音频': 'audio' };
  const showUploadedHere = activeTopTab === 'assets' && (source === '全部' || source === '我上传的');
  const showMockHere = showMockAssets && (source === '全部' || source === 'AI生成的');
  const visiblePickedCount =
    (showUploadedHere ? uploadedFiles.filter((u) => u.type === tabToType[tab] && pickedIds.has(u.id)).length : 0) +
    (showMockHere
      ? (activeTopTab === 'assets'
          ? ASSETS.filter((a) => a.type === tabToType[tab] && pickedIds.has(a.id)).length
          : Object.values(ROLES).flat().filter((r) => pickedIds.has(r.id)).length)
      : 0);

  if (!open || !mounted) return null;

  function toggle(id: string) {
    setPickedIds((s) => {
      const next = new Set(s);
      if (next.has(id)) {
        next.delete(id);
        return next;
      }
      // 限制：最多 max 个
      if (next.size >= max) return s;
      // 去重：按 name + url 长度（blob URL 同样文件长度相同）
      const allItems: { id: string; name: string; url: string }[] = [
        ...(showMockAssets ? ASSETS.map((a) => ({ id: a.id, name: a.name, url: a.url })) : []),
        ...Object.values(ROLES).flat().map((r) => ({ id: r.id, name: r.name, url: r.url })),
        ...uploadedFiles.map((u) => ({ id: u.id, name: u.name, url: u.url })),
      ];
      const targetItem = allItems.find((x) => x.id === id);
      if (targetItem) {
        const targetFp = `${targetItem.name}_${targetItem.url.length}`;
        const dup = [...next].some((existingId) => {
          const ex = allItems.find((x) => x.id === existingId);
          if (!ex) return false;
          return `${ex.name}_${ex.url.length}` === targetFp;
        });
        if (dup) return s; // 已有同名同 url 长度的项，拒绝
      }
      next.add(id);
      return next;
    });
  }

  function handleConfirm() {
    // mock 资产（我的资产 / 角色库）
    const mockPicked: PickedMedia[] = activeTopTab === 'assets'
      ? (showMockAssets ? ASSETS.filter((a) => pickedIds.has(a.id)) : [])
      : Object.values(ROLES).flat().filter((r) => pickedIds.has(r.id))
          .map((r) => ({ id: r.id, type: 'image' as const, url: r.url, name: r.name }));
    // 已上传素材（用户从"素材库"选中的）
    const uploadedPicked = uploadedFiles.filter((u) => pickedIds.has(u.id));
    // 合并 + 按 name 去重（同名图只保留第一个）
    const all = [...uploadedPicked, ...mockPicked];
    const seen = new Set<string>();
    const deduped = all.filter((m) => {
      const k = `${m.name}_${m.url.length || 0}`;
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    });
    onConfirm?.(deduped);
    onClose();
  }

  function handleUploadFiles(files: FileList | null) {
    if (!files) return;
    const list = Array.from(files);
    // 直接回调父组件（同步），避免 setInterval 在 Strict Mode 下双倍触发
    if (onUploadFiles) {
      const items = onUploadFiles(files);
      // 自动选中新上传
      setPickedIds((s) => {
        const next = new Set(s);
        items.forEach((it) => { if (next.size < max) next.add(it.id); });
        return next;
      });
    }
  }

  return createPortal(
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="card resizable-dialog scrollbar-hidden relative flex w-full max-w-[760px] flex-col bg-white p-0 shadow-2xl"
        style={{ maxHeight: 'calc(100vh - 80px)' }}
      >
        {/* 顶部：固定 */}
        <div className="flex shrink-0 items-start justify-between border-b border-bg-line/60 px-6 pb-4 pt-5">
          <div className="flex items-start gap-3">
            <div className="grid h-9 w-9 place-items-center rounded-lg bg-bg-soft text-fg-muted">
              <Upload className="h-4 w-4" />
            </div>
            <div>
              <div className="text-base font-medium text-fg">{title}</div>
              <div className="mt-0.5 text-xs text-fg-muted">{subtitle}</div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className="chip">已选 {visiblePickedCount}/{max}</span>
            <button
              onClick={onClose}
              className="grid h-8 w-8 place-items-center rounded-lg text-fg-muted hover:bg-bg-soft hover:text-fg"
              aria-label="关闭"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* 中部：可滚动 */}
        <div className="scrollbar-hidden flex-1 overflow-auto px-6 py-4">
          {/* 顶部 Tab */}
          <div className="flex items-center gap-6 border-b border-bg-line/60">
            {([
              { k: 'assets', label: '我的资产' },
              { k: 'roles', label: '角色库' },
            ] as const).map(({ k, label }) => (
              <button
                key={k}
                onClick={() => { setActiveTopTab(k); setPickedIds(new Set()); }}
                className={cn(
                  'relative -mb-px py-2 text-sm transition',
                  activeTopTab === k
                    ? 'font-medium text-fg'
                    : 'text-fg-muted hover:text-fg'
                )}
              >
                {label}
                {activeTopTab === k && (
                  <span
                    className="absolute -bottom-px left-0 right-0 h-0.5 rounded-full bg-brand"
                    aria-hidden
                  />
                )}
              </button>
            ))}
          </div>

          {activeTopTab === 'assets' ? (
            <AssetsView
              tab={tab} setTab={setTab}
              source={source} setSource={setSource}
              keyword={keyword} setKeyword={setKeyword}
              pickedIds={pickedIds} setPickedIds={setPickedIds}
              onToggle={toggle}
              onUpload={handleUploadFiles}
              onAskRemove={(id) => setPendingRemoveId(id)}
              uploadedFiles={uploadedFiles}
              uploading={uploading}
              showMockAssets={showMockAssets}
              accept={accept}
            />
          ) : (
            <RolesView
              category={roleCategory} setCategory={setRoleCategory}
              keyword={keyword} setKeyword={setKeyword}
              pickedIds={pickedIds} onToggle={toggle}
            />
          )}
        </div>

        {/* 底部：固定 */}
        <div className="flex shrink-0 items-center justify-end gap-2 border-t border-bg-line/60 px-6 py-3">
          <button onClick={onClose} className="btn-ghost h-9 px-4">取消</button>
          <button
            onClick={handleConfirm}
            disabled={visiblePickedCount === 0}
            className="rounded-xl bg-brand px-4 py-2 text-sm font-medium text-white shadow-glow transition hover:brightness-110 disabled:opacity-50"
          >
            确认选择 {visiblePickedCount > 0 && `(${visiblePickedCount}/${max})`}
          </button>
        </div>
      </div>

      {/* 删除确认弹窗 */}
      {pendingRemoveId && (
        <div
          className="fixed inset-0 z-[1100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          onClick={() => setPendingRemoveId(null)}
          role="dialog"
          aria-modal="true"
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="card relative w-full max-w-sm bg-white p-6 shadow-2xl"
          >
            <div className="flex items-start gap-3">
              <div className="grid h-10 w-10 flex-none place-items-center rounded-full bg-rose-100 text-rose-600">
                <Trash2 className="h-5 w-5" />
              </div>
              <div>
                <div className="text-base font-medium text-fg">确认删除该素材？</div>
                <p className="mt-1 text-sm text-fg-muted">
                  删除后将无法恢复，请谨慎操作。
                </p>
              </div>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <button
                onClick={() => setPendingRemoveId(null)}
                className="btn-ghost h-9 px-4"
              >
                取消
              </button>
              <button
                onClick={() => {
                  const id = pendingRemoveId;
                  setPendingRemoveId(null);
                  if (id) {
                    onRemoveUploaded?.(id);
                    setPickedIds((s) => {
                      const next = new Set(s);
                      next.delete(id);
                      return next;
                    });
                  }
                }}
                className="rounded-xl bg-rose-600 px-4 py-2 text-sm font-medium text-white transition hover:brightness-110"
              >
                删除
              </button>
            </div>
          </div>
        </div>
      )}
    </div>,
    document.body
  );
}

/* ============= 我的资产视图 ============= */
function AssetsView({
  tab, setTab, source, setSource, keyword, setKeyword, pickedIds, setPickedIds, onToggle, onUpload, onAskRemove,
  uploadedFiles, uploading, showMockAssets, accept,
}: {
  tab: typeof TABS[number]; setTab: (t: typeof TABS[number]) => void;
  source: typeof SOURCES[number]; setSource: (s: typeof SOURCES[number]) => void;
  keyword: string; setKeyword: (s: string) => void;
  pickedIds: Set<string>; setPickedIds: (s: Set<string>) => void;
  onToggle: (id: string) => void;
  onUpload: (files: FileList | null) => void;
  onAskRemove: (id: string) => void;
  uploadedFiles: PickedMedia[];
  uploading: { id: string; name: string; progress: number; type: 'image' | 'video' | 'audio' }[];
  showMockAssets: boolean;
  accept: string;
}) {
  // 用户上传文件（按 tab 过滤，tab 是中文"图片/视频/音频"，要转成英文 type）
  const tabToType: Record<typeof tab, string> = { '图片': 'image', '视频': 'video', '音频': 'audio' };
  const kw = keyword.trim();
  // 全部 = uploaded + mock；我上传的 = 只 uploaded；AI生成的 = 只 mock AI
  const showUploaded = source === '全部' || source === '我上传的';
  const showMock = showMockAssets && (source === '全部' || source === 'AI生成的');
  const myUploaded = showUploaded
    ? uploadedFiles.filter((u) => u.type === tabToType[tab] && (kw === '' || u.name.includes(kw)))
    : [];
  // mock 资产按 tab/source/keyword 过滤
  const filtered = showMock
    ? ASSETS.filter(
        (a) => a.type === tabToType[tab] && (source === '全部' || a.source === source) &&
               (kw === '' || a.name.includes(kw))
      )
    : [];

  return (
    <>
      {/* 工具条 */}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button className="btn-ghost h-9 px-3">
          <span className="mr-1 text-fg-subtle">📁</span>
          全部资产
          <ChevronDown className="h-3.5 w-3.5" />
        </button>
        <div className="relative min-w-[220px] flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-subtle" />
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="按文件名搜索素材"
            className="h-9 w-full rounded-xl border border-bg-line bg-bg-soft/60 pl-9 pr-3 text-sm text-fg outline-none placeholder:text-fg-subtle focus:border-brand/60"
          />
        </div>
      </div>

      {/* 二级过滤 */}
      <div className="mt-3 flex items-center justify-between">
        <div className="flex items-center gap-1">
          {TABS.map((t) => (
            <button
              key={t}
              onClick={() => {
                setTab(t);
                // 切换 tab 时清空选择
                setPickedIds(new Set());
              }}
              className={cn(
                'rounded-full px-3 py-1 text-sm transition',
                tab === t
                  ? 'bg-brand-50 text-brand'
                  : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
              )}
            >
              {t}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1">
          {SOURCES.map((s) => (
            <button
              key={s}
              onClick={() => {
                setSource(s);
                // 切换 source 时清空选择（防止跨 source 累积选中）
                setPickedIds(new Set());
              }}
              className={cn(
                'rounded-full px-3 py-1 text-xs transition',
                source === s
                  ? 'bg-fg text-white'
                  : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
              )}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      {/* 网格 */}
      <div className="mt-4 rounded-xl border border-bg-line/60 bg-bg-soft/30 p-4">
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-5">
          <AddMaterialCard
            key="upload-card"
            label="上传素材"
            hint="图片 / 视频 / 音频"
            onUploadFiles={onUpload}
            accept={accept}
            className="rounded-xl"
            iconClassName="h-8 w-8"
          />

          {/* 上传中（带进度） */}
          {uploading.map((u) => (
            <div
              key={u.id}
              className="relative aspect-square overflow-hidden rounded-xl border border-brand bg-white"
            >
              <div className="flex h-full w-full flex-col items-center justify-center gap-2 p-2">
                {/* 圆形进度圈 */}
                <div className="relative grid h-12 w-12 place-items-center">
                  <svg className="absolute inset-0 -rotate-90" viewBox="0 0 48 48">
                    <circle cx="24" cy="24" r="20" fill="none" stroke="currentColor" strokeWidth="3" className="text-bg-soft" />
                    <circle
                      cx="24" cy="24" r="20" fill="none" stroke="currentColor" strokeWidth="3"
                      strokeDasharray={`${2 * Math.PI * 20}`}
                      strokeDashoffset={`${2 * Math.PI * 20 * (1 - u.progress / 100)}`}
                      strokeLinecap="round"
                      className="text-brand transition-all"
                    />
                  </svg>
                  <span className="text-[11px] font-semibold text-fg">{Math.round(u.progress)}%</span>
                </div>
                <div className="text-[10px] text-fg-muted">上传中</div>
                <div className="truncate w-full text-center text-[10px] text-fg-subtle">{u.name}</div>
              </div>
            </div>
          ))}

          {/* 用户已上传的（按 tab 过滤） */}
          {myUploaded.map((a) => {
            const selected = pickedIds.has(a.id);
            return (
              <div
                key={a.id}
                className={cn(
                  'group relative aspect-square cursor-pointer overflow-hidden rounded-xl border bg-bg-card text-left transition',
                  selected
                    ? 'border-brand ring-2 ring-brand/30'
                    : 'border-bg-line hover:border-brand/50'
                )}
                onClick={() => onToggle(a.id)}
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={a.url} alt={a.name} className="h-full w-full object-cover" />
                {selected && (
                  <span className="absolute right-1.5 top-1.5 grid h-5 w-5 place-items-center rounded-full bg-brand text-white shadow-glow">
                    <Check className="h-3 w-3" />
                  </span>
                )}
                {/* hover 左上角垃圾桶按钮：点击弹确认弹窗 */}
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onAskRemove(a.id);
                  }}
                  className="absolute left-1.5 top-1.5 grid h-5 w-5 place-items-center rounded-full bg-black/60 text-white opacity-0 transition hover:bg-rose-600 group-hover:opacity-100"
                  aria-label="删除素材"
                >
                  <Trash2 className="h-3 w-3" />
                </button>
                <div className="absolute inset-x-0 bottom-0 truncate bg-gradient-to-t from-black/60 to-transparent px-2 py-1 text-[10px] text-white">
                  {a.name}
                </div>
              </div>
            );
          })}

          {filtered.map((a) => {
            const selected = pickedIds.has(a.id);
            return (
              <button
                key={a.id}
                onClick={() => onToggle(a.id)}
                className={cn(
                  'group relative aspect-square overflow-hidden rounded-xl border bg-bg-card text-left transition',
                  selected
                    ? 'border-brand ring-2 ring-brand/30'
                    : 'border-bg-line hover:border-brand/50'
                )}
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={a.url} alt={a.name} className="h-full w-full object-cover" />
                {selected && (
                  <span className="absolute right-1.5 top-1.5 grid h-5 w-5 place-items-center rounded-full bg-brand text-white shadow-glow">
                    <Check className="h-3 w-3" />
                  </span>
                )}
                <div className="absolute inset-x-0 bottom-0 truncate bg-gradient-to-t from-black/60 to-transparent px-2 py-1 text-[10px] text-white">
                  {a.name}
                </div>
              </button>
            );
          })}

          {filtered.length === 0 && myUploaded.length === 0 && uploading.length === 0 && (
            <div className="col-span-full grid place-items-center py-10 text-center text-xs text-fg-subtle">
              没有匹配的素材
            </div>
          )}
        </div>
      </div>
    </>
  );
}

/* ============= 角色库视图 ============= */
function RolesView({
  category, setCategory, keyword, setKeyword, pickedIds, onToggle,
}: {
  category: string;
  setCategory: (c: string) => void;
  keyword: string; setKeyword: (s: string) => void;
  pickedIds: Set<string>; onToggle: (id: string) => void;
}) {
  const list = ROLES[category] ?? [];
  const filtered = keyword.trim()
    ? list.filter((r) => r.name.includes(keyword.trim()))
    : list;

  return (
    <>
      {/* 工具条：分类下拉 + 搜索 */}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <RoleCategorySelect
          value={category}
          options={ROLE_CATEGORIES}
          onChange={setCategory}
        />
        <div className="relative min-w-[220px] flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-subtle" />
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="按文件名搜索角色"
            className="h-9 w-full rounded-xl border border-bg-line bg-bg-soft/60 pl-9 pr-3 text-sm text-fg outline-none placeholder:text-fg-subtle focus:border-brand/60"
          />
        </div>
      </div>

      {/* 角色网格 */}
      <div className="mt-4 rounded-xl border border-bg-line/60 bg-bg-soft/30 p-4">
        {filtered.length === 0 ? (
          <div className="grid place-items-center py-10 text-center text-xs text-fg-subtle">
            该分类下暂无角色
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4">
            {filtered.map((r) => {
              const selected = pickedIds.has(r.id);
              return (
                <button
                  key={r.id}
                  onClick={() => onToggle(r.id)}
                  className={cn(
                    'group relative overflow-hidden rounded-xl border bg-white text-left transition',
                    selected
                      ? 'border-emerald-500 ring-2 ring-emerald-300/60'
                      : 'border-bg-line hover:border-emerald-400'
                  )}
                >
                  <div className="relative aspect-[3/4] w-full overflow-hidden">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={r.url} alt={r.name} className="h-full w-full object-cover" />
                    {/* 左下：勾选框 */}
                    <span
                      className={cn(
                        'absolute bottom-2 left-2 grid h-5 w-5 place-items-center rounded border bg-white shadow-soft',
                        selected ? 'border-emerald-500 bg-emerald-500 text-white' : 'border-bg-line'
                      )}
                    >
                      {selected && <Check className="h-3 w-3" />}
                    </span>
                    {/* 右下：绿盾标记 */}
                    <span className="absolute bottom-2 right-2 grid h-5 w-5 place-items-center rounded-full bg-emerald-500 text-white shadow-soft">
                      <ShieldCheck className="h-3 w-3" />
                    </span>
                  </div>
                  <div className="px-2 py-1.5">
                    <div className="truncate text-xs text-fg">{r.name}</div>
                    <div className="mt-0.5 flex items-center justify-between text-[10px] text-fg-subtle">
                      <span>{r.date}</span>
                      <span>{r.size}</span>
                    </div>
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </>
  );
}

/* ============= Mock 数据 ============= */
const ASSETS: (PickedMedia & { source: '我上传的' | 'AI生成的' })[] = [
  { id: 'a1', type: 'image', url: 'https://picsum.photos/seed/a1/300', name: '商品-主图-01.png', source: '我上传的' },
  { id: 'a2', type: 'image', url: 'https://picsum.photos/seed/a2/300', name: '商品-主图-02.png', source: '我上传的' },
  { id: 'a3', type: 'image', url: 'https://picsum.photos/seed/a3/300', name: 'AI-banner.png', source: 'AI生成的' },
  { id: 'a4', type: 'image', url: 'https://picsum.photos/seed/a4/300', name: 'AI-主图.png', source: 'AI生成的' },
  { id: 'a5', type: 'image', url: 'https://picsum.photos/seed/a5/300', name: 'AI-详情页.png', source: 'AI生成的' },
  { id: 'a6', type: 'image', url: 'https://picsum.photos/seed/a6/300', name: 'AI-场景图.png', source: 'AI生成的' },
  { id: 'a7', type: 'image', url: 'https://picsum.photos/seed/a7/300', name: 'AI-海报.png', source: 'AI生成的' },
  { id: 'a8', type: 'image', url: 'https://picsum.photos/seed/a8/300', name: 'AI-商品.png', source: 'AI生成的' },
  { id: 'a9', type: 'image', url: 'https://picsum.photos/seed/a9/300', name: 'AI-背景.png', source: 'AI生成的' },
];
