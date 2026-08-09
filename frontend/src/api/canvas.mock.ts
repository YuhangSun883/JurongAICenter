import type {
  CanvasDetail,
  CanvasListItem,
  CanvasNode,
  CanvasNodeType,
  CreateCanvasNodeRequest,
  CreateCanvasRequest,
  GenerateCanvasNodeRequest,
  GenerateCanvasNodeResponse,
  UpdateCanvasNodeRequest,
  UploadToCanvasOptions,
} from './canvas';

const nodes = new Map<string, CanvasNode>();
const mockCanvases = new Map<string, CanvasListItem>();

/**
 * Mock 版的本地上传：按 mime/扩展名猜节点类型，不真上传。
 * 让前端开发不依赖后端也能跑上传流程。
 */
export async function uploadToCanvas(
  file: File,
  _opts: UploadToCanvasOptions = {},
): Promise<CanvasNode> {
  const type = inferType(file);
  const now = Date.now();
  const node: CanvasNode = {
    id: `canvas_node_${now}_${Math.random().toString(36).slice(2, 7)}`,
    type,
    title: file.name || defaultTitle(type),
    resultUrl: type === 'image' ? 'https://picsum.photos/seed/upload/' + now + '/600/400' : undefined,
    createdAt: now,
    updatedAt: now,
  };
  nodes.set(node.id, node);
  return node;
}

function inferType(file: File): CanvasNodeType {
  const mime = (file.type || '').toLowerCase();
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('video/')) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  const name = (file.name || '').toLowerCase();
  if (/\.(jpg|jpeg|png|gif|webp|bmp)$/.test(name)) return 'image';
  if (/\.(mp4|webm|mov|avi|mkv)$/.test(name)) return 'video';
  if (/\.(mp3|wav|ogg|m4a|aac)$/.test(name)) return 'audio';
  return 'image'; // fallback
}

function defaultTitle(type: CreateCanvasNodeRequest['type']) {
  const titles = {
    text: '文本节点',
    image: '图片节点',
    video: '视频节点',
    audio: '音频节点',
  } satisfies Record<CreateCanvasNodeRequest['type'], string>;
  return titles[type];
}

/** Mock:创建一个新画布 */
export async function createCanvas(req: CreateCanvasRequest): Promise<CanvasListItem> {
  const now = Date.now();
  const item: CanvasListItem = {
    id: `canvas_${now}_${Math.random().toString(36).slice(2, 7)}`,
    name: req.name,
    nodeCount: 0,
    createdAt: now,
    updatedAt: now,
  };
  mockCanvases.set(item.id, item);
  return item;
}

/** Mock:列画布 */
export async function listCanvases(page = 1, pageSize = 50): Promise<CanvasListItem[]> {
  return Array.from(mockCanvases.values()).slice((page - 1) * pageSize, page * pageSize);
}

export async function deleteCanvas(canvasId: string): Promise<{ canvasId: string; status: string }> {
  mockCanvases.delete(canvasId);
  // 顺带删该画布的所有 mock 节点
  for (const [id, n] of nodes.entries()) {
    if (n.canvasId === canvasId) nodes.delete(id);
  }
  return { canvasId, status: 'deleted' };
}

export async function createNode(req: CreateCanvasNodeRequest): Promise<CanvasNode> {
  const now = Date.now();
  const node: CanvasNode = {
    id: `canvas_node_${now}_${Math.random().toString(36).slice(2, 7)}`,
    type: req.type,
    title: req.title ?? defaultTitle(req.type),
    content: req.content,
    assetId: req.assetId,
    createdAt: now,
    updatedAt: now,
  };
  nodes.set(node.id, node);
  return node;
}

export async function updateNode(req: UpdateCanvasNodeRequest): Promise<CanvasNode> {
  const current = nodes.get(req.nodeId);
  const now = Date.now();
  const next: CanvasNode = {
    id: req.nodeId,
    type: current?.type ?? 'text',
    title: req.title ?? current?.title ?? '未命名节点',
    content: req.content ?? current?.content,
    assetId: req.assetId ?? current?.assetId,
    resultUrl: current?.resultUrl,
    createdAt: current?.createdAt ?? now,
    updatedAt: now,
  };
  nodes.set(next.id, next);
  return next;
}

export async function generateNode(req: GenerateCanvasNodeRequest): Promise<GenerateCanvasNodeResponse> {
  const text =
    req.type === 'text'
      ? req.prompt || req.content || '这里是后端模型生成的文本占位内容。'
      : undefined;

  return {
    taskId: `canvas_task_${Date.now()}`,
    nodeId: req.nodeId,
    status: 'success',
    text,
    resultUrl: req.type === 'image' ? 'https://example.com/mock-canvas-image.png' : undefined,
    creditsEstimated: req.type === 'image' ? 1.18 : 0.2,
  };
}

/** Mock 轮询：总是返回 success */
export async function getTask(_taskId: string): Promise<GenerateCanvasNodeResponse> {
  return {
    taskId: _taskId,
    nodeId: '',
    status: 'success',
    text: '这里是 mock 的轮询响应。',
    creditsEstimated: 0.2,
  };
}

/**
 * Mock 拉画布详情：返回空画布 + 空边
 * 2026-08-09 补充：canvas.ts 里的 getCanvasDetail 调用需要 mock 实现
 */
export async function getCanvasDetail(_canvasId: string): Promise<CanvasDetail> {
  const now = Date.now();
  return {
    id: _canvasId,
    name: 'mock画布',
    nodes: [],
    edges: [],
    createdAt: now,
    updatedAt: now,
  };
}

/**
 * Mock 拉单个节点：返回一个 placeholder 节点
 * 2026-08-09 补充：canvas.ts 里的 getNode 调用需要 mock 实现
 */
export async function getNode(nodeId: string): Promise<CanvasNode> {
  const now = Date.now();
  return {
    id: nodeId,
    type: 'text',
    title: 'mock 节点',
    content: '',
    createdAt: now,
    updatedAt: now,
  };
}
