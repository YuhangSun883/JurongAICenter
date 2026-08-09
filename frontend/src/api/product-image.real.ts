import type {
  CreateProductImageRequest,
  FormatOption,
  ProductImageExample,
  ProductImageModel,
  ProductImageTask,
  ResolutionOption,
} from '@/types/product-image';
import { request } from '@/lib/http';

/** 商品套图 —— 真实后端接口 */

/** 拉取可选模型档位 */
export async function listModels(): Promise<ProductImageModel[]> {
  return request<ProductImageModel[]>('/api/product-image/models');
}

/** 拉取分辨率选项 */
export async function listResolutions(): Promise<ResolutionOption[]> {
  return request<ResolutionOption[]>('/api/product-image/resolutions');
}

/** 拉取输出格式选项 */
export async function listFormats(): Promise<FormatOption[]> {
  return request<FormatOption[]>('/api/product-image/formats');
}

/** 拉取参考示例（中间轮播） */
export async function listExamples(): Promise<ProductImageExample[]> {
  return request<ProductImageExample[]>('/api/product-image/examples');
}

/** 提交分析 → 返回任务 */
export async function createProductImageTask(req: CreateProductImageRequest): Promise<ProductImageTask> {
  return request<ProductImageTask>('/api/product-image/tasks', { method: 'POST', body: req });
}

/** 轮询任务状态 */
export async function getProductImageTask(taskId: string): Promise<ProductImageTask> {
  return request<ProductImageTask>(`/api/product-image/tasks/${taskId}`);
}