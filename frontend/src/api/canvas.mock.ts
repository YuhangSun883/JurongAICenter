import type {
  CanvasDetail,
  CanvasEdge,
  CanvasListItem,
  CanvasNode,
  CanvasNodeType,
  CreateCanvasNodeRequest,
  CreateCanvasRequest,
  GenerateCanvasNodeRequest,
  GenerateCanvasNodeResponse,
  GenerateVideoRequest,
  UpdateCanvasRequest,
  UpdateCanvasNodeRequest,
  UploadToCanvasOptions,
} from './canvas';

const nodes = new Map<string, CanvasNode>();
const mockCanvases = new Map<string, CanvasListItem>();
const mockEdges: CanvasEdge[] = [];
const DEFAULT_CANVAS_ID = 'mock_canvas_default';

function ensureDefaultCanvas(): CanvasListItem {
  const existing = mockCanvases.get(DEFAULT_CANVAS_ID);
  if (existing) return existing;

  const now = Date.now();
  const created: CanvasListItem = {
    id: DEFAULT_CANVAS_ID,
    name: '\u9ed8\u8ba4\u753b\u5e03',
    nodeCount: 0,
    createdAt: now,
    updatedAt: now,
  };
  mockCanvases.set(DEFAULT_CANVAS_ID, created);
  return created;
}

function refreshCanvasStats(canvasId: string) {
  const canvas = mockCanvases.get(canvasId);
  if (!canvas) return;
  const canvasNodes = Array.from(nodes.values()).filter((node) => nodeCanvasId(node) === canvasId);
  mockCanvases.set(canvasId, {
    ...canvas,
    nodeCount: canvasNodes.length,
    updatedAt: Date.now(),
    thumbnail: canvasNodes.find((node) => node.resultUrl)?.resultUrl ?? canvas.thumbnail,
  });
}

function nodeCanvasId(node: CanvasNode): string {
  return node.canvasId ?? DEFAULT_CANVAS_ID;
}

export async function getNode(nodeId: string): Promise<CanvasNode> {
  const node = nodes.get(nodeId);
  if (node) return node;
  throw new Error(`mock canvas node not found: ${nodeId}`);
}

export async function getCanvasDetail(canvasId: string): Promise<CanvasDetail> {
  const canvas = mockCanvases.get(canvasId) ?? ensureDefaultCanvas();
  const canvasNodes = Array.from(nodes.values()).filter((node) => nodeCanvasId(node) === canvas.id);
  const canvasNodeIds = new Set(canvasNodes.map((node) => node.id));

  return {
    id: canvas.id,
    name: canvas.name,
    nodes: canvasNodes,
    edges: mockEdges.filter((edge) => canvasNodeIds.has(edge.from) && canvasNodeIds.has(edge.to)),
    createdAt: canvas.createdAt,
    updatedAt: canvas.updatedAt,
  };
}

export async function uploadToCanvas(file: File, opts: UploadToCanvasOptions = {}): Promise<CanvasNode> {
  const type = inferType(file);
  const now = Date.now();
  const canvasId = opts.canvasId ?? ensureDefaultCanvas().id;
  const node: CanvasNode = {
    id: `canvas_node_${now}_${Math.random().toString(36).slice(2, 7)}`,
    canvasId,
    type,
    title: opts.title ?? file.name ?? defaultTitle(type),
    positionX: opts.positionX ?? 0,
    positionY: opts.positionY ?? 0,
    resultUrl: type === 'image' ? `https://picsum.photos/seed/upload-${now}/600/400` : undefined,
    createdAt: now,
    updatedAt: now,
  };
  nodes.set(node.id, node);
  refreshCanvasStats(canvasId);
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
  return 'image';
}

function defaultTitle(type: CreateCanvasNodeRequest['type']) {
  const titles = {
    text: '\u6587\u672c\u8282\u70b9',
    image: '\u56fe\u7247\u8282\u70b9',
    video: '\u89c6\u9891\u8282\u70b9',
    audio: '\u97f3\u9891\u8282\u70b9',
  } satisfies Record<CreateCanvasNodeRequest['type'], string>;
  return titles[type];
}

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

export async function updateCanvas(canvasId: string, req: UpdateCanvasRequest): Promise<CanvasListItem> {
  const current = mockCanvases.get(canvasId);
  if (!current) throw new Error('mock canvas not found');
  const updated = { ...current, name: req.name, updatedAt: Date.now() };
  mockCanvases.set(canvasId, updated);
  return updated;
}

