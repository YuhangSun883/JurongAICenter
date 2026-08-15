'use client';

import { useEffect, useMemo, useRef, useState, Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import {
  Search,
  Image as ImageIcon,
  Film,
  Music2,
  X,
  Plus,
  Check,
  ArrowUp,
  ArrowDown,
  Trash2,
  Pencil,
  Info,
  Download,
  Play,
  Volume2,
  VolumeX,
  ChevronRight,
  CornerDownRight,
} from 'lucide-react';
import { mediaApi } from '@/api/media';
import type { MediaItem, MediaLibrary, MediaType } from '@/types/media';
import { cn } from '@/lib/utils';
import { getAccessToken } from '@/lib/auth-store';
import { VideoThumbnail } from '@/components/common/VideoThumbnail';
import { LibraryTypeBadge, getLibraryTypeKey } from '@/components/common/MediaPickerDialog';

type TypeFilter = 'all' | MediaType;
type SortOrder = 'desc' | 'asc';
// V25：来源筛选（按后端 MediaSource 字段）
// - all       → 全部资产（不传 source）
// - uploaded  → 我上传的
// - ai-generated → AI 生成的
type SourceFilter = 'all' | 'uploaded' | 'ai-generated';

const TYPE_TABS: { key: TypeFilter; label: string; Icon?: typeof ImageIcon }[] = [
  { key: 'all', label: '全部' },
  { key: 'image', label: '图片', Icon: ImageIcon },
  { key: 'video', label: '视频', Icon: Film },
  { key: 'audio', label: '音频', Icon: Music2 },
];

const SOURCE_TABS: { key: SourceFilter; label: string }[] = [
  { key: 'all', label: '全部资产' },
  { key: 'uploaded', label: '我上传的' },
  { key: 'ai-generated', label: 'AI生成的' },
];

/**
 * 拉取素材时使用的 libraryId
 * - "我的资产" 跨库汇总（不传 libraryId = 看全部）
 * - 其他库（AI 库 / 自定义库）按 libraryId 查自己库内素材
 */
function getQueryLibraryId(lib: MediaLibrary | null): number | undefined {
  if (!lib) return undefined;
  if (lib.type === 'system-uploaded') return undefined;
  return lib.id;
}

function formatSize(bytes?: number) {
  if (!bytes) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(s?: string) {
  if (!s) return '';
  return s.slice(0, 10);
}

function getStreamUrl(assetId: number): string {
  const token = getAccessToken();
  return `/api/media/assets/${assetId}/stream${token ? `?token=${encodeURIComponent(token)}` : ''}`;
}

export function AssetsView() {
  // ================== V21：每库独立新页面（URL ?lib=<id>） ==================
  // - 无 ?lib   → 资产首页（库卡片列表 + 系统库/我的资产）
  // - ?lib=X    → 库 X 详情页（面包屑 + 素材网格 + 子库入口）
  //   - X 是根库：显示「全部库 / 库名」
  //   - X 是子库：拉祖先链，渲染全部面包屑
  // ======================================================================
  const router = useRouter();
  const searchParams = useSearchParams();
  const libIdParam = searchParams.get('lib');
  const currentLibId = libIdParam ? Number(libIdParam) : null;

  // 写回 URL：?lib=<id> 或移除
  function navigateToLib(id: number | null) {
    const params = new URLSearchParams(Array.from(searchParams.entries()));
    if (id == null) params.delete('lib');
    else params.set('lib', String(id));
    const qs = params.toString();
    router.replace(qs ? `/assets?${qs}` : '/assets');
  }

  const [libs, setLibs] = useState<MediaLibrary[]>([]);
  const [type, setType] = useState<TypeFilter>('all');
  const [source, setSource] = useState<SourceFilter>('all');
  const [sortOrder, setSortOrder] = useState<SortOrder>('desc');
  const [keyword, setKeyword] = useState('');
  const [assets, setAssets] = useState<MediaItem[]>([]);
  const [loading, setLoading] = useState(false);

  // 批量选择
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [selecting, setSelecting] = useState(false);

  // 库弹窗
  const [editingLib, setEditingLib] = useState<MediaLibrary | null>(null);
  const [showCreateLib, setShowCreateLib] = useState(false);

  // 资产编辑弹窗
  const [editingAsset, setEditingAsset] = useState<MediaItem | null>(null);

  // 视频播放器
  const [playingVideo, setPlayingVideo] = useState<MediaItem | null>(null);
  // 2026-08-15：图片/音频点击预览弹窗（视频走 VideoPlayerModal）
  const [previewingAsset, setPreviewingAsset] = useState<MediaItem | null>(null);

  // 上传
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [uploading, setUploading] = useState(false);

  // 拉取库
  const refreshLibs = async () => {
    const list = await mediaApi.listLibraries();
    setLibs(list);
    return list;
  };

  // 挂载时拉一次（V21：去掉以前的"自动选上传库"逻辑，URL 决定视图）
  useEffect(() => {
    refreshLibs();
  }, []);

  // 2026-08-15 V21：先做 URL → 视图派生
  const isHome = currentLibId == null;
  const currentLib = useMemo(
    () => (currentLibId == null ? null : libs.find((l) => l.id === currentLibId) ?? null),
    [libs, currentLibId]
  );

  // 2026-08-15 V21：activeLib 跟随 URL
  // - 详情页 ?lib=X  → activeLib = X（看 X 的素材）
  // - 首页         → activeLib = 系统库「我的资产」（顶部入库选中态）
  const activeLib = useMemo(() => {
    if (currentLib) return currentLib;
    return libs.find((l) => l.type === 'system-uploaded') ?? null;
  }, [currentLib, libs]);
  const activeLibId = activeLib?.id ?? null;

  const isAILib = activeLib?.type === 'system-ai';
  // AI 库不允许上传
  const canUpload = !!activeLib && !isAILib;

  // 拉取素材（V21：URL ?lib 变化时重拉；首页默认展示「我的资产」跨库汇总）
  useEffect(() => {
    if (activeLibId == null) return;
    setLoading(true);
    const queryLibId = getQueryLibraryId(activeLib);
    mediaApi
      .listAssets({
        libraryId: queryLibId,
        type: type === 'all' ? undefined : type,
        source: source === 'all' ? undefined : source,
        keyword: keyword || undefined,
        page: 1,
        pageSize: 60,
      })
      .then((res) => {
        const sorted = [...res.items].sort((a, b) => {
          const ta = new Date(a.createdAt).getTime();
          const tb = new Date(b.createdAt).getTime();
          return sortOrder === 'desc' ? tb - ta : ta - tb;
        });
        setAssets(sorted);
        setSelected(new Set());
      })
      .finally(() => setLoading(false));
  }, [activeLibId, isHome, type, source, sortOrder, keyword, activeLib]);

  // 切换库时清空选择
  useEffect(() => {
    setSelected(new Set());
    setSelecting(false);
  }, [activeLibId]);

  // 2026-08-15 V21：URL ?lib=X 时，拉取祖先链作为面包屑
  // - 根库：面包屑 = [该库]
  // - 子库：面包屑 = [根库, ...中间祖先, 当前库]
  const [breadcrumb, setBreadcrumb] = useState<MediaLibrary[]>([]);
  useEffect(() => {
    let cancelled = false;
    if (currentLibId == null) {
      setBreadcrumb([]);
      return;
    }
    (async () => {
      try {
        const list = await mediaApi.getLibraryBreadcrumb(currentLibId);
        if (!cancelled) setBreadcrumb(list);
      } catch (e) {
        if (!cancelled) setBreadcrumb([]);
      }
    })();
    return () => { cancelled = true; };
  }, [currentLibId]);

  // 2026-08-15 V21：当前 ?lib=X 时，列出 X 的直接子库（在库详情页显示）
  const childLibs = useMemo(() => {
    if (currentLibId == null) return [];
    return libs
      .filter((l) => l.parentId === currentLibId)
      .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id);
  }, [libs, currentLibId]);

  async function handleSaveLib(payload: {
    name: string;
    description: string;
    bizType?: string;
    authPurpose?: string;
    authExpireAt?: string;
    parentId?: number | null;
  }) {
    if (editingLib) {
      // 编辑模式：只支持改名/描述/授权信息，parentId 不可改（与后端一致）
      const { parentId: _ignored, ...rest } = payload;
      // bizType 强转：CreateLibraryRequest.bizType 是窄类型，rest 里是宽 string
      await mediaApi.renameLibrary(editingLib.id, { ...rest, bizType: rest.bizType as any });
    } else {
      const lib = await mediaApi.createLibrary({
        name: payload.name,
        description: payload.description,
        bizType: payload.bizType as any,
        authPurpose: payload.authPurpose,
        authExpireAt: payload.authExpireAt,
        parentId: payload.parentId ?? undefined,
      });
      // V21：新建后跳到新库页面（每库独立新页面）
      navigateToLib(lib.id);
    }
    await refreshLibs();
    setShowCreateLib(false);
    setEditingLib(null);
  }

  async function handleDeleteLib(lib: MediaLibrary) {
    if (lib.type !== 'custom') return;
    if (!confirm(`确定删除资产库「${lib.name}」？库内所有素材将一并删除。`)) return;
    await mediaApi.deleteLibrary(lib.id);
    await refreshLibs();
    if (currentLibId === lib.id) {
      // 当前页面库被删 → 回首页
      navigateToLib(null);
    }
  }

  async function handleUpload(files: FileList | null) {
    if (!files || !activeLibId || !activeLib || isAILib) return;
    setUploading(true);
    const errors: string[] = [];
    try {
      for (const f of Array.from(files)) {
        // 当前选中哪个库就传进哪个库
        try {
          await mediaApi.uploadAsset(f, activeLibId);
        } catch (e) {
          const msg = e instanceof Error ? e.message : String(e);
          errors.push(`「${f.name}」上传失败：${msg}`);
        }
      }
      const queryLibId = getQueryLibraryId(activeLib);
      const res = await mediaApi.listAssets({
        libraryId: queryLibId,
        type: type === 'all' ? undefined : type,
        page: 1,
        pageSize: 60,
      });
      const sorted = [...res.items].sort((a, b) => {
        const ta = new Date(a.createdAt).getTime();
        const tb = new Date(b.createdAt).getTime();
        return sortOrder === 'desc' ? tb - ta : ta - tb;
      });
      setAssets(sorted);
      await refreshLibs();
      if (errors.length > 0) {
        alert(errors.join('\n'));
      }
    } finally {
      setUploading(false);
    }
  }

  async function handleDeleteAsset(asset: MediaItem) {
    if (!confirm(`确定删除「${asset.name}」？`)) return;
    await mediaApi.deleteAsset(asset.id);
    setAssets((prev) => prev.filter((a) => a.id !== asset.id));
    await refreshLibs();
  }

  function handleEditAsset(asset: MediaItem) {
    setEditingAsset(asset);
  }

  /**
   * 2026-08-15：编辑资产保存
   * - name 没变 + libraryId 没变 → 直接关闭
   * - 只改 name → PATCH { name }
   * - 只改 libraryId → PATCH { libraryId }
   * - 都改 → PATCH { name, libraryId }
   * 任意字段没变就不传（节省 payload + 避免误触发后端重名校验）
   */
  async function handleSaveAssetEdit(payload: { name: string; libraryId: number | null }) {
    if (!editingAsset) return;
    const oldName = editingAsset.name;
    const oldLibId = editingAsset.libraryId ?? null;
    const patch: { name?: string; libraryId?: number } = {};
    if (payload.name !== oldName) patch.name = payload.name;
    if (payload.libraryId !== oldLibId) patch.libraryId = payload.libraryId ?? undefined;
    if (Object.keys(patch).length === 0) {
      setEditingAsset(null);
      return;
    }
    const updated = await mediaApi.patchAsset(editingAsset.id, patch);
    // 把更新后的素材同步进当前 assets 列表
    setAssets((prev) => prev.map((a) => (a.id === updated.id ? updated : a)));
    // 如果换了库，刷新一下 assetCount；素材若已不在当前活动库内，从列表移除
    if (patch.libraryId != null && patch.libraryId !== oldLibId) {
      await refreshLibs();
      const queryId = getQueryLibraryId(activeLib);
      if (queryId != null && queryId !== patch.libraryId) {
        setAssets((prev) => prev.filter((a) => a.id !== editingAsset.id));
      }
    }
  }

  async function handleDownloadAsset(asset: MediaItem) {
    try {
      // 优先用 fetch + blob 方式，可保证 download 属性生效
      const res = await fetch(asset.url);
      if (!res.ok) throw new Error(`download failed: ${res.status}`);
      const blob = await res.blob();
      const blobUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = blobUrl;
      a.download = asset.name;
      document.body.appendChild(a);
      a.click();
      a.remove();
      // 稍后释放，避免大文件下载未完成
      setTimeout(() => URL.revokeObjectURL(blobUrl), 1000);
    } catch (e) {
      // 后端可能没给 CORS，降级为直接打开新标签
      const a = document.createElement('a');
      a.href = asset.url;
      a.download = asset.name;
      a.target = '_blank';
      a.rel = 'noopener';
      document.body.appendChild(a);
      a.click();
      a.remove();
    }
  }

  async function handleBatchDelete() {
    if (selected.size === 0) return;
    if (!confirm(`确定删除选中的 ${selected.size} 项素材？`)) return;
    await mediaApi.batchDeleteAssets(Array.from(selected));
    setAssets((prev) => prev.filter((a) => !selected.has(a.id)));
    setSelected(new Set());
    setSelecting(false);
    await refreshLibs();
  }

  function toggleSelect(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  // 2026-08-15 V21：当前 ?lib 决定页面模式
  // - 无 ?lib   → 首页：系统库（我的资产、AI）+ 所有自定义根库卡片
  // - ?lib=X    → 库详情页：显示 X 的素材网格 + 子库入口；卡片区展示 X 的直接子库
  // isHome / currentLib 已在更早派生，这里直接用

  // 当前层展示的库：
  // - 首页：parentId == null 的根库（系统 + 自定义）
  // - 详情页：currentLibId 的直接子库
  const libsAtCurrentLevel = useMemo(() => {
    if (isHome) {
      return libs
        .filter((l) => l.parentId == null)
        .sort((a, b) => {
          const aSys = String(a.type).startsWith('system-') ? 0 : 1;
          const bSys = String(b.type).startsWith('system-') ? 0 : 1;
          if (aSys !== bSys) return aSys - bSys;
          return (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id;
        });
    }
    return childLibs;
  }, [libs, isHome, childLibs]);

  // 详情页：当前库就是父库（用于 LibraryDialog 传参 + 「新建子库」标识）
  const currentParent = isHome ? null : currentLib;

  // 系统默认库拆 2 类：
  // - system-uploaded（我的资产）：首页不显示卡片，素材区直接展示其跨库汇总
  // - system-ai（AI 生成结果）：首页显示卡片，作为只读库入口
  // 详情页：所有 system-* 库都排除（不允许建系统库的子库，业务上也不需要）
  const systemLibs = isHome
    ? libsAtCurrentLevel.filter((l) => l.type === 'system-ai')
    : [];
  const customLibs = libsAtCurrentLevel.filter((l) => l.type === 'custom');

  return (
    <div className="mx-auto w-full max-w-[1600px] px-6 pb-32 pt-6 sm:px-8 lg:px-10">
      {/* 2026-08-15 V21：面包屑（始终置顶，首页不显示，详情页 = 返回我的资产 / 祖先 / 当前） */}
      {!isHome && (
        <div className="mb-2 flex items-center gap-1.5 text-[13px] text-[#5f6876]">
          <button
            type="button"
            onClick={() => navigateToLib(null)}
            className="rounded px-1.5 py-0.5 transition hover:bg-[#f1f5f9] hover:text-[#1673ff]"
          >
            返回我的资产
          </button>
          {breadcrumb.map((b, i) => {
            const isLast = i === breadcrumb.length - 1;
            return (
              <span key={b.id} className="flex items-center gap-1.5">
                <ChevronRight className="h-3.5 w-3.5 text-[#cbd5e1]" />
                {isLast ? (
                  <span className="rounded px-1.5 py-0.5 font-medium text-[#111318]">{b.name}</span>
                ) : (
                  <button
                    type="button"
                    onClick={() => navigateToLib(b.id)}
                    className="rounded px-1.5 py-0.5 transition hover:bg-[#f1f5f9] hover:text-[#1673ff]"
                  >
                    {b.name}
                  </button>
                )}
              </span>
            );
          })}
        </div>
      )}

      {/* 大标题：当前库名（首页 = 「我的资产」） */}
      <h1 className="mb-5 text-xl font-bold text-[#111318]">
        {isHome ? '我的资产' : currentLib?.name ?? '未命名库'}
      </h1>

      {/* 资产库入口卡片 */}
      <div className="mb-7 flex flex-wrap gap-4">
        {/* 2026-08-15 V24：系统库不可作为父库。
            - 首页：currentParent=null → 始终显示"新建资产库"（建根库）
            - 详情页且 currentParent 是 custom → 显示"新建子库"
            - 详情页且 currentParent 是 system-* → 隐藏"新建子库"按钮 */}
        {(!currentParent || currentParent.type === 'custom') && (
          <button
            type="button"
            onClick={() => {
              setEditingLib(null);
              setShowCreateLib(true);
            }}
            className="group w-[180px] text-left transition-transform hover:-translate-y-0.5"
          >
            <div className="grid aspect-[3/2] w-full place-items-center rounded-xl border border-dashed border-[#cbd5e1] bg-white transition group-hover:border-[#1673ff]">
              <div className="grid h-[58px] w-[58px] place-items-center rounded-[16px] bg-[#f7f7f8] text-[#5f6876] transition group-hover:bg-[#eaf3ff] group-hover:text-[#1673ff]">
                <IconFolderPlus className="h-8 w-8" />
              </div>
            </div>
            <div className="mt-2.5 text-[13.5px] font-medium text-[#111318]">
              {currentParent ? '新建子库' : '新建资产库'}
            </div>
            <div className="mt-0.5 truncate text-xs text-[#8a909b]">
              {currentParent
                ? `归到「${currentParent.name}」下`
                : '自定义管理素材'}
            </div>
          </button>
        )}

        {/* 系统默认库（仅首页展示） */}
        {systemLibs.map((lib) => {
          const isAI = lib.type === 'system-ai';
          return (
            <LibraryCard
              key={lib.id}
              lib={lib}
              isAI={isAI}
              isActive={lib.id === activeLibId}
              onClick={() => navigateToLib(lib.id)}
            />
          );
        })}

        {/* 自定义库：首页=根库；详情页=子库；点击 = 跳到该库的新页面 */}
        {customLibs.map((lib) => (
          <LibraryCard
            key={lib.id}
            lib={lib}
            isAI={false}
            isActive={lib.id === activeLibId}
            // V21：每库独立新页面。点根库/子库卡片都跳到 /assets?lib=<id>，
            // URL 变化驱动面包屑 + 素材列表重新加载
            onClick={() => navigateToLib(lib.id)}
            onEdit={() => {
              setEditingLib(lib);
              setShowCreateLib(true);
            }}
            onDelete={() => handleDeleteLib(lib)}
          />
        ))}
      </div>

      {/* 工具栏 */}
      <div className="mb-4 flex flex-wrap items-center gap-2.5">
        {/* 类型 Tab */}
        <div className="flex items-center gap-0 rounded-[10px] border border-[#edf0f4] bg-white p-[3px] shadow-[0_10px_24px_rgba(25,31,45,0.05)]">
          {TYPE_TABS.map((t) => {
            const Icon = t.Icon;
            const active = type === t.key;
            return (
              <button
                key={t.key}
                type="button"
                onClick={() => setType(t.key)}
                className={cn(
                  'inline-flex h-[30px] items-center gap-1.5 rounded-[7px] px-3 text-[12.5px] font-medium transition',
                  active
                    ? 'bg-[#eaf3ff] text-[#006cff]'
                    : 'text-[#5f6876] hover:bg-[#f7f7f8] hover:text-[#111318]'
                )}
              >
                {Icon && <Icon className="h-3.5 w-3.5" />}
                {t.label}
              </button>
            );
          })}
        </div>

        {/* V25：来源 Tab（AI 库下只有 AI 生成的，分类无意义，隐藏整组） */}
        {!isAILib && (
          <div className="flex items-center gap-0 rounded-[10px] border border-[#edf0f4] bg-white p-[3px] shadow-[0_10px_24px_rgba(25,31,45,0.05)]">
            {SOURCE_TABS.map((t) => {
              const active = source === t.key;
              return (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setSource(t.key)}
                  className={cn(
                    'inline-flex h-[30px] items-center gap-1.5 rounded-[7px] px-3 text-[12.5px] font-medium transition',
                    active
                      ? 'bg-[#eaf3ff] text-[#006cff]'
                      : 'text-[#5f6876] hover:bg-[#f7f7f8] hover:text-[#111318]'
                  )}
                >
                  {t.label}
                </button>
              );
            })}
          </div>
        )}

        {/* 搜索 */}
        <div className="relative min-w-[180px] max-w-[280px] flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#8a909b]" />
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="搜索资产名"
            className="h-9 w-full rounded-lg border border-[#edf0f4] bg-white pl-9 pr-3 text-[13px] text-[#111318] shadow-[0_10px_24px_rgba(25,31,45,0.05)] outline-none transition placeholder:text-[#8a909b] focus:border-[#1673ff] focus:shadow-[0_0_0_3px_#eaf3ff]"
          />
        </div>

        {/* 右侧：排序 + 选择 */}
        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            onClick={() => setSortOrder(sortOrder === 'desc' ? 'asc' : 'desc')}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-[#edf0f4] bg-white px-3 text-[13px] font-medium text-[#5f6876] shadow-[0_10px_24px_rgba(25,31,45,0.05)] hover:bg-[#f7f7f8] hover:text-[#111318]"
          >
            {sortOrder === 'desc' ? (
              <ArrowDown className="h-3.5 w-3.5" />
            ) : (
              <ArrowUp className="h-3.5 w-3.5" />
            )}
            {sortOrder === 'desc' ? '时间降序' : '时间升序'}
          </button>

          {assets.length > 0 && (
            <button
              type="button"
              onClick={() => {
                setSelecting(!selecting);
                setSelected(new Set());
              }}
              className={cn(
                'inline-flex h-9 items-center gap-1.5 rounded-lg border px-3 text-[13px] font-medium transition',
                selecting
                  ? 'border-[#1673ff] bg-[#eaf3ff] text-[#006cff]'
                  : 'border-[#edf0f4] bg-white text-[#5f6876] shadow-[0_10px_24px_rgba(25,31,45,0.05)] hover:bg-[#f7f7f8] hover:text-[#111318]'
              )}
            >
              <Check className="h-3.5 w-3.5" />
              {selecting ? '取消选择' : '批量操作'}
            </button>
          )}
        </div>

        {/* 隐藏的上传 input */}
        <input
          ref={fileRef}
          type="file"
          multiple
          accept="image/*,video/*,audio/*"
          className="hidden"
          onChange={(e) => handleUpload(e.target.files)}
        />
      </div>

      {/* 资产网格 */}
      {loading ? (
        <SkeletonGrid canUpload={canUpload} selecting={selecting} />
      ) : assets.length === 0 && isAILib ? (
        <EmptyState
          isAI
          isAllAssets={false}
          libraryName={activeLib?.name}
          onUpload={() => fileRef.current?.click()}
        />
      ) : (
        <>
          {assets.length === 0 ? (
            <div className="relative min-h-[60vh]">
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
                {canUpload && (
                  <button
                    type="button"
                    onClick={() => fileRef.current?.click()}
                    disabled={uploading}
                    className="group aspect-[4/3] cursor-pointer overflow-hidden rounded-xl border border-dashed border-[#cbd5e1] bg-white transition hover:-translate-y-0.5 hover:border-[#1673ff] hover:shadow-[0_16px_34px_rgba(25,31,45,0.08)]"
                  >
                    <div className="grid h-full w-full place-items-center">
                      <div className="flex flex-col items-center gap-2">
                        <div className="grid h-12 w-12 place-items-center rounded-2xl bg-[#f7f7f8] text-[#5f6876] transition group-hover:bg-[#eaf3ff] group-hover:text-[#1673ff]">
                          <Plus className="h-6 w-6" strokeWidth={1.75} />
                        </div>
                        <span className="text-[12.5px] font-medium text-[#5f6876] group-hover:text-[#1673ff]">
                          {uploading ? '上传中…' : '添加资产'}
                        </span>
                      </div>
                    </div>
                  </button>
                )}
              </div>
              <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
                <div className="flex items-center gap-2 text-[15px] font-medium text-[#5f6876]">
                  <Info className="h-[18px] w-[18px] text-[#1673ff]" strokeWidth={1.75} />
                  <span>当前资产库为空，点击左上角「+ 添加资产」开始上传</span>
                </div>
              </div>
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
          {/* 首位 + 添加卡片（AI 库不允许添加） */}
          {canUpload && (
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              disabled={uploading}
              className="group aspect-[4/3] cursor-pointer overflow-hidden rounded-xl border border-dashed border-[#cbd5e1] bg-white transition hover:-translate-y-0.5 hover:border-[#1673ff] hover:shadow-[0_16px_34px_rgba(25,31,45,0.08)]"
            >
              <div className="grid h-full w-full place-items-center">
                <div className="flex flex-col items-center gap-2">
                  <div className="grid h-12 w-12 place-items-center rounded-2xl bg-[#f7f7f8] text-[#5f6876] transition group-hover:bg-[#eaf3ff] group-hover:text-[#1673ff]">
                    <Plus className="h-6 w-6" strokeWidth={1.75} />
                  </div>
                  <span className="text-[12.5px] font-medium text-[#5f6876] group-hover:text-[#1673ff]">
                    {uploading ? '上传中…' : '添加资产'}
                  </span>
                </div>
              </div>
            </button>
          )}

          {assets.map((a) => (
            <AssetCard
              key={a.id}
              asset={a}
              selecting={selecting}
              selected={selected.has(a.id)}
              onToggleSelect={() => toggleSelect(a.id)}
              onEdit={() => handleEditAsset(a)}
              onDelete={() => handleDeleteAsset(a)}
              onDownload={() => handleDownloadAsset(a)}
              onPlayVideo={(asset) => setPlayingVideo(asset)}
              onPreview={(asset) => {
                // 视频走专用的播放器（带控制条），图片/音频走全屏预览
                if (asset.type === 'video') setPlayingVideo(asset);
                else setPreviewingAsset(asset);
              }}
            />
          ))}
            </div>
          )}
        </>
      )}

      {/* 批量操作浮层 */}
      {selecting && selected.size > 0 && (
        <div className="fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 items-center gap-4 rounded-2xl border border-[#edf0f4] bg-white px-5 py-3 shadow-[0_16px_40px_rgba(25,31,45,0.15)]">
          <span className="text-[13px] text-[#5f6876]">已选 {selected.size} 项</span>
          <button
            type="button"
            onClick={() => setSelected(new Set(assets.map((a) => a.id)))}
            className="text-[13px] font-medium text-[#1673ff] hover:underline"
          >
            全选
          </button>
          <button
            type="button"
            onClick={() => setSelected(new Set())}
            className="text-[13px] font-medium text-[#5f6876] hover:underline"
          >
            清空
          </button>
          <button
            type="button"
            onClick={handleBatchDelete}
            className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-[#dc2626] px-3 text-[13px] font-medium text-white hover:bg-[#b91c1c]"
          >
            <Trash2 className="h-3.5 w-3.5" />
            批量删除
          </button>
        </div>
      )}

      {/* 库弹窗（新建/编辑） */}
      {showCreateLib && (
        <LibraryDialog
          mode={editingLib ? 'edit' : 'create'}
          initial={editingLib}
          libs={libs}
          // 2026-08-15 V19：父库
          // - 编辑模式：null（父库不可改）
          // - 新建：currentParent（根层为 null 建根库；子层为当前父库，建在其下）
          parent={editingLib ? null : currentParent}
          onClose={() => {
            setShowCreateLib(false);
            setEditingLib(null);
          }}
          onSave={handleSaveLib}
        />
      )}

      {/* 资产编辑弹窗（改名 + 换库） */}
      {editingAsset && (
        <AssetEditDialog
          asset={editingAsset}
          assets={assets}
          libraries={libs}
          currentLibId={activeLibId}
          onClose={() => setEditingAsset(null)}
          onSave={handleSaveAssetEdit}
        />
      )}

      {/* 视频播放器弹窗 */}
      {playingVideo && (
        <VideoPlayerModal
          asset={playingVideo}
          onClose={() => setPlayingVideo(null)}
        />
      )}

      {/* 2026-08-15：图片/音频点击预览弹窗（视频走 VideoPlayerModal） */}
      {previewingAsset && (
        <AssetPreviewModal
          asset={previewingAsset}
          onClose={() => setPreviewingAsset(null)}
        />
      )}
    </div>
  );
}

function LibraryDialog({
  mode,
  initial,
  libs,
  parent,
  onClose,
  onSave,
}: {
  mode: 'create' | 'edit';
  initial: MediaLibrary | null;
  libs: MediaLibrary[];
  /**
   * 2026-08-15 V19：当前层父库（仅 create 模式有意义）
   * - null：根层新建，库类型可选
   * - 非空：在该父库下建子库
   *   - bizType 自动锁定为父库 bizType（普通/虚拟人/真人）
   *   - 真人库子库继续强制要求 authPurpose / authExpireAt
   * 编辑模式忽略此参数（不允许改父库）
   */
  parent: MediaLibrary | null;
  onClose: () => void;
  onSave: (payload: {
    name: string;
    description: string;
    bizType?: string;
    authPurpose?: string;
    authExpireAt?: string;
    parentId?: number | null;
  }) => Promise<void>;
}) {
  // 锁定逻辑（V19+V22）：
  // - 编辑模式：沿用 initial.bizType（父库不可改）
  // - 新建子库：父库是 virtual_human/real_person → 锁定为父类型（业务约束），
  //   父库是 normal → 不锁，可选 normal/virtual_human/real_person
  // - 新建根库：默认 normal，可自由选
  const initialBiz = (() => {
    if (initial?.bizType) return String(initial.bizType);
    if (parent?.bizType) {
      const pb = String(parent.bizType);
      if (pb === 'virtual_human' || pb === 'real_person') return pb;
    }
    return 'normal';
  })();
  const [name, setName] = useState(initial?.name ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  // V18 业务类型（编辑模式下沿用原有值，新建有 parent 时锁定为 parent 值，默认 normal）
  const [bizType, setBizType] = useState<string>(initialBiz);
  const [authPurpose, setAuthPurpose] = useState<string>(initial?.authPurpose ?? '');
  const [authExpireAt, setAuthExpireAt] = useState<string>(initial?.authExpireAt ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 2026-08-15 V19：当前 dialog 是建子库？
  const isSubLibrary = mode === 'create' && parent != null;
  // 父库类型锁定（V22）：
  // - 仅在父库是 virtual_human/real_person 时锁定（业务约束）
  // - 普通库下建子库不锁，可自由选 bizType
  // - 编辑模式：父库不可改（initialBiz 已锁定初值）
  const parentIsRestricted = parent?.bizType
    ? (String(parent.bizType) === 'virtual_human' || String(parent.bizType) === 'real_person')
    : false;
  const bizTypeLocked = (mode === 'create' && isSubLibrary && parentIsRestricted) || mode === 'edit';
  // 同级重名校验：建根库 → 排除 parentId=null 的同级；建子库 → 排除 parentId=parent.id 的同级
  const isDuplicate = useMemo(() => {
    const trimmed = name.trim();
    if (!trimmed) return false;
    const targetParentId = isSubLibrary ? parent!.id : null;
    const dup = libs.some(
      (l) =>
        l.name === trimmed
        && (l.parentId ?? null) === (targetParentId ?? null)
        && (!initial || l.id !== initial.id)
    );
    return dup;
  }, [name, libs, initial, isSubLibrary, parent]);

  // 真人库校验：授权用途 + 有效期都必填
  const isRealPerson = bizType === 'real_person';
  const authIncomplete = isRealPerson && (!authPurpose.trim() || !authExpireAt);

  async function handleSubmit() {
    const trimmed = name.trim();
    if (!trimmed) return;
    if (isDuplicate) {
      setError(`同级已存在同名资产库「${trimmed}」，请换个名称`);
      return;
    }
    if (authIncomplete) {
      setError('真人库必须填写授权用途说明和授权有效期');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await onSave({
        name: trimmed,
        description: description.trim(),
        bizType,
        authPurpose: isRealPerson ? authPurpose.trim() : undefined,
        authExpireAt: isRealPerson ? authExpireAt : undefined,
        parentId: isSubLibrary ? parent!.id : null,
      });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes('已存在') || msg.includes('duplicate') || msg.includes('重名')) {
        setError(`同级已存在同名资产库「${trimmed}」，请换个名称`);
      } else if (msg.includes('子库类型') || msg.includes('7007')) {
        setError('子库类型必须与父库一致');
      } else if (msg.includes('系统库') && msg.includes('父库')) {
        setError('系统库不能作为父库');
      } else if (msg.includes('父库不存在')) {
        setError('父库不存在或已被删除');
      } else {
        setError(msg || '保存失败');
      }
    } finally {
      setSaving(false);
    }
  }

  // 输入变化时清掉旧错误
  useEffect(() => {
    setError(null);
  }, [name]);

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/30"
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-[480px] overflow-hidden rounded-2xl bg-white shadow-[0_20px_60px_rgba(0,0,0,0.15)]"
      >
        <div className="flex items-center justify-between px-6 pb-3 pt-5">
          <h3 className="text-base font-semibold text-[#111318]">
            {mode === 'create' ? (isSubLibrary ? '新建子库' : '新建资产库') : '编辑资产库'}
          </h3>
          <button
            onClick={onClose}
            className="grid h-7 w-7 place-items-center rounded-lg text-[#8a909b] hover:bg-[#f7f7f8]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="px-6 pb-6">
          {/* 2026-08-15 V19：父库提示（仅建子库时） */}
          {isSubLibrary && parent && (
            <div className="mb-3 flex items-center gap-2 rounded-lg border border-[#eaf3ff] bg-[#f5f9ff] px-3 py-2 text-[12.5px] text-[#1673ff]">
              <CornerDownRight className="h-3.5 w-3.5" />
              <span>
                将作为子库归到
                <span className="mx-1 font-medium text-[#111318]">「{parent.name}」</span>
                下
              </span>
              <LibraryTypeBadge typeKey={getLibraryTypeKey(parent)} />
            </div>
          )}

          {/* 名称 */}
          <label className="mb-1.5 block text-[12.5px] font-medium text-[#5f6876]">
            资产库名称 <span className="text-[#dc2626]">*</span>
          </label>
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={isSubLibrary ? `例如：${parent?.name}-子分类` : '例如：商品素材 / 角色参考'}
            className={cn(
              'h-10 w-full rounded-lg border bg-white px-3 text-[13px] outline-none transition',
              isDuplicate || error
                ? 'border-[#dc2626] focus:border-[#dc2626] focus:shadow-[0_0_0_3px_#fee2e2]'
                : 'border-[#edf0f4] focus:border-[#1673ff] focus:shadow-[0_0_0_3px_#eaf3ff]'
            )}
          />
          {isDuplicate && !error && (
            <div className="mt-1.5 flex items-center gap-1 text-[12px] text-[#dc2626]">
              <Info className="h-3.5 w-3.5" />
              {isSubLibrary
                ? `「${parent?.name}」下已存在同名子库，请换个名称`
                : '已存在同名资产库，请换个名称'}
            </div>
          )}

          {/* V18: 资产库业务类型（普通 / 虚拟人 / 真人） */}
          <label className="mb-1.5 mt-4 flex items-center gap-1 text-[12.5px] font-medium text-[#5f6876]">
            资产库类型
            {bizTypeLocked && (
              <span className="ml-1 rounded bg-[#f1f5f9] px-1.5 py-0.5 text-[10px] font-normal text-[#5f6876]">
                已锁定（与父库一致）
              </span>
            )}
          </label>
          <div className="flex gap-2">
            {[
              { key: 'normal', label: '普通' },
              { key: 'virtual_human', label: '虚拟人' },
              { key: 'real_person', label: '真人' },
            ].map((opt) => {
              const active = bizType === opt.key;
              const disabled = bizTypeLocked && !active;
              return (
                <button
                  key={opt.key}
                  type="button"
                  disabled={disabled}
                  onClick={() => !disabled && setBizType(opt.key)}
                  className={cn(
                    'inline-flex h-9 flex-1 items-center justify-center rounded-lg border text-[12.5px] font-medium transition',
                    active
                      ? 'border-[#1673ff] bg-[#eaf3ff] text-[#006cff]'
                      : disabled
                        ? 'cursor-not-allowed border-[#edf0f4] bg-[#f7f7f8] text-[#cbd5e1]'
                        : 'border-[#edf0f4] bg-white text-[#5f6876] hover:bg-[#f7f7f8] hover:text-[#111318]'
                  )}
                >
                  {opt.label}
                </button>
              );
            })}
          </div>

          {/* 真人库专属：授权用途 + 授权有效期 */}
          {isRealPerson && (
            <div className="mt-4 space-y-3 rounded-lg border border-[#fde68a] bg-[#fffbeb] p-3.5">
              <div className="text-[12px] font-medium text-[#92400e]">
                真人库需填写授权信息
              </div>
              <div>
                <label className="mb-1 block text-[12px] font-medium text-[#5f6876]">
                  授权用途说明 <span className="text-[#dc2626]">*</span>
                </label>
                <textarea
                  value={authPurpose}
                  onChange={(e) => setAuthPurpose(e.target.value)}
                  placeholder="可填写入库可使用素材的授权说明"
                  rows={2}
                  className="w-full resize-none rounded-lg border border-[#edf0f4] bg-white px-3 py-2 text-[12.5px] leading-relaxed outline-none focus:border-[#1673ff] focus:shadow-[0_0_0_3px_#eaf3ff]"
                />
              </div>
              <div>
                <label className="mb-1 block text-[12px] font-medium text-[#5f6876]">
                  素材有效期限 <span className="text-[#dc2626]">*</span>
                </label>
                <input
                  type="date"
                  value={authExpireAt}
                  onChange={(e) => setAuthExpireAt(e.target.value)}
                  className="h-9 w-full rounded-lg border border-[#edf0f4] bg-white px-3 text-[12.5px] outline-none focus:border-[#1673ff] focus:shadow-[0_0_0_3px_#eaf3ff]"
                />
              </div>
            </div>
          )}

          {/* 备注 */}
          <label className="mb-1.5 mt-4 flex items-center gap-1 text-[12.5px] font-medium text-[#5f6876]">
            <Info className="h-3.5 w-3.5" />
            备注
          </label>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="简单描述这个资产库的用途（可选）"
            rows={2}
            className="w-full resize-none rounded-lg border border-[#edf0f4] bg-white px-3 py-2 text-[13px] leading-relaxed outline-none focus:border-[#1673ff] focus:shadow-[0_0_0_3px_#eaf3ff]"
          />

          {error && (
            <div className="mt-3 flex items-center gap-1 text-[12px] text-[#dc2626]">
              <Info className="h-3.5 w-3.5" />
              {error}
            </div>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-[#edf0f4] bg-[#fafbfc] px-6 py-3.5">
          <button
            onClick={onClose}
            className="h-9 rounded-lg border border-[#edf0f4] bg-white px-4 text-[13px] font-medium text-[#5f6876] hover:bg-[#f7f7f8]"
          >
            取消
          </button>
          <button
            onClick={handleSubmit}
            disabled={!name.trim() || saving || isDuplicate || authIncomplete}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-[#1673ff] px-4 text-[13px] font-medium text-white hover:bg-[#006cff] disabled:opacity-50"
          >
            <Check className="h-3.5 w-3.5" />
            {saving ? '保存中…' : mode === 'create' ? '创建' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}

function AssetEditDialog({
  asset,
  assets,
  libraries = [],
  currentLibId,
  onClose,
  onSave,
}: {
  asset: MediaItem;
  assets: MediaItem[];
  /** 2026-08-15：当前用户的所有库，供"修改所属库"下拉用 */
  libraries?: MediaLibrary[];
  /**
   * 当前查看的 libraryId：用于定位"同库"范围
   * - undefined/null：表示"我的资产"视图（所有素材的并集，含未归库的 null 库）
   * - system-ai：暂不在此场景出现，由调用方传具体 id
   */
  currentLibId?: number | null;
  onClose: () => void;
  /** 2026-08-15：保存回调同时收 name 和 libraryId */
  onSave: (payload: { name: string; libraryId: number | null }) => Promise<void>;
}) {
  const [name, setName] = useState(asset.name);
  /** 2026-08-15：目标库（默认沿用 asset 当前库） */
  const [targetLibId, setTargetLibId] = useState<number | null>(asset.libraryId ?? null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 候选库：排除 system-ai（AI 库只能装 AI 产出，不能手工移入）
  const candidateLibs = useMemo(
    () => libraries.filter((l) => l.type !== 'system-ai'),
    [libraries]
  );

  // 重名预校验：与"目标库"内其他素材比较（排除自己）
  const isDuplicate = useMemo(() => {
    const trimmed = name.trim();
    if (!trimmed) return false;
    return assets.some(
      (a) =>
        a.id !== asset.id
        && a.libraryId === targetLibId
        && a.name === trimmed
    );
  }, [name, assets, asset.id, targetLibId]);

  // 切换 name / targetLibId 时清错误
  useEffect(() => {
    setError(null);
  }, [name, targetLibId]);

  async function handleSubmit() {
    const trimmed = name.trim();
    if (!trimmed) return;
    if (isDuplicate) {
      setError(`目标库「${candidateLibs.find((l) => l.id === targetLibId)?.name ?? ''}」内已存在同名素材，请换个名称`);
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await onSave({ name: trimmed, libraryId: targetLibId });
      onClose();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes('已存在') || msg.includes('duplicate') || msg.includes('重名')) {
        setError(`目标库内已存在同名素材「${trimmed}」，请换个名称`);
      } else if (msg.includes('AI 生成结果') || msg.includes('system-ai')) {
        setError(msg);
      } else {
        setError(msg || '保存失败');
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/30" onClick={onClose}>
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-[440px] overflow-hidden rounded-2xl bg-white shadow-[0_20px_60px_rgba(0,0,0,0.15)]"
      >
        <div className="flex items-center justify-between px-6 pb-3 pt-5">
          <h3 className="text-base font-semibold text-[#111318]">编辑资产</h3>
          <button
            onClick={onClose}
            className="grid h-7 w-7 place-items-center rounded-lg text-[#8a909b] hover:bg-[#f7f7f8]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="px-6 pb-6">
          {/* 资产缩略图 */}
          <div className="mb-4 flex items-center gap-3">
            <div className="grid h-14 w-14 place-items-center overflow-hidden rounded-lg border border-[#edf0f4] bg-[#f7f7f8]">
              {asset.type === 'image' ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={asset.url}
                  alt={asset.name}
                  className="h-full w-full object-cover"
                />
              ) : asset.type === 'video' ? (
                <video
                  src={asset.url}
                  className="h-full w-full object-cover"
                  muted
                  playsInline
                  preload="metadata"
                  onLoadedMetadata={(e) => {
                    const v = e.currentTarget;
                    if (v.currentTime < 0.1) v.currentTime = 0.1;
                  }}
                />
              ) : (
                <Music2 className="h-6 w-6 text-[#cbd5e1]" />
              )}
            </div>
            <div className="min-w-0 flex-1 text-[12px] text-[#8a909b]">
              <div className="truncate text-[13px] font-medium text-[#111318]">
                {asset.type === 'image' ? '图片' : asset.type === 'video' ? '视频' : '音频'}
                {asset.source === 'ai-generated' && (
                  <span className="ml-1.5 rounded bg-[#1673ff] px-1.5 py-0.5 text-[10px] font-semibold text-white">
                    AI
                  </span>
                )}
              </div>
              {formatSize(asset.size) && <div className="mt-0.5">{formatSize(asset.size)}</div>}
            </div>
          </div>

          {/* 视频大预览播放器（带 controls） */}
          {asset.type === 'video' && asset.url && (
            <video
              key={asset.url}
              src={asset.url}
              controls
              preload="metadata"
              className="w-full rounded-lg border border-[#edf0f4] bg-black"
              style={{ maxHeight: '320px' }}
            />
          )}

          {/* 2026-08-15：所属库选择（可换库） */}
          <label className="mb-1.5 block text-[12.5px] font-medium text-[#5f6876]">
            所属资产库
          </label>
          <div className="flex items-center gap-2">
            <select
              value={targetLibId == null ? '' : String(targetLibId)}
              onChange={(e) => setTargetLibId(e.target.value === '' ? null : Number(e.target.value))}
              className="h-10 flex-1 rounded-lg border border-[#edf0f4] bg-white px-3 text-[13px] outline-none focus:border-[#1673ff] focus:shadow-[0_0_0_3px_#eaf3ff]"
            >
              {targetLibId == null && (
                <option value="" disabled>
                  未归库
                </option>
              )}
              {candidateLibs.map((l) => {
                const key = getLibraryTypeKey(l);
                const tag = key === 'ai' ? 'AI' : key === 'mine' ? '我的' : key === 'virtual_human' ? '虚拟人' : key === 'real_person' ? '真人' : '普通';
                return (
                  <option key={l.id} value={String(l.id)}>
                    【{tag}】{l.name}
                  </option>
                );
              })}
            </select>
            {(() => {
              const cur = candidateLibs.find((l) => l.id === targetLibId);
              if (!cur) return null;
              return <LibraryTypeBadge typeKey={getLibraryTypeKey(cur)} />;
            })()}
          </div>
          {asset.libraryId !== targetLibId && targetLibId != null && (
            <div className="mt-1.5 flex items-center gap-1 text-[12px] text-[#1673ff]">
              <Info className="h-3.5 w-3.5" />
              素材将移动到新库
            </div>
          )}

          {/* 名称 */}
          <label className="mb-1.5 mt-4 block text-[12.5px] font-medium text-[#5f6876]">
            资产名称 <span className="text-[#dc2626]">*</span>
          </label>
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleSubmit();
              if (e.key === 'Escape') onClose();
            }}
            placeholder="例如：产品图-01"
            className={cn(
              'h-10 w-full rounded-lg border bg-white px-3 text-[13px] outline-none transition',
              isDuplicate || error
                ? 'border-[#dc2626] focus:border-[#dc2626] focus:shadow-[0_0_0_3px_#fee2e2]'
                : 'border-[#edf0f4] focus:border-[#1673ff] focus:shadow-[0_0_0_3px_#eaf3ff]'
            )}
          />
          {/* 重名/错误提示 */}
          {isDuplicate && !error && (
            <div className="mt-1.5 flex items-center gap-1 text-[12px] text-[#dc2626]">
              <Info className="h-3.5 w-3.5" />
              目标库内已存在同名素材，请换个名称
            </div>
          )}
          {error && (
            <div className="mt-1.5 flex items-center gap-1 text-[12px] text-[#dc2626]">
              <Info className="h-3.5 w-3.5" />
              {error}
            </div>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-[#edf0f4] bg-[#fafbfc] px-6 py-3.5">
          <button
            onClick={onClose}
            className="h-9 rounded-lg border border-[#edf0f4] bg-white px-4 text-[13px] font-medium text-[#5f6876] hover:bg-[#f7f7f8]"
          >
            取消
          </button>
          <button
            onClick={handleSubmit}
            disabled={!name.trim() || saving || isDuplicate}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-[#1673ff] px-4 text-[13px] font-medium text-white hover:bg-[#006cff] disabled:opacity-50"
          >
            <Check className="h-3.5 w-3.5" />
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}

function LibraryCard({
  lib,
  isAI,
  isActive,
  onClick,
  onEdit,
  onDelete,
}: {
  lib: MediaLibrary;
  isAI: boolean;
  isActive: boolean;
  onClick: () => void;
  onEdit?: () => void;
  onDelete?: () => void;
}) {
  const canManage = !!(onEdit || onDelete);
  // V18 业务类型
  const biz = lib.bizType ?? 'normal';
  const isVirtualHuman = biz === 'virtual_human';
  const isRealPerson = biz === 'real_person';
  // 真人库认证状态
  const authOk = isRealPerson && lib.authStatus === 'valid';
  const authExpired = isRealPerson && lib.authStatus === 'expired';
  return (
    <div className="group w-[180px] text-left transition-transform hover:-translate-y-0.5">
      <div
        onClick={onClick}
        className={cn(
          'relative grid aspect-[3/2] w-full cursor-pointer place-items-center overflow-hidden rounded-xl shadow-[0_10px_24px_rgba(25,31,45,0.05)] transition',
          isActive && 'shadow-[0_16px_34px_rgba(22,115,255,0.18)]',
          isAI
            ? 'bg-gradient-to-br from-[#eaf3ff] to-[#dfeaff] text-[#1673ff]'
            : lib.type === 'system-uploaded'
              ? 'bg-gradient-to-br from-[#dfeaff] to-[#cfe0ff] text-[#1673ff]'
              : isRealPerson
                ? 'bg-gradient-to-br from-[#fde68a] to-[#fbbf24] text-[#92400e]'
                : isVirtualHuman
                  ? 'bg-gradient-to-br from-[#a78bfa] to-[#7c3aed] text-white'
                  : 'bg-gradient-to-br from-[#7c8cff] to-[#5b9aff] text-white'
        )}
      >
        {/* 左上：标签组（AI / 虚拟人 / 真人 / 认证状态） */}
        <div className="absolute left-2 top-2 flex flex-col gap-1">
          {isAI && <LibraryTypeBadge typeKey="ai" />}
          {!isAI && isVirtualHuman && <LibraryTypeBadge typeKey="virtual_human" />}
          {!isAI && isRealPerson && <LibraryTypeBadge typeKey="real_person" />}
          {!isAI && !isVirtualHuman && !isRealPerson && lib.type === 'custom' && (
            <LibraryTypeBadge typeKey="normal" />
          )}
        </div>

        {/* 右上：真人库认证状态 */}
        {isRealPerson && (
          <div className="absolute right-2 top-2">
            {authOk && (
              <div className="flex items-center gap-1 rounded bg-[#10b981] px-1.5 py-0.5 text-[10px] font-semibold text-white">
                <span className="h-1.5 w-1.5 rounded-full bg-white" />
                已认证
              </div>
            )}
            {authExpired && (
              <div className="rounded bg-[#dc2626] px-1.5 py-0.5 text-[10px] font-semibold text-white">
                已过期
              </div>
            )}
            {!authOk && !authExpired && (
              <div className="rounded bg-white/85 px-1.5 py-0.5 text-[10px] font-semibold tracking-wide text-[#92400e] backdrop-blur-sm">
                未认证
              </div>
            )}
          </div>
        )}

        {/* 中央图标 */}
        <div className="grid h-16 w-16 place-items-center">
          {isAI ? (
            <IconAiSparkle className="h-9 w-9" />
          ) : lib.type === 'system-uploaded' ? (
            <IconLibraryAll className="h-9 w-9" />
          ) : isRealPerson ? (
            <IconPerson className="h-9 w-9" />
          ) : isVirtualHuman ? (
            <IconUserCircle className="h-9 w-9" />
          ) : (
            <IconFolder className="h-9 w-9" />
          )}
        </div>

        {/* 左下：数量 */}
        <div className="absolute bottom-2 left-2.5 text-lg font-bold tracking-tight opacity-90">
          {lib.assetCount ?? 0}
        </div>

        {/* 右下：操作按钮组（hover 显示） */}
        <div className="absolute bottom-2 right-2 flex items-center gap-1.5">
          {canManage && (
            <div className="flex items-center gap-1.5 opacity-0 transition group-hover:opacity-100">
              {onEdit && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onEdit();
                  }}
                  title="编辑"
                  className="grid h-7 w-7 place-items-center rounded-lg bg-black/55 text-white backdrop-blur-sm transition hover:bg-[#1673ff]"
                >
                  <Pencil className="h-3.5 w-3.5" />
                </button>
              )}
              {onDelete && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onDelete();
                  }}
                  title="删除"
                  className="grid h-7 w-7 place-items-center rounded-lg bg-black/55 text-white backdrop-blur-sm transition hover:bg-[#dc2626]"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              )}
            </div>
          )}
        </div>
      </div>

      <div onClick={onClick} className="mt-2.5 flex cursor-pointer items-center gap-1.5">
        <span className="truncate text-[13.5px] font-medium text-[#111318]">{lib.name}</span>
        {lib.hasChildren && (
          <span
            title="该库下有子库"
            className="inline-flex shrink-0 items-center gap-0.5 rounded-full bg-[#eaf3ff] px-1.5 py-[1px] text-[10px] font-medium text-[#1673ff]"
          >
            <CornerDownRight className="h-2.5 w-2.5" />
            子库
          </span>
        )}
      </div>
      <div className="mt-0.5 truncate text-xs text-[#8a909b]">
        {lib.description ||
          (isAI
            ? 'AI 创作产出'
            : isAllAssetsView(lib)
              ? '所有素材'
              : isRealPerson
                ? authOk
                  ? '已认证真人库'
                  : authExpired
                    ? '授权已过期'
                    : '待认证真人库'
                : isVirtualHuman
                  ? '虚拟人素材库'
                  : '自建分类')}
      </div>
    </div>
  );
}

function isAllAssetsView(lib: MediaLibrary) {
  return lib.type === 'system-uploaded';
}

function AssetCard({
  asset,
  selecting,
  selected,
  onToggleSelect,
  onEdit,
  onDelete,
  onDownload,
  onPlayVideo,
  onPreview,
}: {
  asset: MediaItem;
  selecting: boolean;
  selected: boolean;
  onToggleSelect: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onDownload: () => void;
  onPlayVideo: (asset: MediaItem) => void;
  /** 2026-08-15：非选择态下点击图片/音频卡片 → 打开预览弹窗 */
  onPreview: (asset: MediaItem) => void;
}) {
  return (
    <div
      onClick={selecting ? onToggleSelect : () => onPreview(asset)}
      className={cn(
        'group relative cursor-pointer overflow-hidden rounded-xl border border-[#edf0f4] bg-white shadow-[0_10px_24px_rgba(25,31,45,0.05)] transition hover:-translate-y-0.5 hover:border-[#cbd5e1] hover:shadow-[0_16px_34px_rgba(25,31,45,0.08)]',
        selected && 'border-[#1673ff] shadow-[0_0_0_2px_#eaf3ff]'
      )}
    >
      <div className="relative aspect-[4/3] overflow-hidden bg-[#f1f5f9]">
        {asset.type === 'image' ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={asset.url}
            alt={asset.name}
            className="h-full w-full object-cover transition group-hover:scale-105"
            onError={(e) => {
              (e.target as HTMLImageElement).style.display = 'none';
            }}
          />
        ) : asset.type === 'video' ? (
          <VideoThumbnail
            url={asset.url}
            onPlay={() => onPlayVideo(asset)}
            // 2026-08-15：多选态下点击应触发选择，不触发播放
            disabled={selecting}
          />
        ) : (
          <div className="grid h-full w-full place-items-center">
            <Music2 className="h-9 w-9 text-[#cbd5e1]" strokeWidth={1.5} />
          </div>
        )}
        <div className="absolute right-2 top-2 rounded bg-black/75 px-2 py-0.5 text-[11px] font-medium text-white backdrop-blur-sm">
          {asset.type === 'image' ? '图片' : asset.type === 'video' ? '视频' : '音频'}
        </div>
        {/* AI 徽章：固定左上，不随选择态跳动 */}
        {asset.source === 'ai-generated' && (
          <div className="absolute left-2 top-2 rounded bg-[#1673ff] px-1.5 py-0.5 text-[10px] font-semibold tracking-wide text-white">
            AI
          </div>
        )}

        {/* 选择 checkbox：放左下角，避开左上 AI 徽章和右上类型标签 */}
        {selecting && (
          <div
            className={cn(
              'absolute bottom-2 left-2 grid h-5 w-5 place-items-center rounded-md border-2 transition',
              selected
                ? 'border-[#1673ff] bg-[#1673ff] text-white'
                : 'border-white bg-white/70 backdrop-blur-sm'
            )}
          >
            {selected && <Check className="h-3 w-3" strokeWidth={3} />}
          </div>
        )}

        {/* 操作按钮：编辑 / 下载 / 删除（hover 显示，非选择态） */}
        {!selecting && (
          <div className="absolute bottom-2 right-2 flex items-center gap-1 opacity-0 transition group-hover:opacity-100">
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onEdit();
              }}
              title="编辑名称"
              className="grid h-7 w-7 place-items-center rounded-lg bg-black/60 text-white backdrop-blur-sm transition hover:bg-[#1673ff]"
            >
              <Pencil className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onDownload();
              }}
              title="下载"
              className="grid h-7 w-7 place-items-center rounded-lg bg-black/60 text-white backdrop-blur-sm transition hover:bg-[#1673ff]"
            >
              <Download className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onDelete();
              }}
              title="删除"
              className="grid h-7 w-7 place-items-center rounded-lg bg-black/60 text-white backdrop-blur-sm transition hover:bg-[#dc2626]"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          </div>
        )}
      </div>
      <div className="p-2.5">
        <div className="truncate text-[12.5px] font-medium text-[#111318]">{asset.name}</div>
        <div className="mt-0.5 flex items-center justify-between text-[11px] text-[#8a909b]">
          <span>{formatSize(asset.size)}</span>
          <span>{formatDate(asset.createdAt)}</span>
        </div>
      </div>
    </div>
  );
}

function VideoPlayerModal({
  asset,
  onClose,
}: {
  asset: MediaItem;
  onClose: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [muted, setMuted] = useState(false);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4"
      onClick={onClose}
    >
      <div
        className="relative max-h-[90vh] w-full max-w-4xl overflow-hidden rounded-2xl bg-black shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between bg-black/60 px-4 py-2.5">
          <span className="truncate text-sm font-medium text-white">
            {asset.name}
          </span>
          <button
            onClick={onClose}
            className="grid h-7 w-7 place-items-center rounded-lg text-white/80 transition hover:bg-white/10 hover:text-white"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="relative bg-black">
          <video
            ref={videoRef}
            src={getStreamUrl(asset.id)}
            className="max-h-[75vh] w-full"
            controls
            autoPlay
            muted={muted}
            playsInline
            onError={(e) => {
              const el = e.currentTarget;
              el.style.display = 'none';
            }}
          />
          <div className="absolute bottom-4 right-4 flex gap-2">
            <button
              onClick={() => setMuted(!muted)}
              className="grid h-8 w-8 place-items-center rounded-full bg-black/50 text-white backdrop-blur-sm transition hover:bg-black/70"
              title={muted ? '取消静音' : '静音'}
            >
              {muted ? <VolumeX className="h-4 w-4" /> : <Volume2 className="h-4 w-4" />}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function SkeletonGrid({ canUpload, selecting }: { canUpload: boolean; selecting: boolean }) {
  const count = selecting ? 10 : 9;
  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
      {canUpload && (
        <div className="aspect-[4/3] animate-pulse rounded-xl border border-dashed border-[#e0e2e7] bg-white" />
      )}
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="overflow-hidden rounded-xl border border-[#edf0f4] bg-white">
          <div className="aspect-[4/3] animate-pulse bg-[#f1f5f9]" />
          <div className="space-y-1.5 p-2.5">
            <div className="h-3 w-3/4 animate-pulse rounded bg-[#f1f5f9]" />
            <div className="h-2.5 w-1/2 animate-pulse rounded bg-[#f1f5f9]" />
          </div>
        </div>
      ))}
    </div>
  );
}

