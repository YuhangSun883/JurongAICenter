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
import { useEffect, useState, type ComponentType, type MouseEvent, type PointerEvent } from 'react';
import { canvasApi, type CanvasNodeType } from '@/api/canvas';
import { cn } from '@/lib/utils';

type ToolId = 'select' | 'hand' | 'component' | 'template' | 'history' | 'help';

interface CanvasViewNode {
  id: string;
  type: CanvasNodeType;
  x: number;
  y: number;
  content?: string;
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
  const [addMenu, setAddMenu] = useState<AddMenuState | null>(null);
  const [nodes, setNodes] = useState<CanvasViewNode[]>([]);
  const [edges, setEdges] = useState<CanvasEdge[]>([]);
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null);
  const [prompt, setPrompt] = useState('');
  const [zoom, setZoom] = useState(100);
  const [dragging, setDragging] = useState<{ id: string; offsetX: number; offsetY: number } | null>(null);
  const [linkDrag, setLinkDrag] = useState<LinkDragState | null>(null);
  const [nodeMenu, setNodeMenu] = useState<NodeContextMenuState | null>(null);
  const [copiedNode, setCopiedNode] = useState<CanvasViewNode | null>(null);

  const activeNode = nodes.find((node) => node.id === activeNodeId) ?? null;

  useEffect(() => {
    if (!dragging) return;

    const onPointerMove = (event: globalThis.PointerEvent) => {
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
    };

    const onPointerUp = () => setDragging(null);

    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
    return () => {
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
    };
  }, [dragging]);

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
    const created = await canvasApi.createNode({ type, title: nodeTitle(type) });
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

  const requestGeneration = async () => {
    if (!activeNode) return;
    const result = await canvasApi.generateNode({
      nodeId: activeNode.id,
      type: activeNode.type,
      prompt,
      content: activeNode.content,
      assetIds: edges.filter((edge) => edge.to === activeNode.id).map((edge) => edge.from),
    });

    if (activeNode.type === 'text' && result.text) {
      updateNodeContent(activeNode.id, result.text);
    }
  };

  return (
    <main className="relative h-screen min-h-[640px] overflow-hidden bg-[#f1f2f4] text-[#5e6878]">
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
            hiddenHandleSide={linkDrag?.sourceId === node.id ? linkDrag.side : null}
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
              setDragging({
                id: node.id,
                offsetX: event.clientX - node.x,
                offsetY: event.clientY - node.y,
              });
            }}
          />
        ))
      )}

      {activeNode && (
        <PromptComposer
          node={activeNode}
          value={prompt}
          referenceCount={edges.filter((edge) => edge.to === activeNode.id).length}
          onChange={setPrompt}
          onSubmit={requestGeneration}
        />
      )}
      {addMenu && <AddCanvasMenu menu={addMenu} onAddNode={addNode} />}
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
          to.x >= from.x ? from.x + NODE_WIDTH : from.x,
          from.y + NODE_HEIGHT / 2,
          to.x >= from.x ? to.x : to.x + NODE_WIDTH,
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
      className={cn('absolute z-30 cursor-grab select-none active:cursor-grabbing', active && 'z-40')}
      style={{ left: node.x, top: node.y, width: NODE_WIDTH }}
      onPointerDown={onDragStart}
      onContextMenu={onContextMenu}
      onClick={onActivate}
    >
      {active && <NodeTopActions type={node.type} count={(node.content ?? '').trim().length} />}
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

