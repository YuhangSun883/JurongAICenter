// 媒体模块的 mock
import type {
  CreateLibraryRequest,
  MediaItem,
  MediaLibrary,
  MediaListQuery,
  MediaUploadResponse,
  RoleCategory,
  RoleListQuery,
} from '@/types/media';
import type { PageResult } from '@/types/api';
const ROLES: Record<string, Array<{ id: string; url: string; name: string; size: string }>> = {};

const delay = <T>(v: T, ms = 200) => new Promise<T>((r) => setTimeout(() => r(v), ms));

// ============ Mock 库 ============
let LIB_COUNTER = 100;
let ASSET_COUNTER = 100;
const LIBS: MediaLibrary[] = [
  { id: 1, name: '我的资产', type: 'system-uploaded', iconKey: 'folder', sortOrder: 0, assetCount: 0, createdAt: '2026-08-01' },
  { id: 2, name: 'AI 生成结果', type: 'system-ai', iconKey: 'sparkles', sortOrder: 1, assetCount: 0, createdAt: '2026-08-01' },
];
const ASSETS: MediaItem[] = [
  { id: 1, libraryId: 1, libraryName: '我的资产', type: 'image', source: 'uploaded', url: 'https://picsum.photos/seed/a1/400/300', name: '商品-主图-01.png', size: 102400, createdAt: '2026-08-07 11:20:00' },
  { id: 2, libraryId: 1, libraryName: '我的资产', type: 'image', source: 'uploaded', url: 'https://picsum.photos/seed/a2/400/300', name: '商品-主图-02.png', size: 89400, createdAt: '2026-08-07 10:18:00' },
  { id: 3, libraryId: 2, libraryName: 'AI 生成结果', type: 'image', source: 'ai-generated', url: 'https://picsum.photos/seed/a3/400/300', name: 'AI-banner.png', size: 156000, createdAt: '2026-08-07 09:12:00' },
  { id: 4, libraryId: 2, libraryName: 'AI 生成结果', type: 'image', source: 'ai-generated', url: 'https://picsum.photos/seed/a4/400/300', name: 'AI-主图.png', size: 178000, createdAt: '2026-08-06 22:10:00' },
  { id: 5, libraryId: 2, libraryName: 'AI 生成结果', type: 'image', source: 'ai-generated', url: 'https://picsum.photos/seed/a5/400/300', name: 'AI-详情页.png', size: 92000, createdAt: '2026-08-06 18:33:00' },
];

// 同步 assetCount
const refreshLibCounts = () => {
  const totalForUser = ASSETS.length;
  const totalAiForUser = ASSETS.filter((a) => a.source === 'ai-generated').length;
  LIBS.forEach((lib) => {
    if (lib.type === 'system-uploaded') {
      // 我的资产：跨库汇总（所有素材）
      lib.assetCount = totalForUser;
    } else if (lib.type === 'system-ai') {
      // AI 生成结果：只算 AI 素材
      lib.assetCount = totalAiForUser;
    } else {
      // 自定义库：本库内素材
      lib.assetCount = ASSETS.filter((a) => a.libraryId === lib.id).length;
    }
  });
};
refreshLibCounts();

// ============ 资产库 ============

export async function listLibraries(): Promise<MediaLibrary[]> {
  refreshLibCounts();
  return delay(
    LIBS.slice().sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  );
}

export async function createLibrary(req: CreateLibraryRequest): Promise<MediaLibrary> {
  if (LIBS.some((l) => l.name === req.name)) {
    throw new Error('资产库名称已存在');
  }
  const newLib: MediaLibrary = {
    id: ++LIB_COUNTER,
    name: req.name,
    type: 'custom',
    iconKey: req.iconKey ?? 'folder',
    description: req.description,
    sortOrder: LIBS.length,
    assetCount: 0,
    createdAt: new Date().toISOString(),
  };
  LIBS.push(newLib);
  return delay(newLib);
}

export async function renameLibrary(id: number, req: CreateLibraryRequest): Promise<MediaLibrary> {
  const lib = LIBS.find((l) => l.id === id);
  if (!lib) throw new Error('资产库不存在');
  if (lib.type !== 'custom') throw new Error('系统默认库不可修改');
  if (LIBS.some((l) => l.name === req.name && l.id !== id)) throw new Error('资产库名称已存在');
  lib.name = req.name;
  if (req.iconKey) lib.iconKey = req.iconKey;
  if (req.description !== undefined) lib.description = req.description;
  return delay({ ...lib });
}