function EmptyState({
  isAI,
  isAllAssets,
  libraryName,
  onUpload,
}: {
  isAI: boolean;
  isAllAssets: boolean;
  libraryName?: string;
  onUpload: () => void;
}) {
  return (
    <div className="grid place-items-center rounded-2xl border border-dashed border-[#e0e2e7] bg-white py-24">
      <div className="grid h-16 w-16 place-items-center rounded-2xl bg-[#f7f7f8] text-[#8a909b]">
        {isAI ? <IconAiSparkle className="h-7 w-7" /> : <IconFolder className="h-7 w-7" />}
      </div>
      <div className="mt-5 text-[15px] font-semibold text-[#111318]">
        {libraryName ? `${libraryName} 暂无内容` : '暂无资产'}
      </div>
      <div className="mt-1 text-[13px] text-[#8a909b]">
        {isAI
          ? 'AI 创作完成后，结果会自动归档到这里'
          : isAllAssets
            ? '你上传的素材和 AI 生成结果都会汇聚在这里'
            : '点击下方按钮上传图片、视频或音频到本库'}
      </div>
      {!isAI && (
        <button
          onClick={onUpload}
          className="mt-5 inline-flex h-9 items-center gap-1.5 rounded-lg bg-[#1673ff] px-4 text-[13px] font-medium text-white shadow-[0_4px_12px_-4px_rgba(22,115,255,0.4)] hover:bg-[#006cff]"
        >
          <Plus className="h-3.5 w-3.5" />
          添加资产
        </button>
      )}
    </div>
  );
}