function NodeTopActions({ type, count }: { type: CanvasNodeType; count: number }) {
  if (type === 'image') {
    return (
      <div className="absolute -top-[78px] left-1/2 flex h-10 -translate-x-1/2 items-center gap-3 whitespace-nowrap rounded-md border border-[#e1e5ea] bg-white px-4 text-xs font-medium text-[#4e5a6c] shadow-[0_12px_34px_rgba(52,62,78,0.10)]">
        <button type="button" data-no-node-drag="true" className="inline-flex items-center gap-1.5 hover:text-[#2f78ff]">
          <Upload className="h-3.5 w-3.5" />
          <span>上传</span>
        </button>
        <button type="button" data-no-node-drag="true" className="inline-flex items-center gap-1.5 hover:text-[#2f78ff]">
          <FolderOpen className="h-3.5 w-3.5" />
          <span>从资产库选择</span>
        </button>
      </div>
    );
  }

  return (
    <div className="absolute -top-[78px] left-1/2 flex h-10 -translate-x-1/2 items-center whitespace-nowrap rounded-md border border-[#e1e5ea] bg-white text-xs text-[#4e5a6c] shadow-[0_12px_34px_rgba(52,62,78,0.10)]">
      <span className="px-4 font-medium">{count} 字</span>
      <span className="h-5 w-px bg-[#e7e9ee]" />
      <button type="button" data-no-node-drag="true" className="inline-flex h-full items-center gap-1.5 px-4 text-[#a2abb8] hover:text-[#2f78ff]">
        <Sparkles className="h-3.5 w-3.5" />
        <span>帮我写</span>
      </button>
      <span className="h-5 w-px bg-[#e7e9ee]" />
      <button type="button" data-no-node-drag="true" className="inline-flex h-full items-center gap-1.5 px-4 font-medium hover:text-[#2f78ff]">
        <Expand className="h-3.5 w-3.5" />
        <span>展开</span>
      </button>
    </div>
  );
}

function PromptComposer({
  node,
  value,
  referenceCount,
  onChange,
  onSubmit,
}: {
  node: CanvasViewNode;
  value: string;
  referenceCount: number;
  onChange: (value: string) => void;
  onSubmit: () => void;
}) {
  const isText = node.type === 'text';
  const { width, height } = viewport();
  const left = clamp(node.x + NODE_WIDTH / 2 - PROMPT_WIDTH / 2, 84, Math.max(84, width - PROMPT_WIDTH - 84));
  const top = clamp(
    node.y + NODE_HEIGHT + PROMPT_GAP,
    178,
    Math.max(178, height - PROMPT_HEIGHT - PROMPT_BOTTOM_MARGIN)
  );

  return (
    <div
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
        placeholder={
          isText
            ? '可连续添加素材并 @引用，描述你想生成的文本。例如：提炼这款保温杯的核心卖点，写成适合电商详情页的短文。'
            : '可连续添加素材并 @引用，描述你想生成或编辑的图片。例如：生成一张电商主图，突出商品质感、使用场景和促销氛围。'
        }
        className={cn(
          'h-[118px] w-full resize-none bg-transparent pr-8 text-sm leading-6 text-[#4b5565] outline-none placeholder:text-[#606b7c]',
          referenceCount > 0 && 'pl-[74px]'
        )}
      />
      <div className="absolute right-4 top-4 flex flex-col gap-5 text-[#9aa6b7]">
        <button type="button" className="hover:text-[#2f78ff]" title="展开">
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
      {isText ? null : (
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
      )}
      <div className="absolute bottom-4 right-4 flex items-center gap-2">
        <span className="text-xs font-medium text-[#536072]">
          <span className="text-[#1a8cff]">✦</span> {isText ? '0.20' : '1.18'} / 条
        </span>
        <button
          type="button"
          onClick={onSubmit}
          className="grid h-9 w-9 place-items-center rounded-full bg-[#d9dee8] text-white transition hover:bg-[#2f78ff]"
          title="生成"
        >
          <span className="-mt-0.5 text-xl leading-none">↑</span>
        </button>
      </div>
    </div>
  );
}

function AddCanvasMenu({
  menu,
  onAddNode,
}: {
  menu: AddMenuState;
  onAddNode: (type: CanvasNodeType) => void;
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
      <CanvasMenuItem icon={Upload} label="从本地上传" />
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
