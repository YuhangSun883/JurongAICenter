import { USE_MOCK } from './config';
import * as mock from './canvas.mock';
import * as real from './canvas.real';

export type CanvasNodeType = 'text' | 'image' | 'video' | 'audio';

export interface CanvasNode {
  id: string;
  canvasId?: string;
  type: CanvasNodeType;
  title: string;
  content?: string;
  assetId?: string;
  resultUrl?: string;
  positionX?: number;
  positionY?: number;
  /** 节点设置 JSON(如视频节点的 duration/resolution) */
  settings?: string;
  createdAt: number;
  updatedAt: number;
}

export interface CreateCanvasNodeRequest {
  canvasId?: string;
  type: CanvasNodeType;
  title?: string;
  content?: string;
  assetId?: string;
  upstreamIds?: string[];
  /** 2026-08-10:创建节点时同步传坐标(后端没收到就默认 0,0 → 刷新堆左上角) */
  positionX?: number;
  positionY?: number;
}

export interface NodeConnection {
  port: string;
  nodeId: string;
}

export interface UpdateCanvasNodeRequest {
  nodeId: string;
  title?: string;
  content?: string;
  assetId?: string;
  /** 2026-08-10:拖动结束同步位置到后端 */
  positionX?: number;
  positionY?: number;
  /** 上游连接列表(多端口格式) */
  upstreamIds?: NodeConnection[];
  /** 下游连接列表(多端口格式) */
  downstreamIds?: NodeConnection[];
  /** 节点设置 JSON(视频节点的 duration/resolution 等) */
  settings?: string;
}

export interface GenerateCanvasNodeRequest {
  nodeId: string;
  type: CanvasNodeType;
  prompt: string;
  content?: string;
  assetIds?: string[];
  /** 2026-08-09:提示框中上传的素材节点 id 列表(换装场景) */
  materialNodeIds?: string[];
  settings?: Record<string, unknown>;
}

export interface GenerateCanvasNodeResponse {
  taskId: string;
  nodeId: string;
  status: 'pending' | 'running' | 'success' | 'failed';
  text?: string;
  resultUrl?: string;
  creditsEstimated: number;
  createdNodeIds?: string[];
  /** 2026-08-10 新增:任务失败时的具体原因(由后端从 CanvasTask.errorMessage 透传) */
  failMessage?: string;
}

export interface GenerateVideoRequest {
  nodeId: string;
  duration?: number;
  resolution?: string;
}

export interface CreateCanvasRequest {
  name: string;
}

export interface UpdateCanvasRequest {
  name: string;
}

export interface UploadToCanvasOptions {
  canvasId?: string;
  title?: string;
  positionX?: number;
  positionY?: number;
}

export interface CanvasListItem {
  id: string;
  name: string;
  nodeCount?: number;
  thumbnail?: string;
  createdAt: number;
  updatedAt: number;
}

export interface CanvasDetail {
  id: string;
  name: string;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  createdAt: number;
  updatedAt: number;
}

export interface CanvasEdge {
  id: string;
  from: string;
  to: string;
}

export const canvasApi = {
  createNode: (req: CreateCanvasNodeRequest): Promise<CanvasNode> =>
    USE_MOCK ? mock.createNode(req) : real.createNode(req),

  updateNode: (req: UpdateCanvasNodeRequest): Promise<CanvasNode> =>
    USE_MOCK ? mock.updateNode(req) : real.updateNode(req),

  generateNode: (req: GenerateCanvasNodeRequest): Promise<GenerateCanvasNodeResponse> =>
    USE_MOCK ? mock.generateNode(req) : real.generateNode(req),

  generateVideo: (req: GenerateVideoRequest): Promise<GenerateCanvasNodeResponse> =>
    USE_MOCK ? mock.generateVideo(req) : real.generateVideo(req),

  getTask: (taskId: string): Promise<GenerateCanvasNodeResponse> =>
    USE_MOCK ? mock.getTask(taskId) : real.getTask(taskId),

  uploadToCanvas: (file: File, opts?: UploadToCanvasOptions): Promise<CanvasNode> =>
    USE_MOCK ? mock.uploadToCanvas(file, opts) : real.uploadToCanvas(file, opts),

  createCanvas: (req: CreateCanvasRequest): Promise<CanvasListItem> =>
    USE_MOCK ? mock.createCanvas(req) : real.createCanvas(req),

  updateCanvas: (canvasId: string, req: UpdateCanvasRequest): Promise<CanvasListItem> =>
    USE_MOCK ? mock.updateCanvas(canvasId, req) : real.updateCanvas(canvasId, req),

  deleteCanvas: (canvasId: string): Promise<void> =>
    USE_MOCK ? mock.deleteCanvas(canvasId) : real.deleteCanvas(canvasId),

  listCanvases: (page?: number, pageSize?: number): Promise<CanvasListItem[]> =>
    USE_MOCK ? mock.listCanvases(page, pageSize) : real.listCanvases(page, pageSize),

  getCanvasDetail: (canvasId: string): Promise<CanvasDetail> =>
    USE_MOCK ? mock.getCanvasDetail(canvasId) : real.getCanvasDetail(canvasId),

  getNode: (nodeId: string): Promise<CanvasNode> =>
    USE_MOCK ? mock.getNode(nodeId) : real.getNode(nodeId),

  deleteNode: (nodeId: string): Promise<void> =>
    USE_MOCK ? mock.deleteNode(nodeId) : real.deleteNode(nodeId),
};
