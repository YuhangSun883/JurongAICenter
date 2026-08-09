export type MediaType = 'image' | 'video' | 'audio';
export type MediaSource = 'uploaded' | 'ai-generated';
export type LibraryType = 'system-uploaded' | 'system-ai' | 'custom';

export interface MediaLibrary {
  id: number;
  name: string;
  type: LibraryType | string;
  iconKey?: string;
  description?: string;
  sortOrder?: number;
  assetCount?: number;
  createdAt?: string | number;
  updatedAt?: string | number;
}

export interface MediaItem {
  id: number;
  libraryId?: number | null;
  libraryName?: string;
  type: MediaType;
  source: MediaSource;
  url: string;
  name: string;
  mimeType?: string;
  /** 后端字段 sizeBytes，前端可兼容 size */
  size?: number;
  sizeBytes?: number;
  width?: number;
  height?: number;
  /** 后端字段 durationSec，前端可兼容 duration */
  duration?: number;
  durationSec?: number;
  sourceTool?: string;
  sourceTaskId?: string;
  createdAt: string;
  updatedAt?: string;
}

export type MediaAsset = MediaItem;

export interface MediaListQuery {
  libraryId?: number | null;
  type?: MediaType | string;
  source?: MediaSource | 'all' | string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}

export interface MediaUploadResponse {
  id: number;
  url: string;
  name: string;
  type: MediaType;
  size: number;
  sizeBytes?: number;
}

export interface BatchDeleteRequest {
  ids: number[];
}

export interface PatchAssetRequest {
  name: string;
}

export interface CreateLibraryRequest {
  name: string;
  iconKey?: string;
  description?: string;
}

export interface RoleCategory {
  key: string;
  label: string;
}

export type MediaRoleCategory = RoleCategory;

export interface MediaRole {
  id: number;
  name: string;
  category: string;
  imageUrl: string;
  description?: string;
  tags?: string[];
  createdAt?: number;
}

export interface RoleListQuery {
  category?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}