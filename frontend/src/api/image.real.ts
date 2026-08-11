// 图片生成 API —— 真实后端调用
import { request } from '@/lib/http';

const API = '/api/images';

/** 图片生成请求参数 */
export interface ImageGenerateParams {
  prompt: string;
  size?: string;       // 图片尺寸，默认 1024x1024
  quality?: string;    // 图片质量，默认 standard
  style?: string;      // 图片风格，默认 vivid
  referenceImages?: string[];  // 引用图片列表（base64 data URI 格式），作为素材结合提示词生成
}

/** 图片生成响应 */
export interface ImageGenerateResult {
  imageUrl: string;    // 图片地址（base64 data URI 或 MinIO URL）
  model: string;       // 使用的模型名称
  originalUrl: string; // NewAPI 原始返回的 URL
}

/** 收藏图片响应 */
export interface FavoriteImageResult {
  objectKey: string;   // MinIO objectKey（用于取消收藏时传给后端）
  url: string;         // MinIO 可访问 URL
  createdAt: number;   // 收藏时间戳
}

/**
 * 调用后端 AI 图片生成接口
 * 使用 gpt-image-2-2k 模型生成图片
 * 超时时间 5 分钟
 */
export async function generateImage(
  params: ImageGenerateParams,
  signal?: AbortSignal
): Promise<ImageGenerateResult> {
  return request<ImageGenerateResult>(`${API}/generate`, {
    method: 'POST',
    body: params,
    signal,
  });
}

/**
 * 收藏图片（修改 source_tool 字段，不复制图片）
 * @param objectKey 已生成图片在 MinIO 中的 objectKey
 */
export async function favoriteImage(
  objectKey: string
): Promise<FavoriteImageResult> {
  return request<FavoriteImageResult>(`${API}/favorite`, {
    method: 'POST',
    body: { objectKey },
  });
}

/**
 * 获取用户收藏图片列表
 */
export async function getFavorites(): Promise<FavoriteImageResult[]> {
  return request<FavoriteImageResult[]>(`${API}/favorites`, {
    method: 'GET',
  });
}

/**
 * 取消收藏（从 MinIO 删除）
 * @param objectKey 图片在 MinIO 中的 objectKey
 */
export async function unfavoriteImage(
  objectKey: string
): Promise<{ success: boolean }> {
  return request<{ success: boolean }>(`${API}/favorite?objectKey=${encodeURIComponent(objectKey)}`, {
    method: 'DELETE',
  });
}
