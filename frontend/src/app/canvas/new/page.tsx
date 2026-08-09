'use client';

import {
  BoxSelect,
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
} from 'lucide-react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useEffect, useRef, useState, type ChangeEvent, type ComponentType, type DragEvent as ReactDragEvent, type MouseEvent, type PointerEvent } from 'react';
import { canvasApi, type CanvasNodeType } from '@/api/canvas';
import { getAccessToken } from '@/lib/auth-store';
import { cn } from '@/lib/utils';
import { LoginGate } from '@/components/common/LoginGate';

type ToolId = 'select' | 'hand' | 'component' | 'template' | 'history' | 'help';

interface CanvasViewNode {
  id: string;
  type: CanvasNodeType;
  x: number;
  y: number;
  content?: string;
  resultUrl?: string;
  /** 每个节点独立的 prompt 输入（不跨节点共享） */
  prompt?: string;
}

interface CanvasEdge {
  id: string;
  from: string;
  to: string;
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
  const searchParams = useSearchParams();

  // 从 URL ?canvasId=xxx 读画布 ID,有就调 getCanvasDetail 加载
  // 触发时机:进入页面 / URL 变化
  useEffect(() => {
    const urlCanvasId = searchParams.get('canvasId');
    if (!urlCanvasId) return;
    // 已经在加载这个画布了就不重复
    if (canvasId === urlCanvasId) return;
    setCanvasId(urlCanvasId);
    void canvasApi.getCanvasDetail(urlCanvasId)
      .then((detail) => {
        if (detail?.nodes) setNodes(detail.nodes);
        if (detail?.edges) setEdges(detail.edges);
      })
      .catch((err) => console.warn('[canvas-new] load from URL failed:', err));
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
  const [isDragOver, setIsDragOver] = useState(false);
  const [uploading, setUploading] = useState(false);

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
      setCanvasHistory(list);
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
      if (detail?.nodes) setNodes(detail.nodes);
      if (detail?.edges) setEdges(detail.edges);
    } catch (err) {
      console.warn('[canvas] getCanvasDetail failed:', err);
    }
  }

