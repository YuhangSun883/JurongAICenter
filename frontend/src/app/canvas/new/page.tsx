'use client';

import {
  BoxSelect,
  ChevronDown,
  CircleHelp,
  Coins,
  Clipboard,
  Copy,
  Download,
  Expand,
  FileText,
  Film,
  FolderOpen,
  Grid3X3,
  Hand,
  History,
  Image as ImageIcon,
  LayoutTemplate,
  List,
  Loader2,
  Map,
  Maximize2,
  MessageCircle,
  Minus,
  MousePointer2,
  MousePointerClick,
  PanelsTopLeft,
  Plus,
  Share2,
  Sparkles,
  Trash2,
  Upload,
  Video,
  Volume2,
  X,
} from 'lucide-react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef, useState, type ChangeEvent, type ComponentType, type DragEvent as ReactDragEvent, type MouseEvent as ReactMouseEvent, type PointerEvent } from 'react';
import { flushSync } from 'react-dom';
import { canvasApi, type CanvasNodeType, type CanvasNode } from '@/api/canvas';
import { getAccessToken } from '@/lib/auth-store';
import { cn } from '@/lib/utils';
import { LoginGate } from '@/components/common/LoginGate';

type ToolId = 'select' | 'hand' | 'component' | 'template' | 'history' | 'help';

interface CanvasViewNode {
  id: string;
  type: CanvasNodeType;
  title?: string;
  x: number;
  y: number;
  content?: string;
  resultUrl?: string;
  /** 每个节点独立的 prompt 输入（不跨节点共享） */
  prompt?: string;
  /** 节点设置 JSON(视频节点的 duration/resolution/model 等) */
  settings?: string;
}

/** API 返回的 CanvasNode → 本地视图节点（加 x/y，补默认值） */
function toViewNode(n: CanvasNode): CanvasViewNode {
  return {
    id: n.id,
    type: n.type,
    title: n.title,
    content: n.content,
    resultUrl: n.resultUrl,
    x: n.positionX ?? 0,
    y: n.positionY ?? 0,
    prompt: '',
    settings: n.settings,
  };
}

interface CanvasEdge {
  id: string;
  from: string;
  to: string;
}

function normalizeCanvasNodes(rawNodes: Array<{
  id: string;
  type: CanvasNodeType;
  x?: number;
  y?: number;
  positionX?: number;
  positionY?: number;
  content?: string;
  resultUrl?: string;
  prompt?: string;
}> | null | undefined): CanvasViewNode[] {
  return (rawNodes ?? []).map((node) => ({
    id: node.id,
    type: node.type,
    x: node.x ?? node.positionX ?? 0,
    y: node.y ?? node.positionY ?? 0,
    content: node.content,
    resultUrl: node.resultUrl,
    prompt: node.prompt ?? '',
  }));
}

function normalizeCanvasEdges(rawEdges: Array<{
  id?: string;
  from?: string;
  to?: string;
  fromNode?: string;
  toNode?: string;
}> | null | undefined): CanvasEdge[] {
  const seen = new Set<string>();
  const result: CanvasEdge[] = [];
  for (const edge of rawEdges ?? []) {
    const from = edge.from ?? edge.fromNode;
    const to = edge.to ?? edge.toNode;
    if (!from || !to) continue;
    const id = edge.id ?? `edge_${from}_${to}`;
    if (seen.has(id)) continue;
    seen.add(id);
    result.push({ id, from, to });
  }
  return result;
}

function isUserCanvas(item: import('@/api/canvas').CanvasListItem) {
  return item.id !== 'mock_canvas_default' && item.name !== '\u9ed8\u8ba4\u753b\u5e03' && item.name !== '\u699b\u6a3f\ue17b\u9422\u8bf2\u7af7';
}

// =====================================================================
// 2026-08-10 提示框素材(localStorage 持久化 + 过滤素材节点)
// 背景:
//   - PromptComposer 的「上传素材」按钮会把图片上传到后端建一个 canvas 节点;
//   - 刷新页面时,getCanvasDetail 会把这个节点和其他节点一起返回,衣服图就
//     出现在画布上了;
//   - 用户期望:衣服图节点只在输入框里展示,不显示在画布上。
// 方案:
//   - 上传衣服图后,把这个节点 id 记到 localStorage `materialNodeIds_${canvasId}`
//   - 加载画布时,过滤掉这些 id
//   - promptMaterials 也持久化到 localStorage,刷新后输入框仍能展示
// =====================================================================
const LS_MATERIAL_NODES_KEY = (canvasId: string) => `materialNodeIds_${canvasId}`;
const LS_PROMPT_MATERIALS_KEY = (canvasId: string) => `promptMaterials_${canvasId}`;

function loadMaterialNodeIds(canvasId: string): string[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = localStorage.getItem(LS_MATERIAL_NODES_KEY(canvasId));
    return raw ? (JSON.parse(raw) as string[]) : [];
  } catch { return []; }
}
function saveMaterialNodeIds(canvasId: string, ids: string[]): void {
  if (typeof window === 'undefined') return;
  try { localStorage.setItem(LS_MATERIAL_NODES_KEY(canvasId), JSON.stringify(ids)); } catch {}
}
function loadPromptMaterials(
  canvasId: string,
): Record<string, Array<{ id: string; url: string; name?: string }>> {
  if (typeof window === 'undefined') return {};
  try {
    const raw = localStorage.getItem(LS_PROMPT_MATERIALS_KEY(canvasId));
    return raw ? (JSON.parse(raw) as Record<string, Array<{ id: string; url: string; name?: string }>>) : {};
  } catch { return {}; }
}
function savePromptMaterials(
  canvasId: string,
  dict: Record<string, Array<{ id: string; url: string; name?: string }>>,
): void {
  if (typeof window === 'undefined') return;
  try { localStorage.setItem(LS_PROMPT_MATERIALS_KEY(canvasId), JSON.stringify(dict)); } catch {}
}

interface AddMenuState {
  x: number;
  y: number;
  sourceId?: string;
  title: string;
}

interface LinkDragState {
  sourceId: string;
  side: 'left' | 'right';
  startX: number;
  startY: number;
  x: number;
  y: number;
}

interface NodeContextMenuState {
  x: number;
  y: number;
  nodeId: string;
}

interface EdgeContextMenuState {
  x: number;
  y: number;
  edgeId: string;
}

type NodeContextAction = 'save' | 'copy' | 'duplicate' | 'paste' | 'delete';

const NODE_WIDTH = 320;
const NODE_HEIGHT = 220;
const PROMPT_WIDTH = 660;
const PROMPT_HEIGHT = 220;
const PROMPT_GAP = 22;
const PROMPT_BOTTOM_MARGIN = 38;

const TOOLS: Array<{
  id: ToolId;
  label: string;
  icon: typeof MousePointer2;
  beta?: boolean;
}> = [
  { id: 'select', label: '选择', icon: MousePointer2 },
  { id: 'hand', label: '移动画布', icon: Hand },
  { id: 'component', label: '组件', icon: BoxSelect, beta: true },
  { id: 'template', label: '模板', icon: LayoutTemplate },
  { id: 'history', label: '历史', icon: History },
  { id: 'help', label: '帮助', icon: CircleHelp },
];

