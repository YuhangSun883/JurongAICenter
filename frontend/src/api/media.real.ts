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