// ===================== 资产库精致图标 =====================

/** 我的资产：堆叠的素材卡（表示"所有素材"汇总） */
function IconLibraryAll({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {/* 底层卡片（最深） */}
      <rect x="3.5" y="6" width="13" height="13" rx="2.2" fill="currentColor" fillOpacity="0.08" />
      {/* 中层卡片 */}
      <rect x="6" y="4.5" width="13" height="13" rx="2.2" fill="currentColor" fillOpacity="0.16" />
      {/* 顶层卡片（最亮） */}
      <rect x="8.5" y="3" width="13" height="13" rx="2.2" fill="white" />
      {/* 顶层内容：图片占位 + 折角 */}
      <path d="M11 7h7" />
      <circle cx="11.5" cy="10.5" r="1" fill="currentColor" />
      <path d="m10.5 14 2-2 2 2 1.5-1.5L19 15.5" />
    </svg>
  );
}

/** AI 生成结果：四角星 + 小星 + 光芒（表示 AI 创作） */
function IconAiSparkle({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {/* 主四角星：菱形 + 上下左右尖角 */}
      <path
        d="M12 2.5 13.6 9.1 20.5 10.6 13.6 12 12 21.5 10.4 12 3.5 10.6 10.4 9.1z"
        fill="currentColor"
        fillOpacity="0.18"
      />
      {/* 小四角星：右上 */}
      <path d="m18 4 .7 1.6L20.5 6l-1.8.4L18 8.5l-.7-2.1L15.5 6l1.8-.4z" fill="currentColor" />
      {/* 小四角星：左下 */}
      <path d="m6 15 .55 1.25L8 16.7l-1.45.45L6 18.5l-.55-1.35L4 16.7l1.45-.45z" fill="currentColor" />
    </svg>
  );
}

