// 画布节点接口入口：当前前端走 mock，后端接入后切换 NEXT_PUBLIC_USE_MOCK=false。
import { USE_MOCK } from './config';
import * as mock from './canvas.mock';
import * as real from './canvas.real';

export type CanvasNodeType = 'text' | 'image' | 'video' | 'audio';

export interface CanvasNode {
  id: string;
  type: CanvasNodeType;
  title: string;
  content?: string;
  assetId?: string;
  resultUrl?: string;
  createdAt: number;
  updatedAt: number;
}

export interface CreateCanvasNodeRequest {
  type: CanvasNodeType;
  title?: string;
  content?: string;
  assetId?: string;
  /** 上游节点 ID 列表（用于跨节点传递润色/生成结果） */
  upstreamIds?: string[];
}

export interface UpdateCanvasNodeRequest {
  nodeId: string;
  title?: string;
  content?: string;
  assetId?: string;
}

export interface GenerateCanvasNodeRequest {
  nodeId: string;
  type: CanvasNodeType;
  prompt: string;
  content?: string;
  assetIds?: string[];
  settings?: Record<string, unknown>;
}

export interface GenerateCanvasNodeResponse {
  taskId: string;
  nodeId: string;
  status: 'pending' | 'running' | 'success' | 'failed';
  text?: string;
  resultUrl?: string;
  creditsEstimated: number;
}

export const canvasApi = {
  createNode: (req: CreateCanvasNodeRequest): Promise<CanvasNode> =>
    USE_MOCK ? mock.createNode(req) : real.createNode(req),

  updateNode: (req: UpdateCanvasNodeRequest): Promise<CanvasNode> =>
    USE_MOCK ? mock.updateNode(req) : real.updateNode(req),

  generateNode: (req: GenerateCanvasNodeRequest): Promise<GenerateCanvasNodeResponse> =>
    USE_MOCK ? mock.generateNode(req) : real.generateNode(req),

  /** 轮询任务状态（前端轮询用） */
  getTask: (taskId: string): Promise<GenerateCanvasNodeResponse> =>
    USE_MOCK ? mock.getTask(taskId) : real.getTask(taskId),
};
