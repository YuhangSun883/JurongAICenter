'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Upload, X, Search,
  Check, ShieldCheck, Trash2, Loader2, Music, ChevronDown, ChevronRight, CornerDownRight,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { AddMaterialCard } from './AddMaterialCard';
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
const SOURCES = ['全部资产', '我上传的', 'AI生成的'] as const;

const PAGE_SIZE = 24;

/* ============= 库类型徽章：统一普通/虚拟人/真人/AI/我的 视觉 ============= */
type LibraryTypeBadgeKey = 'normal' | 'virtual_human' | 'real_person' | 'ai' | 'mine';

interface LibraryTypeBadgeStyle {
  /** 圆点颜色（用于下拉前缀等场景） */
  dotClass: string;
  /** 标签文本 */
  label: string;
  /** 徽章底色（深底白字） */
  badgeClass: string;
  /** 徽章浅底（柔和高亮） */
  badgeSoftClass: string;
}

/** 库类型 → 徽章样式（颜色与 AssetsView 卡片保持一致） */
const LIB_TYPE_STYLE: Record<LibraryTypeBadgeKey, LibraryTypeBadgeStyle> = {
  normal: {
    label: '普通',
    dotClass: 'bg-[#7c8cff]',
    badgeClass: 'bg-[#5b9aff] text-white',
    badgeSoftClass: 'bg-[#eaf3ff] text-[#006cff]',
  },
  virtual_human: {
    label: '虚拟人',
    dotClass: 'bg-[#7c3aed]',
    badgeClass: 'bg-[#7c3aed] text-white',
    badgeSoftClass: 'bg-[#ede9fe] text-[#7c3aed]',
  },
  real_person: {
    label: '真人',
    dotClass: 'bg-[#fbbf24]',
    badgeClass: 'bg-[#fbbf24] text-[#92400e]',
    badgeSoftClass: 'bg-[#fef3c7] text-[#92400e]',
  },
  ai: {
    label: 'AI',
    dotClass: 'bg-[#1673ff]',
    badgeClass: 'bg-[#1673ff] text-white',
    badgeSoftClass: 'bg-[#eaf3ff] text-[#1673ff]',
  },
  mine: {
    label: '我的',
    dotClass: 'bg-[#1673ff]',
    badgeClass: 'bg-[#1673ff] text-white',
    badgeSoftClass: 'bg-[#eaf3ff] text-[#1673ff]',
  },
};

/** 把库对象归一为类型键 */
export function getLibraryTypeKey(lib: MediaLibrary | null | undefined): LibraryTypeBadgeKey {
  if (!lib) return 'mine';
  if (lib.type === 'system-ai') return 'ai';
  if (lib.type === 'system-uploaded') return 'mine';
  const biz = (lib.bizType ?? 'normal') as string;
  if (biz === 'virtual_human') return 'virtual_human';
  if (biz === 'real_person') return 'real_person';
  return 'normal';
}

/** 紧凑的圆点 + 文字徽章，可在 select 选项里做前缀或独立显示 */
export function LibraryTypeBadge({
  typeKey,
  variant = 'soft',
  showDot = true,
  className,
}: {
  typeKey: LibraryTypeBadgeKey;
  variant?: 'soft' | 'solid' | 'dot';
  showDot?: boolean;
  className?: string;
}) {
  const s = LIB_TYPE_STYLE[typeKey];
  if (variant === 'dot') {
    return <span className={cn('inline-block h-2 w-2 rounded-full', s.dotClass, className)} aria-hidden />;
  }
  const colorClass = variant === 'solid' ? s.badgeClass : s.badgeSoftClass;
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wide',
        colorClass,
        className
      )}
    >
      {showDot && <span className={cn('h-1.5 w-1.5 rounded-full', s.dotClass)} aria-hidden />}
      {s.label}
    </span>
  );
}

/* ============= 角色库：写死数据 ============= */
/**
 * 2026-08-15：根据用户确认，角色库不归我们管，只展示写死的示例数据。
 * 这里固定放几张占位图 + 角色名，不调任何后端接口。
 */
