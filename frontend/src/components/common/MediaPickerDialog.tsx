'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Upload, X, ChevronDown, Search,
  Check, ShieldCheck, Trash2, Loader2, Music,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { AddMaterialCard } from './AddMaterialCard';
import { RoleCategorySelect, type RoleCategory } from './RoleCategorySelect';
import { mediaApi } from '@/api/media';
import type { MediaAsset, MediaLibrary, MediaRole, MediaRoleCategory } from '@/types/media';

/** 弹窗内可选的素材 */
export interface PickedMedia {
  id: string;
  type: 'image' | 'video' | 'audio';
  url: string;
  name: string;
}

export interface MediaPickerDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm?: (picked: PickedMedia[]) => void;
  /** 外部上传回调：传入文件，父组件处理（显示进度、保存文件等）。返回已上传的 PickedMedia[] */
  onUploadFiles?: (files: FileList | null) => PickedMedia[] | Promise<PickedMedia[]>;
  /** 删除已上传素材回调 */
  onRemoveUploaded?: (id: string) => void;
  max?: number;
  /** 已上传的素材（由父组件持有，关闭弹窗后保留） */
  uploadedFiles?: PickedMedia[];
  showMockAssets?: boolean;
  /** 默认 false: 走真实 API（我的资产 + 角色库） */
  initialTab?: typeof TABS[number];
  title?: string;
  subtitle?: string;
  accept?: string;
}

const TABS = ['图片', '视频', '音频'] as const;
const SOURCES = ['全部', '我上传的', 'AI生成的'] as const;

interface RoleItem {
  id: string;
  name: string;
  url: string;
  date: string;
  size: string;
}

const PAGE_SIZE = 24;

