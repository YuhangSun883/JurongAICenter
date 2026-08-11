'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
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
} from 'lucide-react';
import { mediaApi } from '@/api/media';
import type { MediaItem, MediaLibrary, MediaType } from '@/types/media';
import { cn } from '@/lib/utils';
import { getAccessToken } from '@/lib/auth-store';
import { VideoThumbnail } from '@/components/common/VideoThumbnail';

type TypeFilter = 'all' | MediaType;
type SortOrder = 'desc' | 'asc';

const TYPE_TABS: { key: TypeFilter; label: string; Icon?: typeof ImageIcon }[] = [
  { key: 'all', label: '全部' },
  { key: 'image', label: '图片', Icon: ImageIcon },
  { key: 'video', label: '视频', Icon: Film },
  { key: 'audio', label: '音频', Icon: Music2 },
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
  const [libs, setLibs] = useState<MediaLibrary[]>([]);
  const [activeLibId, setActiveLibId] = useState<number | null>(null);
  const [type, setType] = useState<TypeFilter>('all');
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

  // 上传
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [uploading, setUploading] = useState(false);

  // 拉取库
  const refreshLibs = async () => {
    const list = await mediaApi.listLibraries();
    setLibs(list);
    return list;
  };

  useEffect(() => {
    refreshLibs().then((list) => {
      const uploaded = list.find((l) => l.type === 'system-uploaded') ?? list[0];
      if (uploaded) setActiveLibId(uploaded.id);
    });
  }, []);

  const activeLib = useMemo(
    () => libs.find((l) => l.id === activeLibId) ?? null,
    [libs, activeLibId]
  );

  const isAILib = activeLib?.type === 'system-ai';
  // AI 库不允许上传
  const canUpload = !!activeLib && !isAILib;

  // 拉取素材
  useEffect(() => {
    if (activeLibId == null) return;
    setLoading(true);
    const queryLibId = getQueryLibraryId(activeLib);
    mediaApi
      .listAssets({
        libraryId: queryLibId,
        type: type === 'all' ? undefined : type,
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
  }, [activeLibId, type, sortOrder, keyword, activeLib?.type]);

  // 切换库时清空选择
  useEffect(() => {
    setSelected(new Set());
    setSelecting(false);
  }, [activeLibId]);

  async function handleSaveLib(payload: { name: string; description: string }) {
    if (editingLib) {
      await mediaApi.renameLibrary(editingLib.id, payload);
    } else {
      const lib = await mediaApi.createLibrary(payload);
      setActiveLibId(lib.id);
    }
    await refreshLibs();
    setShowCreateLib(false);
    setEditingLib(null);
  }

  async function handleDeleteLib(lib: MediaLibrary) {
    if (lib.type !== 'custom') return;
    if (!confirm(`确定删除资产库「${lib.name}」？库内所有素材将一并删除。`)) return;
    await mediaApi.deleteLibrary(lib.id);
    const list = await refreshLibs();
    if (activeLibId === lib.id) {
      setActiveLibId(list[0]?.id ?? null);
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

  async function handleSaveAssetName(name: string) {
    if (!editingAsset) return;
    const updated = await mediaApi.renameAsset(editingAsset.id, name);
    setAssets((prev) => prev.map((a) => (a.id === updated.id ? updated : a)));
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

  const systemLibs = libs.filter((l) => l.type !== 'custom');
  const customLibs = libs.filter((l) => l.type === 'custom');

  return (
    <div className="mx-auto w-full max-w-[1200px] px-4 pb-32 pt-6 sm:px-6">
      {/* 标题 */}
      <div className="mb-5 flex items-center justify-between">
        <h1 className="text-xl font-bold text-[#111318]">我的资产</h1>
      </div>

      {/* 资产库入口卡片 */}
      <div className="mb-7 flex flex-wrap gap-4">
        {/* 新建资产库 */}
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
        <div className="mt-2.5 text-[13.5px] font-medium text-[#111318]">新建资产库</div>
        <div className="mt-0.5 text-xs text-[#8a909b]">自定义管理素材</div>
      </button>

        {/* 系统默认库 */}
        {systemLibs.map((lib) => {
          const isAI = lib.type === 'system-ai';
          return (
            <LibraryCard
              key={lib.id}
              lib={lib}
              isAI={isAI}
              isActive={lib.id === activeLibId}
              onClick={() => setActiveLibId(lib.id)}
            />
          );
        })}

        {/* 自定义库（支持编辑/删除） */}
        {customLibs.map((lib) => (
          <LibraryCard
            key={lib.id}
            lib={lib}
            isAI={false}
            isActive={lib.id === activeLibId}
            onClick={() => setActiveLibId(lib.id)}
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
              {selecting ? '取消选择' : '选择'}
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
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
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
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
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
          onClose={() => {
            setShowCreateLib(false);
            setEditingLib(null);
          }}
          onSave={handleSaveLib}
        />
      )}

      {/* 资产编辑弹窗（改名） */}
      {editingAsset && (
        <AssetEditDialog
          asset={editingAsset}
          assets={assets}
          currentLibId={activeLibId}
          onClose={() => setEditingAsset(null)}
          onSave={handleSaveAssetName}
        />
      )}

      {/* 视频播放器弹窗 */}
      {playingVideo && (
        <VideoPlayerModal
          asset={playingVideo}
          onClose={() => setPlayingVideo(null)}
        />
      )}
    </div>
  );
}

function LibraryDialog({
  mode,
  initial,
  libs,
  onClose,
  onSave,
}: {
  mode: 'create' | 'edit';
  initial: MediaLibrary | null;
  libs: MediaLibrary[];
  onClose: () => void;
  onSave: (payload: { name: string; description: string }) => Promise<void>;
}) {
  const [name, setName] = useState(initial?.name ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 预校验：是否与已有资产库重名（排除正在编辑的那个）
  const isDuplicate = useMemo(() => {
    const trimmed = name.trim();
    if (!trimmed) return false;
    // 从全局 libs 中找同名项
    const dup = libs.some(
      (l) => l.name === trimmed && (!initial || l.id !== initial.id)
    );
    return dup;
  }, [name, libs, initial]);

  async function handleSubmit() {
    const trimmed = name.trim();
    if (!trimmed) return;
    if (isDuplicate) {
      setError(`已存在同名资产库「${trimmed}」，请换个名称`);
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await onSave({ name: trimmed, description: description.trim() });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      // 后端 7001 / 通用错误都显示
      if (msg.includes('已存在') || msg.includes('duplicate') || msg.includes('重名')) {
        setError(`已存在同名资产库「${trimmed}」，请换个名称`);
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
        className="w-[440px] overflow-hidden rounded-2xl bg-white shadow-[0_20px_60px_rgba(0,0,0,0.15)]"
      >
        <div className="flex items-center justify-between px-6 pb-3 pt-5">
          <h3 className="text-base font-semibold text-[#111318]">
            {mode === 'create' ? '新建资产库' : '编辑资产库'}
          </h3>
          <button
            onClick={onClose}
            className="grid h-7 w-7 place-items-center rounded-lg text-[#8a909b] hover:bg-[#f7f7f8]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="px-6 pb-6">
          {/* 名称 */}
          <label className="mb-1.5 block text-[12.5px] font-medium text-[#5f6876]">
            资产库名称 <span className="text-[#dc2626]">*</span>
          </label>
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="例如：商品素材 / 角色参考"
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
              已存在同名资产库，请换个名称
            </div>
          )}
          {error && (
            <div className="mt-1.5 flex items-center gap-1 text-[12px] text-[#dc2626]">
              <Info className="h-3.5 w-3.5" />
              {error}
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
            rows={3}
            className="w-full resize-none rounded-lg border border-[#edf0f4] bg-white px-3 py-2 text-[13px] leading-relaxed outline-none focus:border-[#1673ff] focus:shadow-[0_0_0_3px_#eaf3ff]"
          />
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
  currentLibId,
  onClose,
  onSave,
}: {
  asset: MediaItem;
  assets: MediaItem[];
  /**
   * 当前查看的 libraryId：用于定位"同库"范围
   * - undefined/null：表示"我的资产"视图（所有素材的并集，含未归库的 null 库）
   * - system-ai：暂不在此场景出现，由调用方传具体 id
   */
  currentLibId?: number | null;
  onClose: () => void;
  onSave: (name: string) => Promise<void>;
}) {
  const [name, setName] = useState(asset.name);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 重名预校验：与"当前视图同库"内其他素材比较（排除自己）
  const isDuplicate = useMemo(() => {
    const trimmed = name.trim();
    if (!trimmed) return false;
    return assets.some(
      (a) => a.id !== asset.id && a.name === trimmed
    );
  }, [name, assets, asset.id]);

  useEffect(() => {
    setError(null);
  }, [name]);

  async function handleSubmit() {
    const trimmed = name.trim();
    if (!trimmed) return;
    if (trimmed === asset.name) {
      onClose();
      return;
    }
    if (isDuplicate) {
      setError(`当前库内已存在同名素材「${trimmed}」，请换个名称`);
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await onSave(trimmed);
      onClose();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes('已存在') || msg.includes('duplicate') || msg.includes('重名')) {
        setError(`当前库内已存在同名素材「${trimmed}」，请换个名称`);
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
                <Film className="h-6 w-6 text-[#cbd5e1]" />
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

          {/* 名称 */}
          <label className="mb-1.5 block text-[12.5px] font-medium text-[#5f6876]">
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
              当前库内已存在同名素材，请换个名称
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
              : 'bg-gradient-to-br from-[#7c8cff] to-[#5b9aff] text-white'
        )}
      >
        {/* 左上：AI 标签 */}
        {isAI && (
          <div className="absolute left-2 top-2 rounded bg-white/85 px-1.5 py-0.5 text-[10px] font-semibold tracking-wide text-[#1673ff] backdrop-blur-sm">
            AI
          </div>
        )}

        {/* 中央图标 */}
        <div className="grid h-16 w-16 place-items-center">
          {isAI ? (
            <IconAiSparkle className="h-9 w-9" />
          ) : lib.type === 'system-uploaded' ? (
            <IconLibraryAll className="h-9 w-9" />
          ) : (
            <IconFolder className="h-9 w-9" />
          )}
        </div>

        {/* 左下：数量 */}
        <div className="absolute bottom-2 left-2.5 text-lg font-bold tracking-tight opacity-90">
          {lib.assetCount ?? 0}
        </div>

        {/* 右下：操作按钮组（hover 显示） */}
        {canManage && (
          <div className="absolute bottom-2 right-2 flex items-center gap-1.5 opacity-0 transition group-hover:opacity-100">
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

      <div onClick={onClick} className="mt-2.5 flex cursor-pointer items-center gap-1.5">
        <span className="truncate text-[13.5px] font-medium text-[#111318]">{lib.name}</span>
      </div>
      <div className="mt-0.5 truncate text-xs text-[#8a909b]">
        {lib.description ||
          (isAI ? 'AI 创作产出' : isAllAssetsView(lib) ? '所有素材' : '自建分类')}
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
}: {
  asset: MediaItem;
  selecting: boolean;
  selected: boolean;
  onToggleSelect: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onDownload: () => void;
  onPlayVideo: (asset: MediaItem) => void;
}) {
  return (
    <div
      onClick={selecting ? onToggleSelect : undefined}
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
            assetId={asset.id}
            onPlay={() => onPlayVideo(asset)}
          />
        ) : (
          <div className="grid h-full w-full place-items-center">
            <Music2 className="h-9 w-9 text-[#cbd5e1]" strokeWidth={1.5} />
          </div>
        )}
        <div className="absolute right-2 top-2 rounded bg-black/75 px-2 py-0.5 text-[11px] font-medium text-white backdrop-blur-sm">
          {asset.type === 'image' ? '图片' : asset.type === 'video' ? '视频' : '音频'}
        </div>
        {asset.source === 'ai-generated' && (
          <div className="absolute left-2 top-2 rounded bg-[#1673ff] px-1.5 py-0.5 text-[10px] font-semibold tracking-wide text-white">
            AI
          </div>
        )}

        {/* 选择 checkbox */}
        {selecting && (
          <div
            className={cn(
              'absolute left-2 top-2 grid h-5 w-5 place-items-center rounded-md border-2 transition',
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
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
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
