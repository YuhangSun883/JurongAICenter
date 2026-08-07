// 媒体资产类型定义（前后端共用契约）

export interface MediaLibrary {
  id: number;
  name: string;
  /** system-uploaded / system-ai / custom */
  type: string;
  iconKey: string;
  description?: string;
  sortOrder: number;
  assetCount: number;
  createdAt: number;
  updatedAt: number;
}

export interface MediaAsset {
  id: number;
  libraryId: number | null;
  /** image / video / audio */
  type: string;
  /** uploaded / ai-generated */
  source: string;
  name: string;
  mimeType?: string;
  sizeBytes?: number;
  width?: number;
  height?: number;
  durationSec?: number;
  /** 公网 URL（前端 <img src=...>） */
  url: string;
  sourceTool?: string;
  sourceTaskId?: string;
  createdAt: number;
}

export interface MediaAssetListResponse {
  items: MediaAsset[];
  total: number;
  page: number;
  pageSize: number;
}

export interface UploadMediaResponse {
  id: number;
  libraryId: number | null;
  type: string;
  source: string;
  name: string;
  mimeType?: string;
  sizeBytes?: number;
  url: string;
}

export interface MediaRoleCategory {
  key: string;
  label: string;
}

export interface MediaRole {
  id: number;
  name: string;
  category: string;
  imageUrl: string;
  description?: string;
  tags?: string[];
  createdAt: number;
}