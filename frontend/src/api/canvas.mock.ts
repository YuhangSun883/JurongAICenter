import type {
  CanvasNode,
  CreateCanvasNodeRequest,
  GenerateCanvasNodeRequest,
  GenerateCanvasNodeResponse,
  UpdateCanvasNodeRequest,
} from './canvas';

const nodes = new Map<string, CanvasNode>();

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

function defaultTitle(type: CreateCanvasNodeRequest['type']) {
  const titles = {
    text: '文本节点',
    image: '图片节点',
    video: '视频节点',
    audio: '音频节点',
  } satisfies Record<CreateCanvasNodeRequest['type'], string>;
  return titles[type];
}