const HARDCODED_ROLES: MediaRole[] = [
  { id: 'r-1', name: '示例角色 1', category: 'face', imageUrl: 'https://placehold.co/300x400/1673ff/ffffff?text=R1', description: '写死数据 · 占位' },
  { id: 'r-2', name: '示例角色 2', category: 'face', imageUrl: 'https://placehold.co/300x400/7c3aed/ffffff?text=R2', description: '写死数据 · 占位' },
  { id: 'r-3', name: '示例角色 3', category: 'face', imageUrl: 'https://placehold.co/300x400/10b981/ffffff?text=R3', description: '写死数据 · 占位' },
  { id: 'r-4', name: '示例角色 4', category: 'body', imageUrl: 'https://placehold.co/300x400/f59e0b/ffffff?text=R4', description: '写死数据 · 占位' },
  { id: 'r-5', name: '示例角色 5', category: 'body', imageUrl: 'https://placehold.co/300x400/dc2626/ffffff?text=R5', description: '写死数据 · 占位' },
  { id: 'r-6', name: '示例角色 6', category: 'body', imageUrl: 'https://placehold.co/300x400/0ea5e9/ffffff?text=R6', description: '写死数据 · 占位' },
];

const HARDCODED_ROLE_CATEGORIES: MediaRoleCategory[] = [
  { key: 'all', label: '全部' },
  { key: 'face', label: '人脸' },
  { key: 'body', label: '全身' },
];

/* ============= 2026-08-15 V19：把扁平的库列表扁平化为带 depth 的树序 ============= */
/**
 * 用途：在自定义树形下拉里展示父库 → 子库 → 孙库 …
 * - 系统库 + 自定义根库：depth=0
 * - 子库：depth = 父库 depth + 1
 * - 同一父库下的兄弟按 sortOrder ASC, id ASC 排
 * - 父库被删的孤儿节点会按根库对待（深度 0）
 */
/**
 * 把库按树序展平。V26：默认全部折叠——只返回根库；只有 expanded 集合中的父库会展开其直接子库（递归）。
 * 返回的项带 hasChildren 标记，便于 UI 决定是否渲染展开按钮。
 */
