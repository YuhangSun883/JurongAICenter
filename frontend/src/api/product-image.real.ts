import type {
  CreateProductImageRequest,
  FormatOption,
  ProductImageAnalysisItem,
  ProductImageAnalysisTask,
  ProductImageExample,
  ProductImageModel,
  ProductImageTask,
  RefineAnalysisItemRequest,
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

/** 查询当前用户的套图任务列表（后端内存任务态，新任务在前）：页面重新进入时恢复任务队列与生成结果 */
export async function listTasks(): Promise<ProductImageTask[]> {
  return request<ProductImageTask[]>('/api/product-image/tasks');
}

/** 批量删除任务（与资产批量删除 API 同语义：直接删除数据库数据与 MinIO 文件，不可恢复） */
export async function batchDeleteTasks(ids: string[]): Promise<{ deleted: number; requested: number }> {
  return request<{ deleted: number; requested: number }>('/api/product-image/tasks/batch-delete', {
    method: 'POST',
    body: { ids },
  });
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

/** 单条分析文案重写（同步）：按所选定位与画布比例，多模态 LLM 重写该条设计分析 */
export async function refineAnalysisItem(req: RefineAnalysisItemRequest): Promise<ProductImageAnalysisItem> {
  return request<ProductImageAnalysisItem>('/api/product-image/analysis/refine', { method: 'POST', body: req });
}