export async function deleteLibrary(id: number): Promise<void> {
  const idx = LIBS.findIndex((l) => l.id === id);
  if (idx < 0) throw new Error('资产库不存在');
  if (LIBS[idx].type !== 'custom') throw new Error('系统默认库不可删除');
  LIBS.splice(idx, 1);
  // 级联删除素材
  for (let i = ASSETS.length - 1; i >= 0; i--) {
    if (ASSETS[i].libraryId === id) ASSETS.splice(i, 1);
  }
}

// ============ 素材 ============

export async function listAssets(q: MediaListQuery = {}): Promise<PageResult<MediaItem>> {
  let items = ASSETS.slice();
  if (q.libraryId != null) items = items.filter((a) => a.libraryId === q.libraryId);
  if (q.type) items = items.filter((a) => a.type === q.type);
  if (q.source && q.source !== 'all') items = items.filter((a) => a.source === q.source);
  if (q.keyword) items = items.filter((a) => a.name.includes(q.keyword!));
  const page = q.page ?? 1;
  const pageSize = q.pageSize ?? 20;
  const start = (page - 1) * pageSize;
  return delay({ items: items.slice(start, start + pageSize), total: items.length, page, pageSize });
}

export async function getAsset(id: number): Promise<MediaItem> {
  const a = ASSETS.find((x) => x.id === id);
  if (!a) throw new Error('素材不存在');
  return delay(a);
}

export async function renameAsset(id: number, name: string): Promise<MediaItem> {
  const a = ASSETS.find((x) => x.id === id);
  if (!a) throw new Error('素材不存在');
  a.name = name;
  return delay({ ...a });
}

export async function patchAsset(
  id: number,
  payload: { name?: string; libraryId?: number | null }
): Promise<MediaItem> {
  const a = ASSETS.find((x) => x.id === id);
  if (!a) throw new Error('素材不存在');
  if (payload.name !== undefined) a.name = payload.name;
  if (payload.libraryId != null) {
    a.libraryId = payload.libraryId;
    const lib = LIBS.find((l) => l.id === payload.libraryId);
    if (lib) a.libraryName = lib.name;
  }
  return delay({ ...a });
}

export async function deleteAsset(id: number): Promise<void> {
  const idx = ASSETS.findIndex((x) => x.id === id);
  if (idx >= 0) ASSETS.splice(idx, 1);
}

export async function batchDeleteAssets(ids: number[]): Promise<{ deleted: number; requested: number }> {
  let deleted = 0;
  for (let i = ASSETS.length - 1; i >= 0; i--) {
    if (ids.includes(ASSETS[i].id)) {
      ASSETS.splice(i, 1);
      deleted++;
    }
  }
  return delay({ deleted, requested: ids.length });
}

export async function uploadAsset(file: File, libraryId?: number): Promise<MediaUploadResponse> {
  // 没传 libraryId 默认进 "我的资产"
  const targetLibId = libraryId ?? LIBS.find((l) => l.type === 'system-uploaded')?.id ?? 1;
  const lib = LIBS.find((l) => l.id === targetLibId);
  const newAsset: MediaItem = {
    id: ++ASSET_COUNTER,
    libraryId: targetLibId,
    libraryName: lib?.name,
    type: file.type.startsWith('video') ? 'video' : file.type.startsWith('audio') ? 'audio' : 'image',
    source: 'uploaded',
    url: URL.createObjectURL(file),
    name: file.name,
    size: file.size,
    createdAt: new Date().toISOString(),
  };
  ASSETS.unshift(newAsset);
  refreshLibCounts();
  return delay({
    id: newAsset.id,
    url: newAsset.url,
    name: newAsset.name,
    type: newAsset.type as any,
    size: newAsset.size!,
  });
}

// ============ 角色库（兼容旧接口） ============

const ROLE_CATEGORIES: RoleCategory[] = [
  { key: 'face', label: '逼真人脸' },
  { key: 'urban-blue', label: '都市蓝领' },
  { key: 'kids', label: '儿童' },
  { key: 'fashion', label: '时尚模特' },
];

export async function listRoleCategories(): Promise<RoleCategory[]> {
  return delay(ROLE_CATEGORIES);
}

export async function listRoles(q: RoleListQuery = {}): Promise<{ items: MediaItem[]; total: number }> {
  const list = (q.category && ROLES[q.category]) || [];
  const items: MediaItem[] = list.map((r) => ({
    id: parseInt(r.id, 10) || 0,
    type: 'image',
    source: 'ai-generated',
    url: r.url,
    name: r.name,
    width: parseInt(r.size.split('x')[0], 10),
    height: parseInt(r.size.split('x')[1], 10),
    createdAt: new Date().toISOString(),
  }));
  return delay({ items, total: items.length });
}