function flattenLibraryTree(
  list: MediaLibrary[],
  expanded: Set<number>,
): (MediaLibrary & { depth: number; hasChildren: boolean })[] {
  // 排序：sortOrder ASC, id ASC
  const sorted = [...list].sort(
    (a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id
  );
  // 构造 children 列表
  const childrenOf = new Map<number | null, MediaLibrary[]>();
  sorted.forEach((l) => {
    const pid = l.parentId ?? null;
    if (!childrenOf.has(pid)) childrenOf.set(pid, []);
    childrenOf.get(pid)!.push(l);
  });
  // DFS 展平（按 expanded 决定是否递归）
  const flat: (MediaLibrary & { depth: number; hasChildren: boolean })[] = [];
  const walk = (pid: number | null, depth: number) => {
    const kids = childrenOf.get(pid) ?? [];
    kids.forEach((k) => {
      const hasChildren = (childrenOf.get(k.id)?.length ?? 0) > 0;
      flat.push({ ...k, depth, hasChildren });
      if (hasChildren && expanded.has(k.id)) {
        walk(k.id, depth + 1);
      }
    });
  };
  walk(null, 0);
  return flat;
}

export function MediaPickerDialog({
  open, onClose, onConfirm, max = 12, uploadedFiles: propUploadedFiles, onUploadFiles, onRemoveUploaded,
  initialTab = '图片', title = '选择参考素材', subtitle = '支持图片、视频和音频参考。',
  accept = 'image/*,video/*,audio/*',
}: MediaPickerDialogProps) {
  const [tab, setTab] = useState<typeof TABS[number]>(initialTab);
  const [source, setSource] = useState<typeof SOURCES[number]>('全部资产');
  const [keyword, setKeyword] = useState('');
  const [activeTopTab, setActiveTopTab] = useState<'assets' | 'roles'>('assets');
  const [pickedIds, setPickedIds] = useState<Set<string>>(new Set());
  const [mounted, setMounted] = useState(false);
  const [roleCategory, setRoleCategory] = useState<string>('all');
  /** 待删除的素材 id（弹确认弹窗用） */
  const [pendingRemoveId, setPendingRemoveId] = useState<string | null>(null);

  /** ============ 真实 API 数据 ============ */
  const [libraries, setLibraries] = useState<MediaLibrary[]>([]);
  const [assets, setAssets] = useState<MediaAsset[]>([]);
  // 角色库：写死数据（不调后端）
  const [roles] = useState<MediaRole[]>(HARDCODED_ROLES);
  const [roleCategories] = useState<MediaRoleCategory[]>(HARDCODED_ROLE_CATEGORIES);
  // V23：currentLibId 始终绑定到具体库 id（不保留 null 的"虚拟汇总"状态）。
  // 默认值在 libs 加载完后会自动对齐到 system-uploaded「我的资产」库。
  const [currentLibId, setCurrentLibId] = useState<number | null>(null);
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

  // 打开弹窗：拉库列表 + 第一页素材（角色库为写死数据，不调接口）
  useEffect(() => {
    if (!open) return;
    setTab(initialTab);
    setPickedIds(new Set());
    setAssetsPage(1);
    setDataError(null);
    (async () => {
      try {
        const libs = await mediaApi.listLibraries();
        setLibraries(libs);
        // V23：默认选「我的资产」系统库（跨库汇总，代替之前的"全部资产"虚拟入口）。
        // 已有 currentLibId（来自用户已点选）则保持不动。
        if (currentLibId == null) {
          const sysUploaded = libs.find((l) => l.type === 'system-uploaded');
          if (sysUploaded) setCurrentLibId(sysUploaded.id);
        }
      } catch (err) {
        console.warn('[media-picker] init load failed:', err);
        setDataError('加载数据失败,请稍后重试');
      }
    })();
  }, [open, initialTab, currentLibId]);

  /** 标签 → 类型映射（提到 useEffect 之前，避免引用未定义） */
  const tabToType: Record<typeof TABS[number], string> = { '图片': 'image', '视频': 'video', '音频': 'audio' };
  /** 源 → API 字段映射（提到 useEffect 之前） */
  function sourceToApiSource(s: typeof SOURCES[number]): string | undefined {
    if (s === '我上传的') return 'uploaded';
    if (s === 'AI生成的') return 'ai-generated';
    return undefined;
  }

  // V23：把 currentLibId 转译为后端查询参数。
  // system-uploaded「我的资产」是跨库汇总语义，需要传 undefined 触发后端汇总逻辑。
  // 其余库传具体 id。
  const sel = currentLibId == null
    ? null
    : libraries.find((l) => l.id === currentLibId) ?? null;
  const queryLibId = sel?.type === 'system-uploaded' ? undefined : currentLibId ?? undefined;

  // 拉素材（库 + 类型 + 来源 + 关键词 + 页码变化时）
  useEffect(() => {
    if (!open) return;
    if (activeTopTab !== 'assets') return;
    setAssetsLoading(true);
    (async () => {
      try {
        const res = await mediaApi.listAssets({
          libraryId: queryLibId,
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
  }, [open, activeTopTab, currentLibId, tab, source, keyword, assetsPage, libraries]);

  // 过滤后的角色（写死数据，按 category 筛；'all' 表示全部）
  const filteredRoles = activeTopTab === 'roles'
    ? (!roleCategory || roleCategory === 'all'
        ? roles
        : roles.filter((r) => r.category === roleCategory))
    : [];

  // ============ 派生 ============
  const showUploadedHere = activeTopTab === 'assets' && (source === '全部资产' || source === '我上传的');
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
          libraryId: queryLibId,
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
  const showUploaded = source === '全部资产' || source === '我上传的';
  const PAGE_SIZE = 24;
  // 已入库素材的 URL 集合：本地 blob 素材与 assets 重复时去重，避免同一张图展示两次
  const assetUrls = useMemo(() => new Set(assets.map((a) => a.url)), [assets]);

  // 2026-08-15 V19：自定义下拉 popover
  const [libMenuOpen, setLibMenuOpen] = useState(false);
  const libMenuRef = useRef<HTMLDivElement | null>(null);
  // V26：库下拉的展开/折叠状态。默认全部折叠，子库隐藏。
  const [expandedLibIds, setExpandedLibIds] = useState<Set<number>>(new Set());
  function toggleExpand(libId: number) {
    setExpandedLibIds((s) => {
      const next = new Set(s);
      if (next.has(libId)) next.delete(libId);
      else next.add(libId);
      return next;
    });
  }
  // 注：弹窗未打开时整组件 return null（见 MediaPickerDialog 顶部），
  // 每次打开都是全新挂载，expandedLibIds 自动回到默认空集，无需额外重置。

  // 2026-08-15 V19：库扁平化为带 depth 的树序
  const flatLibs = useMemo(
    () => flattenLibraryTree(libraries, expandedLibIds),
    [libraries, expandedLibIds]
  );

  // 当前选中的库
  const selectedLib = currentLibId == null ? null : libraries.find((l) => l.id === currentLibId) ?? null;

  // 点外面关闭
  useEffect(() => {
    if (!libMenuOpen) return;
    const onDocClick = (e: MouseEvent) => {
      if (libMenuRef.current && !libMenuRef.current.contains(e.target as Node)) {
        setLibMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [libMenuOpen]);

  return (
    <>
      {/* 工具条 */}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        {/* 2026-08-15 V19：库下拉改树形（自定义 popover + 缩进） */}
        <div ref={libMenuRef} className="relative">
          <button
            type="button"
            onClick={() => setLibMenuOpen((o) => !o)}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-bg-line bg-bg-card px-3 text-sm text-fg outline-none transition hover:border-brand/40"
          >
            {/* V23：currentLibId 默认就是 system-uploaded「我的资产」id，selectedLib 始终能查到 */}
            <LibraryTypeBadge typeKey={getLibraryTypeKey(selectedLib)} />
            <span className="max-w-[180px] truncate">
              {selectedLib?.name ?? '我的资产'}
            </span>
            {selectedLib && (
              <span className="text-[11px] text-fg-subtle">({selectedLib.assetCount ?? 0})</span>
            )}
            <ChevronDown className={cn('h-3.5 w-3.5 text-fg-muted transition', libMenuOpen && 'rotate-180')} />
          </button>
          {libMenuOpen && (
            <div className="card absolute left-0 top-full z-50 mt-1.5 max-h-[360px] min-w-[260px] overflow-auto bg-white p-1 shadow-2xl">
              {/* V23：去掉"全部资产"虚拟入口。system-uploaded「我的资产」就是跨库汇总，二选一即可。 */}
              {flatLibs.length === 0 ? (
                <div className="px-2.5 py-3 text-center text-xs text-fg-subtle">暂无资产库</div>
              ) : (
                flatLibs.map((l) => {
                  const active = currentLibId === l.id;
                  const isChild = (l.depth ?? 0) > 0;
                  const isExpanded = expandedLibIds.has(l.id);
                  return (
                    <div
                      key={l.id}
                      className={cn(
                        'flex w-full items-center gap-1.5 rounded-md py-1.5 pr-2.5 text-left text-sm transition',
                        active ? 'bg-brand-50 text-brand' : 'text-fg hover:bg-bg-soft'
                      )}
                      style={{ paddingLeft: `${8 + (l.depth ?? 0) * 16}px` }}
                    >
                      {/* V26：父库前的展开/折叠按钮（不冒泡到下面选库按钮） */}
                      {l.hasChildren ? (
                        <button
                          type="button"
                          onClick={(e) => { e.stopPropagation(); toggleExpand(l.id); }}
                          className="flex h-5 w-5 shrink-0 items-center justify-center rounded text-fg-muted transition hover:bg-bg-soft hover:text-fg"
                          title={isExpanded ? '折叠子库' : '展开子库'}
                        >
                          {isExpanded
                            ? <ChevronDown className="h-3.5 w-3.5" />
                            : <ChevronRight className="h-3.5 w-3.5" />}
                        </button>
                      ) : isChild ? (
                        <CornerDownRight className="h-3 w-3 shrink-0 text-fg-subtle" />
                      ) : (
                        <span className="inline-block h-3.5 w-3.5 shrink-0" />
                      )}
                      <button
                        type="button"
                        onClick={() => { onChangeLibrary(l.id); setLibMenuOpen(false); }}
                        className="flex flex-1 items-center gap-2 text-left"
                        title={l.name}
                      >
                        <LibraryTypeBadge typeKey={getLibraryTypeKey(l)} />
                        <span className="flex-1 truncate">{l.name}</span>
                        <span className="text-[10px] text-fg-subtle">{l.assetCount ?? 0}</span>
                        {active && <Check className="h-3.5 w-3.5" />}
                      </button>
                    </div>
                  );
                })
              )}
            </div>
          )}
        </div>
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
        {/* V24：AI 库下只有 AI 生成的素材，"全部/我上传的/AI生成的" 分类无意义，隐藏整条筛选 */}
        {selectedLib?.type !== 'system-ai' && (
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
        )}
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
