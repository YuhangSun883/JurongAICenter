// 真实后端实现 —— 文件上传走 multipart/form-data，其它用 JSON
import { request } from '@/lib/http';
import type {
  MediaItem,
  MediaListQuery,
  MediaUploadResponse,
  RoleCategory,
  RoleListQuery,
} from '@/types/media';

const API = '/api/media';
const UPLOAD_API = '/api/comfyui'; // 后端实际上传端点

export async function listAssets(q: MediaListQuery = {}): Promise<{ items: MediaItem[]; total: number }> {
  // 后端暂无 /api/media/assets 列表接口
  return Promise.resolve({ items: [], total: 0 });
}

export async function deleteAsset(_id: string): Promise<void> {
  // 后端暂无 /api/media/assets/{id} 删除接口
  return Promise.resolve();
}

/** 文件上传：后端实际端点是 POST /api/comfyui/upload (multipart) */
export async function uploadAsset(file: File): Promise<MediaUploadResponse> {
  const form = new FormData();
  form.append('file', file);
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null;
  const res = await fetch(`${UPLOAD_API}/upload`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
  });
  if (!res.ok) throw new Error(`upload failed: ${res.status}`);
  return res.json();
}

/** 角色库分类：后端暂无 */
export async function listRoleCategories(): Promise<RoleCategory[]> {
  return Promise.resolve([]);
}

/** 角色库列表：后端暂无 */
export async function listRoles(q: RoleListQuery = {}): Promise<{ items: MediaItem[]; total: number }> {
  return Promise.resolve({ items: [], total: 0 });
}
