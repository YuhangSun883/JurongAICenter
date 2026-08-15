import type {
  CreateProductImageRequest,
  FormatOption,
  ProductImageAnalysisTask,
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

/** 拉取套图类型名称（分析卡片类型下拉选项） */
export async function listRoles(): Promise<string[]> {
  return request<string[]>('/api/product-image/roles');
}

/** 提交商品详解分析 → 返回分析任务（LLM 多模态生成，文案语言 = 用户语种） */
export async function createAnalysis(req: CreateProductImageRequest): Promise<ProductImageAnalysisTask> {
  return request<ProductImageAnalysisTask>('/api/product-image/analysis', { method: 'POST', body: req });
}

/** 轮询分析任务状态 */
export async function getAnalysis(taskId: string): Promise<ProductImageAnalysisTask> {
  return request<ProductImageAnalysisTask>(`/api/product-image/analysis/${taskId}`);
}