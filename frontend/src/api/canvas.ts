// 画布节点接口入口：当前前端走 mock，后端接入后切换 NEXT_PUBLIC_USE_MOCK=false。
import { USE_MOCK } from './config';
import * as mock from './canvas.mock';
import * as real from './canvas.real';

export type CanvasNodeType = 'text' | 'image' | 'video' | 'audio';

export interface CanvasNode {
  id: string;
  canvasId?: string; // 所属画布（reloade 时拿这个调用 getCanvasDetail）
  type: CanvasNodeType;
  title: string;
  content?: string;
  assetId?: string;
  resultUrl?: string;
  /** 画布坐标 X（后端 CanvasNodeResponse.positionX） */
  positionX?: number;
  /** 画布坐标 Y（后端 CanvasNodeResponse.positionY） */
  positionY?: number;
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
  /**
   * 本次任务新建的 CanvasNode ID 列表（仅 success 时有值）。
   * 前端用这个列表只拉本次新建的节点 merge 到本地 state，避免 reload 整张画布
   * 导致之前所有节点堆到左上角。
   */
  createdNodeIds?: string[];
}

/** 创建画布请求(后端 CreateCanvasRequest: 必填 name) */
export interface CreateCanvasRequest {
  name: string;
}

/** 创建画布节点请求 */
export interface CreateCanvasNodeRequest {
  type: CanvasNodeType;
  title?: string;
  content?: string;
  assetId?: string;
  /** 上游节点 ID 列表(用于跨节点传递润色/生成结果) */
  upstreamIds?: string[];
}

/** 本地上传到画布的可选参数 */
export interface UploadToCanvasOptions {
  canvasId?: string;
  title?: string;
  positionX?: number;
  positionY?: number;
}

/** 我的画布列表项（"我的创作"列表用） */
export interface CanvasListItem {
  id: string;
  name: string;
  /** 节点总数 */
  nodeCount?: number;
  /** 缩略图 URL */
  thumbnail?: string;
  createdAt: number;
  updatedAt: number;
}

/** 画布详情：画布 + 节点 + 连线（一次性拉全量，刷新/初始化用） */
export interface CanvasDetail {
  id: string;
  name: string;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  createdAt: number;
  updatedAt: number;
}

/** 连线边 */
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

  /** 轮询任务状态（前端轮询用） */
  getTask: (taskId: string): Promise<GenerateCanvasNodeResponse> =>
    USE_MOCK ? mock.getTask(taskId) : real.getTask(taskId),

  /**
   * 本地上传文件到画布：自动建对应类型的画布节点。
   * 后端按 mime/扩展名判断节点类型（image/video/audio）。
   */
  uploadToCanvas: (file: File, opts?: UploadToCanvasOptions): Promise<CanvasNode> =>
    USE_MOCK
      ? mock.uploadToCanvas(file, opts)
      : real.uploadToCanvas(file, opts),

  /** 拿我的画布列表（"我的创作"用） */
  /** 创建一个新画布(返回 CanvasListItem 包含新画布 id) */
  createCanvas: (req: CreateCanvasRequest): Promise<CanvasListItem> =>
    USE_MOCK ? mock.createCanvas(req) : real.createCanvas(req),

  listCanvases: (page?: number, pageSize?: number): Promise<CanvasListItem[]> =>
    USE_MOCK
      ? mock.listCanvases(page, pageSize)
      : real.listCanvases(page, pageSize),

  /** 拿画布完整快照：画布 + 所有节点 + 所有连线（拉一次够用） */
  getCanvasDetail: (canvasId: string): Promise<CanvasDetail> =>
    USE_MOCK ? mock.getCanvasDetail(canvasId) : real.getCanvasDetail(canvasId),

  /** 拿单个节点（刷新指定节点用，比如抽帧后刷新 video 节点 content） */
  getNode: (nodeId: string): Promise<CanvasNode> =>
    USE_MOCK ? mock.getNode(nodeId) : real.getNode(nodeId),
};