/** 自定义库：精致文件夹（带顶部标签细节） */
function IconFolder({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path
        d="M3.5 6.5A2 2 0 0 1 5.5 4.5h3.2a1.5 1.5 0 0 1 1.06.44L11 6.2h7.5a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5.5a2 2 0 0 1-2-2z"
        fill="currentColor"
        fillOpacity="0.1"
      />
      <path d="M3.5 9.5h17" strokeOpacity="0.4" />
      <path d="M7 13.5h6" strokeOpacity="0.6" />
      <path d="M7 16h4" strokeOpacity="0.4" />
    </svg>
  );
}

/** 新建资产库：简洁加号（居中圆角方块 + 加号） */
function IconFolderPlus({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {/* 圆角方形底 */}
      <rect
        x="3.5"
        y="3.5"
        width="17"
        height="17"
        rx="3.5"
        fill="currentColor"
        fillOpacity="0.08"
      />
      {/* 加号 */}
      <path d="M12 8v8" strokeWidth="1.75" />
      <path d="M8 12h8" strokeWidth="1.75" />
    </svg>
  );
}

/** 真人库：人物剪影（带头肩的简化像） */
function IconPerson({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {/* 头 */}
      <circle cx="12" cy="8" r="3.5" fill="currentColor" fillOpacity="0.18" />
      {/* 肩 */}
      <path
        d="M5 19.5a7 7 0 0 1 14 0"
        fill="currentColor"
        fillOpacity="0.12"
      />
    </svg>
  );
}