export async function deleteCanvas(canvasId: string): Promise<void> {
  mockCanvases.delete(canvasId);
  for (const [nodeId, node] of nodes.entries()) {
    if (nodeCanvasId(node) === canvasId) nodes.delete(nodeId);
  }
  for (let index = mockEdges.length - 1; index >= 0; index -= 1) {
    const edge = mockEdges[index];
    if (!nodes.has(edge.from) || !nodes.has(edge.to)) mockEdges.splice(index, 1);
  }
}

/**
 * 删除单个节点 + 清掉所有引用该节点的 edge。
 * 用于前端"删除节点"按钮,保证 mock 模式下行为跟后端对齐。
 */
export async function deleteNode(nodeId: string): Promise<void> {
  nodes.delete(nodeId);
  for (let index = mockEdges.length - 1; index >= 0; index -= 1) {
    const edge = mockEdges[index];
    if (edge.from === nodeId || edge.to === nodeId) mockEdges.splice(index, 1);
  }
}

export async function listCanvases(page = 1, pageSize = 50): Promise<CanvasListItem[]> {
  ensureDefaultCanvas();
  return Array.from(mockCanvases.values())
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice((page - 1) * pageSize, page * pageSize);
}

export async function createNode(req: CreateCanvasNodeRequest): Promise<CanvasNode> {
  const now = Date.now();
  const canvasId = req.canvasId ?? ensureDefaultCanvas().id;
  const node: CanvasNode = {
    id: `canvas_node_${now}_${Math.random().toString(36).slice(2, 7)}`,
    canvasId,
    type: req.type,
    title: req.title ?? defaultTitle(req.type),
    positionX: 0,
    positionY: 0,
    content: req.content,
    assetId: req.assetId,
    createdAt: now,
    updatedAt: now,
  };
  nodes.set(node.id, node);

  for (const upstreamId of req.upstreamIds ?? []) {
    const edgeId = `edge_${upstreamId}_${node.id}`;
    if (!mockEdges.some((edge) => edge.id === edgeId)) {
      mockEdges.push({ id: edgeId, from: upstreamId, to: node.id });
    }
  }

  refreshCanvasStats(canvasId);
  return node;
}

export async function updateNode(req: UpdateCanvasNodeRequest): Promise<CanvasNode> {
  const current = nodes.get(req.nodeId);
  const now = Date.now();
  const next: CanvasNode = {
    id: req.nodeId,
    canvasId: current?.canvasId ?? DEFAULT_CANVAS_ID,
    type: current?.type ?? 'text',
    title: req.title ?? current?.title ?? '\u672a\u547d\u540d\u8282\u70b9',
    content: req.content ?? current?.content,
    assetId: req.assetId ?? current?.assetId,
    positionX: current?.positionX ?? 0,
    positionY: current?.positionY ?? 0,
    resultUrl: current?.resultUrl,
    createdAt: current?.createdAt ?? now,
    updatedAt: now,
  };
  nodes.set(next.id, next);
  refreshCanvasStats(nodeCanvasId(next));
  return next;
}

export async function generateNode(req: GenerateCanvasNodeRequest): Promise<GenerateCanvasNodeResponse> {
  return {
    taskId: `canvas_task_${Date.now()}`,
    nodeId: req.nodeId,
    status: 'success',
    text: req.type === 'text' ? req.prompt || req.content || '\u8fd9\u662f mock \u751f\u6210\u7684\u811a\u672c\u6587\u6848\u3002' : undefined,
    resultUrl: req.type === 'image' ? 'https://example.com/mock-canvas-image.png' : undefined,
    creditsEstimated: req.type === 'image' ? 1.18 : 0.2,
  };
}

export async function generateVideo(req: GenerateVideoRequest): Promise<GenerateCanvasNodeResponse> {
  return {
    taskId: `canvas_task_video_${Date.now()}`,
    nodeId: req.nodeId,
    status: 'success',
    resultUrl: 'https://example.com/mock-canvas-video.mp4',
    creditsEstimated: 20,
  };
}

export async function getTask(taskId: string): Promise<GenerateCanvasNodeResponse> {
  return {
    taskId,
    nodeId: '',
    status: 'success',
    text: 'mock \u4efb\u52a1\u5df2\u5b8c\u6210',
    creditsEstimated: 0.2,
  };
}