export function MediaPickerDialog({
  open, onClose, onConfirm, max = 12, uploadedFiles: propUploadedFiles, onUploadFiles, onRemoveUploaded,
  initialTab = '图片', title = '选择参考素材', subtitle = '支持图片、视频和音频参考。',
  accept = 'image/*,video/*,audio/*',
}: MediaPickerDialogProps) {
  const [tab, setTab] = useState<typeof TABS[number]>(initialTab);
  const [source, setSource] = useState<typeof SOURCES[number]>('全部');
  const [keyword, setKeyword] = useState('');
  const [activeTopTab, setActiveTopTab] = useState<'assets' | 'roles'>('assets');
  const [pickedIds, setPickedIds] = useState<Set<string>>(new Set());
  const [mounted, setMounted] = useState(false);
  const [roleCategory, setRoleCategory] = useState<string>('face');
  /** 待删除的素材 id（弹确认弹窗用） */
  const [pendingRemoveId, setPendingRemoveId] = useState<string | null>(null);

  /** ============ 真实 API 数据 ============ */
  const [libraries, setLibraries] = useState<MediaLibrary[]>([]);
  const [assets, setAssets] = useState<MediaAsset[]>([]);
  const [roles, setRoles] = useState<MediaRole[]>([]);
  const [roleCategories, setRoleCategories] = useState<MediaRoleCategory[]>([]);
  const [currentLibId, setCurrentLibId] = useState<number | null>(null);  // null = 全部资产
  const [assetsLoading, setAssetsLoading] = useState(false);
  const [assetsPage, setAssetsPage] = useState(1);
  const [assetsTotal, setAssetsTotal] = useState(0);
  const [dataError, setDataError] = useState<string | null>(null);

  /** 已上传素材：完全由父组件持有（prop 模式） */
  const uploadedFiles = propUploadedFiles ?? [];

  useEffect(() => setMounted(true), []);

  // ESC 关闭
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

  // 打开弹窗：拉库列表 + 角色分类 + 第一页素材
  useEffect(() => {
    if (!open) return;
    setTab(initialTab);
    setPickedIds(new Set());
    setAssetsPage(1);
    setDataError(null);
    (async () => {
      try {
        const [libs, cats, firstRoles] = await Promise.all([
          mediaApi.listLibraries(),
          mediaApi.listRoleCategories(),
          mediaApi.listRoles(),  // 全量,后面按 category 筛
        ]);
        setLibraries(libs);
        setRoleCategories(cats);
        setRoles(firstRoles);
      } catch (err) {
        console.warn('[media-picker] init load failed:', err);
        setDataError('加载数据失败,请稍后重试');
      }
    })();
  }, [open, initialTab]);

  /** 标签 → 类型映射（提到 useEffect 之前，避免引用未定义） */
  const tabToType: Record<typeof TABS[number], string> = { '图片': 'image', '视频': 'video', '音频': 'audio' };
  /** 源 → API 字段映射（提到 useEffect 之前） */
  function sourceToApiSource(s: typeof SOURCES[number]): string | undefined {
    if (s === '我上传的') return 'uploaded';
    if (s === 'AI生成的') return 'ai-generated';
    return undefined;
  }

  // 拉素材（库 + 类型 + 来源 + 关键词 + 页码变化时）
  useEffect(() => {
    if (!open) return;
    if (activeTopTab !== 'assets') return;
    setAssetsLoading(true);
    (async () => {
      try {
        const res = await mediaApi.listAssets({
          libraryId: currentLibId,
          type: tabToType[tab],
          source: sourceToApiSource(source),
          keyword,
          page: assetsPage,
          pageSize: PAGE_SIZE,
        });
        setAssets(res.items);
        setAssetsTotal(res.total);
      } catch (err) {
        console.warn('[media-picker] listAssets failed:', err);
        setAssets([]);
        setAssetsTotal(0);
        setDataError('加载素材失败');
      } finally {
        setAssetsLoading(false);
      }
    })();
  }, [open, activeTopTab, currentLibId, tab, source, keyword, assetsPage]);

  // 过滤后的角色（按 category 筛）
  const filteredRoles = activeTopTab === 'roles'
    ? (roleCategory ? roles.filter((r) => r.category === roleCategory) : roles)
    : [];

  // ============ 派生 ============
  const showUploadedHere = activeTopTab === 'assets' && (source === '全部' || source === '我上传的');
  const assetUrls = new Set(assets.map((asset) => asset.url).filter(Boolean));
  const visiblePickedCount =
    (showUploadedHere
      ? uploadedFiles.filter((u) => u.type === tabToType[tab] && pickedIds.has(u.id)).length
      : 0) +
    (activeTopTab === 'assets'
      ? assets.filter((a) => pickedIds.has(String(a.id))).length
      : roles.filter((r) => pickedIds.has(String(r.id))).length);

  if (!open || !mounted) return null;

  function toggle(id: string) {
    setPickedIds((s) => {
      const next = new Set(s);
      if (next.has(id)) {
        next.delete(id);
        return next;
      }
      if (next.size >= max) return s;
      next.add(id);
      return next;
    });
  }

  function handleConfirm() {
    // 本地上传但尚未入库的素材（已入库的会在 assets 中处理）
    const assetUrlSet = new Set(assets.map((a) => a.url));
    const uploadedPicked = uploadedFiles.filter((u) => pickedIds.has(u.id) && !assetUrlSet.has(u.url));
    const assetPicked: PickedMedia[] = assets
      .filter((a) => pickedIds.has(String(a.id)))
      .map((a) => ({ id: String(a.id), type: a.type as 'image' | 'video' | 'audio', url: a.url, name: a.name }));
    const rolePicked: PickedMedia[] = roles
      .filter((r) => pickedIds.has(String(r.id)))
      .map((r) => ({ id: String(r.id), type: 'image' as const, url: r.imageUrl, name: r.name }));
    const all = [...uploadedPicked, ...assetPicked, ...rolePicked];
    onConfirm?.(all);
    onClose();
  }

  async function handleUploadFiles(files: FileList | null) {
    if (!files) return;
    const list = Array.from(files);
    if (onUploadFiles) {
      const items = await onUploadFiles(files);
      // 自动选中新上传
      setPickedIds((s) => {
        const next = new Set(s);
        items.forEach((it) => { if (next.size < max) next.add(it.id); });
        return next;
      });
      // 上传完后刷新素材库
      if (activeTopTab === 'assets') {
        const res = await mediaApi.listAssets({
          libraryId: currentLibId,
          type: tabToType[tab],
          source: sourceToApiSource(source),
          keyword,
          page: 1,
          pageSize: PAGE_SIZE,
        });
        setAssets(res.items);
        setAssetsTotal(res.total);
        setAssetsPage(1);
      }
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
                  <span className="absolute -bottom-px left-0 right-0 h-0.5 rounded-full bg-brand" aria-hidden />
                )}
              </button>
            ))}
          </div>

          {dataError && (
            <div className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600">
              {dataError}
            </div>
          )}

          {activeTopTab === 'assets' ? (
            <AssetsView
              tab={tab} setTab={setTab}
              source={source} setSource={setSource}
              keyword={keyword} setKeyword={setKeyword}
              pickedIds={pickedIds}
              onToggle={toggle}
              onUpload={handleUploadFiles}
              onAskRemove={(id) => setPendingRemoveId(id)}
              uploadedFiles={uploadedFiles}
              libraries={libraries}
              currentLibId={currentLibId}
              onChangeLibrary={setCurrentLibId}
              assets={assets}
              assetsLoading={assetsLoading}
              assetsPage={assetsPage}
              assetsTotal={assetsTotal}
              onChangePage={setAssetsPage}
              accept={accept}
            />
          ) : (
            <RolesView
              categories={roleCategories}
              category={roleCategory} setCategory={setRoleCategory}
              keyword={keyword} setKeyword={setKeyword}
              pickedIds={pickedIds} onToggle={toggle}
              roles={filteredRoles}
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
          <div onClick={(e) => e.stopPropagation()} className="card relative w-full max-w-sm bg-white p-6 shadow-2xl">
            <div className="flex items-start gap-3">
              <div className="grid h-10 w-10 flex-none place-items-center rounded-full bg-rose-100 text-rose-600">
                <Trash2 className="h-5 w-5" />
              </div>
              <div>
                <div className="text-base font-medium text-fg">确认删除该素材？</div>
                <p className="mt-1 text-sm text-fg-muted">删除后将无法恢复，请谨慎操作。</p>
              </div>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <button onClick={() => setPendingRemoveId(null)} className="btn-ghost h-9 px-4">取消</button>
              <button
                onClick={async () => {
                  const id = pendingRemoveId;
                  setPendingRemoveId(null);
                  if (id) {
                    onRemoveUploaded?.(id);
                    setPickedIds((s) => { const n = new Set(s); n.delete(id); return n; });
                    try {
                      await mediaApi.deleteAsset(Number(id));
                    } catch (err) {
                      console.warn('[media-picker] delete failed:', err);
                    }
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
  tab, setTab, source, setSource, keyword, setKeyword, pickedIds, onToggle, onUpload, onAskRemove,
  uploadedFiles, libraries, currentLibId, onChangeLibrary,
  assets, assetsLoading, assetsPage, assetsTotal, onChangePage, accept,
}: {
  tab: typeof TABS[number]; setTab: (t: typeof TABS[number]) => void;
  source: typeof SOURCES[number]; setSource: (s: typeof SOURCES[number]) => void;
  keyword: string; setKeyword: (s: string) => void;
  pickedIds: Set<string>;
  onToggle: (id: string) => void;
  onUpload: (files: FileList | null) => void | Promise<void>;
  onAskRemove: (id: string) => void;
  uploadedFiles: PickedMedia[];
  libraries: MediaLibrary[];
  currentLibId: number | null;
  onChangeLibrary: (id: number | null) => void;
  assets: MediaAsset[];
  assetsLoading: boolean;
  assetsPage: number;
  assetsTotal: number;
  onChangePage: (p: number) => void;
  accept: string;
}) {
  const tabToType: Record<typeof tab, string> = { '图片': 'image', '视频': 'video', '音频': 'audio' };
  const showUploaded = source === '全部' || source === '我上传的';
  const PAGE_SIZE = 24;

  return (
    <>
      {/* 工具条 */}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        {/* 库下拉 */}
        <select
          value={currentLibId == null ? '' : String(currentLibId)}
          onChange={(e) => onChangeLibrary(e.target.value === '' ? null : Number(e.target.value))}
          className="h-9 rounded-lg border border-bg-line bg-bg-card px-3 text-sm text-fg outline-none focus:border-brand/60"
        >
          <option value="">全部资产</option>
          {libraries.map((l) => (
            <option key={l.id} value={String(l.id)}>
              {l.name} ({l.assetCount})
            </option>
          ))}
        </select>
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
              onClick={() => { setTab(t); onChangePage(1); }}
              className={cn(
                'rounded-full px-3 py-1 text-sm transition',
                tab === t ? 'bg-brand-50 text-brand' : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
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
              onClick={() => { setSource(s); onChangePage(1); }}
              className={cn(
                'rounded-full px-3 py-1 text-xs transition',
                source === s ? 'bg-fg text-white' : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
              )}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      {/* 网格 */}
      <div className="mt-4 rounded-xl border border-bg-line/60 bg-bg-soft/30 p-4">
        {assetsLoading ? (
          <div className="grid place-items-center py-10">
            <Loader2 className="h-6 w-6 animate-spin text-brand" />
          </div>
        ) : (
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

            {/* 用户已上传但尚未入库的素材（本地 blob URL） */}
            {/* 已通过服务器上传的素材会同时出现在 assets 中，这里需要去重 */}
            {showUploaded && uploadedFiles
              .filter((u) => u.type === tabToType[tab])
              .filter((u) => !assetUrls.has(u.url))
              .map((a) => {
                const selected = pickedIds.has(a.id);
                return (
                  <div
                    key={`local-${a.id}`}
                    className={cn(
                      'group relative aspect-square cursor-pointer overflow-hidden rounded-xl border bg-bg-card text-left transition',
                      selected ? 'border-brand ring-2 ring-brand/30' : 'border-bg-line hover:border-brand/50'
                    )}
                    onClick={() => onToggle(a.id)}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                  {a.type === 'video' ? (
                    <video
                      src={a.url}
                      className="h-full w-full object-cover"
                      muted
                      playsInline
                      preload="metadata"
                      onLoadedMetadata={(e) => {
                        const v = e.currentTarget;
                        if (v.currentTime < 0.1) v.currentTime = 0.1;
                      }}
                    />
                  ) : a.type === 'audio' ? (
                    <div className="grid h-full w-full place-items-center bg-bg-soft text-fg-muted">
                      <Music className="h-6 w-6" />
                    </div>
                  ) : (
                    <img src={a.url} alt={a.name} className="h-full w-full object-cover" />
                  )}
                    {selected && (
                      <span className="absolute right-1.5 top-1.5 grid h-5 w-5 place-items-center rounded-full bg-brand text-white shadow-glow">
                        <Check className="h-3 w-3" />
                      </span>
                    )}
                    <button
                      type="button"
                      onClick={(e) => { e.stopPropagation(); onAskRemove(a.id); }}
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

            {/* 服务端素材 */}
            {assets.map((a) => {
              const selected = pickedIds.has(String(a.id));
              return (
                <div
                  key={a.id}
                  className={cn(
                    'group relative aspect-square cursor-pointer overflow-hidden rounded-xl border bg-bg-card text-left transition',
                    selected ? 'border-brand ring-2 ring-brand/30' : 'border-bg-line hover:border-brand/50'
                  )}
                  onClick={() => onToggle(String(a.id))}
                >
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  {a.type === 'video' ? (
                    <video
                      src={a.url}
                      className="h-full w-full object-cover"
                      muted
                      playsInline
                      preload="metadata"
                      // 推进到 0.1s 强制渲染第一帧（默认 metadata 模式下不主动显示）
                      onLoadedMetadata={(e) => {
                        const v = e.currentTarget;
                        if (v.currentTime < 0.1) v.currentTime = 0.1;
                      }}
                    />
                  ) : a.type === 'audio' ? (
                    <div className="grid h-full w-full place-items-center bg-bg-soft text-fg-muted">
                      <Music className="h-6 w-6" />
                    </div>
                  ) : (
                    <img src={a.url} alt={a.name} className="h-full w-full object-cover" />
                  )}
                  {selected && (
                    <span className="absolute right-1.5 top-1.5 grid h-5 w-5 place-items-center rounded-full bg-brand text-white shadow-glow">
                      <Check className="h-3 w-3" />
                    </span>
                  )}
                  <div className="absolute inset-x-0 bottom-0 truncate bg-gradient-to-t from-black/60 to-transparent px-2 py-1 text-[10px] text-white">
                    {a.name}
                  </div>
                </div>
              );
            })}

            {/* 空状态 */}
            {assets.length === 0 && uploadedFiles.filter((u) => u.type === tabToType[tab]).length === 0 && (
              <div className="col-span-full grid place-items-center py-10 text-center text-xs text-fg-subtle">
                没有匹配的素材
              </div>
            )}
          </div>
        )}

        {/* 简单分页 */}
        {assetsTotal > PAGE_SIZE && (
          <div className="mt-3 flex items-center justify-center gap-2 text-xs">
            <button
              onClick={() => onChangePage(Math.max(1, assetsPage - 1))}
              disabled={assetsPage === 1}
              className="rounded border border-bg-line px-2 py-1 disabled:opacity-40"
            >
              上一页
            </button>
            <span className="text-fg-muted">
              {assetsPage} / {Math.ceil(assetsTotal / PAGE_SIZE)}
            </span>
            <button
              onClick={() => onChangePage(assetsPage + 1)}
              disabled={assetsPage * PAGE_SIZE >= assetsTotal}
              className="rounded border border-bg-line px-2 py-1 disabled:opacity-40"
            >
              下一页
            </button>
          </div>
        )}
      </div>
    </>
  );
}

/* ============= 角色库视图 ============= */
function RolesView({
  categories, category, setCategory, keyword, setKeyword, pickedIds, onToggle, roles,
}: {
  categories: MediaRoleCategory[];
  category: string;
  setCategory: (c: string) => void;
  keyword: string; setKeyword: (s: string) => void;
  pickedIds: Set<string>; onToggle: (id: string) => void;
  roles: MediaRole[];
}) {
  const filtered = keyword.trim()
    ? roles.filter((r) => r.name.includes(keyword.trim()))
    : roles;

  return (
    <>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <select
          value={category}
          onChange={(e) => setCategory(e.target.value)}
          className="h-9 rounded-lg border border-bg-line bg-bg-card px-3 text-sm text-fg outline-none focus:border-brand/60"
        >
          {categories.map((c) => (
            <option key={c.key} value={c.key}>{c.label}</option>
          ))}
        </select>
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

      <div className="mt-4 rounded-xl border border-bg-line/60 bg-bg-soft/30 p-4">
        {filtered.length === 0 ? (
          <div className="grid place-items-center py-10 text-center text-xs text-fg-subtle">
            该分类下暂无角色
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4">
            {filtered.map((r) => {
              const selected = pickedIds.has(String(r.id));
              return (
                <button
                  key={r.id}
                  onClick={() => onToggle(String(r.id))}
                  className={cn(
                    'group relative overflow-hidden rounded-xl border bg-white text-left transition',
                    selected
                      ? 'border-emerald-500 ring-2 ring-emerald-300/60'
                      : 'border-bg-line hover:border-emerald-400'
                  )}
                >
                  <div className="relative aspect-[3/4] w-full overflow-hidden">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={r.imageUrl} alt={r.name} className="h-full w-full object-cover" />
                    <span
                      className={cn(
                        'absolute bottom-2 left-2 grid h-5 w-5 place-items-center rounded border bg-white shadow-soft',
                        selected ? 'border-emerald-500 bg-emerald-500 text-white' : 'border-bg-line'
                      )}
                    >
                      {selected && <Check className="h-3 w-3" />}
                    </span>
                    <span className="absolute bottom-2 right-2 grid h-5 w-5 place-items-center rounded-full bg-emerald-500 text-white shadow-soft">
                      <ShieldCheck className="h-3 w-3" />
                    </span>
                  </div>
                  <div className="px-2 py-1.5">
                    <div className="truncate text-xs text-fg">{r.name}</div>
                    {r.description && (
                      <div className="mt-0.5 truncate text-[10px] text-fg-subtle">{r.description}</div>
                    )}
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