/** 虚拟人库：用户圆圈 + 装饰光环（区别于真人） */
function IconUserCircle({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {/* 外圈 */}
      <circle cx="12" cy="12" r="8.5" fill="currentColor" fillOpacity="0.1" />
      {/* 头 */}
      <circle cx="12" cy="10" r="2.8" fill="currentColor" fillOpacity="0.22" />
      {/* 肩部弧线 */}
      <path d="M6.5 18.5a5.5 5.5 0 0 1 11 0" />
    </svg>
  );
}

/* ============= 图片/音频点击预览弹窗 ============= */
function AssetPreviewModal({
  asset,
  onClose,
}: {
  asset: MediaItem;
  onClose: () => void;
}) {
  // ESC 关闭
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-[1200] flex items-center justify-center bg-black/80 p-6 backdrop-blur-sm"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
    >
      {/* 顶部信息条 */}
      <div
        className="pointer-events-none absolute left-1/2 top-6 z-10 flex max-w-[90vw] -translate-x-1/2 items-center gap-3 rounded-full bg-black/60 px-5 py-2 text-white backdrop-blur-md"
        onClick={(e) => e.stopPropagation()}
      >
        <span className="text-[12px] font-medium uppercase tracking-wider text-white/70">
          {asset.type === 'image' ? '图片预览' : '音频预览'}
        </span>
        <span className="h-3 w-px bg-white/30" />
        <span className="truncate text-[13px] font-medium">{asset.name}</span>
        {asset.source === 'ai-generated' && (
          <span className="rounded-full bg-[#1673ff] px-2 py-0.5 text-[10px] font-semibold">
            AI
          </span>
        )}
        {asset.libraryName && (
          <span className="rounded-full bg-white/15 px-2 py-0.5 text-[11px]">
            {asset.libraryName}
          </span>
        )}
      </div>

      {/* 关闭按钮 */}
      <button
        type="button"
        onClick={onClose}
        className="absolute right-6 top-6 z-10 grid h-10 w-10 place-items-center rounded-full bg-black/60 text-white backdrop-blur-md transition hover:bg-black/80"
        aria-label="关闭预览"
      >
        <X className="h-5 w-5" />
      </button>

      {/* 内容区：点击自身不关闭（避免图片点击穿透） */}
      <div
        className="relative flex max-h-[88vh] max-w-[92vw] items-center justify-center"
        onClick={(e) => e.stopPropagation()}
      >
        {asset.type === 'image' ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={asset.url}
            alt={asset.name}
            className="max-h-[88vh] max-w-[92vw] rounded-lg object-contain shadow-[0_30px_80px_rgba(0,0,0,0.5)]"
          />
        ) : asset.type === 'audio' ? (
          <div className="flex w-[480px] flex-col items-center gap-6 rounded-2xl bg-white p-10 shadow-[0_30px_80px_rgba(0,0,0,0.5)]">
            <div className="grid h-24 w-24 place-items-center rounded-full bg-gradient-to-br from-[#eaf3ff] to-[#cfe0ff] text-[#1673ff]">
              <Music2 className="h-12 w-12" strokeWidth={1.5} />
            </div>
            <div className="text-center">
              <div className="truncate text-[15px] font-medium text-[#111318]">{asset.name}</div>
              {asset.libraryName && (
                <div className="mt-1 text-[12px] text-[#8a909b]">{asset.libraryName}</div>
              )}
            </div>
            <audio
              src={asset.url}
              controls
              autoPlay
              className="w-full"
            />
          </div>
        ) : null}
      </div>

      {/* 底部操作提示 */}
      <div className="pointer-events-none absolute bottom-6 left-1/2 -translate-x-1/2 text-[11px] text-white/60">
        点击任意空白处或按 ESC 关闭
      </div>
    </div>
  );
}
