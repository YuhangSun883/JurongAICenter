// 媒体（素材库 / 资产库 / 上传）

export type MediaType = 'image' | 'video' | 'audio';
export type MediaSource = 'uploaded' | 'ai-generated';
export type MediaCategory = 'image' | 'video' | 'audio';

/** system-uploaded / system-ai / custom */
export type LibraryType = 'system-uploaded' | 'system-ai' | 'custom';

/** 资产库 */
export interface MediaLibrary {
  id: number;
  name: string;
  type: LibraryType;
  iconKey?: string;
  description?: string;
  sortOrder?: number;
  assetCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

/** 素材 */
export interface MediaItem {
  id: number;
  libraryId?: number;
  libraryName?: string;
  type: MediaType;
  source: MediaSource;
  url: string;
  name: string;
  mimeType?: string;
  size?: number;
  width?: number;
  height?: number;
  duration?: number;
  sourceTool?: string;
  sourceTaskId?: string;
  createdAt: string;
  updatedAt?: string;
}

/** 列表查询 */
export interface MediaListQuery {
  libraryId?: number;
  type?: MediaType;
  source?: MediaSource | 'all';
  keyword?: string;
  page?: number;
  pageSize?: number;
}

/** 上传响应 */
export interface MediaUploadResponse {
  id: number;
  url: string;
  name: string;
  type: MediaType;
  size: number;
}

/** 批量删除请求 */
export interface BatchDeleteRequest {
  ids: number[];
}

/** 改名请求 */
export interface PatchAssetRequest {
  name: string;
}

/** 创建库请求 */
export interface CreateLibraryRequest {
  name: string;
  iconKey?: string;
  description?: string;
}

/** 角色库分类（保留旧字段，向后兼容） */
export interface RoleCategory {
  key: string;
  label: string;
}

/** 角色库列表查询（保留旧字段，向后兼容） */
export interface RoleListQuery {
  category?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}
