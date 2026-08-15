import { request } from '@/lib/http';
import type {
  BatchDeleteRequest,
  CreateLibraryRequest,
  MediaItem,
  MediaLibrary,
  MediaListQuery,
  MediaRole,
  MediaUploadResponse,
  PatchAssetRequest,
  RoleCategory,
  RoleListQuery,
} from '@/types/media';
import type { PageResult } from '@/types/api';
import { getAccessToken } from '@/lib/auth-store';

const API = '/api/media';

export async function listLibraries(): Promise<MediaLibrary[]> {
  const res = await request<MediaLibrary[]>(`${API}/libraries`);
  return res ?? [];
}

/**
 * 2026-08-15 V19：只列根库（parent_id IS NULL）
 * 用于首屏加载时只显示顶层库，避免重复展示子库
 */
export async function listRootLibraries(): Promise<MediaLibrary[]> {
  const res = await request<MediaLibrary[]>(`${API}/libraries/roots`);
  return res ?? [];
}

/**
 * 2026-08-15 V19：列某父库下直接子库
 * - 父库 id=0 或不合法：返回空数组
 * - 返回按 sort_order ASC, id ASC
 */
export async function listChildLibraries(parentId: number): Promise<MediaLibrary[]> {
  if (!parentId || parentId <= 0) return [];
  const res = await request<MediaLibrary[]>(`${API}/libraries/${parentId}/children`);
  return res ?? [];
}

/**
 * 2026-08-15 V19：取某库面包屑（root → ... → 当前）
 * - 返回按从远到近的顺序，第一项是根库，最后一项是当前库
 * - 如果 id 不存在或不属于当前用户，返回空数组
 */
export async function getLibraryBreadcrumb(libraryId: number): Promise<MediaLibrary[]> {
  if (!libraryId || libraryId <= 0) return [];
  const res = await request<MediaLibrary[]>(`${API}/libraries/${libraryId}/breadcrumb`);
  return res ?? [];
}

export async function createLibrary(req: CreateLibraryRequest): Promise<MediaLibrary> {
  return request<MediaLibrary>(`${API}/libraries`, { method: 'POST', body: req });
}

export async function renameLibrary(id: number, req: CreateLibraryRequest): Promise<MediaLibrary> {
  return request<MediaLibrary>(`${API}/libraries/${id}`, { method: 'PATCH', body: req });
}

export async function deleteLibrary(id: number): Promise<void> {
  await request(`${API}/libraries/${id}`, { method: 'DELETE' });
}

export async function listAssets(q: MediaListQuery = {}): Promise<PageResult<MediaItem>> {
  const query: Record<string, string | number | boolean | undefined> = {};
  if (q.libraryId != null) query.libraryId = q.libraryId;
  if (q.type) query.type = q.type;
  if (q.source && q.source !== 'all') query.source = q.source;
  if (q.keyword) query.keyword = q.keyword;
  query.page = q.page ?? 1;
  query.pageSize = q.pageSize ?? 24;

  const res = await request<PageResult<MediaItem>>(`${API}/assets`, { query });
  return res ?? { items: [], total: 0, page: query.page as number, pageSize: query.pageSize as number };
}

export async function getAsset(id: number): Promise<MediaItem> {
  return request<MediaItem>(`${API}/assets/${id}`);
}

export async function renameAsset(id: number, name: string): Promise<MediaItem> {
  return request<MediaItem>(`${API}/assets/${id}`, {
    method: 'PATCH',
    body: { name } as PatchAssetRequest,
  });
}

/**
 * 2026-08-15：通用 PATCH 资产。
 * - 只传 name：等价于重命名
 * - 只传 libraryId：把素材搬到目标库
 * - 都传：同时改名+搬库
 */
export async function patchAsset(
  id: number,
  payload: { name?: string; libraryId?: number | null }
): Promise<MediaItem> {
  return request<MediaItem>(`${API}/assets/${id}`, {
    method: 'PATCH',
    body: payload as PatchAssetRequest,
  });
}

export async function deleteAsset(id: number): Promise<void> {
  await request(`${API}/assets/${id}`, { method: 'DELETE' });
}

export async function batchDeleteAssets(ids: number[]): Promise<{ deleted: number; requested: number }> {
  return request(`${API}/assets/batch-delete`, {
    method: 'POST',
    body: { ids } as BatchDeleteRequest,
  });
}

export async function uploadAsset(file: File, libraryId?: number): Promise<MediaUploadResponse> {
  const form = new FormData();
  form.append('file', file);
  if (libraryId != null) form.append('libraryId', String(libraryId));

  const token = getAccessToken();
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;

  // 走相对路径，由 Next.js rewrites 代理到 :8080
  const res = await fetch(`${API}/assets`, {
    method: 'POST',
    headers,
    body: form,
  });

  let payload: any = undefined;
  try {
    payload = await res.json();
  } catch {
    // ignore non-json response
  }

  if (!res.ok) {
    throw new Error(payload?.message || `upload failed: ${res.status}`);
  }

  if (payload && typeof payload === 'object' && 'code' in payload && 'data' in payload) {
    if (payload.code !== 0) throw new Error(payload.message || `HTTP ${payload.code}`);
    return payload.data as MediaUploadResponse;
  }

  return payload as MediaUploadResponse;
}

export async function listRoleCategories(): Promise<RoleCategory[]> {
  const res = await request<RoleCategory[]>(`${API}/roles/categories`);
  return res ?? [];
}

export async function listRoles(q: RoleListQuery = {}): Promise<MediaRole[]> {
  const res = await request<MediaRole[] | PageResult<MediaRole>>(`${API}/roles`, {
    query: q as Record<string, string | number | boolean | undefined>,
  });
  if (Array.isArray(res)) return res;
  return res?.items ?? [];
}
