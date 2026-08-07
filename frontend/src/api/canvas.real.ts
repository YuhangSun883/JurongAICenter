import { request } from '@/lib/http';
import type {
  CanvasNode,
  CreateCanvasNodeRequest,
  GenerateCanvasNodeRequest,
  GenerateCanvasNodeResponse,
  UpdateCanvasNodeRequest,
} from './canvas';

const API = '/api/canvas';

export function createNode(req: CreateCanvasNodeRequest): Promise<CanvasNode> {
  return request<CanvasNode>(`${API}/nodes`, { method: 'POST', body: req });
}

export function updateNode(req: UpdateCanvasNodeRequest): Promise<CanvasNode> {
  return request<CanvasNode>(`${API}/nodes/${req.nodeId}`, {
    method: 'PATCH',
    body: req,
  });
}

export function generateNode(req: GenerateCanvasNodeRequest): Promise<GenerateCanvasNodeResponse> {
  return request<GenerateCanvasNodeResponse>(`${API}/nodes/${req.nodeId}/generate`, {
    method: 'POST',
    body: req,
  });
}

/** 轮询任务状态 */
export function getTask(taskId: string): Promise<GenerateCanvasNodeResponse> {
  return request<GenerateCanvasNodeResponse>(`${API}/tasks/${taskId}`);
}