  /** 隐藏的 <input type="file"> ref，供菜单/画布触发本地选择 */
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // 加载本地缓存的画布节点(刷新不丢，弥补后端没有 getCanvasDetail 入口时的本地持久化)
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      const raw = localStorage.getItem('canvas_nodes');
      if (raw) setNodes(JSON.parse(raw) as CanvasViewNode[]);
      const rawEdges = localStorage.getItem('canvas_edges');
      if (rawEdges) setEdges(JSON.parse(rawEdges) as CanvasEdge[]);
    } catch { /* ignore */ }
  }, []);

  // 节点变化时写 localStorage(延迟 200ms 防抖，避免每次拖动都写盘)
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const t = setTimeout(() => {
      localStorage.setItem('canvas_nodes', JSON.stringify(nodes));
    }, 200);
    return () => clearTimeout(t);
  }, [nodes]);

  // 连线变化时写 localStorage
  useEffect(() => {
    if (typeof window === 'undefined') return;
    localStorage.setItem('canvas_edges', JSON.stringify(edges));
  }, [edges]);

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
      if (detail?.nodes) {
        setNodes(detail.nodes);
      }
      if (detail?.edges) {
        setEdges(detail.edges);
      }
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
      setAddMenu({
        x: event.clientX + 12,
        y: event.clientY + 10,
        sourceId: linkDrag.sourceId,
        title: '引用节点',
      });
      setLinkDrag(null);
    };

    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
    return () => {
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
    };
  }, [linkDrag]);

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
    const created = await canvasApi.createNode({
      type,
      title: nodeTitle(type),
      upstreamIds: source ? [source.id] : undefined,
    });
    const position = source
      ? {
          x: source.x + 430,
          y: source.y + Math.min(54, Math.max(-54, nodes.length % 2 === 0 ? -34 : 34)),
        }
      : centeredNodePosition();
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
    if (!activeNode || generating) return;
    const nodePrompt = (activeNode.prompt ?? '').trim();
    const upstreamCount = edges.filter((edge) => edge.to === activeNode.id).length;
    // 有上游节点:允许空 prompt(后端 mergePrompts 会用上游内容 fallback)
    // 无上游节点:必须输入 prompt(否则 AI 端没东西可加工)
    if (!nodePrompt && upstreamCount === 0) {
      setGenerateError('请先输入提示词，或从节点左侧/右侧 + 拖一个上游节点来引用');
      return;
    }
    setGenerating(true);
    setGenerateError('');

    try {
      // 1. 提交生成任务（后端立刻返回 pending 状态）
      const initial = await canvasApi.generateNode({
        nodeId: activeNode.id,
        type: activeNode.type,
        prompt: nodePrompt,
        content: activeNode.content,
        assetIds: edges.filter((edge) => edge.to === activeNode.id).map((edge) => edge.from),
      });

      // 2. 轮询任务状态（按节点类型分档超时）
      const POLL_INTERVAL = 2000;
      // 文本：200s（NewAPI 润色需时 60-180s，frontend 要多给点 buffer）
      // 图片：300s（5 分钟，gpt-image 实际需要）
      // 视频：600s（10 分钟，NewAPI waitForVideo 允许更长）
      // 后端超时:NewApiClient.chatCompletion = 180s，imagePollTimeoutSec = 600s，videoPollTimeoutSec = 1200s
      const MAX_DURATION =
        activeNode.type === 'text'  ? 200_000 :
        activeNode.type === 'image' ? 300_000 :
        /* video */                  600_000;
      const start = Date.now();
      let lastResult = initial;

      while (Date.now() - start < MAX_DURATION) {
        await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL));
        try {
          lastResult = await canvasApi.getTask(initial.taskId);
        } catch (pollErr) {
          // 轮询中途网络错误：继续重试，不直接报错
          console.warn('[canvas] poll failed, retrying:', pollErr);
          continue;
        }

        if (lastResult.status === 'success') {
          // 3. 成功：根据节点类型更新 UI
          if (activeNode.type === 'text' && lastResult.text) {
            updateNodeContent(activeNode.id, lastResult.text);
          } else if ((activeNode.type === 'image' || activeNode.type === 'video')
                     && lastResult.resultUrl) {
            setNodes((current) =>
              current.map((n) =>
                n.id === activeNode.id ? { ...n, resultUrl: lastResult.resultUrl } : n
              )
            );
          }
          return;
        }

        if (lastResult.status === 'failed') {
          setGenerateError(`生成失败：${lastResult.status}`);
          return;
        }
        // pending / running 继续轮询
      }

      setGenerateError('生成超时，请重试');
    } catch (err: any) {
      console.error('[canvas] generate error:', err);
      setGenerateError(err?.message || '生成出错，请重试');
    } finally {
      setGenerating(false);
    }
  };

  // ===== 视频抽帧描述 / 脚本拆解 =====

  // 顶部工具栏目标节点：只有激活节点是视频时才显示，否则隐藏
  const videoToolbarTarget =
    activeNode && activeNode.type === 'video' && activeNode.resultUrl
      ? activeNode
      : null;

  type ExtractMode = 'script' | 'frames' | 'both';
  const handleExtractCaption = async (node: CanvasViewNode | null, mode: ExtractMode) => {
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
    setGenerating(true);
    setGenerateError('');

    const pollTask = async (taskId: string) => {
      try {
        const data: {
          status?: string;
          text?: string;
          message?: string;
        } = await fetch(`/api/canvas/tasks/${taskId}`, {
          credentials: 'include',
          headers: (() => {
            const t = getAccessToken();
            return t ? { Authorization: `Bearer ${t}` } : {};
          })(),
        }).then((r) => r.json());

        if (data.status === 'success') {
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
            } catch (mergeErr) {
              console.warn('[canvas] merge new nodes failed:', mergeErr);
            }
          }
          setGenerating(false);
          return;
        }
        if (data.status === 'failed') {
          setGenerateError(`${mode === 'frames' ? '抽帧' : '脚本拆解'}失败: ${data.message || '未知错误'}`);
          setGenerating(false);
          return;
        }
        // pending / running —— 2 秒后再问
        setTimeout(() => pollTask(taskId), 2000);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : '轮询失败';
        setGenerateError(`失败: ${msg}`);
        setGenerating(false);
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
        <Link
          href="/canvas"
          className="pointer-events-auto flex h-[38px] items-center gap-2 rounded-xl border border-[#e2e5ea] bg-white/90 px-2.5 shadow-[0_8px_22px_rgba(41,48,61,0.06)] backdrop-blur"
          aria-label="返回画布首页"
        >
          <span className="grid h-6 w-6 place-items-center rounded-lg bg-gradient-to-br from-[#ff3f9b] via-[#725bff] to-[#317bff] text-[11px] font-bold text-white shadow-sm">
            J
          </span>
          <span className="h-4 w-px bg-[#e2e5ea]" />
          <span className="pr-1 text-xs font-semibold text-[#4f5969]">未命名画布</span>
        </Link>

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
          onClick={async () => {
            // 「新建画布」按钮:每次点击都创建一个新画布(后端 createCanvas) + 清空当前节点
            // 历史列表会自动刷新显示新画布
            try {
              const item = await canvasApi.createCanvas({ name: '未命名画布' });
              setCanvasId(item.id);
              if (typeof window !== 'undefined') {
                localStorage.removeItem('canvas_nodes');
                localStorage.removeItem('canvas_edges');
              }
              setNodes([]);
              setEdges([]);
              setActiveNodeId(null);
              setGenerateError('');
              // 刷新历史侧栏(异步,失败不阻塞)
              void fetchCanvasHistory();
              console.info('[canvas] 新建画布成功: id=' + item.id);
            } catch (err) {
              console.warn('[canvas] createCanvas failed, fallback to clear:', err);
              // 创建失败就只清空本地(降级, 不影响用户继续画)
              if (typeof window !== 'undefined') {
                localStorage.removeItem('canvas_nodes');
                localStorage.removeItem('canvas_edges');
              }
              setNodes([]);
              setEdges([]);
              setActiveNodeId(null);
              setGenerateError('');
            }
          }}
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
          />
        ))
      )}

      {activeNode && (
        <PromptComposer
          node={activeNode}
          value={activeNode.prompt ?? ''}
          referenceCount={edges.filter((edge) => edge.to === activeNode.id).length}
          onChange={(value) => updateNodePrompt(activeNode.id, value)}
          onSubmit={requestGeneration}
          generating={generating}
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

function CanvasNodeCard({
  node,
  active,
  hiddenHandleSide,
  onActivate,
  onTextChange,
  onOpenMenu,
  onStartLinkDrag,
  onContextMenu,
  onDragStart,
}: {
  node: CanvasViewNode;
  active: boolean;
  hiddenHandleSide: 'left' | 'right' | null;
  onActivate: () => void;
  onTextChange: (value: string) => void;
  onOpenMenu: (node: CanvasViewNode, side: 'left' | 'right') => void;
  onStartLinkDrag: (node: CanvasViewNode, side: 'left' | 'right', event: PointerEvent<HTMLButtonElement>) => void;
  onContextMenu: (event: MouseEvent<HTMLDivElement>) => void;
  onDragStart: (event: PointerEvent<HTMLDivElement>) => void;
}) {
  const isText = node.type === 'text';

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
                  setExpandedImage({ url: node.resultUrl!, title: node.title || nodeTitle(node.type) });
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
        ) : node.resultUrl ? (
          // 生成成功：按类型渲染 <video> 或 <img>
          // 注意:img/video 不能加 data-no-node-drag="true",否则点击图片区域无法拖动节点
          node.type === 'video' ? (
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
                  setExpandedImage({ url: node.resultUrl!, title: nodeTitle(node.type) });
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
                  setExpandedImage({ url: node.resultUrl!, title: nodeTitle(node.type) });
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
            title="上传图片"
          >
            <ImageIcon className="h-8 w-8" />
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
}: {
  node: CanvasViewNode;
  value: string;
  referenceCount: number;
  onChange: (value: string) => void;
  onSubmit: () => void;
  generating?: boolean;
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
      <textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        className={cn(
          'h-[118px] w-full resize-none bg-transparent pr-8 text-sm leading-6 text-[#4b5565] outline-none placeholder:text-[#606b7c]',
          referenceCount > 0 && 'pl-[74px]'
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
        <button type="button" className="hover:text-[#2f78ff]" title="上传素材">
          <Upload className="h-4 w-4" />
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
        // 视频节点：模型 / 时长 / 分辨率
        <div className="absolute bottom-5 left-4 flex items-center gap-2 text-xs font-medium text-[#516074]">
          <button type="button" className="inline-flex items-center gap-1 hover:text-[#2f78ff]">
            <Sparkles className="h-3 w-3" />
            <span>doubao-seedance</span>
          </button>
          <span className="text-[#b4bdc9]">⌄</span>
          <button type="button" className="inline-flex items-center gap-1 hover:text-[#2f78ff]">
            <span>4 秒</span>
          </button>
          <span className="text-[#b4bdc9]">⌄</span>
          <button type="button" className="inline-flex items-center gap-1 hover:text-[#2f78ff]">
            <span>480P</span>
          </button>
          <span className="text-[#b4bdc9]">⌄</span>
        </div>
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