export default function NewCanvasPage() {
  const [activeTool, setActiveTool] = useState<ToolId>('select');
  // 当前画布 ID:首次访问 /canvas/new 时为 null,用户点"+新建"或从历史打开画布后会被设置
  // 之后 createNode / upload 等操作会带上这个 canvasId
  const [canvasId, setCanvasId] = useState<string | null>(null);
  const [canvasName, setCanvasName] = useState<string>('未命名画布');
  const [canvasLoadState, setCanvasLoadState] = useState<'idle' | 'loading' | 'loaded' | 'failed'>('idle');
  const [showCanvasMenu, setShowCanvasMenu] = useState(false);
  const [isEditingName, setIsEditingName] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [isCreatingCanvas, setIsCreatingCanvas] = useState(false);
  const canvasMenuRef = useRef<HTMLDivElement | null>(null);
  const nameInputRef = useRef<HTMLInputElement | null>(null);
  const searchParams = useSearchParams();
  const router = useRouter();

  // 点击画布菜单外部关闭菜单
  useEffect(() => {
    if (!showCanvasMenu) return;
    const handler = (e: globalThis.MouseEvent) => {
      if (canvasMenuRef.current && !canvasMenuRef.current.contains(e.target as Node)) {
        setShowCanvasMenu(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showCanvasMenu]);

  // 从 URL ?canvasId=xxx 读画布 ID,有就调 getCanvasDetail 加载
  // 触发时机:进入页面 / URL 变化
  useEffect(() => {
    const urlCanvasId = searchParams.get('canvasId');
    if (!urlCanvasId) return;
    // 已经在加载这个画布了就不重复
    if (canvasId === urlCanvasId) return;
    setCanvasId(urlCanvasId);
    setCanvasLoadState('loading');
    void canvasApi.getCanvasDetail(urlCanvasId)
      .then((detail) => {
        // 2026-08-10:从 localStorage 读素材节点 id 列表 + promptMaterials,刷新后保留
        const matIds = loadMaterialNodeIds(urlCanvasId);
        setMaterialNodeIds(matIds);
        const savedMaterials = loadPromptMaterials(urlCanvasId);
        setPromptMaterials(savedMaterials);
        setNodes(normalizeCanvasNodes(detail?.nodes).filter((n) => !matIds.includes(n.id)));
        setEdges(normalizeCanvasEdges(detail?.edges));
        // 更新画布名称
        if (detail?.name) setCanvasName(detail.name);
        setCanvasLoadState('loaded');
      })
      .catch((err) => {
        console.warn('[canvas-new] load from URL failed:', err);
        setCanvasLoadState('failed');
      });
  }, [searchParams, canvasId]);
  const [addMenu, setAddMenu] = useState<AddMenuState | null>(null);
  const [nodes, setNodes] = useState<CanvasViewNode[]>([]);
  const [edges, setEdges] = useState<CanvasEdge[]>([]);
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null);
  // 去掉全局 prompt：每个节点的 prompt 独立存储在 node.prompt 里
  // const [prompt, setPrompt] = useState('');
  const [zoom, setZoom] = useState(100);
  const [dragging, setDragging] = useState<{ id: string; offsetX: number; offsetY: number } | null>(null);
  // 阈值拖动:pointerdown 时先记录起始位置,移动超过阈值才真正开始拖动
  // 这样单击/双击不会被 drag 吃掉了
  const [dragPending, setDragPending] = useState<{
    id: string;
    startX: number;
    startY: number;
    nodeX: number;
    nodeY: number;
  } | null>(null);
  const [linkDrag, setLinkDrag] = useState<LinkDragState | null>(null);
  const [nodeMenu, setNodeMenu] = useState<NodeContextMenuState | null>(null);
  const [edgeMenu, setEdgeMenu] = useState<EdgeContextMenuState | null>(null);
  const [copiedNode, setCopiedNode] = useState<CanvasViewNode | null>(null);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string>('');
  // 2026-08-10 优化:生成进度条相关状态
  // generationStage 描述当前阶段(提交中 / 抽帧中 / 图片生成中 / 视频生成中 / 脚本拆解中 / 完成 / 失败)
  // generationStart 记录开始时间,用于计算进度百分比
  // generationKind 用于区分任务类型(图生图 / 图生视频 / 抽帧 / 脚本拆解)
  const [generationStage, setGenerationStage] = useState<string>('');
  const [generationStart, setGenerationStart] = useState<number>(0);
  const [generationKind, setGenerationKind] = useState<'image' | 'video' | 'extract' | ''>('');
  const [generationProgress, setGenerationProgress] = useState<number>(0);
  // 强制 500ms 刷新一次用于进度条 UI(计算进度百分比)
  const [, forceTick] = useState({});
  useEffect(() => {
    if (!generating || !generationStart) return;
    const id = setInterval(() => {
      forceTick({});
      // 根据任务类型和已用时间估算进度
      const elapsed = (Date.now() - generationStart) / 1000;
      const estimatedTotal =
        generationKind === 'video' ? 600 :    // 视频预计 10 分钟
        generationKind === 'extract' ? 60 :   // 抽帧预计 1 分钟
        45;                                   // 图片预计 45 秒
      // 进度非线性:前期快后期慢,最高 95%,完成时设为 100%
      let progress = Math.min(95, Math.round((elapsed / estimatedTotal) * 100));
      if (generationStage.startsWith('完成')) progress = 100;
      if (generationStage.startsWith('失败') || generationStage.startsWith('异常')) progress = 0;
      setGenerationProgress(progress);
    }, 500);
    return () => clearInterval(id);
  }, [generating, generationStart, generationKind, generationStage]);
  const [isDragOver, setIsDragOver] = useState(false);
  const [uploading, setUploading] = useState(false);
  // ===== 提示词素材(每节点一份,点 PromptComposer 的上传素材按钮填充) =====
  const [promptMaterials, setPromptMaterials] = useState<
    Record<string, Array<{ id: string; url: string; name?: string }>>
  >({});

  // 2026-08-10:画布里所有"上传的素材"节点 id(用于刷新后过滤画布上的衣服图节点)
  const [materialNodeIds, setMaterialNodeIds] = useState<string[]>([]);

  // ===== 我的创作 · 历史侧边面板 =====
  const [showHistoryPanel, setShowHistoryPanel] = useState(false);
  // ===== 展开图片 modal（双击图片节点触发放大查看） =====
  const [expandedImage, setExpandedImage] = useState<{ url: string; title: string } | null>(null);
  const [canvasHistory, setCanvasHistory] = useState<
    import('@/api/canvas').CanvasListItem[]
  >([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  const fetchCanvasHistory = async () => {
    setHistoryLoading(true);
    try {
      const list = await canvasApi.listCanvases(1, 50);
      setCanvasHistory(list.filter(isUserCanvas));
    } catch (err) {
      console.warn('[canvas] listCanvases failed:', err);
      setCanvasHistory([]);
    } finally {
      setHistoryLoading(false);
    }
  };

  const openCanvasFromHistory = async (canvasId: string) => {
    setShowHistoryPanel(false);
    try {
      const detail = await canvasApi.getCanvasDetail(canvasId);
      // 切换到该画布:设 canvasId + 用 detail.nodes/edges 覆盖本地 state
      setCanvasId(canvasId);
      if (detail?.name) setCanvasName(detail.name);
      const matIds = loadMaterialNodeIds(canvasId);
      setMaterialNodeIds(matIds);
      setPromptMaterials(loadPromptMaterials(canvasId));
      setNodes(normalizeCanvasNodes(detail?.nodes).filter((n) => !matIds.includes(n.id)));
      setEdges(normalizeCanvasEdges(detail?.edges));
    } catch (err) {
      console.warn('[canvas] getCanvasDetail failed:', err);
    }
  }

  /** 隐藏的 <input type="file"> ref，供菜单/画布触发本地选择 */
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  // 2026-08-10 fix:用于在卸载 / 画布切换时 abort 所有未完成的轮询 fetch + 清理 setTimeout,避免内存泄漏 + zombie 请求
  const pollAbortRef = useRef<AbortController | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollGuardRef = useRef<boolean>(false); // 简易 debounce:同一时间只允许一个轮询链

  // 卸载 / canvasId 变化时清理
  useEffect(() => {
    return () => {
      if (pollTimerRef.current) {
        clearTimeout(pollTimerRef.current);
        pollTimerRef.current = null;
      }
      if (pollAbortRef.current) {
        pollAbortRef.current.abort();
        pollAbortRef.current = null;
      }
    };
  }, [canvasId]);

  // 加载本地缓存的画布节点(刷新不丢，弥补后端没有 getCanvasDetail 入口时的本地持久化)
  useEffect(() => {
    if (typeof window === 'undefined' || !canvasId || canvasLoadState !== 'failed') return;
    try {
      const raw = localStorage.getItem(`canvas_nodes_${canvasId}`);
      if (raw) setNodes(JSON.parse(raw) as CanvasViewNode[]);
      const rawEdges = localStorage.getItem(`canvas_edges_${canvasId}`);
      if (rawEdges) setEdges(JSON.parse(rawEdges) as CanvasEdge[]);
      localStorage.removeItem('canvas_nodes');
      localStorage.removeItem('canvas_edges');
    } catch { /* ignore */ }
  }, [canvasId, canvasLoadState]);

  useEffect(() => {
    if (typeof window === 'undefined' || !canvasId || canvasLoadState === 'loading') return;
    const t = setTimeout(() => {
      localStorage.setItem(`canvas_nodes_${canvasId}`, JSON.stringify(nodes));
    }, 200);
    return () => clearTimeout(t);
  }, [nodes, canvasId, canvasLoadState]);

  useEffect(() => {
    if (typeof window === 'undefined' || !canvasId || canvasLoadState === 'loading') return;
    localStorage.setItem(`canvas_edges_${canvasId}`, JSON.stringify(edges));
  }, [edges, canvasId, canvasLoadState]);

  // 2026-08-10:持久化 promptMaterials 到 localStorage,刷新后输入框仍能展示衣服图
  useEffect(() => {
    if (typeof window === 'undefined' || !canvasId || canvasLoadState === 'loading') return;
    savePromptMaterials(canvasId, promptMaterials);
  }, [promptMaterials, canvasId, canvasLoadState]);

  /**
   * 抽帧 / 脚本拆解成功后：从后端拉一份最新画布快照，覆盖本地 nodes / edges。
   * 后端接口是 GET /api/canvas/canvases/{id}，canvasApi.getCanvasDetail 是封装。
   * 如果当前没有 canvasId（极端情况），则仅刷新当前节点的 content。
   */
  const reloadCanvasFromBackend = async (anchorNodeId: string) => {
    try {
      // 1) 先拿 anchor 节点的 canvasId（local state 的 CanvasViewNode 没带 canvasId，必须后端查）
      const nodeResp = await canvasApi.getNode(anchorNodeId);
      const canvasId = nodeResp?.canvasId;
      if (!canvasId) {
        console.warn('[canvas] reloadCanvasFromBackend: node has no canvasId, skip');
        return;
      }
      // 2) 拉整张画布（画布元信息 + 所有节点 + 所有连线）
      const detail = await canvasApi.getCanvasDetail(canvasId);
      setCanvasId(canvasId);
      const matIds = loadMaterialNodeIds(canvasId);
      setMaterialNodeIds(matIds);
      setPromptMaterials(loadPromptMaterials(canvasId));
      setNodes(normalizeCanvasNodes(detail?.nodes).filter((n) => !matIds.includes(n.id)));
      setEdges(normalizeCanvasEdges(detail?.edges));
    } catch (err) {
      console.warn('[canvas] reloadCanvasFromBackend failed:', err);
    }
  };

    const activeNode = nodes.find((node) => node.id === activeNodeId) ?? null;

  useEffect(() => {
    if (!dragPending && !dragging) return;

    const DRAG_THRESHOLD = 4; // px — 移动超过这个距离才视为拖动

    const onPointerMove = (event: globalThis.PointerEvent) => {
      // 阶段 1: 按下了但还在阈值内 — 等用户移动超 4px 才开始拖
      if (dragPending && !dragging) {
        const dx = event.clientX - dragPending.startX;
        const dy = event.clientY - dragPending.startY;
        const distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < DRAG_THRESHOLD) return; // 不开始拖,让 click/dblclick 正常触发

        // 跨过阈值,提升为正式 drag
        const newDrag = {
          id: dragPending.id,
          offsetX: event.clientX - dragPending.nodeX,
          offsetY: event.clientY - dragPending.nodeY,
        };
        setDragPending(null);
        setDragging(newDrag);
        // 同步更新位置(避免首帧跳跃)
        setNodes((current) =>
          current.map((node) =>
            node.id === newDrag.id
              ? {
                  ...node,
                  x: clampNodeX(event.clientX - newDrag.offsetX),
                  y: clampNodeY(event.clientY - newDrag.offsetY),
                }
              : node
          )
        );
        return;
      }

      // 阶段 2: 正式 drag 中 — 更新节点位置
      if (dragging) {
        setNodes((current) =>
          current.map((node) =>
            node.id === dragging.id
              ? {
                  ...node,
                  x: clampNodeX(event.clientX - dragging.offsetX),
                  y: clampNodeY(event.clientY - dragging.offsetY),
                }
              : node
          )
        );
      }
    };

    const onPointerUp = () => {
      // 2026-08-10 修复:拖动结束同步坐标到后端,否则刷新后位置丢
      if (dragging) {
        setNodes((current) => {
          const draggedNode = current.find((n) => n.id === dragging.id);
          if (draggedNode) {
            void canvasApi.updateNode({
              nodeId: draggedNode.id,
              positionX: Math.round(draggedNode.x),
              positionY: Math.round(draggedNode.y),
            }).catch((err) => {
              console.warn('[canvas] drag sync position failed:', err);
            });
          }
          return current; // 引用不变,React 不会重渲染
        });
      }
      setDragPending(null);
      setDragging(null);
    };

    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
    return () => {
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
    };
  }, [dragPending, dragging]);

  useEffect(() => {
    if (!linkDrag) return;

    const onPointerMove = (event: globalThis.PointerEvent) => {
      setLinkDrag((current) =>
        current
          ? {
              ...current,
              x: event.clientX,
              y: event.clientY,
            }
          : current
      );
    };

    const onPointerUp = (event: globalThis.PointerEvent) => {
      // Hit-test: 检查是否拖拽到了已有节点上
      // 右侧拖出 → source 是上游，drop 目标是下游
      // 左侧拖出 → source 是下游，drop 目标是上游
      const targetNode = nodes.find(
        (n) =>
          n.id !== linkDrag.sourceId &&
          event.clientX >= n.x &&
          event.clientX <= n.x + NODE_WIDTH &&
          event.clientY >= n.y &&
          event.clientY <= n.y + NODE_HEIGHT
      );

      if (targetNode) {
        // 连接两个已有节点
        const fromId = linkDrag.side === 'right' ? linkDrag.sourceId : targetNode.id;
        const toId = linkDrag.side === 'right' ? targetNode.id : linkDrag.sourceId;
        const edgeId = `edge_${fromId}_${toId}`;
        setEdges((current) => {
          // 避免重复连接
          if (current.some((e) => e.from === fromId && e.to === toId)) {
            return current;
          }
          return [...current, { id: edgeId, from: fromId, to: toId }];
        });
        // 同步下游节点的 upstreamIds 到后端
        const newUpstreamIds = [
          ...edges.filter((e) => e.to === toId).map((e) => ({ port: 'default', nodeId: e.from })),
          { port: 'default', nodeId: fromId },
        ];
        void canvasApi.updateNode({ nodeId: toId, upstreamIds: newUpstreamIds }).catch((err) => {
          console.warn('[canvas] connect nodes: updateNode failed:', err);
        });
      } else {
        // 没有命中已有节点：打开添加菜单(原行为)
        setAddMenu({
          x: event.clientX + 12,
          y: event.clientY + 10,
          sourceId: linkDrag.sourceId,
          title: '引用节点',
        });
      }
      setLinkDrag(null);
    };

    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
    return () => {
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
    };
  }, [linkDrag, nodes, edges]);

  const changeZoom = (amount: number) => {
    setZoom((current) => Math.min(200, Math.max(50, current + amount)));
  };

  const openRootMenu = () => {
    const { width, height } = viewport();
    setAddMenu({
      x: width / 2 + 18,
      y: height / 2 + 8,
      title: '添加节点',
    });
  };

  const openNodeMenu = (node: CanvasViewNode, side: 'left' | 'right') => {
    setActiveNodeId(node.id);
    setAddMenu({
      x: side === 'right' ? node.x + NODE_WIDTH + 12 : node.x - 6,
      y: node.y + NODE_HEIGHT / 2 + 8,
      sourceId: node.id,
      title: '引用节点',
    });
  };

  const startLinkDrag = (node: CanvasViewNode, side: 'left' | 'right', event: PointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    const start = nodePort(node, side);
    setActiveNodeId(node.id);
    setAddMenu(null);
    setLinkDrag({
      sourceId: node.id,
      side,
      startX: start.x,
      startY: start.y,
      x: event.clientX,
      y: event.clientY,
    });
  };

  const addNode = async (type: CanvasNodeType) => {
    const source = addMenu?.sourceId ? nodes.find((node) => node.id === addMenu.sourceId) : null;
    const position = source
      ? {
          x: source.x + 430,
          y: source.y + Math.min(54, Math.max(-54, nodes.length % 2 === 0 ? -34 : 34)),
        }
      : centeredNodePosition();
    const created = await canvasApi.createNode({
      canvasId: canvasId ?? undefined,
      type,
      title: nodeTitle(type),
      upstreamIds: source ? [source.id] : undefined,
      // 2026-08-10 修复:创建时同步传坐标,后端不存的话刷新会默认 0,0
      positionX: Math.round(position.x),
      positionY: Math.round(position.y),
    });
    const nextNode: CanvasViewNode = {
      id: created.id,
      type,
      x: clampNodeX(position.x),
      y: clampNodeY(position.y),
      content: created.content,
      resultUrl: created.resultUrl,
      prompt: '', // 每个节点独立 prompt，初始为空
    };

    setNodes((current) => [...current, nextNode]);
    if (source) {
      setEdges((current) => [
        ...current,
        {
          id: `edge_${source.id}_${nextNode.id}`,
          from: source.id,
          to: nextNode.id,
        },
      ]);
    }
    setActiveNodeId(nextNode.id);
    setAddMenu(null);
  };

  const updateNodeContent = (id: string, value: string) => {
    setNodes((current) => current.map((node) => (node.id === id ? { ...node, content: value } : node)));
  };

  const updateNodePrompt = (id: string, value: string) => {
    setNodes((current) => current.map((node) => (node.id === id ? { ...node, prompt: value } : node)));
  };

  /** 视频节点:更新 duration/resolution 等设置,同步到后端 DB(刷新后保留) */
  const updateNodeSettings = (id: string, patch: Record<string, unknown>) => {
    setNodes((current) =>
      current.map((node) => {
        if (node.id !== id) return node;
        let currentSettings: Record<string, unknown> = {};
        if (node.settings) {
          try {
            currentSettings = JSON.parse(node.settings);
          } catch {
            /* ignore */
          }
        }
        const merged = { ...currentSettings, ...patch };
        const json = JSON.stringify(merged);
        // 同步到后端
        void canvasApi.updateNode({ nodeId: id, settings: json }).catch(() => {});
        return { ...node, settings: json };
      })
    );
  };

  /** 从节点 settings JSON 中读取视频参数 */
  const parseVideoSettings = (node: CanvasViewNode): { duration: number; resolution: string } => {
    // 2026-08-10 v6:默认值从上游抽帧节点推断(1 秒 1 帧),兜底 9 秒
    const inferDefault = (): number => {
      const upstreams = edges
        .filter((e) => e.to === node.id)
        .map((e) => nodes.find((n) => n.id === e.from))
        .filter((n): n is CanvasViewNode => Boolean(n));
      for (const up of upstreams) {
        // 优先级 1:抽帧节点的 settings.frameCount(后端持久化,可靠)
        try {
          const upSettings = up.settings ? JSON.parse(up.settings) : {};
          if (typeof upSettings.frameCount === 'number' && upSettings.frameCount > 0) {
            return upSettings.frameCount;
          }
        } catch { /* ignore */ }
        // 优先级 2:抽帧节点的 content "已抽帧 N 张..."(老数据兜底)
        const m = (up.content || '').match(/已抽帧\s*(\d+)\s*张/);
        if (m) {
          const n = parseInt(m[1], 10);
          if (n > 0) return n;
        }
      }
      return 9;
    };
    const defaultDuration = inferDefault();
    try {
      const s = node.settings ? JSON.parse(node.settings) : {};
      return {
        duration: typeof s.duration === 'number' ? s.duration : defaultDuration,
        resolution: typeof s.resolution === 'string' ? s.resolution : '720P',
      };
    } catch {
      return { duration: defaultDuration, resolution: '720P' };
    }
  };

  const duplicateNode = (node: CanvasViewNode) => {
    const nextNode: CanvasViewNode = {
      ...node,
      id: `copy_${node.id}_${Date.now()}`,
      x: clampNodeX(node.x + 36),
      y: clampNodeY(node.y + 36),
    };
    setNodes((current) => [...current, nextNode]);
    setActiveNodeId(nextNode.id);
  };

  const deleteNode = (nodeId: string) => {
    // 2026-08-10 修复:先走后端,失败不阻塞前端(本地仍然过滤,避免 UI 卡住)
    // 否则只删本地 state,刷新 getCanvasDetail 会把"删除"过的节点重新拉回来
    void canvasApi.deleteNode(nodeId).catch((err) => {
      console.warn('[canvas] deleteNode backend failed:', err);
    });
    setNodes((current) => current.filter((node) => node.id !== nodeId));
    setEdges((current) => current.filter((edge) => edge.from !== nodeId && edge.to !== nodeId));
    setActiveNodeId((current) => (current === nodeId ? null : current));
  };

  const deleteEdge = (edgeId: string) => {
    setEdges((current) => current.filter((edge) => edge.id !== edgeId));
    setEdgeMenu(null);
  };

  const handleNodeMenuAction = (action: NodeContextAction) => {
    if (!nodeMenu) return;
    const node = nodes.find((item) => item.id === nodeMenu.nodeId);
    if (!node) return;

    if (action === 'copy') {
      setCopiedNode(node);
    }
    if (action === 'duplicate') {
      duplicateNode(node);
    }
    if (action === 'paste' && copiedNode) {
      duplicateNode({ ...copiedNode, x: node.x + 36, y: node.y + 36 });
    }
    if (action === 'delete') {
      deleteNode(node.id);
    }
    setNodeMenu(null);
  };

  // ===== 本地上传：上传文件到画布，抹平建对应类型节点 =====

  const handleUploadToCanvas = async (files: FileList | File[] | null | undefined) => {
    if (!files || (files as FileList).length === 0) return;
    const list = Array.from(files as ArrayLike<File>);
    setUploading(true);
    try {
      const source = addMenu?.sourceId ? nodes.find((node) => node.id === addMenu.sourceId) : null;
      // 多个文件按纵向铺开（第一个从 source 右下方开始，后续每个下移 60px）
      let offsetIndex = 0;
      for (const file of list) {
        const created = await canvasApi.uploadToCanvas(file, canvasId ? { canvasId } : {});
        const basePos = source
          ? { x: source.x + 430, y: source.y + offsetIndex * 60 }
          : centeredNodePosition();
        const nextNode: CanvasViewNode = {
          id: created.id,
          type: created.type,
          x: clampNodeX(basePos.x),
          y: clampNodeY(basePos.y),
          content: created.content,
          resultUrl: created.resultUrl,
          prompt: '',
        };
        setNodes((current) => [...current, nextNode]);
        if (source) {
          setEdges((current) => [
            ...current,
            { id: `edge_${source.id}_${nextNode.id}`, from: source.id, to: nextNode.id },
          ]);
        }
        offsetIndex++;
      }
      setAddMenu(null);
    } catch (err: unknown) {
      console.error('[canvas] upload error:', err);
      const msg = err instanceof Error ? err.message : '上传失败，请重试';
      setGenerateError(msg);
    } finally {
      setUploading(false);
    }
  };

  const handleUploadClick = () => {
    setAddMenu(null);
    fileInputRef.current?.click();
  };

  const handleFileInputChange = (event: ChangeEvent<HTMLInputElement>) => {
    void handleUploadToCanvas(event.target.files);
    // 重置以便下次选同一文件还能触发 change
    event.target.value = '';
  };

  /**
   * PromptComposer 的「上传素材」按钮回调:
   *   1) 程序化创建一个 <input type="file"> 弹文件选择器
   *   2) 选中后复用 canvasApi.uploadToCanvas 上传到后端
   *   3) 把返回的 {id, resultUrl, ...} 转成 {id, url, name} 塞进
         promptMaterials[nodeId] 数组末尾
   *
   * 关键:点按钮瞬间就把 activeNode.id 捕获到局部变量 nodeId,
         即使用户在文件选择器开着的时候切换了 activeNode,素材也只进原节点。
   */
  // 2026-08-14 新增:删除单个素材(从 promptMaterials + materialNodeIds + 后端节点 中移除)
  // 修复:之前只清前端状态,刷新后画布上仍会出现该素材节点
  // 现在同时调后端 deleteNode 永久删除
  const handleRemoveMaterial = (materialId: string) => {
    const nodeId = activeNode?.id;
    if (!nodeId) return;
    setPromptMaterials((current) => {
      const existing = current[nodeId] ?? [];
      const next = existing.filter((m) => m.id !== materialId);
      return { ...current, [nodeId]: next };
    });
    // 从 materialNodeIds 中也移除(避免脏数据)
    setMaterialNodeIds((current) => {
      const next = current.filter((id) => id !== materialId);
      if (canvasId) saveMaterialNodeIds(canvasId, next);
      return next;
    });
    // 2026-08-14 修复:同步删除后端 canvas_node 实体,
    // 否则刷新页面 getCanvasDetail 会把该素材节点又拉回来画布上
    void canvasApi.deleteNode(materialId).catch((err) => {
      console.warn('[canvas] delete material node backend failed:', err);
    });
    // 同时也从当前 nodes state 过滤掉(防止节点仍展示)
    setNodes((current) => current.filter((n) => n.id !== materialId));
    setEdges((current) => current.filter((e) => e.from !== materialId && e.to !== materialId));
  };

  const handleUploadMaterial = () => {
    const nodeId = activeNode?.id;
    if (!nodeId) return;
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = async (event) => {
      const target = event.target as HTMLInputElement;
      const file = target.files?.[0];
      if (!file) return;
      setUploading(true);
      try {
        const created = await canvasApi.uploadToCanvas(
          file,
          canvasId ? { canvasId } : {},
        );
        const newMaterial = {
          id: created.id,
          url: created.resultUrl ?? created.content ?? '',
          name: file.name,
        };
        setPromptMaterials((current) => {
          const existing = current[nodeId] ?? [];
          return { ...current, [nodeId]: [...existing, newMaterial] };
        });
        // 2026-08-10:标记这个节点是素材节点,刷新后从画布上过滤掉(只在输入框里展示)
        setMaterialNodeIds((current) => {
          if (current.includes(created.id)) return current;
          const next = [...current, created.id];
          if (canvasId) saveMaterialNodeIds(canvasId, next);
          return next;
        });
      } catch (err) {
        console.error('[canvas] upload material error:', err);
        const msg = err instanceof Error ? err.message : '素材上传失败，请重试';
        setGenerateError(msg);
      } finally {
        setUploading(false);
      }
    };
    input.click();
  };

  const handleDragOver = (event: ReactDragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    if (!isDragOver) setIsDragOver(true);
  };

  const handleDragLeave = (event: ReactDragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    // 只在离开主容器时取消高亮（避免子元素拖动闪烁）
    if (event.currentTarget === event.target) setIsDragOver(false);
  };

  const handleDrop = (event: ReactDragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    setIsDragOver(false);
    void handleUploadToCanvas(event.dataTransfer.files);
  };

  // ===== 生成 =====

  const requestGeneration = async () => {
    if (!activeNode) return;
    // 2026-08-10 fix:同时只允许一个轮询链(避免连点生成按钮导致多个 task 并发)
    if (generating || pollGuardRef.current) return;
    pollGuardRef.current = true;
    // 合并后：video 节点且有上游连接(脚本拆解+换装总图)时，走 generate-video 端点
    const upstreamCount = edges.filter((edge) => edge.to === activeNode.id).length;
    const isVideoGen = activeNode.type === 'video' && upstreamCount > 0;
    const nodePrompt = (activeNode.prompt ?? '').trim();

    if (isVideoGen) {
      // 视频生成(图生视频)：必须有上游(脚本拆解文本 + 换装总图)
      // 上游不足时会在后端报错提示
    } else {
      // 其他节点：有上游允许空 prompt，无上游必须输入 prompt
      if (!nodePrompt && upstreamCount === 0) {
        setGenerateError('请先输入提示词，或从节点左侧/右侧 + 拖一个上游节点来引用');
        pollGuardRef.current = false;
        return;
      }
    }
    setGenerating(true);
    setGenerateError('');
    // 2026-08-10 优化:启动进度条
    const genKind = isVideoGen ? 'video' : (activeNode.type === 'image' ? 'image' : 'image');
    const genStage = isVideoGen ? '视频生成中…(排队中)' :
                     activeNode.type === 'text' ? '文案生成中…(排队中)' :
                     activeNode.type === 'image' ? '图片生成中…(排队中)' :
                     '生成中…(排队中)';
    startGeneration(genKind, genStage);
    // 2026-08-10 fix:本次轮询 abort controller,卸载/画布切换时自动取消
    if (pollAbortRef.current) pollAbortRef.current.abort();
    const abortController = new AbortController();
    pollAbortRef.current = abortController;

    try {
      // 1. 提交生成任务（后端立刻返回 pending 状态）
      let initial: import('@/api/canvas').GenerateCanvasNodeResponse;

      if (isVideoGen) {
        // 视频生成：先把用户输入(content)和上游连接(upstreamIds)持久化到后端节点，
        // 再调专用 generate-video 端点(后端 CanvasVideoGenService 读 node.upstreamIds + node.content)
        const upstreamIds = edges
          .filter((edge) => edge.to === activeNode.id)
          .map((edge) => ({ port: 'default', nodeId: edge.from }));
        // 2026-08-10 v5:从节点 settings 读取用户选择的 duration/resolution
        const vs = parseVideoSettings(activeNode);
        await canvasApi.updateNode({
          nodeId: activeNode.id,
          content: activeNode.content ?? '',
          upstreamIds,
          // 同步 settings(确保刷新后仍保留用户选择)
          settings: activeNode.settings ?? JSON.stringify({ duration: vs.duration, resolution: vs.resolution }),
        });
        initial = await canvasApi.generateVideo({
          nodeId: activeNode.id,
          duration: vs.duration,
          resolution: vs.resolution,
        });
      } else {
        initial = await canvasApi.generateNode({
          nodeId: activeNode.id,
          type: activeNode.type,
          prompt: nodePrompt,
          content: activeNode.content,
          assetIds: edges.filter((edge) => edge.to === activeNode.id).map((edge) => edge.from),
          // 2026-08-09:提示框中上传的素材 id(换装场景用)
          materialNodeIds: promptMaterials[activeNode.id]?.map((m) => m.id) ?? [],
        });
      }

      // 2. 轮询任务状态（按节点类型分档超时）
      const POLL_INTERVAL = 2000;
      // 文本：200s（NewAPI 润色需时 60-180s，frontend 要多给点 buffer）
      // 图片：600s（10 分钟，gpt-image 实际需要）
      // 文生视频：900s（15 分钟，NewAPI doubao-seedance 实测 8-12 分钟）
      // 图生视频：600s（10 分钟，与 NewAPI 视频接口默认对齐）
      // 后端超时:NewApiClient.chatCompletion = 180s，imagePollTimeoutSec = 600s，videoPollTimeoutSec = 1200s
      const MAX_DURATION =
        activeNode.type === 'text'  ? 200_000 :
        activeNode.type === 'image' ? 600_000 :
        /* video: 文生视频实测 8-12 分钟 + 后端补救 ~20s,留 15 分钟 buffer */
        activeNode.type === 'video' ? 900_000 :
        /* video-generation 默认 10 分钟 */ 600_000;
      const start = Date.now();
      let lastResult = initial;

      while (Date.now() - start < MAX_DURATION) {
        // 2026-08-10 fix:每轮检测 abort,卸载/画布切换时立即退出
        if (abortController.signal.aborted) return;
        await new Promise((resolve) => {
          // 2026-08-10 fix:把 timer id 存进 ref,卸载时可清理
          pollTimerRef.current = setTimeout(resolve, POLL_INTERVAL);
        });
        pollTimerRef.current = null;
        try {
          lastResult = await canvasApi.getTask(initial.taskId);
        } catch (pollErr) {
          // 轮询中途网络错误：继续重试，不直接报错
          console.warn('[canvas] poll failed, retrying:', pollErr);
          continue;
        }
        // 2026-08-10 优化:更新进度条文案 + 已耗时
        const elapsedSec = Math.round((Date.now() - start) / 1000);
        const runningStage = lastResult.status === 'pending'
          ? (isVideoGen ? `视频生成中…(排队中,已用 ${elapsedSec}s)` : `生成中…(排队中,已用 ${elapsedSec}s)`)
          : (isVideoGen ? `视频生成中…(处理中,已用 ${elapsedSec}s)` : `生成中…(处理中,已用 ${elapsedSec}s)`);
        updateGenerationStage(runningStage);

        if (lastResult.status === 'success') {
          // 3. 成功：根据节点类型更新 UI
          // 3a) 文本节点:text 直接更新
          if (activeNode.type === 'text' && lastResult.text) {
            updateNodeContent(activeNode.id, lastResult.text);
          } else if ((activeNode.type === 'image' || activeNode.type === 'video')
                     && lastResult.resultUrl) {
            // 换装/图片生成/视频生成:统一把 resultUrl 写到目标节点
            // 2026-08-14 修复:用 lastResult.nodeId(后端返回的真实节点 ID)而不是 activeNode.id,
            //   防止用户在等待生成期间切换了 activeNode 导致 setNodes 找不到目标节点 → 看起来"没生效"
            const targetNodeId = lastResult.nodeId || activeNode.id;
            // 使用 flushSync 强制立即渲染,确保无需刷新页面也能看到结果
            flushSync(() => {
              setNodes((current) =>
                current.map((n) =>
                  n.id === targetNodeId ? { ...n, resultUrl: lastResult.resultUrl } : n
                )
              );
            });
            // 如果用户已切换了 activeNode,把 resultUrl 节点激活,让用户立即看到
            if (targetNodeId !== activeNode.id) {
              setActiveNodeId(targetNodeId);
            }
          }
          // 3b) createdNodeIds(如有) merge 进画布
          // 注意:换装场景不会返回 createdNodeIds,这里只处理脚本拆解/视频抽帧等场景
          const newIds = lastResult.createdNodeIds ?? [];
          if (newIds.length > 0) {
            // 计算源→目标的偏移量,apply 到每个新节点上,让新节点出现在目标右边而不是源右边。
            const sourceEdge = edges.find((e) => e.to === activeNode.id);
            const sourceNode = sourceEdge
              ? nodes.find((n) => n.id === sourceEdge.from)
              : null;
            const anchorDx = sourceNode && activeNode
              ? (activeNode.x ?? 0) - (sourceNode.x ?? 0)
              : 0;
            const anchorDy = sourceNode && activeNode
              ? (activeNode.y ?? 0) - (sourceNode.y ?? 0)
              : 0;

            void (async () => {
              try {
                const newNodes = await Promise.all(
                  newIds.map(async (id) => {
                    const apiNode = await canvasApi.getNode(id);
                    return {
                      ...apiNode,
                      x: (apiNode.positionX ?? 0) + anchorDx,
                      y: (apiNode.positionY ?? 0) + anchorDy,
                      prompt: '',
                    } as CanvasViewNode;
                  })
                );
                setNodes((current) => [...current, ...newNodes]);
                // 2026-08-10 fix:同步补全当前激活节点→新节点的连线(防御性,目前换装不会返回 createdNodeIds)
                setEdges((current) => {
                  const additions: CanvasEdge[] = [];
                  for (const nn of newNodes) {
                    const eid = `edge_${activeNode.id}_${nn.id}`;
                    if (current.some((e) => e.id === eid || (e.from === activeNode.id && e.to === nn.id))) continue;
                    additions.push({ id: eid, from: activeNode.id, to: nn.id });
                  }
                  return additions.length > 0 ? [...current, ...additions] : current;
                });
              } catch (mergeErr) {
                console.warn('[canvas] merge new nodes failed:', mergeErr);
              }
            })();
          }
          // 2026-08-10 优化:成功 -> 显示"完成"文案,1 秒后清理进度条
          updateGenerationStage(`完成,用时 ${Math.round((Date.now() - start) / 1000)}s`);
          setTimeout(() => endGeneration(), 1000);
          return;
        }

        if (lastResult.status === 'failed') {
          // 2026-08-10 优化:显示后端透传的具体失败原因(不再只是"failed"字面量)
          const failMsg = lastResult.failMessage || (lastResult as any).message || lastResult.status;
          setGenerateError(`生成失败: ${failMsg}`);
          updateGenerationStage(`失败: ${failMsg}`);
          setTimeout(() => endGeneration(), 2500);
          return;
        }
        // pending / running 继续轮询
      }

      // 2026-08-09:超时后兑换底重试 1 次(换装经常刚好超时)
      console.warn('[canvas] 轮询超时，最后兑换底重试 1 次 ...');
      try {
        lastResult = await canvasApi.getTask(initial.taskId);
        if (lastResult.status === 'success' || lastResult.status === 'failed') {
          // 走上面同个 success 分支逻辑可能不推，因为已经过了 while — 手动处理
          if (lastResult.status === 'failed') {
            setGenerateError(`生成失败：${(lastResult as any).message || (lastResult as any).failMessage || lastResult.status}`);
          } else {
            setGenerateError('后端已完成，但兑换底重试未拉取新节点。请刷新画布查看。');
          }
          return;
        }
      } catch (finalErr) {
        console.error('[canvas] 兑换底重试也失败:', finalErr);
      }
      setGenerateError('生成超时，请重试');
    } catch (err: any) {
      console.error('[canvas] generate error:', err);
      setGenerateError(err?.message || '生成出错，请重试');
      updateGenerationStage(`异常: ${err?.message || '生成出错'}`);
      setTimeout(() => endGeneration(), 2500);
    } finally {
      setGenerating(false);
      // 2026-08-10 fix:重置 debounce guard,允许下次点击
      pollGuardRef.current = false;
      if (pollAbortRef.current === abortController) {
        pollAbortRef.current = null;
      }
    }
  };

  // ===== 视频抽帧描述 / 脚本拆解 =====

  /** 创建新画布(下拉菜单 + 侧边栏共用),带过渡动画 */
  const handleCreateNewCanvas = async () => {
    setShowCanvasMenu(false);
    setIsCreatingCanvas(true);
    try {
      const item = await canvasApi.createCanvas({ name: '未命名画布' });
      setCanvasId(item.id);
      setCanvasName('未命名画布');
      if (typeof window !== 'undefined') {
        localStorage.removeItem(`canvas_nodes_${item.id}`);
        localStorage.removeItem(`canvas_edges_${item.id}`);
        if (canvasId) {
          localStorage.removeItem(`canvas_nodes_${canvasId}`);
          localStorage.removeItem(`canvas_edges_${canvasId}`);
        }
        localStorage.removeItem('canvas_nodes');
        localStorage.removeItem('canvas_edges');
      }
      setNodes([]);
      setEdges([]);
      setActiveNodeId(null);
      setGenerateError('');
      void fetchCanvasHistory();
    } catch (err) {
      console.warn('[canvas] createCanvas failed:', err);
      // 降级:只清空本地
      if (typeof window !== 'undefined') {
        localStorage.removeItem('canvas_nodes');
        localStorage.removeItem('canvas_edges');
      }
      setNodes([]);
      setEdges([]);
      setActiveNodeId(null);
    } finally {
      // 600ms 后隐藏过渡动画,让用户看到"新画布"效果
      setTimeout(() => setIsCreatingCanvas(false), 600);
    }
  };

  // 2026-08-10 优化:统一管理生成进度条状态
  // 调用方只需 startGeneration('image' | 'video' | 'extract', '阶段文案') 即可,避免散落 setState
  const startGeneration = (kind: 'image' | 'video' | 'extract', stage: string) => {
    setGenerationKind(kind);
    setGenerationStage(stage);
    setGenerationStart(Date.now());
    setGenerationProgress(0);
  };
  const updateGenerationStage = (stage: string) => setGenerationStage(stage);
  const endGeneration = () => {
    setGenerating(false);
    setGenerationStage('');
    setGenerationStart(0);
    setGenerationKind('');
    setGenerationProgress(0);
  };

  // 顶部工具栏目标节点：只有激活节点是视频时才显示，否则隐藏
  const videoToolbarTarget =
    activeNode && activeNode.type === 'video' && activeNode.resultUrl
      ? activeNode
      : null;

  type ExtractMode = 'script' | 'frames' | 'both';
  const handleExtractCaption = async (node: CanvasViewNode | null, mode: ExtractMode) => {
    // 2026-08-10 fix:同时只允许一个轮询链
    if (generating || pollGuardRef.current) return;
    if (!node) {
      setGenerateError('画布里没有视频节点，请先上传一个视频');
      setGenerating(false);
      return;
    }
    if (!node.resultUrl) {
      setGenerateError('视频节点还没有视频，请先上传或生成');
      setGenerating(false);
      return;
    }
    pollGuardRef.current = true;
    setGenerating(true);
    setGenerateError('');
    // 2026-08-10 优化:启动进度条,模式不同文案不同
    const extractStage =
      mode === 'frames' ? '视频抽帧中…(上传/排队)' :
      mode === 'script' ? '脚本拆解中…(上传/排队)' :
      '抽帧 + 脚本拆解中…(上传/排队)';
    const extractStart = Date.now();
    startGeneration('extract', extractStage);
    // 2026-08-10 fix:抽帧任务也用同一个 abort + timer ref,卸载时统一清理
    if (pollAbortRef.current) pollAbortRef.current.abort();
    if (pollTimerRef.current) {
      clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
    const abortController = new AbortController();
    pollAbortRef.current = abortController;

    const pollTask = async (taskId: string) => {
      // 2026-08-10 fix:每轮检测 abort
      if (abortController.signal.aborted) return;
      try {
        const data: {
          status?: string;
          text?: string;
          message?: string;
          failMessage?: string;
          createdNodeIds?: string[];
        } = await fetch(`/api/canvas/tasks/${taskId}`, {
          credentials: 'include',
          signal: abortController.signal,
          headers: (() => {
            const t = getAccessToken();
            const headers: Record<string, string> = {};
            if (t) headers.Authorization = `Bearer ${t}`;
            return headers;
          })(),
        }).then((r) => r.json());

        if (data.status === 'success') {
          // 2026-08-10 优化:轮询过程中更新进度
          const elapsedSec = Math.round((Date.now() - extractStart) / 1000);
          updateGenerationStage(`合并结果中…(已用 ${elapsedSec}s)`);
          // 写入视频节点 content（可能是口播文案，也可能是 "已抽帧 N 张" 状态标记）
          if (data.text) {
            setNodes((current) =>
              current.map((n) => (n.id === node.id ? { ...n, content: data.text! } : n))
            );
          }
          // 合并本次新建的节点（帧拼图 / 口播文案文本节点）到本地 state
          // 不再调 reloadCanvasFromBackend（那会拉整张画布，把历史节点堆左上角）
          const newIds = data.createdNodeIds ?? [];
          if (newIds.length > 0) {
            try {
              const newNodes = await Promise.all(
                newIds.map(async (id) => {
                  const apiNode = await canvasApi.getNode(id);
                  // 关键映射：后端返回 positionX/positionY，前端 CanvasViewNode 要 x/y
                  // 没这一步 → 新节点全是 (0,0) 堆左上角 + 拖动计算为 NaN
                  return {
                    ...apiNode,
                    x: apiNode.positionX ?? 0,
                    y: apiNode.positionY ?? 0,
                    prompt: '',
                  } as CanvasViewNode;
                })
              );
              setNodes((current) => [...current, ...newNodes]);
              // 2026-08-10 fix:同步补全父节点(视频节点)→新节点的连线,无需刷新页面
              // 后端 VideoFrameCaptionService 已通过 connectNodes 写入 node.upstreamIds/downstreamIds,
              // 但 CanvasNodeResponse 不返回这些字段(防止泄露其他用户节点 ID),
              // 所以前端必须手动基于"抽帧任务的源节点 = node.id"补一条 edge。
              setEdges((current) => {
                const additions: CanvasEdge[] = [];
                for (const nn of newNodes) {
                  const edgeId = `edge_${node.id}_${nn.id}`;
                  // 避免重复添加(极端情况下后端可能已同步回来)
                  if (current.some((e) => e.id === edgeId || (e.from === node.id && e.to === nn.id))) continue;
                  additions.push({ id: edgeId, from: node.id, to: nn.id });
                }
                return additions.length > 0 ? [...current, ...additions] : current;
              });
              // 持久化到后端:让 reload 时也能正确恢复。
              // updateNode 是覆盖式写入,所以先合并已有 downstreamIds + 新边,再写回。
              try {
                const existingDowns: { port: string; nodeId: string }[] = (() => {
                  try {
                    const raw = (node as any).downstreamIds;
                    if (typeof raw === 'string') return JSON.parse(raw);
                    if (Array.isArray(raw)) return raw;
                  } catch { /* ignore */ }
                  return [];
                })();
                const newDowns = newNodes.map((nn) => ({ port: 'video', nodeId: nn.id }));
                // 去重合并:按 nodeId + port 去重
                const merged = [...existingDowns];
                for (const nd of newDowns) {
                  const dup = merged.some((m) => m.nodeId === nd.nodeId && m.port === nd.port);
                  if (!dup) merged.push(nd);
                }
                if (merged.length > existingDowns.length) {
                  await canvasApi.updateNode({
                    nodeId: node.id,
                    downstreamIds: merged,
                  });
                }
              } catch (syncErr) {
                console.warn('[canvas] sync downstreamIds failed (non-fatal):', syncErr);
              }
            } catch (mergeErr) {
              console.warn('[canvas] merge new nodes failed:', mergeErr);
            }
          }
          setGenerating(false);
          pollGuardRef.current = false;
          // 2026-08-10 优化:抽帧成功 -> 显示"完成"文案
          updateGenerationStage(`完成,用时 ${Math.round((Date.now() - extractStart) / 1000)}s`);
          setTimeout(() => endGeneration(), 1000);
          return;
        }
        if (data.status === 'failed') {
          setGenerateError(`${mode === 'frames' ? '抽帧' : '脚本拆解'}失败: ${data.failMessage || data.message || '未知错误'}`);
          setGenerating(false);
          pollGuardRef.current = false;
          updateGenerationStage(`失败: ${data.failMessage || data.message || '未知错误'}`);
          setTimeout(() => endGeneration(), 2500);
          return;
        }
        // pending / running —— 2 秒后再问
        // 2026-08-10 fix:timer id 存进 ref,卸载时可清理
        pollTimerRef.current = setTimeout(() => {
          pollTimerRef.current = null;
          pollTask(taskId);
        }, 2000);
        // 2026-08-10 优化:抽帧 pending / running 期间持续更新进度文案
        const eSec = Math.round((Date.now() - extractStart) / 1000);
        const extractRunningStage =
          data.status === 'pending'
            ? (mode === 'frames' ? `视频抽帧中…(排队中,已用 ${eSec}s)` : `脚本拆解中…(排队中,已用 ${eSec}s)`)
            : (mode === 'frames' ? `视频抽帧中…(处理中,已用 ${eSec}s)` : `脚本拆解中…(处理中,已用 ${eSec}s)`);
        updateGenerationStage(extractRunningStage);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : '轮询失败';
        setGenerateError(`失败: ${msg}`);
        setGenerating(false);
        pollGuardRef.current = false;
        updateGenerationStage(`异常: ${msg}`);
        setTimeout(() => endGeneration(), 2500);
      }
    };

    try {
      const token = getAccessToken();
      const headers: Record<string, string> = {};
      if (token) headers.Authorization = `Bearer ${token}`;
      const initial: { taskId?: string; message?: string } = await fetch(
        `/api/canvas/nodes/${node.id}/extract-caption?fps=1&mode=${mode}`,
        { method: 'POST', credentials: 'include', headers }
      ).then((r) => r.json());

      if (!initial?.taskId) {
        throw new Error(initial?.message || '提交任务失败');
      }
      // 开始轮询
      pollTask(initial.taskId);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '提交失败';
      setGenerateError(`失败: ${msg}`);
      setGenerating(false);
    }
  };

  return (
    <LoginGate>
    <main
      className={cn(
        'relative h-screen min-h-[640px] overflow-auto bg-[#f1f2f4] text-[#5e6878] transition-colors',
        isDragOver && 'bg-[#eef5ff]'
      )}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      onClick={(event) => {
        // 点击空白处（不是节点、不是节点上方的工具栏按钮）时，清掉 activeNodeId，
        // 这样顶部工具栏 / PromptComposer 都跟着隐藏。
        const target = event.target as HTMLElement;
        if (!target.closest('[data-canvas-node="true"], [data-toolbar="true"]')) {
          setActiveNodeId(null);
        }
      }}
    >
      {/* 2026-08-10 优化:生成进度条 - 仅在生成中显示,右上角浮动卡片 */}
      {generating && (
        <div
          data-progress-card="true"
          className="pointer-events-none fixed right-4 top-4 z-[100] flex items-start gap-3 rounded-xl border border-[#dfe3e8] bg-white/95 px-4 py-3 shadow-[0_12px_28px_rgba(41,48,61,0.18)] backdrop-blur"
          style={{ minWidth: 280, maxWidth: 360 }}
        >
          {/* 旋转图标 */}
          <div
            className={cn(
              'mt-0.5 h-5 w-5 shrink-0 rounded-full border-2 border-transparent animate-spin',
              generationStage.startsWith('失败') || generationStage.startsWith('异常')
                ? 'border-t-red-500 border-r-red-500'
                : generationStage.startsWith('完成')
                ? 'border-t-emerald-500 border-r-emerald-500'
                : 'border-t-[#2f78ff] border-r-[#2f78ff] border-b-[#2f78ff]/20 border-l-[#2f78ff]/20'
            )}
          />
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <span
                className={cn(
                  'text-sm font-semibold',
                  generationStage.startsWith('失败') || generationStage.startsWith('异常')
                    ? 'text-red-600'
                    : generationStage.startsWith('完成')
                    ? 'text-emerald-600'
                    : 'text-[#2f78ff]'
                )}
              >
                生成中
              </span>
              {generationStage.startsWith('完成') && (
                <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-medium text-emerald-700">
                  完成
                </span>
              )}
            </div>
            <div className="mt-0.5 text-xs leading-relaxed text-[#5e6878]">
              {generationStage}
            </div>
            {/* 进度条 */}
            <div className="mt-2 flex items-center gap-2">
              <div className="h-2 flex-1 overflow-hidden rounded-full bg-[#eef2f7]">
                <div
                  className={cn(
                    'h-full rounded-full transition-all duration-500',
                    generationStage.startsWith('失败') || generationStage.startsWith('异常')
                      ? 'bg-gradient-to-r from-red-400 to-red-500'
                      : generationStage.startsWith('完成')
                      ? 'bg-gradient-to-r from-emerald-400 to-emerald-500'
                      : 'bg-gradient-to-r from-[#2f78ff] to-[#4c8dff]'
                  )}
                  style={{ width: `${generationProgress}%` }}
                />
              </div>
              <span
                className={cn(
                  'w-10 text-right text-xs font-medium',
                  generationStage.startsWith('失败') || generationStage.startsWith('异常')
                    ? 'text-red-600'
                    : generationStage.startsWith('完成')
                    ? 'text-emerald-600'
                    : 'text-[#2f78ff]'
                )}
              >
                {generationProgress}%
              </span>
            </div>
          </div>
        </div>
      )}

      {/* 删除确认弹窗 */}
      {showDeleteConfirm && (
        <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 backdrop-blur-sm">
          <div className="mx-4 w-full max-w-sm rounded-2xl bg-white p-6 shadow-2xl">
            <div className="mb-4 flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-red-100">
                <Trash2 className="h-5 w-5 text-red-600" />
              </div>
              <h3 className="text-lg font-semibold text-[#1f2937]">确认删除</h3>
            </div>
            <p className="mb-6 text-sm text-[#6b7280]">
              确定要删除画布「<span className="font-medium text-[#1f2937]">{canvasName}</span>」吗？
              此操作不可恢复，删除后画布中的所有节点和内容将被永久移除。
            </p>
            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setShowDeleteConfirm(false)}
                className="rounded-lg border border-[#e5e7eb] bg-white px-4 py-2 text-sm font-medium text-[#4f5969] transition hover:bg-[#f9fafb]"
              >
                取消
              </button>
              <button
                type="button"
                onClick={async () => {
                  setShowDeleteConfirm(false);
                  try {
                    if (canvasId) {
                      await canvasApi.deleteCanvas(canvasId);
                    }
                    setCanvasId(null);
                    setCanvasName('未命名画布');
                    if (typeof window !== 'undefined') {
                      if (canvasId) {
                        localStorage.removeItem(`canvas_nodes_${canvasId}`);
                        localStorage.removeItem(`canvas_edges_${canvasId}`);
                      }
                      localStorage.removeItem('canvas_nodes');
                      localStorage.removeItem('canvas_edges');
                    }
                    setNodes([]);
                    setEdges([]);
                    setActiveNodeId(null);
                    setGenerateError('');
                    void fetchCanvasHistory();
                    // 删除成功后跳转到画布列表
                    router.push('/canvas');
                  } catch (err) {
                    console.warn('[canvas] deleteCanvas failed:', err);
                    setGenerateError('删除画布失败');
                  }
                }}
                className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-red-700"
              >
                确认删除
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 创建新画布过渡动画 */}
      {isCreatingCanvas && (
        <div className="fixed inset-0 z-[200] flex items-center justify-center bg-white/80 backdrop-blur-sm">
          <div className="flex flex-col items-center gap-4">
            <div className="h-10 w-10 animate-spin rounded-full border-[3px] border-[#e2e5ea] border-t-[#2f78ff]" />
            <span className="text-sm font-medium text-[#4f5969]">正在创建新画布…</span>
          </div>
        </div>
      )}

      {/* 隐藏的 file input — 菜单/拖拽都通过它选择文件 */}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*,video/*,audio/*"
        multiple
        onChange={handleFileInputChange}
        className="hidden"
        aria-hidden
      />

      {/* 顶部视频操作工具栏：跟随目标视频节点浮动在它上方 */}
      {videoToolbarTarget && (
        <div
          data-toolbar="true"
          className="pointer-events-auto absolute z-40 flex -translate-x-1/2 gap-1 rounded-xl border border-[#dfe3e8] bg-white/95 px-2 py-1 shadow-[0_8px_22px_rgba(41,48,61,0.10)] backdrop-blur"
          style={{
            left: videoToolbarTarget.x + NODE_WIDTH / 2,
            top: Math.max(8, videoToolbarTarget.y - 48),
          }}
        >
          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              handleExtractCaption(videoToolbarTarget, 'script');
            }}
            className="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium text-[#4c7eff] hover:bg-[#eef3ff]"
            title="脚本拆解：用 VL 模型给每帧生成口播文案，输出文本节点"
          >
            <FileText className="h-3.5 w-3.5" />
            <span>脚本拆解</span>
          </button>
          <span className="self-center text-[#dfe3e8]">|</span>
          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              handleExtractCaption(videoToolbarTarget, 'frames');
            }}
            className="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium text-[#4c7eff] hover:bg-[#eef3ff]"
            title="视频抽帧：抽取帧缩略图到右侧，输出帧网格"
          >
            <ImageIcon className="h-3.5 w-3.5" />
            <span>视频抽帧</span>
          </button>
        </div>
      )}

      {/* 拖拽高亮提示 */}
      {isDragOver && (
        <div className="pointer-events-none absolute inset-3 z-[60] grid place-items-center rounded-2xl border-2 border-dashed border-[#4c8dff] bg-white/40 backdrop-blur-sm">
          <div className="flex flex-col items-center gap-2 text-[#4c8dff]">
            <Upload className="h-10 w-10" />
            <span className="text-base font-semibold">拖拽图片/视频/音频到这里上传</span>
            <span className="text-xs text-[#7a8aa3]">上传后自动建对应类型节点</span>
          </div>
        </div>
      )}

      {/* 上传中提示 */}
      {uploading && (
        <div className="fixed bottom-24 left-1/2 z-50 -translate-x-1/2 rounded-md border border-blue-200 bg-blue-50 px-4 py-2 text-sm text-blue-700 shadow-lg">
          正在上传并创建节点…
        </div>
      )}
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_50%_46%,rgba(255,255,255,0.16),transparent_38%)]" />
      {addMenu && (
        <button
          type="button"
          aria-label="关闭添加菜单"
          className="absolute inset-0 z-10 cursor-default"
          onClick={() => setAddMenu(null)}
        />
      )}
      {nodeMenu && (
        <button
          type="button"
          aria-label="关闭节点菜单"
          className="absolute inset-0 z-40 cursor-default"
          onClick={() => setNodeMenu(null)}
        />
      )}

      <header className="absolute left-3 right-3 top-3 z-40 flex items-center justify-between">
        {/* 画布名称下拉菜单 */}
        <div ref={canvasMenuRef} className="pointer-events-auto relative">
          <div
            className="flex h-[38px] items-center gap-2 rounded-xl border border-[#e2e5ea] bg-white/90 px-2.5 shadow-[0_8px_22px_rgba(41,48,61,0.06)] backdrop-blur hover:border-[#c5d0e6]"
          >
            <button
              type="button"
              onClick={() => setShowCanvasMenu((v) => !v)}
              className="flex items-center gap-1.5"
              aria-label="画布菜单"
            >
              <span className="grid h-6 w-6 place-items-center rounded-lg bg-gradient-to-br from-[#ff3f9b] via-[#725bff] to-[#317bff] text-[11px] font-bold text-white shadow-sm">
                J
              </span>
              <span className="h-4 w-px bg-[#e2e5ea]" />
            </button>
            {/* 画布名称 - 可编辑 */}
            {isEditingName ? (
              <input
                ref={nameInputRef}
                type="text"
                value={canvasName}
                onChange={(e) => setCanvasName(e.target.value)}
                onBlur={async () => {
                  setIsEditingName(false);
                  if (canvasId) {
                    try {
                      await canvasApi.updateCanvas(canvasId, { name: canvasName });
                    } catch (err) {
                      console.warn('[canvas] updateCanvas name failed:', err);
                    }
                  }
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    nameInputRef.current?.blur();
                  }
                  if (e.key === 'Escape') {
                    setCanvasName(canvasName);
                    setIsEditingName(false);
                    nameInputRef.current?.blur();
                  }
                }}
                onClick={(e) => e.stopPropagation()}
                className="w-32 rounded border border-[#2f78ff] bg-white px-1.5 py-0.5 text-xs font-semibold text-[#4f5969] outline-none focus:ring-1 focus:ring-[#2f78ff]"
                autoFocus
              />
            ) : (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setIsEditingName(true);
                  setTimeout(() => {
                    nameInputRef.current?.focus();
                    nameInputRef.current?.select();
                  }, 0);
                }}
                className="pr-1 text-xs font-semibold text-[#4f5969] hover:text-[#2f78ff] hover:underline"
                title="点击编辑画布名称"
              >
                {canvasName}
              </button>
            )}
            <button
              type="button"
              onClick={() => setShowCanvasMenu((v) => !v)}
              className="p-0.5 text-[#74839a] hover:text-[#2f78ff]"
              aria-label="画布菜单"
            >
              <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', showCanvasMenu && 'rotate-180')} />
            </button>
          </div>
          {/* 下拉菜单 */}
          {showCanvasMenu && (
            <div className="absolute left-0 top-full z-50 mt-2 w-48 rounded-xl border border-[#e2e5ea] bg-white/95 py-1 shadow-[0_12px_28px_rgba(41,48,61,0.18)] backdrop-blur">
              {/* 回到主页 */}
              <button
                type="button"
                onClick={() => {
                  setShowCanvasMenu(false);
                  router.push('/canvas');
                }}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-[#4f5969] hover:bg-[#f4f7ff] hover:text-[#2f78ff]"
              >
                <span className="flex h-5 w-5 items-center justify-center text-[#74839a]">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
                </span>
                <span>回到主页</span>
              </button>
              {/* 全部画布 */}
              <button
                type="button"
                onClick={() => {
                  setShowCanvasMenu(false);
                  setShowHistoryPanel(true);
                  void fetchCanvasHistory();
                }}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-[#4f5969] hover:bg-[#f4f7ff] hover:text-[#2f78ff]"
              >
                <span className="flex h-5 w-5 items-center justify-center text-[#74839a]">
                  <FolderOpen className="h-4 w-4" />
                </span>
                <span>全部画布</span>
              </button>
              {/* 分割线 */}
              <div className="my-1 h-px bg-[#e2e5ea]" />
              {/* 创建新画布 */}
              <button
                type="button"
                onClick={handleCreateNewCanvas}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-[#4f5969] hover:bg-[#f4f7ff] hover:text-[#2f78ff]"
              >
                <span className="flex h-5 w-5 items-center justify-center text-[#74839a]">
                  <Plus className="h-4 w-4" />
                </span>
                <span>创建新画布</span>
              </button>
              {/* 删除画布 */}
              <button
                type="button"
                onClick={() => {
                  setShowCanvasMenu(false);
                  if (!canvasId) {
                    setGenerateError('没有可删除的画布');
                    return;
                  }
                  setShowDeleteConfirm(true);
                }}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-red-500 hover:bg-red-50"
              >
                <span className="flex h-5 w-5 items-center justify-center">
                  <Trash2 className="h-4 w-4" />
                </span>
                <span>删除画布</span>
              </button>
            </div>
          )}
        </div>

        <div className="pointer-events-auto flex h-[38px] items-center gap-3 rounded-xl border border-[#e2e5ea] bg-white/90 px-3 text-xs shadow-[0_8px_22px_rgba(41,48,61,0.06)] backdrop-blur">
          <button type="button" className="inline-flex items-center gap-1 text-[#4f5969] hover:text-[#2f78ff]" title="积分">
            <Coins className="h-3.5 w-3.5" />
            <span>0</span>
          </button>
          <span className="h-4 w-px bg-[#e2e5ea]" />
          <button type="button" className="inline-flex items-center gap-1 text-[#4f5969] hover:text-[#2f78ff]" title="分享画布">
            <Share2 className="h-3.5 w-3.5" />
            <span>分享</span>
          </button>
          <button type="button" className="inline-flex items-center gap-1 text-[#4f5969] hover:text-[#2f78ff]" title="打开对话">
            <MessageCircle className="h-3.5 w-3.5" />
            <span>对话</span>
          </button>
        </div>
      </header>

      <FlowLines nodes={nodes} edges={edges} preview={linkDrag} />
      {linkDrag && (
        <div
          className="pointer-events-none absolute z-50 grid h-5 w-5 place-items-center rounded-full border border-[#304156] bg-white text-[#304156] shadow-[0_8px_18px_rgba(45,55,72,0.16)]"
          style={{ left: linkDrag.x - 10, top: linkDrag.y - 10 }}
        >
          <Plus className="h-3.5 w-3.5" />
        </div>
      )}

      <aside className="absolute left-3 top-1/2 z-40 flex w-[55px] -translate-y-1/2 flex-col items-center overflow-hidden rounded-xl border border-[#e1e4e9] bg-white/95 py-1.5 shadow-[0_10px_26px_rgba(41,48,61,0.10)] backdrop-blur">
        <button
          type="button"
          onClick={handleCreateNewCanvas}
          className="grid h-11 w-full place-items-center border-b border-[#eceef1] text-[#74839a] transition hover:bg-[#f4f7ff] hover:text-[#3978ff]"
          title="新建画布（清空当前画布）"
          aria-label="新建画布"
        >
          <Plus className="h-5 w-5" />
        </button>
        <button
          type="button"
          onClick={() => {
            setShowHistoryPanel(true);
            void fetchCanvasHistory();
          }}
          className="grid h-11 w-full place-items-center border-b border-[#eceef1] text-[#74839a] transition hover:bg-[#f4f7ff] hover:text-[#3978ff]"
          title="我的创作"
          aria-label="我的创作"
        >
          <History className="h-5 w-5" />
        </button>
        <button
          type="button"
          onClick={openRootMenu}
          className="grid h-11 w-full place-items-center border-b border-[#eceef1] text-[#74839a] transition hover:bg-[#f4f7ff] hover:text-[#3978ff]"
          title="添加卡片"
        >
          <Plus className="h-5 w-5" />
        </button>
        {TOOLS.map(({ id, label, icon: Icon, beta }) => (
          <button
            key={id}
            type="button"
            onClick={() => setActiveTool(id)}
            className={cn(
              'relative grid h-[42px] w-full place-items-center text-[#8a96a8] transition hover:bg-[#f4f7ff] hover:text-[#3978ff]',
              activeTool === id && 'mx-1 w-[47px] rounded-xl bg-white text-[#4d596a] shadow-[0_5px_14px_rgba(35,44,61,0.12)]'
            )}
            title={label}
            aria-label={label}
          >
            <Icon className="h-[15px] w-[15px]" />
            {id !== 'select' && id !== 'hand' && (
              <span className="absolute bottom-1 text-[9px] leading-none">{label}</span>
            )}
            {beta && (
              <span className="absolute right-0 top-0 rounded bg-[#2c313b] px-1 text-[8px] font-semibold leading-3 text-white">
                Beta
              </span>
            )}
          </button>
        ))}
      </aside>

      {nodes.length === 0 ? (
        <button
          type="button"
          onClick={openRootMenu}
          onDoubleClick={openRootMenu}
          className="absolute left-1/2 top-1/2 z-20 inline-flex -translate-x-1/2 -translate-y-1/2 items-center gap-2 rounded-full border border-[#e1e3e8] bg-white/75 px-5 py-3 text-sm text-[#8c97a8] shadow-[0_7px_24px_rgba(51,58,72,0.10)] backdrop-blur transition hover:bg-white hover:text-[#6c788b]"
          title="双击画布添加新卡片"
        >
          <MousePointerClick className="h-[18px] w-[18px]" />
          <span>双击画布添加新卡片</span>
        </button>
      ) : (
        nodes.map((node) => (
          <CanvasNodeCard
            key={node.id}
            node={node}
            active={node.id === activeNodeId}
            hiddenHandleSide={linkDrag?.sourceId === node.id ? linkDrag?.side ?? null : null}
            onActivate={() => setActiveNodeId(node.id)}
            onTextChange={(value) => updateNodeContent(node.id, value)}
            onOpenMenu={openNodeMenu}
            onStartLinkDrag={startLinkDrag}
            onOpenExpanded={(target) => {
              if (target.resultUrl) setExpandedImage({ url: target.resultUrl, title: nodeTitle(target.type) });
            }}
            onContextMenu={(event) => {
              event.preventDefault();
              setActiveNodeId(node.id);
              setNodeMenu({ x: event.clientX, y: event.clientY, nodeId: node.id });
            }}
            onDragStart={(event) => {
              const target = event.target as HTMLElement;
              if (target.closest('[data-no-node-drag="true"],a')) return;
              event.preventDefault();
              setActiveNodeId(node.id);
              // 只记录起始位置,等 pointermove 跨过阈值(4px)才真正开始 drag
              // 这样单击/双击不会被 drag 吃掉
              setDragPending({
                id: node.id,
                startX: event.clientX,
                startY: event.clientY,
                nodeX: node.x,
                nodeY: node.y,
              });
            }}
            onExpandImage={(image) => setExpandedImage(image)}
            onUpdateSettings={updateNodeSettings}
            upstreamNodes={edges
              .filter((e) => e.to === node.id)
              .map((e) => nodes.find((n) => n.id === e.from))
              .filter((n): n is CanvasViewNode => Boolean(n))}
          />
        ))
      )}

      {activeNode && (
        <PromptComposer
          node={activeNode}
          // 视频节点且有上游(图生视频场景):用户输入存到 content(后端 CanvasVideoGenService 读 node.content)
          // 其他情况:用户输入存到 prompt(通过 generateNode 请求传给后端)
          value={activeNode.type === 'video'
            && edges.some((edge) => edge.to === activeNode.id)
            ? (activeNode.content ?? '')
            : (activeNode.prompt ?? '')}
          referenceCount={edges.filter((edge) => edge.to === activeNode.id).length}
          onChange={activeNode.type === 'video'
            && edges.some((edge) => edge.to === activeNode.id)
            ? (value) => updateNodeContent(activeNode.id, value)
            : (value) => updateNodePrompt(activeNode.id, value)}
          onSubmit={requestGeneration}
          generating={generating}
          onUploadMaterial={handleUploadMaterial}
          uploading={uploading}
          materials={promptMaterials[activeNode.id] ?? []}
          onRemoveMaterial={handleRemoveMaterial}
          onActivate={() => setActiveNodeId(activeNode.id)}
          onUpdateSettings={updateNodeSettings}
          upstreamNodes={edges
            .filter((e) => e.to === activeNode.id)
            .map((e) => nodes.find((n) => n.id === e.from))
            .filter((n): n is CanvasViewNode => Boolean(n))}
        />
      )}

      {generateError && (
        <div className="fixed bottom-24 left-1/2 z-50 -translate-x-1/2 rounded-md border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-600 shadow-lg">
          {generateError}
          <button
            type="button"
            className="ml-3 text-red-400 hover:text-red-600"
            onClick={() => setGenerateError('')}
          >
            ✕
          </button>
        </div>
      )}
      {addMenu && <AddCanvasMenu menu={addMenu} onAddNode={addNode} onUploadClick={handleUploadClick} />}

      {/* 展开图片 modal（双击图片节点触发） */}
      {expandedImage && (
        <div
          className="fixed inset-0 z-[80] flex items-center justify-center bg-black/80 p-8"
          onClick={() => setExpandedImage(null)}
        >
          <div
            className="relative max-h-[90vh] max-w-[90vw]"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              onClick={() => setExpandedImage(null)}
              className="absolute -top-3 -right-3 grid h-9 w-9 place-items-center rounded-full bg-white text-[#4e5a6c] shadow-lg hover:bg-[#f4f7ff]"
              title="关闭"
              aria-label="关闭"
            >
              ✕
            </button>
            <img
              src={expandedImage.url}
              alt={expandedImage.title}
              className="max-h-[90vh] max-w-[90vw] rounded-lg bg-white object-contain"
            />
            <div className="mt-3 text-center text-sm text-white/80">
              {expandedImage.title}
            </div>
          </div>
        </div>
      )}

      {/* 我的创作 · 侧边面板 */}
      {showHistoryPanel && (
        <div
          className="fixed inset-0 z-[70] flex"
          onClick={(event) => {
            // 点击 backdrop 关闭
            if (event.target === event.currentTarget) {
              setShowHistoryPanel(false);
            }
          }}
        >
          <div className="absolute inset-0 bg-black/30" />
          <div className="relative flex h-full w-[360px] flex-col border-r border-[#e1e4e9] bg-white shadow-2xl">
            <div className="flex items-center justify-between border-b border-[#eceef1] px-4 py-3">
              <div className="flex items-center gap-2">
                <History className="h-4 w-4 text-[#3978ff]" />
                <span className="text-sm font-semibold text-[#4e5a6c]">我的创作</span>
                {canvasHistory.length > 0 && (
                  <span className="rounded-full bg-[#eef3ff] px-2 py-0.5 text-[10px] font-medium text-[#3978ff]">
                    {canvasHistory.length}
                  </span>
                )}
              </div>
              <button
                type="button"
                onClick={() => setShowHistoryPanel(false)}
                className="grid h-7 w-7 place-items-center rounded text-[#9aa6b7] hover:bg-[#f4f7ff] hover:text-[#4e5a6c]"
                title="关闭"
              >
                ✕
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-2">
              {historyLoading ? (
                <div className="grid place-items-center py-12 text-xs text-[#a8b0bd]">
                  加载中…
                </div>
              ) : canvasHistory.length === 0 ? (
                <div className="grid place-items-center py-12 text-xs text-[#a8b0bd]">
                  还没有创作，去画布上传一个素材开始吧
                </div>
              ) : (
                canvasHistory.map((c) => (
                  <button
                    key={c.id}
                    type="button"
                    onClick={() => void openCanvasFromHistory(c.id)}
                    className="block w-full rounded-lg px-3 py-3 text-left transition hover:bg-[#f4f7ff]"
                  >
                    <div className="flex items-center justify-between">
                      <span className="truncate text-sm font-medium text-[#344256]">
                        {c.name || '未命名画布'}
                      </span>
                      <span className="text-[10px] text-[#a8b0bd]">
                        {new Date(c.updatedAt).toLocaleDateString()}
                      </span>
                    </div>
                    <div className="mt-1 text-[11px] text-[#a8b0bd]">
                      {c.nodeCount ?? 0} 个节点
                    </div>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      )}
      {nodeMenu && (
        <NodeContextMenu
          menu={nodeMenu}
          canPaste={Boolean(copiedNode)}
          onAction={handleNodeMenuAction}
        />
      )}

      <div className="absolute bottom-3 left-2 z-40 flex h-[42px] items-center gap-1 rounded-xl border border-[#dfe3e8] bg-white/95 px-2 shadow-[0_8px_22px_rgba(41,48,61,0.08)] backdrop-blur">
        <button type="button" className="grid h-7 w-7 place-items-center rounded-md text-[#8190a4] hover:bg-[#f2f5fa] hover:text-[#4c7eff]" title="画布导航">
          <Map className="h-3.5 w-3.5" />
        </button>
        <button type="button" className="grid h-7 w-7 place-items-center rounded-md text-[#8190a4] hover:bg-[#f2f5fa] hover:text-[#4c7eff]" title="显示网格">
          <Grid3X3 className="h-3.5 w-3.5" />
        </button>
        <span className="mx-1 h-5 w-px bg-[#e6e9ed]" />
        <button
          type="button"
          onClick={() => changeZoom(-10)}
          className="grid h-7 w-7 place-items-center rounded-md text-[#8190a4] hover:bg-[#f2f5fa] hover:text-[#4c7eff]"
          title="缩小"
        >
          <Minus className="h-3.5 w-3.5" />
        </button>
        <span className="min-w-[42px] text-center text-xs font-semibold text-[#374151]">{zoom}%</span>
        <button
          type="button"
          onClick={() => changeZoom(10)}
          className="grid h-7 w-7 place-items-center rounded-md text-[#8190a4] hover:bg-[#f2f5fa] hover:text-[#4c7eff]"
          title="放大"
        >
          <Plus className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          onClick={() => setZoom(100)}
          className="ml-1 inline-flex h-7 items-center gap-1 rounded-md px-2 text-xs font-medium text-[#8190a4] hover:bg-[#f2f5fa] hover:text-[#4c7eff]"
          title="重置缩放"
        >
          <Maximize2 className="h-3 w-3" />
          <span>重置</span>
        </button>
      </div>
    </main>
    </LoginGate>
  );
}

function FlowLines({
  nodes,
  edges,
  preview,
}: {
  nodes: CanvasViewNode[];
  edges: CanvasEdge[];
  preview: LinkDragState | null;
}) {
  return (
    <svg className="pointer-events-none absolute inset-0 z-10 h-full w-full">
      {edges.map((edge) => {
        const from = nodes.find((node) => node.id === edge.from);
        const to = nodes.find((node) => node.id === edge.to);
        if (!from || !to) return null;

        const path = connectionPath(
          from.x + NODE_WIDTH,           // source: 输出永远在右边（固定）
          from.y + NODE_HEIGHT / 2,
          to.x,                          // target: 输入永远在左边（固定）
          to.y + NODE_HEIGHT / 2
        );

        return <FlowPath key={edge.id} path={path} />;
      })}
      {preview && <FlowPath path={connectionPath(preview.startX, preview.startY, preview.x, preview.y)} preview />}
    </svg>
  );
}

function FlowPath({ path, preview }: { path: string; preview?: boolean }) {
  return (
    <g>
      <path
        d={path}
        fill="none"
        stroke={preview ? '#697587' : '#6d7685'}
        strokeLinecap="round"
        strokeWidth={preview ? '2.5' : '3'}
        opacity={preview ? '0.9' : '1'}
      />
      <path
        d={path}
        fill="none"
        opacity="0.95"
        stroke="#c7e2ff"
        strokeDasharray="18 56"
        strokeLinecap="round"
        strokeWidth={preview ? '4.5' : '4'}
      >
        <animate attributeName="stroke-dashoffset" dur="1.25s" from="74" repeatCount="indefinite" to="0" />
      </path>
    </g>
  );
}

/** 2026-08-10 v5:视频节点底部设置条(模型/时长/分辨率) */
function VideoNodeSettingsBar({
  node,
  active,
  onActivate,
  onUpdateSettings,
  upstreamNodes = [],
}: {
  node: CanvasViewNode;
  active: boolean;
  onActivate: () => void;
  onUpdateSettings?: (id: string, patch: Record<string, unknown>) => void;
  /** 上游节点列表(用于推断默认时长:抽帧节点 1 秒 1 帧,帧数 = 秒数) */
  upstreamNodes?: CanvasViewNode[];
}) {
  // 2026-08-10 v6:从上游抽帧节点推断默认时长(1 秒 1 帧,帧数 = 秒数)
  const inferDefaultDuration = (): number => {
    for (const up of upstreamNodes) {
      // 优先级 1:settings.frameCount(后端持久化)
      try {
        const s = up.settings ? JSON.parse(up.settings) : {};
        if (typeof s.frameCount === 'number' && s.frameCount > 0) return s.frameCount;
      } catch { /* ignore */ }
      // 优先级 2:content "已抽帧 N 张..."(老数据兜底)
      const m = (up.content || '').match(/已抽帧\s*(\d+)\s*张/);
      if (m) {
        const n = parseInt(m[1], 10);
        if (n > 0) return n;
      }
    }
    return 10;
  };
  const defaultDuration = inferDefaultDuration();

  let duration = defaultDuration;
  let resolution = '720P';
  try {
    const s = node.settings ? JSON.parse(node.settings) : {};
    if (typeof s.duration === 'number') duration = s.duration;
    if (typeof s.resolution === 'string') resolution = s.resolution;
  } catch {
    /* ignore */
  }

  // 时长预设选项:2/4/6/8/10/12,外加"自定义"
  const durationPresets = [2, 4, 6, 8, 10, 12];
  const isPreset = durationPresets.includes(duration);
  const [customDuration, setCustomDuration] = useState<string>(
    isPreset ? '' : String(duration)
  );
  const [showCustomInput, setShowCustomInput] = useState<boolean>(!isPreset);
  const DURATION_CUSTOM_FLAG = '__custom__';

  const resolutionOptions = ['480P', '720P', '1080P'];

  return (
    <div className="absolute bottom-5 left-4 flex items-center gap-2 text-xs font-medium text-[#516074]">
      {/* 模型名(静态,无下拉箭头) */}
      <span className="inline-flex items-center gap-1 select-none">
        <Sparkles className="h-3 w-3" />
        <span>doubao-seedance</span>
      </span>
      {/* 时长下拉 + 自定义输入 */}
      {showCustomInput ? (
        <div className="flex items-center gap-1">
          <input
            type="number"
            min={1}
            max={60}
            value={customDuration}
            autoFocus
            onClick={(e) => e.stopPropagation()}
            onChange={(e) => setCustomDuration(e.target.value)}
            onBlur={() => {
              const v = parseInt(customDuration, 10);
              if (!isNaN(v) && v >= 1) {
                onUpdateSettings?.(node.id, { duration: v });
              } else {
                setShowCustomInput(false);
              }
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
              if (e.key === 'Escape') {
                setCustomDuration(String(duration));
                setShowCustomInput(false);
              }
            }}
            className="w-12 rounded border border-[#d3dae3] bg-white px-1 py-0 text-xs outline-none focus:border-[#2f78ff] focus:text-[#2f78ff]"
          />
          <span>秒</span>
          <button
            type="button"
            onClick={() => setShowCustomInput(false)}
            className="text-[#b4bdc9] hover:text-[#2f78ff]"
            title="取消"
          >
            ×
          </button>
        </div>
      ) : (
        <select
          value={isPreset ? String(duration) : DURATION_CUSTOM_FLAG}
          onClick={onActivate}
          onChange={(e) => {
            onActivate();
            if (e.target.value === DURATION_CUSTOM_FLAG) {
              setCustomDuration(String(duration));
              setShowCustomInput(true);
            } else {
              const v = parseInt(e.target.value, 10);
              if (!isNaN(v)) onUpdateSettings?.(node.id, { duration: v });
            }
          }}
          className={`cursor-pointer rounded bg-transparent px-1 outline-none transition-colors ${
            active ? 'text-[#2f78ff]' : 'hover:text-[#2f78ff]'
          }`}
        >
          {durationPresets.map((d) => (
            <option key={d} value={d}>
              {d} 秒
            </option>
          ))}
          <option value={DURATION_CUSTOM_FLAG}>自定义</option>
        </select>
      )}
      <span className="text-[#b4bdc9]">⌄</span>
      {/* 分辨率下拉 */}
      <select
        value={resolution}
        onClick={onActivate}
        onChange={(e) => {
          onActivate();
          onUpdateSettings?.(node.id, { resolution: e.target.value });
        }}
        className={`cursor-pointer rounded bg-transparent px-1 outline-none transition-colors ${
          active ? 'text-[#2f78ff]' : 'hover:text-[#2f78ff]'
        }`}
      >
        {resolutionOptions.map((r) => (
          <option key={r} value={r}>
            {r}
          </option>
        ))}
      </select>
      <span className="text-[#b4bdc9]">⌄</span>
    </div>
  );
}

function CanvasNodeCard({
  node,
  active,
  hiddenHandleSide,
  onActivate,
  onTextChange,
  onOpenMenu,
  onStartLinkDrag,
  onOpenExpanded,
  onContextMenu,
  onDragStart,
  onExpandImage,
  onUpdateSettings,
  upstreamNodes = [],
}: {
  node: CanvasViewNode;
  active: boolean;
  hiddenHandleSide: 'left' | 'right' | null;
  onActivate: () => void;
  onTextChange: (value: string) => void;
  onOpenMenu: (node: CanvasViewNode, side: 'left' | 'right') => void;
  onStartLinkDrag: (node: CanvasViewNode, side: 'left' | 'right', event: PointerEvent<HTMLButtonElement>) => void;
  onOpenExpanded: (node: CanvasViewNode) => void;
  onContextMenu: (event: ReactMouseEvent<HTMLDivElement>) => void;
  onDragStart: (event: PointerEvent<HTMLDivElement>) => void;
  /** 双击图片节点放大查看 */
  onExpandImage: (image: { url: string; title: string }) => void;
  /** 视频节点:更新 duration/resolution 设置 */
  onUpdateSettings?: (id: string, patch: Record<string, unknown>) => void;
  /** 上游节点列表(用于推断默认时长) */
  upstreamNodes?: CanvasViewNode[];
}) {
  const isText = node.type === 'text';
  const isVideoLike = node.type === 'video';

  return (
    <div
      data-canvas-node="true"
      className={cn('absolute z-30 cursor-grab select-none active:cursor-grabbing', active && 'z-40')}
      style={{ left: node.x, top: node.y, width: NODE_WIDTH }}
      onPointerDown={onDragStart}
      onContextMenu={onContextMenu}
      onClick={(e) => {
                  e.stopPropagation();
                  onActivate();
                }}
                onDoubleClick={(e) => {
                  e.stopPropagation();
                  onOpenExpanded(node);
                }}
    >
      {active && (
        <NodeTopActions type={node.type} />
      )}
      <div className="mb-2 flex items-center gap-1 text-xs text-[#778699]">
        {isText ? <FileText className="h-3.5 w-3.5" /> : <ImageIcon className="h-3.5 w-3.5" />}
        <span>{nodeTitle(node.type)}</span>
      </div>
      <div className="relative">
        <button
          type="button"
          data-no-node-drag="true"
          onClick={(event) => {
            event.stopPropagation();
          }}
          onPointerDown={(event) => onStartLinkDrag(node, 'left', event)}
          className={cn(
            'absolute -left-10 top-1/2 grid h-5 w-5 -translate-y-1/2 place-items-center rounded-full border border-[#304156] bg-white text-[#304156] shadow-sm transition hover:scale-110 hover:border-[#2f78ff] hover:text-[#2f78ff] active:scale-95',
            hiddenHandleSide === 'left' && 'pointer-events-none opacity-0'
          )}
          title="添加引用节点"
        >
          <Plus className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          data-no-node-drag="true"
          onClick={(event) => {
            event.stopPropagation();
          }}
          onPointerDown={(event) => onStartLinkDrag(node, 'right', event)}
          className={cn(
            'absolute -right-10 top-1/2 grid h-5 w-5 -translate-y-1/2 place-items-center rounded-full border border-[#304156] bg-white text-[#304156] shadow-sm transition hover:scale-110 hover:border-[#2f78ff] hover:text-[#2f78ff] active:scale-95',
            hiddenHandleSide === 'right' && 'pointer-events-none opacity-0'
          )}
          title="添加引用节点"
        >
          <Plus className="h-3.5 w-3.5" />
        </button>
        {isText ? (
          <textarea
            value={node.content ?? ''}
            onChange={(event) => onTextChange(event.target.value)}
            onFocus={onActivate}
            placeholder="请输入您的指令、提示词或脚本等..."
            className={cn(
              'h-[220px] w-[320px] cursor-grab resize-none rounded-lg bg-white/35 p-2 text-xs leading-5 text-[#344256] outline-none placeholder:text-[#8c97a7] active:cursor-grabbing',
              active ? 'border border-[#48566a]' : 'border border-[#e0e4ea]'
            )}
          />
        ) : (node.resultUrl && node.resultUrl.length > 0) ? (
          // 生成成功：按类型渲染 <video> 或 <img>
          // 注意:img/video 不能加 data-no-node-drag="true",否则点击图片区域无法拖动节点
          isVideoLike ? (
            <div className="relative">
              <button
                type="button"
                data-no-node-drag="true"
                onClick={(e) => {
                  e.stopPropagation();
                  window.open(node.resultUrl!, '_blank', 'noopener,noreferrer');
                }}
                className="absolute right-1.5 top-1.5 z-10 grid h-9 w-9 place-items-center rounded-lg bg-black/70 text-white shadow-lg ring-1 ring-white/20 hover:bg-black/85 hover:scale-105 transition"
                title="在新标签页打开原图"
                aria-label="打开原图"
              >
                <Expand className="h-4 w-4" />
              </button>
              <video
                src={node.resultUrl}
                controls
                className="h-[220px] min-h-[140px] min-w-[200px] w-[320px] resize overflow-auto cursor-grab rounded-lg border border-[#e0e4ea] bg-black object-contain active:cursor-grabbing"
                onClick={(e) => {
                  e.stopPropagation();
                  onActivate();
                }}
                onDoubleClick={(e) => {
                  e.stopPropagation();
                  onOpenExpanded(node);
                }}
              />
            </div>
          ) : (
                        <div className="relative">
              <button
                type="button"
                data-no-node-drag="true"
                onClick={(e) => {
                  e.stopPropagation();
                  window.open(node.resultUrl!, '_blank', 'noopener,noreferrer');
                }}
                className="absolute right-1.5 top-1.5 z-10 grid h-9 w-9 place-items-center rounded-lg bg-black/70 text-white shadow-lg ring-1 ring-white/20 hover:bg-black/85 hover:scale-105 transition"
                title="在新标签页打开原图"
                aria-label="打开原图"
              >
                <Expand className="h-4 w-4" />
              </button>
              <img
                src={node.resultUrl}
                alt={nodeTitle(node.type)}
                draggable={false}
                className="h-[220px] min-h-[140px] min-w-[200px] w-[320px] resize overflow-auto cursor-grab rounded-lg border border-[#e0e4ea] bg-white/35 object-contain active:cursor-grabbing"
                onClick={(e) => {
                  e.stopPropagation();
                  onActivate();
                }}
                onDoubleClick={(e) => {
                  e.stopPropagation();
                  onOpenExpanded(node);
                }}
              />
            </div>
          )
        ) : (
          <button
            type="button"
            onClick={onActivate}
            className={cn(
              'grid h-[220px] w-[320px] cursor-grab place-items-center rounded-lg bg-white/35 text-[#718197] outline-none transition hover:bg-white/50 hover:text-[#2f78ff] active:cursor-grabbing',
              active ? 'border border-[#596476]' : 'border border-[#e0e4ea]'
            )}
            title={isVideoLike ? '视频生成节点' : '上传图片'}
          >
            {isVideoLike ? <Film className="h-8 w-8" /> : <ImageIcon className="h-8 w-8" />}
          </button>
        )}
      </div>
    </div>
  );
}

function NodeTopActions({ type }: { type: CanvasNodeType }) {
  // 不再在节点顶部展示任何按钮（上传 / 从资产库选择 移除）
  // 需要操作请点开节点或用全局工具栏
  return null;
}

function PromptComposer({
  node,
  value,
  referenceCount,
  onChange,
  onSubmit,
  generating,
  onUploadMaterial,
  uploading,
  materials,
  onRemoveMaterial,
  onActivate,
  onUpdateSettings,
  upstreamNodes = [],
}: {
  node: CanvasViewNode;
  value: string;
  referenceCount: number;
  onChange: (value: string) => void;
  onSubmit: () => void;
  generating?: boolean;
  /** 点击上传素材: 调起文件选择器, 上传后会自动插入 material */
  onUploadMaterial: () => void;
  /** 上传中 flag */
  uploading?: boolean;
  /** 当前节点已上传的素材列表(包含缩略图 url) */
  materials?: Array<{ id: string; url: string; name?: string }>;
  onRemoveMaterial?: (materialId: string) => void;
  /** 视频节点:激活回调(点击设置时) */
  onActivate?: () => void;
  /** 视频节点:更新 duration/resolution */
  onUpdateSettings?: (id: string, patch: Record<string, unknown>) => void;
  /** 视频节点:上游节点列表(用于推断默认时长) */
  upstreamNodes?: CanvasViewNode[];
}) {
  const isText = node.type === 'text';
  const isImage = node.type === 'image';
  const isVideo = node.type === 'video';
  const { width, height } = viewport();
  const left = clamp(node.x + NODE_WIDTH / 2 - PROMPT_WIDTH / 2, 84, Math.max(84, width - PROMPT_WIDTH - 84));
  const top = clamp(
    node.y + NODE_HEIGHT + PROMPT_GAP,
    178,
    Math.max(178, height - PROMPT_HEIGHT - PROMPT_BOTTOM_MARGIN)
  );

  // 按节点类型定制 placeholder
  const placeholder = isText
    ? '可连续添加素材并 @引用，描述你想生成的文本。例如：提炼这款保温杯的核心卖点，写成适合电商详情页的短文。'
    : isImage
    ? '可连续添加素材并 @引用，描述你想生成或编辑的图片。例如：生成一张电商主图，突出商品质感、使用场景和促销氛围。'
    : '可连续添加素材并 @引用，描述你想生成的视频内容。例如：一只小猫在草地上散步，阳光透过树梢洒下来，镜头缓缓推进。';

  // 按节点类型定制价格（与后端 estimateCredits 对齐）
  const creditCost = isText ? '0.20' : isImage ? '1.18' : '20.00';

  return (
    <div
      data-toolbar="true"
      className={cn(
        'absolute z-20 rounded-lg border border-[#dfe4ea] bg-white p-3 shadow-[0_10px_28px_rgba(44,54,70,0.10)]',
        isText ? 'h-[220px]' : 'h-[218px]'
      )}
      style={{ left, top, width: PROMPT_WIDTH }}
    >
      {referenceCount > 0 && (
        <div className="absolute left-4 top-3 grid h-12 w-12 place-items-center rounded-lg bg-[#e5e9f0] text-[#8290a3]">
          <span className="absolute left-1 top-1 grid h-4 w-4 place-items-center rounded-full bg-[#737d8d] text-[10px] font-semibold leading-none text-white">
            {referenceCount}
          </span>
          <List className="h-4 w-4" />
        </div>
      )}
      {/* 2026-08-09 新增:提示素材 chip 区(点上传按钮后填充) */}
      {materials && materials.length > 0 && (
        <div className="mb-2 flex flex-wrap items-center gap-2 pt-2">
          {materials.map((m, idx) => (
            <div
              key={m.id}
              className="group relative flex h-12 w-12 items-center justify-center overflow-hidden rounded-md border border-[#dfe4ea] bg-[#f4f6fa]"
              title={m.name ?? m.id}
            >
              {m.url ? (
                <img src={m.url} alt={m.name ?? '素材'} className="h-full w-full object-cover" />
              ) : (
                <ImageIcon className="h-5 w-5 text-[#9aa6b7]" />
              )}
              <span className="absolute left-0.5 top-0.5 grid h-4 w-4 place-items-center rounded-full bg-black/60 text-[10px] font-semibold leading-none text-white">
                {idx + 1}
              </span>
              {/* 2026-08-14 新增:删除按钮(hover 时显示),点击移除该素材 */}
              {onRemoveMaterial && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onRemoveMaterial(m.id);
                  }}
                  className="absolute right-0.5 top-0.5 hidden h-4 w-4 items-center justify-center rounded-full bg-red-500 text-white shadow hover:bg-red-600 group-hover:flex"
                  title="删除该素材"
                >
                  <X className="h-2.5 w-2.5" strokeWidth={3} />
                </button>
              )}
            </div>
          ))}
        </div>
      )}
      <textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        className={cn(
          'h-[118px] w-full resize-none bg-transparent pr-8 text-sm leading-6 text-[#4b5565] outline-none placeholder:text-[#606b7c]',
          (referenceCount > 0 || (materials && materials.length > 0)) && 'pl-[74px]'
        )}
      />
      <div className="absolute right-4 top-4 flex flex-col gap-5 text-[#9aa6b7]">
        <button type="button" className="hover:text-[#2f78ff]" title="帮我写">
          <Sparkles className="h-4 w-4" />
        </button>
                <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            if (node.resultUrl) {
              window.open(node.resultUrl, '_blank', 'noopener,noreferrer');
            }
          }}
          disabled={!node.resultUrl}
          className={cn(
            'hover:text-[#2f78ff]',
            !node.resultUrl && 'cursor-not-allowed text-[#cbd2dc] hover:text-[#cbd2dc]'
          )}
          title={node.resultUrl ? '在新标签页打开原图' : '当前节点没有图片可放大'}
        >
          <Expand className="h-4 w-4" />
        </button>
        <button
          type="button"
          onClick={onUploadMaterial}
          className="hover:text-[#2f78ff]"
          title="上传素材"
          disabled={uploading}
        >
          {uploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
        </button>
        <button type="button" className="hover:text-[#2f78ff]" title="参数列表">
          <List className="h-4 w-4" />
        </button>
      </div>
      <div className="absolute bottom-14 left-3 right-3 h-px bg-[#e9edf2]" />
      {isText ? null : isImage ? (
        // 图片节点：高级版 / 自适应 / 1K / 技能包
        <div className="absolute bottom-5 left-4 flex items-center gap-2 text-xs font-medium text-[#516074]">
          <button type="button" className="inline-flex items-center gap-1 hover:text-[#2f78ff]">
            <Sparkles className="h-3 w-3" />
            <span>高级版 VIP</span>
          </button>
          <span className="text-[#b4bdc9]">⌄</span>
          <button type="button" className="inline-flex items-center gap-1 hover:text-[#2f78ff]">
            <BoxSelect className="h-3 w-3" />
            <span>自适应</span>
          </button>
          <button type="button" className="inline-flex items-center gap-1 hover:text-[#2f78ff]">
            <span>标清 1K</span>
          </button>
          <span className="text-[#b4bdc9]">⌄</span>
          <button type="button" className="inline-flex items-center gap-1 hover:text-[#2f78ff]">
            <CircleHelp className="h-3 w-3" />
            <span>技能包</span>
          </button>
          <span className="text-[#b4bdc9]">⌄</span>
        </div>
      ) : (
        // 视频节点：模型 / 时长 / 分辨率 — 2026-08-10 v5:改为可交互选择,选择存到 node.settings
        <VideoNodeSettingsBar
          node={node}
          active={true}
          onActivate={() => onActivate?.()}
          onUpdateSettings={onUpdateSettings}
          upstreamNodes={upstreamNodes}
        />
      )}
      <div className="absolute bottom-4 right-4 flex items-center gap-2">
        <span className="text-xs font-medium text-[#536072]">
          <span className="text-[#1a8cff]">✦</span> {creditCost} / 条
        </span>
        <button
          type="button"
          onClick={onSubmit}
          disabled={generating}
          className={cn(
            'grid h-9 w-9 place-items-center rounded-full text-white transition',
            generating
              ? 'cursor-not-allowed bg-[#d9dee8]'
              : 'bg-[#d9dee8] hover:bg-[#2f78ff]'
          )}
          title={generating ? '生成中…' : '生成'}
        >
          {generating ? (
            <span className="block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
          ) : (
            <span className="-mt-0.5 text-xl leading-none">↑</span>
          )}
        </button>
      </div>
    </div>
  );
}

function AddCanvasMenu({
  menu,
  onAddNode,
  onUploadClick,
}: {
  menu: AddMenuState;
  onAddNode: (type: CanvasNodeType) => void;
  onUploadClick: () => void;
}) {
  const { width, height } = viewport();
  const left = clamp(menu.x, 74, width - 210);
  const top = clamp(menu.y, 70, height - 310);

  return (
    <div
      className="absolute z-50 w-[186px] rounded-lg border border-[#e0e4ea] bg-white py-2 text-xs text-[#4c5868] shadow-[0_18px_45px_rgba(34,42,56,0.14)]"
      style={{ left, top }}
    >
      <div className="px-3 pb-1 text-[10px] text-[#9aa5b4]">{menu.title}</div>
      <CanvasMenuItem icon={FileText} label="文本" onClick={() => onAddNode('text')} />
      <CanvasMenuItem icon={ImageIcon} label="图片" onClick={() => onAddNode('image')} />
      <CanvasMenuItem icon={Video} label="视频" onClick={() => onAddNode('video')} />
      <CanvasMenuItem icon={Volume2} label="音频" onClick={() => onAddNode('audio')} />
      <div className="mx-2 my-2 h-px bg-[#edf0f4]" />
      <div className="px-3 pb-1 text-[10px] text-[#9aa5b4]">添加资源</div>
      <CanvasMenuItem icon={Upload} label="从本地上传" onClick={onUploadClick} />
      <CanvasMenuItem icon={FolderOpen} label="从资产库选择" />
    </div>
  );
}

function NodeContextMenu({
  menu,
  canPaste,
  onAction,
}: {
  menu: NodeContextMenuState;
  canPaste: boolean;
  onAction: (action: NodeContextAction) => void;
}) {
  const { width, height } = viewport();
  const left = clamp(menu.x, 74, width - 250);
  const top = clamp(menu.y, 70, height - 230);

  return (
    <div
      className="absolute z-50 w-[230px] rounded-lg border border-[#dde2e8] bg-white py-2 text-sm text-[#354154] shadow-[0_20px_48px_rgba(34,42,56,0.16)]"
      style={{ left, top }}
    >
      <ContextMenuItem icon={Download} label="保存到本地" disabled onClick={() => onAction('save')} />
      <ContextMenuItem icon={Copy} label="复制节点" shortcut="Ctrl+C" onClick={() => onAction('copy')} />
      <ContextMenuItem icon={PanelsTopLeft} label="创建副本" onClick={() => onAction('duplicate')} />
      <ContextMenuItem icon={Clipboard} label="粘贴" shortcut="Ctrl+V" disabled={!canPaste} onClick={() => onAction('paste')} />
      <ContextMenuItem icon={Trash2} label="删除" onClick={() => onAction('delete')} />
    </div>
  );
}

function ContextMenuItem({
  icon: Icon,
  label,
  shortcut,
  disabled,
  onClick,
}: {
  icon: ComponentType<{ className?: string }>;
  label: string;
  shortcut?: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={cn(
        'flex h-9 w-full items-center gap-3 px-4 text-left transition',
        disabled
          ? 'cursor-not-allowed text-[#aeb7c3]'
          : 'text-[#344154] hover:bg-[#f5f7fb] hover:text-[#2f78ff]'
      )}
    >
      <Icon className="h-4 w-4" />
      <span className="flex-1">{label}</span>
      {shortcut && <span className="text-xs text-[#9aa5b4]">{shortcut}</span>}
    </button>
  );
}

function CanvasMenuItem({
  icon: Icon,
  label,
  onClick,
}: {
  icon: ComponentType<{ className?: string }>;
  label: string;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex h-9 w-full items-center gap-3 px-5 text-left transition hover:bg-[#f5f7fb] hover:text-[#2f78ff]"
    >
      <Icon className="h-3.5 w-3.5 text-[#8c9aab]" />
      <span>{label}</span>
    </button>
  );
}

function centeredNodePosition() {
  const { width, height } = viewport();
  return {
    x: width / 2 - NODE_WIDTH / 2,
    y: height / 2 - NODE_HEIGHT / 2 - 58,
  };
}

function clampNodeX(value: number) {
  const { width } = viewport();
  return clamp(value, 84, Math.max(84, width - NODE_WIDTH - 84));
}

function clampNodeY(value: number) {
  const { height } = viewport();
  return clamp(value, 92, Math.max(92, height - NODE_HEIGHT - PROMPT_HEIGHT - PROMPT_GAP - PROMPT_BOTTOM_MARGIN));
}

function viewport() {
  if (typeof window === 'undefined') return { width: 1280, height: 760 };
  return { width: window.innerWidth, height: window.innerHeight };
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function nodePort(node: CanvasViewNode, side: 'left' | 'right') {
  return {
    x: side === 'right' ? node.x + NODE_WIDTH : node.x,
    y: node.y + NODE_HEIGHT / 2,
  };
}

function connectionPath(startX: number, startY: number, endX: number, endY: number) {
  const toRight = endX >= startX;
  const distance = Math.max(80, Math.abs(endX - startX));
  const curve = toRight ? distance * 0.42 : -distance * 0.42;
  return `M ${startX} ${startY} C ${startX + curve} ${startY}, ${endX - curve} ${endY}, ${endX} ${endY}`;
}

function nodeTitle(type: CanvasNodeType) {
  const titles = {
    text: '文本节点',
    image: '图片节点',
    video: '视频节点',
    audio: '音频节点',
  } satisfies Record<CanvasNodeType, string>;
  return titles[type];
}
