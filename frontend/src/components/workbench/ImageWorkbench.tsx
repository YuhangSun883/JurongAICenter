'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Bot,
  Check,
  ChevronDown,
  ChevronRight,
  Coins,
  Download,
  Edit2,
  FolderOpen,
  Image as ImageIcon,
  Layers3,
  List,
  ListChecks,
  Loader2,
  Menu,
  MessageCircleQuestion,
  PanelRightClose,
  Play,
  Plus,
  Save,
  Sparkles,
  Star,
  Trash2,
  X,
} from 'lucide-react';
import { nanoid } from 'nanoid';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { MediaPreviewDialog } from '@/components/common/MediaPreviewDialog';
import { ReferenceMediaThumbnail } from '@/components/common/ReferenceMediaThumbnail';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
import { cn } from '@/lib/utils';
import { imageApi, mediaApi, promptApi } from '@/api';
import type { UserPromptResult } from '@/api/prompt';

/** 图片生成超时时间：5 分钟（300 秒） */
const GENERATE_TIMEOUT_SECONDS = 300;

const MAX_REFS = 9;
const MAX_PROMPT = 2000;

type ImageModel = '高级版 VIP' | '高级版' | '标准版';
type ImageRatio = '自适应' | '1:1' | '3:4' | '4:3' | '9:16' | '16:9';
type ImageResolution = '1K' | '2K' | '4K';
type ImageFormat = 'JPEG' | 'PNG';

const MODELS: ImageModel[] = ['高级版 VIP', '高级版', '标准版'];
const RATIOS: ImageRatio[] = ['自适应', '1:1', '3:4', '4:3', '9:16', '16:9'];
const RESOLUTIONS: ImageResolution[] = ['1K', '2K', '4K'];
const FORMATS: ImageFormat[] = ['JPEG', 'PNG'];

/** 技能分类：决定生成图片的主要方向 */
interface ReferenceSlot {
  label: string;
  optional?: boolean;
}
interface SkillItem {
  id: string;
  name: string;
  description: string;
  tag: string; // 注入到提示词中的标签
  category?: string; // 所属分类名称（搜索菜单中使用）
  referenceSlots?: ReferenceSlot[]; // 技能专属的参考图片槽位
  maxReferences?: number; // 最大参考图片数
  hintText?: string; // 上传图片的提示文字
}
interface SkillCategory {
  id: string;
  name: string;
  skills: SkillItem[];
}

const SKILL_CATEGORIES: SkillCategory[] = [
  {
    id: 'character',
    name: '人物',
    skills: [
      { id: 'face-3view', name: '脸部三视图', description: '生成正视、侧脸、半侧脸参考', tag: '[脸部三视图]',
        referenceSlots: [{ label: '添加人脸参考图' }], maxReferences: 3,
        hintText: '上传一张清晰的人脸照片，AI 将生成三视图参考。' },
      { id: 'character-4view', name: '人物四视图', description: '生成全身三视图与脸部特写', tag: '[人物四视图]',
        referenceSlots: [{ label: '添加人物照片' }], maxReferences: 4,
        hintText: '上传人物全身照片，AI 将生成四视图与脸部特写。' },
      { id: 'model-face-swap', name: '模特换脸', description: '模特脸部替换为其他人物', tag: '[模特换脸]',
        referenceSlots: [{ label: '添加模特', optional: true }, { label: '添加目标人脸' }], maxReferences: 2,
        hintText: '上传模特图和目标人脸图，实现换脸效果。' },
      { id: 'face-fusion', name: '人脸融合', description: '融合两张人脸生成全新虚拟头像资产', tag: '[人脸融合]',
        referenceSlots: [{ label: '添加人脸A' }, { label: '添加人脸B' }], maxReferences: 2,
        hintText: '上传两张人脸照片，AI 将融合生成全新虚拟头像。' },
    ],
  },
  {
    id: 'product',
    name: '商品',
    skills: [
      { id: 'one-click-outfit', name: '一键换衣', description: '替换模特身上的服装衣物', tag: '[一键换衣]',
        referenceSlots: [
          { label: '添加模特' },
          { label: '添加上衣', optional: true },
          { label: '添加裤子', optional: true },
          { label: '添加包', optional: true },
        ], maxReferences: 6,
        hintText: '上传模特图，服装图，一键模特换衣' },
      { id: 'product-display', name: '商品展示', description: '商品多角度展示图生成', tag: '[商品展示]',
        referenceSlots: [{ label: '添加商品图' }], maxReferences: 9,
        hintText: '上传商品图片，AI 将生成多角度展示效果图。' },
      { id: 'model-pose', name: '模特姿势', description: '生成多种模特姿势姿态', tag: '[模特姿势]',
        referenceSlots: [{ label: '添加模特照片' }], maxReferences: 9,
        hintText: '上传模特照片，AI 将生成多种姿势姿态。' },
      { id: 'background-replace', name: '背景替换', description: '替换商品图中的背景', tag: '[背景替换]',
        referenceSlots: [{ label: '添加商品图' }, { label: '添加背景参考', optional: true }], maxReferences: 9,
        hintText: '上传商品图和目标背景，AI 将智能替换背景。' },
    ],
  },
  {
    id: 'enhance',
    name: '增强',
    skills: [
      { id: 'hd-enhance', name: '高清增强', description: '提升图片清晰度与细节', tag: '[高清增强]',
        referenceSlots: [{ label: '添加待增强图片' }], maxReferences: 9,
        hintText: '上传需要高清增强的图片，AI 将提升细节与清晰度。' },
      { id: 'style-transfer', name: '风格迁移', description: '艺术风格迁移与渲染', tag: '[风格迁移]',
        referenceSlots: [{ label: '添加原图' }, { label: '添加风格参考' }], maxReferences: 9,
        hintText: '上传原图和风格参考图，AI 将进行艺术风格迁移。' },
      { id: 'lighting', name: '光影增强', description: '增强图片光影效果', tag: '[光影增强]',
        referenceSlots: [{ label: '添加待处理图片' }], maxReferences: 9,
        hintText: '上传图片，AI 将增强光影效果，打造电影级视觉。' },
      { id: 'color-grade', name: '调色风格', description: '电影级调色风格处理', tag: '[调色风格]',
        referenceSlots: [{ label: '添加待调色图片' }], maxReferences: 9,
        hintText: '上传图片，AI 将进行电影级调色风格处理。' },
    ],
  },
];

// 将所有技能扁平化为列表，供 "/" 搜索时使用
const ALL_SKILLS: SkillItem[] = SKILL_CATEGORIES.flatMap((cat) =>
  cat.skills.map((s) => ({ ...s, category: cat.name }))
);

interface ImageTask {
  id: string;
  status: 'queued' | 'running' | 'succeeded' | 'failed';
  progress: number;
  prompt: string;
  referenceIds: string[];
  createdAt: number;
}

function mediaToPicked(file: File): GlobalMaterial {
  return {
    id: nanoid(10),
    type: file.type.startsWith('video') ? 'video' : file.type.startsWith('audio') ? 'audio' : 'image',
    url: URL.createObjectURL(file),
    name: file.name,
    size: file.size,
  };
}

/**
 * 基于"光标所在文本节点"检测触发字符（@ 或 /）
 * 从光标位置向前搜索，遇到空格/换行停止，确保只检测用户当前输入上下文
 * 避免芯片内的 "@图片名" 文本干扰 lastIndexOf 检测
 */
function getTriggerContext(): { char: '@' | '/' | null; query: string } {
  const selection = window.getSelection();
  if (!selection || selection.rangeCount === 0) return { char: null, query: '' };

  const range = selection.getRangeAt(0);
  if (!range.collapsed) return { char: null, query: '' };

  const container = range.startContainer;
  // 只在文本节点中检测，跳过元素节点（如芯片）
  if (container.nodeType !== Node.TEXT_NODE) return { char: null, query: '' };

  const textNode = container as Text;
  const offset = range.startOffset;
  const textBeforeCursor = textNode.data.substring(0, offset);

  // 从光标位置向前查找 @ 或 /，遇到空格/换行停止
  let triggerIdx = -1;
  let triggerChar: '@' | '/' | null = null;
  for (let i = textBeforeCursor.length - 1; i >= 0; i--) {
    const ch = textBeforeCursor[i];
    if (ch === '@' || ch === '/') {
      triggerIdx = i;
      triggerChar = ch as '@' | '/';
      break;
    }
    if (/\s/.test(ch)) break; // 遇到空格/换行停止搜索
  }

  if (triggerIdx === -1 || triggerChar === null) {
    return { char: null, query: '' };
  }

  const query = textBeforeCursor.substring(triggerIdx + 1);
  return { char: triggerChar, query };
}

export function ImageWorkbench() {
  const { materials, addMaterials, removeMaterial } = useMaterials();
  const [prompt, setPrompt] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [references, setReferences] = useState<PickedMedia[]>([]);
  const [referencePreview, setReferencePreview] = useState<PickedMedia | null>(null);
  const [model, setModel] = useState<ImageModel>('高级版 VIP');
  const [ratio, setRatio] = useState<ImageRatio>('自适应');
  const [resolution, setResolution] = useState<ImageResolution>('1K');

  /**
   * Agent 模块跳转过来的预填：
   *   URL 参数：
   *     prefill=true
   *     prompt=xxx
   *     attachmentIds=assetId1,assetId2
   *
   * 行为：
   *   1. 自动填入 prompt
   *   2. 自动把 attachmentIds 转成 PickedMedia 加到 references 列表
   */
  const [prefillApplied, setPrefillApplied] = useState(false);
  useEffect(() => {
    if (prefillApplied) return;
    if (typeof window === 'undefined') return;
    const params = new URLSearchParams(window.location.search);
    if (params.get('prefill') !== 'true') {
      setPrefillApplied(true); // 标记已处理，下次直接跳过
      return;
    }

    // 1) 填 prompt
    const p = params.get('prompt');
    if (p) {
      setPrompt(p);
      // 同步到 contenteditable 的 DOM（不然编辑器显示空白）
      // 等 React 把组件挂载完再设
      requestAnimationFrame(() => {
        if (editorRef.current) {
          editorRef.current.innerText = p;
        }
      });
    }

    // 2) 加素材到 references
    const idsStr = params.get('attachmentIds');
    if (idsStr) {
      const ids = idsStr.split(',').filter(Boolean);
      if (ids.length > 0) {
        // 异步加载素材详情
        (async () => {
          const picked: PickedMedia[] = [];
          for (const id of ids) {
            try {
              // getAsset 期望 number，但 URL 里来的是 string，强转一下
              const numericId = Number(id);
              if (!Number.isFinite(numericId)) continue;
              const asset = await mediaApi.getAsset(numericId);
              if (asset) {
                picked.push({
                  id: String(asset.id),
                  url: asset.url,
                  type: asset.type as 'image' | 'video' | 'audio',
                  name: asset.name,
                });
              }
            } catch (e) {
              console.warn('[ImageWorkbench] failed to load prefill asset', id, e);
            }
          }
          if (picked.length > 0) {
            setReferences((prev) => [...prev, ...picked]);
            // 顺手加到 materials 上下文
            addMaterials(picked.map((p) => ({
              id: p.id,
              url: p.url,
              type: p.type,
              name: p.name,
            })));

            // 2026-08-14 增强:把 prefill 的图片作为 @图片1 chip 插入到 prompt 编辑器,
            //   这样用户能在 prompt 文本里"看到"哪张图被引用了。
            // 复用 selectImageReference 逻辑（确保编号与 references 数组一致）:
            //   1) 先 setReferences（line 272）
            //   2) 用 setTimeout 推到下一个事件循环,等 React 完成 setState
            //   3) 调 selectImageReference 把 chip 插到 contenteditable
            setTimeout(() => {
              try {
                for (const p of picked) {
                  selectImageReference(p);
                }
                console.log('[ImageWorkbench] prefill: inserted @ image chips to editor (via selectImageReference)');
              } catch (e) {
                console.warn('[ImageWorkbench] failed to insert @ chips via selectImageReference:', e);
              }
            }, 50);
          }
        })();
      }
    }

    // 清掉 URL 参数（避免刷新页面再次触发）
    if (typeof window.history?.replaceState === 'function') {
      const cleanUrl = window.location.pathname;
      window.history.replaceState({}, '', cleanUrl);
    }
    setPrefillApplied(true);
  }, [prefillApplied, addMaterials]);
  const [format, setFormat] = useState<ImageFormat>('JPEG');
  const [tasks, setTasks] = useState<ImageTask[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // 侧栏折叠状态（仿 AI 视频侧栏，折叠后只显示展开按钮）
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  // AI 图片生成相关状态
  const [generatedUrl, setGeneratedUrl] = useState<string | null>(null);
  const [generatedAt, setGeneratedAt] = useState<number | null>(null); // 当前预览图片的生成时间（毫秒）
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  // 当前正在生成任务的元信息（用于切走后再回来仍显示"生成中"占位卡）
  const ACTIVE_GEN_KEY = 'image-workbench:active-generation';
  const [activeGeneration, setActiveGeneration] = useState<{
    prompt: string;
    referencePreviewUrl?: string;
    startedAt: number;
  } | null>(() => {
    if (typeof window === 'undefined') return null;
    try {
      const raw = window.sessionStorage.getItem(ACTIVE_GEN_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      // 超过 10 分钟的任务视为过期（避免永久卡死显示）
      if (parsed && typeof parsed.startedAt === 'number'
          && Date.now() - parsed.startedAt < 10 * 60 * 1000) {
        return parsed;
      }
      window.sessionStorage.removeItem(ACTIVE_GEN_KEY);
      return null;
    } catch {
      return null;
    }
  });

  // 同步 activeGeneration 到 sessionStorage
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      if (activeGeneration) {
        window.sessionStorage.setItem(ACTIVE_GEN_KEY, JSON.stringify(activeGeneration));
      } else {
        window.sessionStorage.removeItem(ACTIVE_GEN_KEY);
      }
    } catch {
      // 静默忽略
    }
  }, [activeGeneration]);
  // 历史生成图片记录（侧栏状态栏下方缩略图卡片使用）
  // 用 sessionStorage 缓存：组件卸载/页面刷新/切换功能后回来都能立即恢复，不需等待接口
  const HISTORY_CACHE_KEY = 'image-workbench:generation-history:v2';
  const HISTORY_CACHE_KEY_LEGACY = 'image-workbench:generation-history'; // 旧版（脏数据），启动时清理
  // 清理旧版本缓存（key 升级时一次性迁移）
  if (typeof window !== 'undefined') {
    try {
      window.sessionStorage.removeItem(HISTORY_CACHE_KEY_LEGACY);
    } catch {
      // 静默忽略
    }
  }
  // 按 url 严格去重（保留首次出现的）
  // 双键去重：同一 item 的 objectKey 和 url 都加入"已见集合"，任何一方命中都视为重复。
  // 这样：
  //   - 后端拉取（带 objectKey）会与本地新生成（无 objectKey 但同 url）正确合并
  //   - 同一 objectKey 多次返回（url 因预签名不同）也能正确去重
  const dedupeHistory = (
    list: { id: string; url: string; objectKey?: string; prompt: string; createdAt: number }[]
  ) => {
    const seenKeys = new Set<string>();
    const seenUrls = new Set<string>();
    const out: typeof list = [];
    for (const item of list) {
      const ok = item.objectKey;
      const url = item.url;
      const dupByKey = ok && seenKeys.has(ok);
      const dupByUrl = url && seenUrls.has(url);
      if (!ok && !url) continue; // 没有任何可识别键，跳过
      if (dupByKey || dupByUrl) continue;
      if (ok) seenKeys.add(ok);
      if (url) seenUrls.add(url);
      out.push(item);
    }
    return out;
  };
  const [generationHistory, setGenerationHistory] = useState<{ id: string; url: string; objectKey?: string; prompt: string; createdAt: number }[]>(() => {
    if (typeof window === 'undefined') return [];
    try {
      const raw = window.sessionStorage.getItem(HISTORY_CACHE_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return [];
      return dedupeHistory(parsed).sort((a, b) => b.createdAt - a.createdAt).slice(0, 50);
    } catch {
      return [];
    }
  });

  // 同步 history 到 sessionStorage（切换功能/刷新后回来能立即看到）
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      // 写入前再做一次去重兜底，防止历史 sessionStorage 脏数据持续污染
      const cleaned = dedupeHistory(generationHistory)
        .sort((a, b) => b.createdAt - a.createdAt)
        .slice(0, 50);
      window.sessionStorage.setItem(HISTORY_CACHE_KEY, JSON.stringify(cleaned));
    } catch {
      // 配额超限等异常静默忽略
    }
  }, [generationHistory]);

  // 组件挂载时加载后端历史：从 media_assets 表中取 type=image, source=ai-generated 的图片
  // 与本地缓存按 url 合并去重，再按 createdAt 倒序
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const page = await mediaApi.listAssets({ type: 'image', source: 'ai-generated', page: 1, pageSize: 50 });
        if (cancelled) return;
        const remote = (page?.items ?? []).map((it) => {
          const createdAt = it.createdAt ? new Date(it.createdAt).getTime() || Date.now() : Date.now();
          return {
            id: `server-${it.id}`,
            url: it.url,
            objectKey: it.objectKey,
            prompt: it.name || '',
            createdAt,
          };
        });
        // 合并：远端为权威数据 + 保留本地"更新"的条目（用户会话期间新生成但远端尚未返回的）
        // 关键：避免远端已有数据时本地重复出现。
        setGenerationHistory((prev) => {
          if (remote.length === 0) {
            // 远端没拉取到（如失败），保留本地
            return dedupeHistory(prev).sort((a, b) => b.createdAt - a.createdAt).slice(0, 50);
          }
          // 远端有数据 → 完全以远端为准，本地"比远端还新"的（createdAt > 远端最大）追加在前
          const maxRemoteCreated = remote.reduce((m, r) => Math.max(m, r.createdAt), 0);
          const localNewer = prev.filter((p) => p.createdAt > maxRemoteCreated);
          const merged = [...localNewer, ...remote];
          return dedupeHistory(merged).sort((a, b) => b.createdAt - a.createdAt).slice(0, 50);
        });
      } catch (e) {
        console.warn('[ImageWorkbench] 加载生成历史失败:', e);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);
  const [isFavorited, setIsFavorited] = useState(false); // 当前生成图片是否已收藏
  const [currentFavoriteId, setCurrentFavoriteId] = useState<string | null>(null); // 当前生成图片对应的收藏 ID（MinIO objectKey）
  const [showNewConfirm, setShowNewConfirm] = useState(false); // 新建确认弹窗
  const [activeTab, setActiveTab] = useState<'preview' | 'favorites'>('preview'); // 预览/收藏 Tab
  // 收藏图片列表：从后端加载，包含 MinIO objectKey、URL、收藏时间
  const [favorites, setFavorites] = useState<{ objectKey: string; url: string; createdAt: number }[]>([]);
  const [favoriting, setFavoriting] = useState(false); // 收藏操作进行中
  const [loadingFavorites, setLoadingFavorites] = useState(false); // 加载收藏列表中
  const [deletingIds, setDeletingIds] = useState<Set<string>>(new Set()); // 正在删除的图片ID集合
  const abortRef = useRef<AbortController | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // 提示词相关状态
  const [showSavePromptDialog, setShowSavePromptDialog] = useState(false); // 保存提示词弹窗
  const [showEditPromptDialog, setShowEditPromptDialog] = useState(false); // 编辑提示词弹窗
  const [editingPromptId, setEditingPromptId] = useState<number | null>(null); // 正在编辑的提示词 ID
  const [editingTitle, setEditingTitle] = useState(''); // 编辑中的标题
  const [editingContent, setEditingContent] = useState(''); // 编辑中的内容
  const [showMyPromptsDialog, setShowMyPromptsDialog] = useState(false); // 我的提示词弹窗
  const [myPrompts, setMyPrompts] = useState<UserPromptResult[]>([]); // 我的提示词列表
  const [loadingPrompts, setLoadingPrompts] = useState(false); // 加载提示词中
  const [savingPrompt, setSavingPrompt] = useState(false); // 保存/更新提示词中
  const [saveTitleInput, setSaveTitleInput] = useState(''); // 保存弹窗中的标题输入

  // 技能选择相关状态
  const [selectedSkill, setSelectedSkill] = useState<SkillItem | null>(null); // 当前选中的技能
  const [showSkillPanel, setShowSkillPanel] = useState(false); // 顶部下拉技能面板
  const [showSlashMenu, setShowSlashMenu] = useState(false); // 输入"/"触发的技能菜单
  const [activeCategory, setActiveCategory] = useState<string>('character'); // 当前激活的技能分类
  const [slashQuery, setSlashQuery] = useState(''); // "/" 菜单搜索关键词
  const editorRef = useRef<HTMLDivElement | null>(null); // contenteditable div 引用

  // "@" 引用上传图片相关状态
  const [showAtMenu, setShowAtMenu] = useState(false); // 输入"@"触发的图片引用菜单
  const [atQuery, setAtQuery] = useState(''); // "@" 菜单搜索关键词

  // 已引用的图片列表：用于在编辑器中显示芯片（chip）
  // 每个引用包含：源图片ID、名称、缩略图URL、引用序号（@1, @2, ...）
  const [referencedImages, setReferencedImages] = useState<{
    refId: string;
    name: string;
    url: string;
    index: number;
  }[]>([]);

  // 光标位置（相对于编辑器容器）：用于让 "/" 和 "@" 菜单跟随光标
  const menuPosRef = useRef<{ x: number; y: number }>({ x: 0, y: 0 });
  const [menuPos, setMenuPos] = useState<{ x: number; y: number }>({ x: 0, y: 0 });
  // 编辑器容器引用：用于计算菜单相对坐标
  const editorContainerRef = useRef<HTMLDivElement | null>(null);

  // 获取当前光标在编辑器容器中的相对坐标
  const updateMenuPosition = useCallback(() => {
    const editor = editorRef.current;
    const container = editorContainerRef.current;
    if (!editor || !container) return;

    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) return;

    const range = selection.getRangeAt(0);
    // 使用 Range 的 clientRects 获取光标 bounding rect
    let rect: DOMRect | null = null;
    if (range.getClientRects().length > 0) {
      rect = range.getClientRects()[0];
    } else if (range.collapsed) {
      // 折叠选区且没有 rects，创建一个临时 span 获取坐标
      const temp = document.createElement('span');
      temp.appendChild(document.createTextNode('\u200b'));
      range.insertNode(temp);
      rect = temp.getBoundingClientRect();
      temp.remove();
      // 恢复选区
      const newRange = document.createRange();
      newRange.selectNodeContents(editor);
      newRange.collapse(false);
      selection.removeAllRanges();
      selection.addRange(newRange);
    }
    if (!rect) return;

    const containerRect = container.getBoundingClientRect();
    // 计算相对于编辑器容器的坐标，菜单显示在光标右下方
    const x = rect.left - containerRect.left;
    const y = rect.bottom - containerRect.top + 6; // 下方留 6px 间距
    menuPosRef.current = { x, y };
    setMenuPos({ x, y });
  }, []);

  // 判断编辑器中是否有内容（文字或图片），有内容时点击新建需要弹窗确认
  const hasEditorContent = useMemo(() => {
    return prompt.trim().length > 0 || references.length > 0 || referencedImages.length > 0 || generating || !!generatedUrl;
  }, [prompt, references, referencedImages, generating, generatedUrl]);

  // 执行新建操作：清空编辑器状态
  const handleNewConfirm = useCallback(() => {
    setPrompt('');
    setReferences([]);
    setSelectedTaskId(null);
    setGeneratedUrl(null);
    setGenerateError(null);
    setIsFavorited(false);
    setCurrentFavoriteId(null); // 重置收藏 ID
    setSelectedSkill(null);
    setShowSkillPanel(false);
    setShowSlashMenu(false);
    setSlashQuery('');
    setShowAtMenu(false); // 重置 "@" 菜单状态
    setAtQuery('');
    setReferencedImages([]); // 重置已引用图片列表
    setShowNewConfirm(false);
  }, []);

  // 选择技能：在编辑器中插入芯片，并决定生成方向
  // 使用 DOM Range 操作替换正在输入的 "/xxx" 文本，确保中文输入法、HTML 实体、
  // 多个文本节点等场景下都能可靠地把 "/" 和技能名替换为芯片
  const selectSkill = useCallback((skill: SkillItem) => {
    setSelectedSkill(skill);
    // 切换技能时清空已上传的参考图片
    setReferences([]);
    setReferencedImages([]);

    const editor = editorRef.current;
    if (!editor) {
      setPrompt((prev) => {
        const cleaned = prev.replace(/^\[.*?\]\s*/, '').replace(/\/\w*\s*/, '');
        return `${skill.tag} ${cleaned}`.trim();
      });
      return;
    }

    // 先移除已有的技能芯片
    editor.querySelectorAll('[data-skill-chip]').forEach((el) => el.remove());

    // 读取一次文本节点快照，避免后续 DOM 操作影响查找
    const textNodes: Text[] = [];
    const walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT);
    let node: Node | null = walker.nextNode();
    while (node) {
      const t = node as Text;
      if (t.data.length > 0) textNodes.push(t);
      node = walker.nextNode();
    }

    // 构建芯片 DOM（不再使用 innerHTML 拼接，避免实体编码问题）
    const chip = document.createElement('span');
    chip.contentEditable = 'false';
    chip.setAttribute('data-skill-chip', skill.id);
    chip.setAttribute('data-skill-name', skill.name);
    chip.setAttribute('data-skill-tag', skill.tag);
    chip.className =
      'inline-flex items-center gap-1 rounded-md border border-[#c7d4ff] bg-[#eef3ff] px-2 py-0.5 align-middle mx-0.5 text-xs leading-5 font-semibold text-[#3677ff] shadow-sm';
    const icon = document.createElement('span');
    icon.className = 'text-[#3677ff]';
    icon.textContent = '✦';
    const label = document.createElement('span');
    label.textContent = skill.name;
    chip.appendChild(icon);
    chip.appendChild(label);
    const trailingSpace = document.createTextNode('\u00A0');

    // 找到最后一个未被芯片包裹的 "/" 所在的文本节点
    let slashNode: Text | null = null;
    let slashOffset = -1;
    for (let i = textNodes.length - 1; i >= 0; i--) {
      const t = textNodes[i];
      const idx = t.data.lastIndexOf('/');
      if (idx !== -1) {
        slashNode = t;
        slashOffset = idx;
        break;
      }
    }

    if (slashNode) {
      // 计算需要被替换的 "查询文本"（包含 "/" 和后面直到空格/标签/末尾的字符）
      const beforeText = slashNode.data.substring(0, slashOffset);
      const after = slashNode.data.substring(slashOffset + 1);
      const queryMatch = after.match(/^[^\s<]*/);
      const queryLen = queryMatch ? queryMatch[0].length : 0;
      const afterQuery = after.substring(queryLen);

      // 用 Range 精确替换：从 "/" 开始到查询文本结束
      const replaceRange = document.createRange();
      replaceRange.setStart(slashNode, slashOffset);
      replaceRange.setEnd(slashNode, slashOffset + 1 + queryLen);
      replaceRange.deleteContents();

      // 在替换位置依次插入：芯片 + 尾随空格 + 原始查询之后的文本
      const insertFrag = document.createDocumentFragment();
      insertFrag.appendChild(chip);
      insertFrag.appendChild(trailingSpace);
      if (afterQuery) {
        insertFrag.appendChild(document.createTextNode(afterQuery));
      }
      replaceRange.insertNode(insertFrag);

      // 如果 "/" 之前没有其他文本且 slashNode 已空，移除该空文本节点
      if (!beforeText && slashNode.parentNode) {
        slashNode.parentNode.removeChild(slashNode);
      }
    } else {
      // 找不到 "/"，在编辑器开头插入芯片
      const frag = document.createDocumentFragment();
      frag.appendChild(chip);
      frag.appendChild(trailingSpace);
      if (editor.firstChild) {
        editor.insertBefore(frag, editor.firstChild);
      } else {
        editor.appendChild(frag);
      }
    }

    // 更新 prompt（移除旧技能标签与可能残留的 "/xxx"）
    const text = editor.innerText || '';
    const cleaned = text
      .replace(/\/[^\s<]*/g, '')
      .replace(/\s+/g, ' ')
      .trim();
    setPrompt(`${skill.tag} ${cleaned}`.trim());

    // 重新聚焦编辑器并将光标移到末尾
    requestAnimationFrame(() => {
      editor.focus();
      const range = document.createRange();
      range.selectNodeContents(editor);
      range.collapse(false);
      const sel = window.getSelection();
      sel?.removeAllRanges();
      sel?.addRange(range);
      // 触发一次 input 事件以同步 referencedImages 等派生状态
      const inputEvent = new Event('input', { bubbles: true });
      editor.dispatchEvent(inputEvent);
    });

    setShowSkillPanel(false);
    setShowSlashMenu(false);
    setSlashQuery('');
    setShowAtMenu(false); // 选择技能时关闭 "@" 菜单
  }, []);

  // "/" 菜单过滤后的技能列表
  const filteredSlashSkills = useMemo(() => {
    if (!slashQuery) return ALL_SKILLS.slice(0, 8);
    const lower = slashQuery.toLowerCase();
    return ALL_SKILLS.filter(
      (s) => s.name.toLowerCase().includes(lower) || s.description.toLowerCase().includes(lower)
    ).slice(0, 8);
  }, [slashQuery]);

  // "@" 菜单过滤后的上传图片列表（仅显示图片类型的素材）
  const filteredAtImages = useMemo(() => {
    const imageRefs = references.filter((ref) => {
      // 仅保留图片类型素材（排除视频/音频）
      return true;
    });
    if (!atQuery) return imageRefs.slice(0, 8);
    const lower = atQuery.toLowerCase();
    return imageRefs
      .filter((ref) => ref.name.toLowerCase().includes(lower))
      .slice(0, 8);
  }, [atQuery, references]);

  // 选择图片引用：在编辑器中插入芯片，替换正在输入的 "@xxx"
  // 注意：不直接更新 prompt 和 referencedImages，由 onInput 事件自动处理
  const selectImageReference = useCallback((ref: PickedMedia) => {
    // 在 contenteditable div 中插入芯片
    const editor = editorRef.current;
    if (!editor) {
      setPrompt((prev) => {
        const lastAtIdx = prev.lastIndexOf('@');
        if (lastAtIdx === -1) return prev;
        return prev.slice(0, lastAtIdx) + `@[${ref.name}] `;
      });
      setShowAtMenu(false);
      setAtQuery('');
      return;
    }

    // 用上方 references 中该 ref 的索引作为编号（1-based），保证芯片顺序与上方缩略图一致
    const refIdx = references.findIndex((r) => r.id === ref.id);
    const newIndex = refIdx >= 0
      ? refIdx + 1
      : editor.querySelectorAll('[data-ref-id]').length + 1; // 兜底

    // 构建芯片 HTML：缩略图 + "图片N"（不再显示文件名与蓝色索引徽章，简洁整齐）
    const chipHtml = `
      <span contenteditable="false" data-ref-id="${ref.id}" data-ref-name="${ref.name}"
        class="inline-flex items-center gap-1 rounded border border-[#d0d7e8] bg-[#eef3ff] px-1.5 py-0.5 align-middle mx-0.5 text-xs leading-5">
        <img src="${ref.url}" class="h-4 w-4 rounded object-cover" alt="图片${newIndex}" />
        <span class="text-[#1a1d26] font-medium">图片${newIndex}</span>
      </span>&nbsp;`;

    // 查找编辑器中最后一个 "@" 位置，替换 "@xxx" 文本为芯片
    const html = editor.innerHTML;
    const lastAtIdx = html.lastIndexOf('@');
    if (lastAtIdx !== -1) {
      // 找到 @ 后面的非空白字符
      const afterAt = html.slice(lastAtIdx + 1);
      const match = afterAt.match(/^[^<\s]*/);
      const endIdx = match ? lastAtIdx + 1 + match[0].length : lastAtIdx + 1;
      const newHtml = html.slice(0, lastAtIdx) + chipHtml + html.slice(endIdx);
      editor.innerHTML = newHtml;
    } else {
      // 找不到 @，直接在末尾插入芯片
      editor.innerHTML += chipHtml;
    }

    setShowAtMenu(false);
    setAtQuery('');
    // 重新聚焦编辑器并将光标移到末尾
    requestAnimationFrame(() => {
      editor.focus();
      const range = document.createRange();
      range.selectNodeContents(editor);
      range.collapse(false);
      const sel = window.getSelection();
      sel?.removeAllRanges();
      sel?.addRange(range);
      // 触发一次 input 事件以同步 prompt 和 referencedImages
      const inputEvent = new Event('input', { bubbles: true });
      editor.dispatchEvent(inputEvent);
    });
  }, [references]);

  // 点击新建按钮：有内容时弹窗确认，无内容时直接新建
  const handleNewClick = useCallback(() => {
    if (hasEditorContent) {
      setShowNewConfirm(true);
    } else {
      handleNewConfirm();
    }
  }, [hasEditorContent, handleNewConfirm]);

  // ==================== 提示词相关函数 ====================

  /** 加载我的提示词列表 */
  const loadMyPrompts = useCallback(() => {
    setLoadingPrompts(true);
    promptApi.listPrompts()
      .then((data) => {
        setMyPrompts(data);
      })
      .catch((err) => {
        console.error('加载提示词列表失败:', err);
      })
      .finally(() => {
        setLoadingPrompts(false);
      });
  }, []);

  /** 保存当前提示词 */
  const handleSavePrompt = useCallback(async () => {
    const trimmedPrompt = prompt.trim();
    if (!trimmedPrompt) return;
    setSavingPrompt(true);
    try {
      await promptApi.savePrompt({ title: saveTitleInput.trim() || undefined, prompt: trimmedPrompt });
      setShowSavePromptDialog(false);
      setSaveTitleInput('');
      // 刷新我的提示词列表
      loadMyPrompts();
    } catch (err) {
      console.error('保存提示词失败:', err);
    } finally {
      setSavingPrompt(false);
    }
  }, [prompt, saveTitleInput, loadMyPrompts]);

  /** 打开编辑提示词弹窗 */
  const handleOpenEditPrompt = useCallback((item: UserPromptResult) => {
    setEditingPromptId(item.id);
    setEditingTitle(item.title || '');
    setEditingContent(item.prompt);
    setShowEditPromptDialog(true);
  }, []);

  /** 保存编辑后的提示词 */
  const handleUpdatePrompt = useCallback(async () => {
    if (editingPromptId == null) return;
    const trimmedContent = editingContent.trim();
    if (!trimmedContent) return;
    setSavingPrompt(true);
    try {
      await promptApi.updatePrompt(editingPromptId, {
        title: editingTitle.trim() || undefined,
        prompt: trimmedContent,
      });
      setShowEditPromptDialog(false);
      setEditingPromptId(null);
      setEditingTitle('');
      setEditingContent('');
      loadMyPrompts();
    } catch (err) {
      console.error('编辑提示词失败:', err);
    } finally {
      setSavingPrompt(false);
    }
  }, [editingPromptId, editingTitle, editingContent, loadMyPrompts]);

  /** 使用提示词（点击"我的提示词"中的某条提示词时触发） */
  const handleUsePrompt = useCallback(async (item: UserPromptResult) => {
    // 将提示词填入编辑器
    if (editorRef.current) {
      editorRef.current.innerText = item.prompt;
      setPrompt(item.prompt);
    }
    // 使用次数+1
    try {
      await promptApi.usePrompt(item.id);
      loadMyPrompts();
    } catch (err) {
      console.error('更新提示词使用次数失败:', err);
    }
    // 关闭"我的提示词"弹窗，用户继续编辑
    setShowMyPromptsDialog(false);
  }, [loadMyPrompts]);

  /** 删除提示词 */
  const handleDeletePrompt = useCallback(async (id: number) => {
    try {
      await promptApi.deletePrompt(id);
      loadMyPrompts();
    } catch (err) {
      console.error('删除提示词失败:', err);
    }
  }, [loadMyPrompts]);

  // 切换到收藏 Tab 时自动从后端加载收藏列表
  useEffect(() => {
    if (activeTab === 'favorites') {
      setLoadingFavorites(true);
      imageApi.getFavorites()
        .then((data) => {
          setFavorites(data);
        })
        .catch((err) => {
          console.error('加载收藏列表失败:', err);
        })
        .finally(() => {
          setLoadingFavorites(false);
        });
    }
  }, [activeTab]);

  // 计时器：生成过程中显示已用时间
  useEffect(() => {
    if (generating) {
      setElapsedSeconds(0);
      timerRef.current = setInterval(() => {
        setElapsedSeconds((prev) => prev + 1);
      }, 1000);
    } else if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [generating]);

  // contenteditable placeholder 处理：通过 CSS :empty + ::before 实现
  useEffect(() => {
    const editor = editorRef.current;
    if (!editor) return;
    const placeholder = '输入 / 选择技能，输入 @ 引用参考图片';
    // 通过 data-placeholder 属性设置 placeholder 文本
    editor.setAttribute('data-placeholder', placeholder);
    // 初始状态设置
    const updatePlaceholder = () => {
      if (editor.innerText.trim() === '') {
        editor.classList.add('is-empty');
      } else {
        editor.classList.remove('is-empty');
      }
    };
    updatePlaceholder();
    // 监听 input 事件更新 placeholder 状态
    editor.addEventListener('input', updatePlaceholder);
    return () => editor.removeEventListener('input', updatePlaceholder);
  }, []);

  /** 格式化秒数为 mm:ss */
  const formatTime = useCallback((seconds: number) => {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }, []);

  // 根据当前选中的技能获取最大参考图片数
  const currentMaxRefs = selectedSkill?.maxReferences ?? MAX_REFS;
  const remainingRefs = Math.max(0, currentMaxRefs - references.length);
  const queued = tasks.filter((task) => task.status === 'queued').length;
  const running = tasks.filter((task) => task.status === 'running').length;
  const selectedTask = tasks.find((task) => task.id === selectedTaskId) ?? null;

  // 计算当前AI生成图片任务的状态栏显示数量
  // 排队中 = tasks.queued + (generating 但还没到 3 秒时算作排队阶段)
  // 生成中 = tasks.running + (generating 超过 3 秒时算作生成中)
  // 已完成 = 有 generatedUrl 且 非 generating 时算 1
  const displayQueueCount = useMemo(() => {
    return queued;
  }, [queued]);

  const displayRunningCount = useMemo(() => {
    // 当前任务由 activeGeneration 表示，不再叠加"generating"避免重复计数
    return running;
  }, [running]);

  const displayCompletedCount = useMemo(() => {
    return generatedUrl && !generating ? 1 : 0;
  }, [generatedUrl, generating]);
  // 积分估算：根据提示词字数、模型、清晰度综合计算
  // 输入为空时返回 null，表示显示 "--"
  const estimatedCredits = useMemo(() => {
    const trimmed = prompt.trim();
    if (trimmed.length === 0 && referencedImages.length === 0) return null;

    // 基础积分：按模型档位
    const baseCredits = model.includes('VIP') ? 1.18 : model === '高级版' ? 0.88 : 0.58;

    // 清晰度系数：1K=1.0, 2K=1.5, 4K=2.5
    const resolutionFactor = resolution === '4K' ? 2.5 : resolution === '2K' ? 1.5 : 1.0;

    // 提示词字数影响：每 100 字增加 0.1 积分
    const promptFactor = 1.0 + Math.floor(trimmed.length / 100) * 0.1;

    // 最终积分 = 基础 × 清晰度系数 × 提示词系数
    return baseCredits * resolutionFactor * promptFactor;
  }, [prompt, model, resolution]);
  // 只要有提示词文本或图片（槽位/引用）即可提交
  const hasAnyImage = selectedSkill ? references.length > 0 : referencedImages.length > 0;
  const canSubmit = (prompt.trim().length > 0 || hasAnyImage) && !submitting && !generating;

  async function handleUploadFiles(files: FileList | null): Promise<PickedMedia[]> {
    if (!files || files.length === 0) return [];
    const fresh: GlobalMaterial[] = [];

    for (const file of Array.from(files)) {
      const fingerprint = `${file.name}_${file.size}`;
      // 已存在则跳过
      if (materials.some((m) => `${m.name}_${m.size ?? m.url.length}` === fingerprint)) continue;

      try {
        // 调用后端 API 上传到 MinIO + 写入 media_assets 表
        const result = await mediaApi.uploadAsset(file);
        fresh.push({
          id: nanoid(10),
          type: file.type.startsWith('video') ? 'video' : file.type.startsWith('audio') ? 'audio' : 'image',
          url: result.url, // 使用 MinIO 返回的 URL
          name: file.name,
          size: file.size,
        });
      } catch (err) {
        console.error('[ImageWorkbench] upload failed:', file.name, err);
        // 失败时回退到本地 blob URL，保证可用性
        fresh.push(mediaToPicked(file));
      }
    }

    if (fresh.length > 0) {
      addMaterials(fresh);
    }
    return fresh;
  }

  function handleConfirmPicked(picked: PickedMedia[]) {
    setReferences((current) => {
      const existingIds = new Set(current.map((item) => item.id));
      const existingFingerprints = new Set(current.map((item) => `${item.name}_${item.url.length}`));
      const fresh = picked.filter((item) => {
        const fingerprint = `${item.name}_${item.url.length}`;
        if (existingIds.has(item.id) || existingFingerprints.has(fingerprint)) return false;
        existingIds.add(item.id);
        existingFingerprints.add(fingerprint);
        return true;
      });
      return [...current, ...fresh].slice(0, currentMaxRefs);
    });
  }

  /**
   * 取消正在进行的图片生成
   */
  function cancelGeneration() {
    abortRef.current?.abort();
    abortRef.current = null;
    setGenerating(false);
  }

  /**
   * 将图片 URL 转换为 base64 data URI 格式
   * 支持 blob URL、远程 URL 等
   */
  async function urlToBase64(url: string): Promise<string> {
    const response = await fetch(url);
    const blob = await response.blob();
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => resolve(reader.result as string);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  /**
   * 提交图片生成请求
   * 调用后端 /api/images/generate 接口，使用 gpt-image-2-2k 模型
   * 如果有引用图片，后端会调用 /v1/images/edits 接口将引用图片作为素材
   * 超时 5 分钟
   */
  async function submit() {
    const trimmedPrompt = prompt.trim();
    const hasSourceImages = selectedSkill ? references.length > 0 : referencedImages.length > 0;
    if (!trimmedPrompt && !hasSourceImages) {
      console.warn('[ImageWorkbench] submit blocked: empty prompt and no images');
      return;
    }

    // 合并"图片源"用于提交：
    //   - 有技能选中：使用槽位图片（references），AI 根据技能/提示词/槽位图生成
    //   - 无技能选中：使用 @ 引用图片（referencedImages），仅根据提示词+引用图生成
    const sourceImages = selectedSkill ? references : referencedImages;
    let referenceImagesBase64: string[] = [];
    if (sourceImages.length > 0) {
      try {
        console.log('[ImageWorkbench] converting source images to base64...');
        referenceImagesBase64 = await Promise.all(
          sourceImages.map((ref) => urlToBase64(ref.url))
        );
        console.log('[ImageWorkbench] source images converted:', referenceImagesBase64.length, 'images');
      } catch (err) {
        console.error('[ImageWorkbench] failed to convert source images:', err);
        setGenerateError('图片转换失败，请重试');
        return;
      }
    }

    console.log('[ImageWorkbench] submit start:', {
      promptLen: trimmedPrompt.length,
      refImageCount: referenceImagesBase64.length,
    });

    // 重置状态
    setGenerateError(null);
    setGeneratedUrl(null);
    setSubmitting(true);
    setGenerating(true);
    // 记录当前正在生成的任务（切走后再回来能恢复"生成中"占位卡）
    setActiveGeneration({
      prompt: trimmedPrompt || '(空提示词)',
      referencePreviewUrl: sourceImages[0]?.url,
      startedAt: Date.now(),
    });

    // 创建 AbortController 用于超时取消
    const controller = new AbortController();
    abortRef.current = controller;

    // 设置 5 分钟超时
    const timeoutId = setTimeout(() => {
      console.warn('[ImageWorkbench] timeout triggered');
      controller.abort();
      setGenerateError('图片生成超时（已超过 5 分钟），请重试');
      setGenerating(false);
      setSubmitting(false);
    }, GENERATE_TIMEOUT_SECONDS * 1000);

    try {
      console.log('[ImageWorkbench] calling imageApi.generateImage...');
      // 调用图片生成 API
      // - 有引用图片时，后端调用 /v1/images/edits 接口
      // - 无引用图片时，后端调用 /v1/images/generations 接口
      const result = await imageApi.generateImage(
        {
          prompt: trimmedPrompt,
          size: '1024x1024',
          quality: 'standard',
          style: 'vivid',
          referenceImages: referenceImagesBase64.length > 0 ? referenceImagesBase64 : undefined,
        },
        controller.signal
      );

      console.log('[ImageWorkbench] generateImage result:', result);

      if (result && result.imageUrl) {
        // 生成成功，显示图片并自动切到预览 Tab
        setGeneratedUrl(result.imageUrl);
        setGeneratedAt(Date.now());
        // 追加到历史缩略图列表（侧栏状态栏下方展示，按 url + objectKey 双键去重）
        setGenerationHistory((prev) => {
          return dedupeHistory([
            {
              id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
              url: result.imageUrl,
              prompt: trimmedPrompt,
              createdAt: Date.now(),
            },
            ...prev,
          ]).slice(0, 50);
        });
        setActiveTab('preview');
        console.log('[ImageWorkbench] image URL set:', result.imageUrl);
      } else {
        console.warn('[ImageWorkbench] result missing imageUrl:', result);
        setGenerateError('图片生成失败：未获取到图片地址');
      }
    } catch (err: unknown) {
      console.error('[ImageWorkbench] generateImage error:', err);
      // 处理错误
      if (err instanceof DOMException && err.name === 'AbortError') {
        // 超时或用户取消
        if (!generateError) {
          setGenerateError('图片生成已取消');
        }
      } else if (err instanceof Error) {
        setGenerateError(`生成失败：${err.message}`);
      } else {
        setGenerateError('图片生成失败，请稍后重试');
      }
    } finally {
      clearTimeout(timeoutId);
      abortRef.current = null;
      setGenerating(false);
      setSubmitting(false);
      // 生成任务结束，清除"生成中"占位卡的持久化状态
      setActiveGeneration(null);
      console.log('[ImageWorkbench] submit finished');
    }
  }

  return (
    <main className="h-screen overflow-hidden bg-[#f7f7f8] px-4 py-5 text-[#16181d] sm:px-6">
      <div className={cn(
        'grid h-full min-h-0 gap-3',
        sidebarCollapsed
          ? 'grid-cols-[minmax(420px,610px)_minmax(0,1fr)_36px]'
          : 'grid-cols-[minmax(420px,610px)_minmax(0,1fr)_112px]'
      )}>
        <section className="relative flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-white">
          <div className="flex h-14 items-center justify-between px-4">
            {/* 技能选择下拉栏：点击展开技能面板 */}
            <button
              type="button"
              onClick={() => setShowSkillPanel((v) => !v)}
              className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-sm font-semibold text-[#1a1d26] hover:bg-[#f3f4f6]"
            >
              <Sparkles className="h-4 w-4 text-[#3677ff]" />
              <span>
                {selectedSkill ? selectedSkill.name : '图片创作'}
              </span>
              {selectedSkill && (
                <span className="rounded bg-[#eaf0ff] px-1.5 py-0.5 text-[10px] font-medium text-[#3677ff]">
                  {selectedSkill.tag}
                </span>
              )}
              <ChevronDown className={cn('h-3.5 w-3.5 text-[#7a818d] transition-transform', showSkillPanel && 'rotate-180')} />
            </button>
            <button
              type="button"
              onClick={handleNewClick}
              className="inline-flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium hover:bg-[#f3f4f6]"
            >
              <Plus className="h-3.5 w-3.5" />
              新建
            </button>
          </div>

          {/* 技能选择面板：点击下拉栏展开 */}
          {showSkillPanel && (
            <>
              {/* 遮罩层：点击关闭 */}
              <div
                className="absolute inset-0 z-20"
                onClick={() => setShowSkillPanel(false)}
              />
              {/* 技能面板 */}
              <div className="absolute left-0 right-0 top-14 z-30 mx-3 rounded-xl border border-[#e4e5e9] bg-white shadow-xl">
                <div className="flex items-center justify-between border-b border-[#f0f1f3] px-5 py-3">
                  <div>
                    <h2 className="text-sm font-semibold text-[#1a1d26]">你想创作什么？</h2>
                    <p className="mt-0.5 text-xs text-[#8a8f99]">让每一个想法，都能成为画面</p>
                  </div>
                  <div className="relative">
                    <input
                      type="text"
                      placeholder="搜一搜"
                      className="h-8 w-44 rounded-md border border-[#e4e5e9] bg-[#f7f8fa] px-3 pr-7 text-xs outline-none focus:border-[#3677ff]"
                    />
                    <List className="absolute right-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#9ca2ad]" />
                  </div>
                </div>
                {/* 分类 Tab */}
                <div className="flex gap-4 border-b border-[#f0f1f3] px-5 py-2">
                  {SKILL_CATEGORIES.map((cat) => (
                    <button
                      key={cat.id}
                      type="button"
                      onClick={() => setActiveCategory(cat.id)}
                      className={cn(
                        'pb-1 text-xs font-medium transition-colors',
                        activeCategory === cat.id
                          ? 'border-b-2 border-[#3677ff] text-[#3677ff]'
                          : 'text-[#6b7280] hover:text-[#1a1d26]'
                      )}
                    >
                      {cat.name}
                    </button>
                  ))}
                </div>
                {/* 技能卡片网格 */}
                <div className="grid grid-cols-2 gap-2 p-4">
                  {SKILL_CATEGORIES.find((c) => c.id === activeCategory)?.skills.map((skill) => (
                    <button
                      key={skill.id}
                      type="button"
                      onClick={() => selectSkill(skill)}
                      className={cn(
                        'flex items-start gap-3 rounded-lg border p-3 text-left transition-all hover:border-[#3677ff] hover:bg-[#f5f8ff]',
                        selectedSkill?.id === skill.id
                          ? 'border-[#3677ff] bg-[#f5f8ff]'
                          : 'border-[#eceef2] bg-white'
                      )}
                    >
                      <div className="grid h-9 w-9 flex-none place-items-center rounded-lg bg-[#f0f3ff]">
                        <Sparkles className="h-4 w-4 text-[#3677ff]" />
                      </div>
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5">
                          <span className="text-sm font-medium text-[#1a1d26]">{skill.name}</span>
                          <span className="rounded bg-[#f0f3ff] px-1 py-0.5 text-[10px] text-[#3677ff]">
                            {skill.tag}
                          </span>
                        </div>
                        <p className="mt-0.5 text-xs text-[#8a8f99] line-clamp-1">{skill.description}</p>
                      </div>
                    </button>
                  ))}
                </div>
                <div className="border-t border-[#f0f1f3] px-5 py-2 text-center text-xs text-[#9ca2ad]">
                  更多技能持续上线中
                </div>
              </div>
            </>
          )}

          <div className="mx-3 flex min-h-0 flex-1 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
            <div className="flex items-center justify-between px-4 pt-4 text-xs text-[#737985]">
              {/* 左：参考图片计数（始终显示在左上角，根据是否选中技能显示不同分母） */}
              <span className="min-w-[40px]">
                {references.length} / {currentMaxRefs}
              </span>
              {/* 右：保存/我的提示词按钮（始终固定在右侧） */}
              <div className="flex items-center gap-3 text-[#a4aab5]">
                <button
                  type="button"
                  onClick={() => setShowSavePromptDialog(true)}
                  className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-[#737985] transition-colors hover:bg-[#eef1f6] hover:text-[#3677ff]"
                  title="保存提示词"
                >
                  <Save className="h-4 w-4" />
                  <span>保存提示词</span>
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setShowMyPromptsDialog(true);
                    loadMyPrompts();
                  }}
                  className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-[#737985] transition-colors hover:bg-[#eef1f6] hover:text-[#3677ff]"
                  title="我的提示词"
                >
                  <FolderOpen className="h-4 w-4" />
                  <span>我的提示词</span>
                </button>
              </div>
            </div>

            {/* 技能选中时：显示带标签的上传槽位 + 技能 Banner */}
            {selectedSkill?.referenceSlots && selectedSkill.referenceSlots.length > 0 ? (
              <div className="mt-4 px-4">
                {/* 带标签的上传槽位 */}
                <div className="mb-3 flex flex-wrap gap-2">
                  {selectedSkill.referenceSlots.map((slot, idx) => {
                    const ref = references[idx];
                    return (
                      <div key={idx} className="flex flex-col items-start gap-1">
                        {ref ? (
                          <div
                            onClick={() => setReferencePreview(ref)}
                            className="group/ref relative h-[60px] w-[60px] cursor-pointer overflow-hidden rounded-lg bg-white shadow-[0_8px_20px_rgba(21,25,36,0.06)] ring-1 ring-[#eff0f3] transition hover:ring-[#4d73ff]/40"
                            title={`${ref.name}（点击预览）`}
                          >
                            <ReferenceMediaThumbnail media={ref} />
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setReferences((current) => {
                                  const next = [...current];
                                  next[idx] = null as unknown as PickedMedia;
                                  // remove null entries and truncate
                                  return next.filter((r) => r !== null).slice(0, currentMaxRefs);
                                });
                              }}
                              className="absolute right-1 top-1 grid h-4 w-4 place-items-center rounded-full bg-black/55 text-[10px] leading-none text-white"
                              aria-label={`移除 ${ref.name}`}
                            >
                              ×
                            </button>
                          </div>
                        ) : (
                          <button
                            type="button"
                            onClick={() => setPickerOpen(true)}
                            className="grid h-[60px] w-[60px] place-items-center rounded-lg border-2 border-dashed border-[#d7dbe3] bg-[#f9fafb] text-[#a4aab5] transition-colors hover:border-[#3677ff] hover:bg-[#f5f8ff] hover:text-[#3677ff]"
                          >
                            <Plus className="h-5 w-5" />
                          </button>
                        )}
                        <span className="text-[10px] text-[#737985]">
                          {slot.label}
                          {slot.optional && <span className="ml-0.5 text-[#b8bdc7]">(可选)</span>}
                        </span>
                      </div>
                    );
                  })}
                  {/* 额外上传槽位（超过预定义槽位后继续添加） */}
                  {references.length > selectedSkill.referenceSlots.length &&
                    references.slice(selectedSkill.referenceSlots.length).map((ref, idx) => {
                      const actualIdx = selectedSkill.referenceSlots!.length + idx;
                      return (
                        <div key={`extra-${idx}`} className="flex flex-col items-start gap-1">
                          <div
                            onClick={() => setReferencePreview(ref)}
                            className="group/ref relative h-[60px] w-[60px] cursor-pointer overflow-hidden rounded-lg bg-white shadow-[0_8px_20px_rgba(21,25,36,0.06)] ring-1 ring-[#eff0f3] transition hover:ring-[#4d73ff]/40"
                            title={`${ref.name}（点击预览）`}
                          >
                            <ReferenceMediaThumbnail media={ref} />
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setReferences((current) => current.filter((_, i) => i !== actualIdx));
                              }}
                              className="absolute right-1 top-1 grid h-4 w-4 place-items-center rounded-full bg-black/55 text-[10px] leading-none text-white"
                            >
                              ×
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  {/* 继续添加按钮 */}
                  {remainingRefs > 0 && (
                    <AddMaterialCard
                      disabled={remainingRefs === 0}
                      onClick={() => setPickerOpen(true)}
                      label="添加图片"
                      className="h-[60px] w-[60px] rounded-lg border-[#eceef2] bg-[#f9fafb] text-[10px] text-[#747b87] hover:border-[#d7defc] hover:text-[#4d73ff]"
                      iconClassName="h-5 w-5"
                      labelClassName="mt-0.5 text-[10px]"
                    />
                  )}
                </div>

                {/* 技能 Banner */}
                <div className="flex items-start gap-2 rounded-lg bg-[#f0f3ff] px-3 py-2">
                  <Sparkles className="mt-0.5 h-3.5 w-3.5 flex-none text-[#3677ff]" />
                  <div className="min-w-0">
                    <span className="text-xs font-medium text-[#3677ff]">
                      {selectedSkill.name}
                    </span>
                    {selectedSkill.hintText && (
                      <span className="ml-1 text-[11px] text-[#737985]">
                        {selectedSkill.hintText}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            ) : (
              /* 无技能选中时：保持原来的参考图片区域 */
              <div className="mt-4 flex min-h-[92px] items-start gap-3 px-4">
                {references.length > 0 && (
                  <div className="flex min-w-0 flex-1 gap-2 overflow-x-auto pb-1">
                    {references.map((reference, index) => (
                      <div
                        key={reference.id}
                        onClick={() => setReferencePreview(reference)}
                        className="group/ref relative h-[60px] w-[60px] flex-none cursor-pointer overflow-hidden rounded-lg bg-white shadow-[0_8px_20px_rgba(21,25,36,0.06)] ring-1 ring-[#eff0f3] transition hover:ring-[#4d73ff]/40"
                        title={`${reference.name}（点击预览）`}
                      >
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <ReferenceMediaThumbnail media={reference} />
                        <span className="absolute left-1 top-1 grid h-4 min-w-4 place-items-center rounded-full bg-black/65 px-1 text-[10px] font-semibold leading-4 text-white">
                          {index + 1}
                        </span>
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            setReferences((current) => current.filter((item) => item.id !== reference.id));
                          }}
                          className="absolute right-1 top-1 hidden h-4 w-4 place-items-center rounded-full bg-black/55 text-[10px] leading-none text-white group-hover/ref:grid"
                          aria-label={`移除 ${reference.name}`}
                        >
                          ×
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                <AddMaterialCard
                  disabled={remainingRefs === 0}
                  onClick={() => setPickerOpen(true)}
                  label="点击添加"
                  className="ml-auto h-[72px] w-[72px] flex-none rounded-lg border-[#eceef2] bg-[#f9fafb] text-[10px] text-[#747b87] hover:border-[#d7defc] hover:text-[#4d73ff]"
                  iconClassName="h-5 w-5"
                  labelClassName="mt-1 text-[10px]"
                />
              </div>
            )}

            <div ref={editorContainerRef} className="relative mt-1 flex min-h-0 flex-1 px-4 pb-4">
              {/* 灰色提示词占位符：编辑器无内容时显示，有内容时隐藏 */}
              {prompt.length === 0 && (
                <div className="pointer-events-none absolute left-4 top-2 text-sm leading-7 text-[#b8bdc7] select-none">
                  输入 / 选择技能，输入 @ 引用参考图片
                </div>
              )}
              {/* contenteditable 编辑器：支持文本和图片引用芯片 */}
              <div
                ref={editorRef}
                contentEditable
                suppressContentEditableWarning
                onKeyDown={(e) => {
                  // Backspace / Delete：允许删除紧邻光标的引用芯片（contenteditable=false 元素）
                  if (e.key === 'Backspace' || e.key === 'Delete') {
                    const sel = window.getSelection();
                    if (!sel || sel.rangeCount === 0) return;
                    const range = sel.getRangeAt(0);
                    if (!range.collapsed) return; // 有选区时不处理
                    const editor = editorRef.current;
                    if (!editor) return;

                    // 找到光标所在文本节点
                    let node: Node | null = range.startContainer;
                    const offset = range.startOffset;
                    // 上跳到元素层
                    while (node && node.nodeType === Node.TEXT_NODE && node.parentNode) {
                      node = node.parentNode;
                    }
                    if (!node) return;

                    // 检查 Backspace：当前光标前一个兄弟节点是否是 chip
                    if (e.key === 'Backspace') {
                      // 情况 1：文本节点开头，光标前一个字符是 chip 的一部分（不常见）
                      // 情况 2：编辑器根节点的直接子节点是 chip，且光标在其后
                      const prev = node.previousSibling as HTMLElement | null;
                      const prevPrev = (node as HTMLElement).previousElementSibling;
                      // 最常见：当前文本节点紧跟在 chip 之后，且 offset=0
                      if (
                        range.startContainer.nodeType === Node.TEXT_NODE &&
                        offset === 0 &&
                        (prev?.nodeType === Node.ELEMENT_NODE || prevPrev)
                      ) {
                        const chip = (prev as HTMLElement | null)?.querySelector?.('[data-ref-id]')
                          || (prevPrev as HTMLElement | null);
                        if (chip && chip.matches?.('[data-ref-id]')) {
                          e.preventDefault();
                          chip.remove();
                          // 触发 input 事件同步 prompt 和 referencedImages
                          const inputEvent = new Event('input', { bubbles: true });
                          editor.dispatchEvent(inputEvent);
                          return;
                        }
                      }
                      // 情况 3：chip 内最后位置的文本（contenteditable=false 内的文本节点不可编辑，
                      //         浏览器允许 backspace 删除该 chip）
                      //         浏览器原生行为已能处理，这里不用管
                    }
                  }
                }}
                onKeyUp={() => {
                  // 键松开后更新菜单位置（跟随光标）
                  updateMenuPosition();
                }}
                onClick={() => {
                  // 点击后更新菜单位置
                  updateMenuPosition();
                }}
                onInput={(e) => {
                  // 输入时先更新菜单位置（紧跟光标）
                  updateMenuPosition();
                  const text = (e.target as HTMLDivElement).innerText || '';
                  setPrompt(text);

                  // 基于"光标所在文本节点"检测 @ 和 / 触发字符
                  // 避免芯片内的 @文本 干扰 lastIndexOf 检测
                  const triggerCtx = getTriggerContext();
                  if (triggerCtx.char === '@') {
                    setAtQuery(triggerCtx.query);
                    setShowAtMenu(true);
                    setShowSlashMenu(false);
                  } else if (triggerCtx.char === '/') {
                    setSlashQuery(triggerCtx.query);
                    setShowSlashMenu(true);
                    setShowAtMenu(false);
                  } else {
                    setShowSlashMenu(false);
                    setShowAtMenu(false);
                  }

                  // 同步已引用图片列表（从编辑器中的芯片元素解析）
                  const chips = (e.target as HTMLDivElement).querySelectorAll('[data-ref-id]');
                  const refs: { refId: string; name: string; url: string; index: number }[] = [];
                  chips.forEach((chip, i) => {
                    const refId = chip.getAttribute('data-ref-id') || '';
                    const name = chip.getAttribute('data-ref-name') || '';
                    // 从 references 中查找对应的图片 URL
                    const matchedRef = references.find((r) => r.id === refId);
                    if (matchedRef) {
                      refs.push({ refId, name, url: matchedRef.url, index: i + 1 });
                    }
                  });
                  setReferencedImages(refs);
                }}
                className="h-full min-h-[420px] w-full resize-none overflow-y-auto bg-transparent pb-8 pt-2 text-sm leading-7 text-[#242832] outline-none placeholder:text-[#b8bdc7] [&_[contenteditable=false]]:inline-flex"
                data-placeholder="输入 / 选择技能，输入 @ 引用参考图片"
              />
              <div className="pointer-events-none absolute bottom-5 left-5 text-xs text-[#555b66]">
                {prompt.length} / {MAX_PROMPT}
              </div>

              {/* "/" 触发的技能选择菜单：跟随光标右下方 */}
              {showSlashMenu && filteredSlashSkills.length > 0 && (
                <div
                  className="absolute z-40 w-64 rounded-lg border border-[#e4e5e9] bg-white p-2 shadow-xl"
                  style={{
                    left: Math.min(menuPos.x, (editorContainerRef.current?.clientWidth ?? 400) - 260),
                    top: Math.min(menuPos.y, (editorContainerRef.current?.clientHeight ?? 500) - 320),
                  }}
                >
                  <div className="mb-1.5 px-1 text-xs text-[#8a8f99]">
                    {slashQuery ? `搜索 "${slashQuery}"` : '选择技能'}
                  </div>
                  <div className="max-h-60 overflow-y-auto">
                    {filteredSlashSkills.map((skill) => (
                      <button
                        key={skill.id}
                        type="button"
                        onClick={() => selectSkill(skill)}
                        className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left hover:bg-[#f3f4f6]"
                      >
                        <Sparkles className="h-3.5 w-3.5 flex-none text-[#3677ff]" />
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5">
                            <span className="text-xs font-medium text-[#1a1d26]">{skill.name}</span>
                            <span className="text-[10px] text-[#9ca2ad]">· {skill.category}</span>
                          </div>
                          <p className="truncate text-[11px] text-[#8a8f99]">{skill.description}</p>
                        </div>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* "@" 触发的图片引用菜单：跟随光标右下方 */}
              {showAtMenu && (
                <div
                  className="absolute z-40 w-72 rounded-lg border border-[#e4e5e9] bg-white p-2 shadow-xl"
                  style={{
                    left: Math.min(menuPos.x, (editorContainerRef.current?.clientWidth ?? 400) - 296),
                    top: Math.min(menuPos.y, (editorContainerRef.current?.clientHeight ?? 500) - 320),
                  }}
                >
                  <div className="mb-1.5 flex items-center justify-between px-1">
                    <span className="text-xs text-[#8a8f99]">
                      {atQuery ? `搜索 "${atQuery}"` : '引用图片'}
                    </span>
                    <span className="text-[10px] text-[#b8bdc7]">@ 引用参考图</span>
                  </div>
                  {filteredAtImages.length > 0 ? (
                    <div className="max-h-60 overflow-y-auto">
                      {filteredAtImages.map((ref) => (
                        <button
                          key={ref.id}
                          type="button"
                          onClick={() => selectImageReference(ref)}
                          className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left hover:bg-[#f3f4f6]"
                        >
                          {/* 缩略图 */}
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img
                            src={ref.url}
                            alt={ref.name}
                            className="h-9 w-9 flex-none rounded object-cover ring-1 ring-[#eff0f3]"
                          />
                          <div className="min-w-0 flex-1">
                            <span className="block truncate text-xs font-medium text-[#1a1d26]">
                              {ref.name}
                            </span>
                            <span className="text-[10px] text-[#9ca2ad]">点击引用此图片</span>
                          </div>
                        </button>
                      ))}
                    </div>
                  ) : (
                    <div className="px-2 py-4 text-center text-xs text-[#9ca2ad]">
                      {references.length === 0
                        ? '请先在上方上传图片'
                        : `未找到匹配 "${atQuery}" 的图片`}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>

          <div className="mx-3 mt-2 grid grid-cols-2 gap-2">
            <SelectCard
              icon={<Sparkles className="h-4 w-4" />}
              label="模型"
              value={model}
              subValue={model.includes('VIP') ? '更快更稳' : undefined}
              options={MODELS}
              onChange={(value) => setModel(value as ImageModel)}
            />
            <ImageSettingsCard
              ratio={ratio}
              setRatio={setRatio}
              resolution={resolution}
              setResolution={setResolution}
              format={format}
              setFormat={setFormat}
            />
          </div>

          <div className="m-3 mt-2">
            <button
              type="button"
              disabled={!canSubmit}
              onClick={submit}
              className={cn(
                'flex h-10 w-full items-center justify-center gap-2 rounded-lg text-sm font-semibold text-white transition',
                canSubmit
                  ? 'bg-[#3677ff] hover:bg-[#2a67e6]'
                  : 'bg-[#c9ccd1] cursor-not-allowed'
              )}
            >
              {generating ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  生成中...
                  <span className="text-xs font-medium opacity-70">
                    ({formatTime(elapsedSeconds)})
                  </span>
                </>
              ) : submitting ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <>
                  <Play className="h-4 w-4" />
                  立即生成图片
                </>
              )}
              <span className="text-xs font-medium opacity-90">
                预计 {estimatedCredits !== null ? `${estimatedCredits.toFixed(2)} 积分` : '-- 积分'}
              </span>
            </button>
          </div>
        </section>

        <section className="flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc]">
          <div className="flex h-12 items-center gap-7 px-5 text-xs font-semibold">
            <button
              onClick={() => setActiveTab('preview')}
              className={cn(
                'relative h-12 transition-colors',
                activeTab === 'preview'
                  ? 'text-[#1d222b] after:absolute after:bottom-0 after:left-1/2 after:h-0.5 after:w-4 after:-translate-x-1/2 after:rounded-full after:bg-[#1d222b]'
                  : 'text-[#575e69] hover:text-[#1d222b]'
              )}
            >
              预览
            </button>
            <button
              onClick={() => setActiveTab('favorites')}
              className={cn(
                'relative h-12 transition-colors',
                activeTab === 'favorites'
                  ? 'text-[#1d222b] after:absolute after:bottom-0 after:left-1/2 after:h-0.5 after:w-4 after:-translate-x-1/2 after:rounded-full after:bg-[#1d222b]'
                  : 'text-[#575e69] hover:text-[#1d222b]'
              )}
            >
              收藏
            </button>
          </div>

          {activeTab === 'preview' ? (
          <div className="grid flex-1 place-items-center px-8 pb-8">
            {generating ? (
              // 生成中状态
              <div className="w-full max-w-[520px] text-center">
                <div className="mx-auto mb-5 grid h-20 w-20 place-items-center rounded-full bg-white shadow-sm">
                  <Loader2 className="h-10 w-10 animate-spin text-[#3677ff]" />
                </div>
                <div className="text-sm font-medium text-[#303642]">
                  AI 正在生成图片...
                </div>
                <div className="mt-2 text-xs text-[#6f7682]">
                  已用时 {formatTime(elapsedSeconds)} / 05:00
                </div>
                <button
                  type="button"
                  onClick={cancelGeneration}
                  className="mt-4 inline-flex items-center gap-1 rounded-md border border-[#e4e5e9] bg-white px-3 py-1.5 text-xs text-[#68707c] hover:bg-[#f3f4f6]"
                >
                  <X className="h-3.5 w-3.5" />
                  取消生成
                </button>
              </div>
            ) : generateError ? (
              // 错误状态
              <div className="w-full max-w-[520px] text-center">
                <div className="mx-auto mb-5 grid h-16 w-16 place-items-center rounded-full bg-white shadow-sm">
                  <X className="h-8 w-8 text-[#e5484d]" />
                </div>
                <div className="text-sm font-medium text-[#e5484d]">{generateError}</div>
                <button
                  type="button"
                  onClick={() => setGenerateError(null)}
                  className="mt-4 rounded-md bg-[#3677ff] px-3 py-1.5 text-xs font-medium text-white hover:bg-[#2a67e6]"
                >
                  知道了
                </button>
              </div>
            ) : generatedUrl ? (
              // 生成成功：显示图片 + 右下角收藏按钮
              <div className="w-full max-w-[520px]">
                <div className="group relative overflow-hidden rounded-lg bg-white shadow-sm">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={generatedUrl}
                    alt="AI 生成的图片"
                    className="w-full object-contain"
                    style={{ maxHeight: 'calc(100vh - 260px)' }}
                    onError={(e) => {
                      e.currentTarget.style.display = 'none';
                    }}
                  />
                  {/* 右下角收藏按钮（星型）：点击一次收藏，再点击一次取消收藏；鼠标按下/松开两种样式 */}
                  <button
                    type="button"
                    disabled={favoriting}
                    onClick={async () => {
                      if (!generatedUrl || favoriting) return;
                      setFavoriting(true);
                      try {
                        if (!isFavorited) {
                          // 收藏：从 history 中找到当前图片的 objectKey，调后端标记 source_tool='favorite'
                          const matched = dedupeHistory(generationHistory).find((h) => h.url === generatedUrl);
                          const objectKey = matched?.objectKey;
                          if (!objectKey) {
                            // 没有 objectKey（用户切走后再回来老 sessionStorage 中的旧条目）—— 兜底：等下次刷新再收藏
                            console.warn('[ImageWorkbench] 当前图片缺少 objectKey，请刷新后再试');
                            alert('当前图片尚未同步到服务器，请刷新页面后再收藏');
                            return;
                          }
                          const result = await imageApi.favoriteImage(objectKey);
                          setFavorites((prev) => [{ objectKey: result.objectKey, url: result.url, createdAt: result.createdAt }, ...prev]);
                          setCurrentFavoriteId(objectKey); // 记录当前图片对应的 objectKey（取消收藏时用）
                          setIsFavorited(true);
                        } else {
                          // 取消收藏：把 source_tool 改回 'image'（不删除图片）
                          const objectKey = currentFavoriteId;
                          if (objectKey) {
                            await imageApi.unfavoriteImage(objectKey);
                            setFavorites((prev) => prev.filter((f) => f.objectKey !== objectKey));
                            setCurrentFavoriteId(null);
                          }
                          setIsFavorited(false);
                        }
                      } catch (err) {
                        console.error('收藏操作失败:', err);
                      } finally {
                        setFavoriting(false);
                      }
                    }}
                    className={cn(
                      'absolute bottom-3 right-3 grid h-9 w-9 place-items-center rounded-full shadow-[0_4px_14px_rgba(0,0,0,0.18)] transition-all select-none',
                      favoriting && 'opacity-60 cursor-wait',
                      // 按下时样式（缩小 + 加深阴影）
                      'active:scale-90 active:shadow-[0_2px_8px_rgba(0,0,0,0.25)]',
                      isFavorited
                        ? 'bg-[#f5a524] text-white hover:scale-105 active:bg-[#d8901f]'
                        : 'bg-white/95 text-[#7a818d] hover:bg-white hover:text-[#f5a524] hover:scale-105 active:bg-[#f0f1f3] active:text-[#5a616b]'
                    )}
                    aria-label={isFavorited ? '取消收藏' : '收藏图片'}
                    title={favoriting ? '处理中...' : isFavorited ? '点击取消收藏' : '点击收藏图片'}
                  >
                    <Star className={cn('h-[18px] w-[18px] transition-transform', isFavorited ? 'fill-current' : '', 'active:scale-90')} />
                  </button>
                </div>
                {/* 图片下方操作栏：日期 | 查看原图 | 下载 */}
                <div className="mt-3 flex items-center justify-between text-xs text-[#6f7682]">
                  <span>
                    {generatedAt
                      ? new Date(generatedAt).toLocaleString('zh-CN', {
                          year: 'numeric', month: '2-digit', day: '2-digit',
                          hour: '2-digit', minute: '2-digit', hour12: false,
                        }).replace(/\//g, '-')
                      : '——'}
                  </span>
                  <div className="flex items-center gap-2">
                    <a
                      href={generatedUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="text-[#3677ff] hover:underline"
                    >
                      查看原图
                    </a>
                    {/* 下载按钮：支持 base64 data URI 和远程 URL */}
                    <button
                      type="button"
                      onClick={async () => {
                        if (!generatedUrl) return;
                        try {
                          let blob: Blob;
                          let filename = 'ai-image.png';
                          if (generatedUrl.startsWith('data:')) {
                            // base64 data URI：直接解码
                            const res = await fetch(generatedUrl);
                            blob = await res.blob();
                            // 根据 MIME 类型确定扩展名
                            if (generatedUrl.startsWith('data:image/jpeg')) filename = 'ai-image.jpg';
                          } else {
                            // 远程 URL：下载
                            const res = await fetch(generatedUrl);
                            blob = await res.blob();
                          }
                          const url = URL.createObjectURL(blob);
                          const a = document.createElement('a');
                          a.href = url;
                          a.download = filename;
                          document.body.appendChild(a);
                          a.click();
                          document.body.removeChild(a);
                          URL.revokeObjectURL(url);
                        } catch (err) {
                          console.error('下载失败:', err);
                          window.open(generatedUrl, '_blank');
                        }
                      }}
                      className="inline-flex items-center gap-1 rounded border border-[#e4e5e9] px-2 py-1 hover:bg-[#f3f4f6]"
                      title="下载图片"
                    >
                      <Download className="h-3.5 w-3.5" />
                      下载
                    </button>
                  </div>
                </div>
              </div>
            ) : selectedTask ? (
              // 任务状态（兼容旧逻辑）
              <div className="w-full max-w-[520px] text-center">
                <div className="mx-auto mb-5 grid h-16 w-16 place-items-center rounded-full bg-white shadow-sm">
                  <ImageIcon className="h-8 w-8 text-[#757b86]" />
                </div>
                <div className="text-sm font-medium text-[#303642]">
                  {selectedTask.status === 'queued' ? '排队中' : '生成中'}
                  <span className="ml-2 text-[#6f7682]">{selectedTask.progress}%</span>
                </div>
              </div>
            ) : (
              // 空状态
              <div className="text-center text-[#747b86]">
                <ImageIcon className="mx-auto mb-7 h-12 w-12 stroke-[1.5] text-[#c3c7cf]" />
                <p className="text-sm">输入提示词后点击「立即生成图片」开始创作</p>
              </div>
            )}
          </div>
          ) : (
          /* 收藏 Tab 内容 —— 从后端 MinIO 加载 */
          <div className="flex-1 overflow-auto px-6 py-4">
            {loadingFavorites ? (
              <div className="grid h-full place-items-center">
                <Loader2 className="h-8 w-8 animate-spin text-[#3677ff]" />
              </div>
            ) : favorites.length === 0 ? (
              <div className="grid h-full place-items-center text-center text-[#747b86]">
                <div>
                  <Star className="mx-auto mb-4 h-10 w-10 text-[#c3c7cf]" />
                  <p className="text-sm">还没有收藏的图片</p>
                  <p className="mt-1 text-xs text-[#9ca2ad]">生成图片后点击右下角星标收藏</p>
                </div>
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-3">
                {favorites.map((fav) => (
                  <div key={fav.objectKey} className="group relative overflow-hidden rounded-lg bg-white shadow-sm">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={fav.url}
                      alt="收藏的图片"
                      className="h-32 w-full object-cover"
                    />
                    <div className="absolute inset-0 flex items-center justify-center gap-1 bg-black/40 opacity-0 transition-opacity group-hover:opacity-100">
                      <button
                        onClick={async () => {
                          try {
                            const res = await fetch(fav.url);
                            const blob = await res.blob();
                            const url = URL.createObjectURL(blob);
                            const a = document.createElement('a');
                            a.href = url;
                            a.download = 'favorite-image.png';
                            document.body.appendChild(a);
                            a.click();
                            document.body.removeChild(a);
                            URL.revokeObjectURL(url);
                          } catch {
                            window.open(fav.url, '_blank');
                          }
                        }}
                        className="rounded bg-white/90 px-2 py-1 text-xs text-[#303642] hover:bg-white"
                        title="下载"
                      >
                        下载
                      </button>
                      <button
                        disabled={deletingIds.has(fav.objectKey)}
                        onClick={async () => {
                          // 标记为删除中（乐观更新）
                          setDeletingIds((prev) => new Set(prev).add(fav.objectKey));
                          setFavorites((prev) => prev.filter((f) => f.objectKey !== fav.objectKey));
                          try {
                            await imageApi.unfavoriteImage(fav.objectKey);
                          } catch (err) {
                            console.error('取消收藏失败:', err);
                            // 失败时回退
                            setFavorites((prev) => [fav, ...prev]);
                          } finally {
                            setDeletingIds((prev) => {
                              const next = new Set(prev);
                              next.delete(fav.objectKey);
                              return next;
                            });
                          }
                        }}
                        className="rounded bg-white/90 px-2 py-1 text-xs text-[#e5484d] hover:bg-white disabled:opacity-50 disabled:cursor-not-allowed"
                        title={deletingIds.has(fav.objectKey) ? '删除中...' : '取消收藏'}
                      >
                        {deletingIds.has(fav.objectKey) ? '删除中' : '移除'}
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
          )}
        </section>

        <aside className="flex min-h-0 flex-col rounded-xl border border-[#e4e5e9] bg-[#fbfbfc] py-4">
          {sidebarCollapsed ? (
            <button
              type="button"
              onClick={() => setSidebarCollapsed(false)}
              className="mx-auto grid h-7 w-7 place-items-center rounded-md text-[#a6abb4] hover:bg-[#e8ecf1] hover:text-[#3f4652]"
              title="展开侧栏"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          ) : (
            <div className="flex items-center justify-between px-3">
              <span className="text-xs font-semibold text-[#3f4652]">任务队列</span>
              <button
                type="button"
                onClick={() => setSidebarCollapsed(true)}
                className="grid h-6 w-6 place-items-center rounded-md text-[#a6abb4] hover:bg-[#e8ecf1] hover:text-[#3f4652]"
                title="收起侧栏"
              >
                <PanelRightClose className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
          {!sidebarCollapsed && (
            <>
              <div className="mt-5 flex gap-2 rounded-lg border border-[#eceef2] bg-white px-2.5 py-2.5 text-center shadow-sm">
                <div className="flex-1">
                  <div className="text-[10px] text-[#9ca2ad]">排队中</div>
                  <div className="text-base font-semibold leading-5">{displayQueueCount}</div>
                </div>
                <div className="w-px bg-[#eceef2]" />
                <div className="flex-1">
                  <div className="text-[10px] text-[#9ca2ad]">生成中</div>
                  <div className="text-base font-semibold leading-5">
                    {/* 优先显示当前活动生成任务，再叠加 store 中其他任务数 */}
                    {(activeGeneration ? 1 : 0) + displayRunningCount}
                  </div>
                </div>
              </div>
              <div className="mt-3 flex min-h-0 flex-1 flex-col items-center gap-2 overflow-auto px-1.5">
                {/* 当前正在生成的任务（仿视频侧栏卡片样式：aspect-square） */}
                {activeGeneration && !generationHistory.some((h) => h.url === generatedUrl) && (
                  <div
                    className={cn(
                      'relative w-full flex-none overflow-hidden rounded-lg border shadow-sm transition',
                      'border-[#e7e9ed]'
                    )}
                  >
                    <div
                      className="flex aspect-square w-full items-center justify-center bg-[#f4f5f7] text-[#9ca2ad]"
                      style={{
                        backgroundImage: generatedUrl
                          ? `url(${generatedUrl})`
                          : activeGeneration.referencePreviewUrl
                            ? `url(${activeGeneration.referencePreviewUrl})`
                            : undefined,
                        backgroundSize: 'cover',
                        backgroundPosition: 'center',
                      }}
                    >
                      {!generatedUrl && !activeGeneration.referencePreviewUrl && (
                        <ImageIcon className="h-4 w-4 text-[#a4aab5]" />
                      )}
                      {/* 切走再回来后 React state generating 已为 false，但仍要显示旋转 Loader */}
                      <Loader2 className="h-4 w-4 animate-spin text-[#4f7cff]" />
                    </div>
                    {/* 蓝色进度条：始终显示 */}
                    <div className="absolute bottom-0 left-0 h-0.5 bg-[#4f7cff] w-full" />
                  </div>
                )}

                {/* 历史生成图列表 */}
                {generationHistory.length === 0 && !activeGeneration && (
                  <p className="pt-8 text-[10px] text-[#c2c6cf]">暂无任务</p>
                )}
                {dedupeHistory(generationHistory).map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => {
                      setGeneratedUrl(item.url);
                      setGeneratedAt(item.createdAt);
                      setActiveTab('preview');
                    }}
                    title={item.prompt || '(空提示词)'}
                    className={cn(
                      'relative w-full flex-none overflow-hidden rounded-lg border shadow-sm transition',
                      generatedUrl === item.url
                        ? 'border-[#4f7cff] ring-1 ring-[#4f7cff]/30'
                        : 'border-[#e7e9ed] hover:border-[#cbd3e6]'
                    )}
                  >
                    <img src={item.url} alt="" className="aspect-square w-full object-cover" />
                  </button>
                ))}
                </div>
              <div className="mt-auto pb-2" />
            </>
          )}
        </aside>
      </div>

      <MediaPickerDialog
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        onConfirm={handleConfirmPicked}
        onUploadFiles={handleUploadFiles}
        onRemoveUploaded={(id) => {
          removeMaterial(id);
          setReferences((current) => current.filter((reference) => reference.id !== id));
        }}
        uploadedFiles={materials.filter((material) => material.type === 'image')}
        showMockAssets={false}
        max={remainingRefs}
      />

      {/* 参考素材预览弹窗:点击已选缩略图时全屏播放 */}
      <MediaPreviewDialog media={referencePreview} onClose={() => setReferencePreview(null)} />

      {/* 新建确认弹窗：当编辑器有内容（文字/图片/生成中/已生成）时弹出确认 */}
      {showNewConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-[2px]">
          <div className="w-[380px] rounded-xl bg-white p-6 shadow-xl">
            <div className="mb-4 flex items-center gap-2">
              <MessageCircleQuestion className="h-5 w-5 text-[#3677ff]" />
              <h3 className="text-base font-semibold text-[#1a1d26]">已有正在编辑的任务</h3>
            </div>
            <p className="mb-6 text-sm text-[#6b7280]">
              新建会覆盖当前编辑内容，可继续编辑或新建
            </p>
            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setShowNewConfirm(false)}
                className="rounded-md border border-[#e4e5e9] px-4 py-2 text-sm text-[#6b7280] hover:bg-[#f3f4f6]"
              >
                继续编辑
              </button>
              <button
                type="button"
                onClick={handleNewConfirm}
                className="rounded-md bg-[#3677ff] px-4 py-2 text-sm font-medium text-white hover:bg-[#2a67e6]"
              >
                新建
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 保存提示词弹窗 */}
      {showSavePromptDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-[2px]">
          <div className="w-[480px] rounded-xl bg-white p-6 shadow-xl">
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Save className="h-5 w-5 text-[#3677ff]" />
                <h3 className="text-base font-semibold text-[#1a1d26]">保存提示词</h3>
              </div>
              <button
                type="button"
                onClick={() => { setShowSavePromptDialog(false); setSaveTitleInput(''); }}
                className="rounded-md p-1 text-[#9ca2ad] hover:bg-[#f3f4f6]"
                aria-label="关闭"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <p className="mb-3 text-sm text-[#6b7280]">
              将当前输入的提示词保存到"我的提示词"，方便下次快速使用
            </p>
            <div className="mb-3">
              <label className="mb-1 block text-sm text-[#6b7280]">标题</label>
              <input
                type="text"
                value={saveTitleInput}
                onChange={(e) => setSaveTitleInput(e.target.value)}
                placeholder="为提示词命名（可选）"
                maxLength={50}
                className="h-9 w-full rounded-lg border border-[#e4e5e9] bg-white px-3 text-sm text-[#1a1d26] outline-none placeholder:text-[#b8bdc7] focus:border-[#3677ff]"
              />
            </div>
            <div className="mb-4">
              <label className="mb-1 block text-sm text-[#6b7280]">内容</label>
              <div className="max-h-40 overflow-y-auto rounded-lg border border-[#e4e5e9] bg-[#f7f8fa] p-3 text-sm text-[#1a1d26]">
                {prompt.trim() || <span className="text-[#9ca2ad]">暂无提示词内容</span>}
              </div>
            </div>
            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => { setShowSavePromptDialog(false); setSaveTitleInput(''); }}
                className="rounded-md border border-[#e4e5e9] px-4 py-2 text-sm text-[#6b7280] hover:bg-[#f3f4f6]"
              >
                取消
              </button>
              <button
                type="button"
                onClick={handleSavePrompt}
                disabled={!prompt.trim() || savingPrompt}
                className="flex items-center gap-1 rounded-md bg-[#3677ff] px-4 py-2 text-sm font-medium text-white hover:bg-[#2a67e6] disabled:opacity-50"
              >
                {savingPrompt && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                保存
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 编辑提示词弹窗 */}
      {showEditPromptDialog && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/30 backdrop-blur-[2px]">
          <div className="w-[480px] rounded-xl bg-white p-6 shadow-xl">
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Save className="h-5 w-5 text-[#3677ff]" />
                <h3 className="text-base font-semibold text-[#1a1d26]">编辑提示词</h3>
              </div>
              <button
                type="button"
                onClick={() => { setShowEditPromptDialog(false); setEditingPromptId(null); }}
                className="rounded-md p-1 text-[#9ca2ad] hover:bg-[#f3f4f6]"
                aria-label="关闭"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="mb-3">
              <label className="mb-1 block text-sm text-[#6b7280]">标题</label>
              <input
                type="text"
                value={editingTitle}
                onChange={(e) => setEditingTitle(e.target.value)}
                placeholder="为提示词命名（可选）"
                maxLength={50}
                className="h-9 w-full rounded-lg border border-[#e4e5e9] bg-white px-3 text-sm text-[#1a1d26] outline-none placeholder:text-[#b8bdc7] focus:border-[#3677ff]"
              />
            </div>
            <div className="mb-4">
              <label className="mb-1 block text-sm text-[#6b7280]">内容</label>
              <textarea
                value={editingContent}
                onChange={(e) => setEditingContent(e.target.value)}
                placeholder="输入提示词内容"
                rows={5}
                className="w-full resize-none rounded-lg border border-[#e4e5e9] bg-[#f7f8fa] p-3 text-sm text-[#1a1d26] outline-none placeholder:text-[#9ca2ad] focus:border-[#3677ff]"
              />
            </div>
            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => { setShowEditPromptDialog(false); setEditingPromptId(null); }}
                className="rounded-md border border-[#e4e5e9] px-4 py-2 text-sm text-[#6b7280] hover:bg-[#f3f4f6]"
              >
                取消
              </button>
              <button
                type="button"
                onClick={handleUpdatePrompt}
                disabled={!editingContent.trim() || savingPrompt}
                className="flex items-center gap-1 rounded-md bg-[#3677ff] px-4 py-2 text-sm font-medium text-white hover:bg-[#2a67e6] disabled:opacity-50"
              >
                {savingPrompt && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                保存修改
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 我的提示词弹窗 */}
      {showMyPromptsDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-[2px]">
          <div className="w-[560px] rounded-xl bg-white p-6 shadow-xl">
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <FolderOpen className="h-5 w-5 text-[#3677ff]" />
                <h3 className="text-base font-semibold text-[#1a1d26]">我的提示词</h3>
                <span className="rounded-full bg-[#f0f3ff] px-2 py-0.5 text-xs text-[#3677ff]">
                  {myPrompts.length} 条
                </span>
              </div>
              <button
                type="button"
                onClick={() => setShowMyPromptsDialog(false)}
                className="rounded-md p-1 text-[#9ca2ad] hover:bg-[#f3f4f6]"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="max-h-[360px] overflow-y-auto">
              {loadingPrompts ? (
                <div className="flex items-center justify-center py-10 text-sm text-[#9ca2ad]">
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  加载中...
                </div>
              ) : myPrompts.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-10">
                  <FolderOpen className="mb-3 h-10 w-10 text-[#d7dbe3]" />
                  <p className="text-sm text-[#9ca2ad]">暂无保存的提示词</p>
                  <p className="mt-1 text-xs text-[#c3c7cf]">在生成图片前点击"保存提示词"来保存您的常用提示词</p>
                </div>
              ) : (
                <ul className="space-y-2">
                  {myPrompts.map((item) => (
                    <li
                      key={item.id}
                      className="group flex items-start gap-3 rounded-lg border border-[#eceef2] bg-white p-3 transition-colors hover:border-[#3677ff] hover:bg-[#f5f8ff] cursor-pointer"
                      onClick={() => handleUsePrompt(item)}
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate text-sm font-medium text-[#1a1d26]">{item.title || '未命名'}</span>
                          <span className="flex-none text-[11px] text-[#9ca2ad]">
                            {new Date(item.createdAt).toLocaleDateString('zh-CN', {
                              month: '2-digit',
                              day: '2-digit',
                            })}
                          </span>
                        </div>
                        <p className="mt-1 text-xs text-[#6b7280] line-clamp-2">{item.prompt}</p>
                      </div>
                      <div className="flex flex-none items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
                        <button
                          type="button"
                          onClick={(e) => { e.stopPropagation(); handleOpenEditPrompt(item); }}
                          className="rounded-md p-1.5 text-[#9ca2ad] hover:bg-[#f0f3ff] hover:text-[#3677ff]"
                          title="编辑"
                        >
                          <Edit2 className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={(e) => { e.stopPropagation(); handleDeletePrompt(item.id); }}
                          className="rounded-md p-1.5 text-[#9ca2ad] hover:bg-[#fef0f0] hover:text-[#ef4444]"
                          title="删除"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </div>
      )}
    </main>
  );
}

export function ImageSidebarBottom() {
  return (
    <>
      <button
        type="button"
        className="flex w-12 flex-col items-center gap-0.5 rounded-xl py-1.5 text-fg-muted hover:text-brand"
        title="积分"
      >
        <span className="grid h-9 w-9 place-items-center rounded-xl border border-bg-line bg-bg-soft">
          <Coins className="h-4 w-4 text-brand" />
        </span>
        <span className="text-[10px]">0</span>
      </button>
      <button
        type="button"
        className="grid h-9 w-9 place-items-center rounded-full bg-brand-50 text-brand shadow-soft"
        title="助手"
      >
        <Bot className="h-4 w-4" />
      </button>
      <button
        type="button"
        className="grid h-9 w-9 place-items-center rounded-xl text-fg-muted hover:bg-bg-soft hover:text-fg"
        title="菜单"
      >
        <Menu className="h-4 w-4" />
      </button>
    </>
  );
}

function SelectCard<T extends string>({
  icon,
  label,
  value,
  subValue,
  options,
  onChange,
}: {
  icon: ReactNode;
  label: string;
  value: T;
  subValue?: string;
  options: readonly T[];
  onChange: (value: T) => void;
}) {
  const [open, setOpen] = useState(false);

  return (
    <div className="relative">
      {open && (
        <div className="absolute bottom-full left-0 z-50 mb-2 w-full min-w-[260px] rounded-xl border border-[#dfe2e8] bg-[#fbfbfc] p-2 shadow-[0_12px_28px_rgba(29,35,48,0.12)]">
          {options.map((option) => {
            const selected = option === value;
            return (
              <button
                key={option}
                type="button"
                onClick={() => {
                  onChange(option);
                  setOpen(false);
                }}
                className={cn(
                  'flex w-full items-center justify-between rounded-lg px-2 py-2 text-left text-xs transition',
                  selected ? 'bg-white font-semibold text-[#20242b] shadow-sm' : 'text-[#68707c] hover:bg-white'
                )}
              >
                {option}
                {selected && <Check className="h-3.5 w-3.5" />}
              </button>
            );
          })}
        </div>
      )}
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="flex h-[52px] w-full items-center gap-3 rounded-lg border border-[#e4e5e9] bg-[#fbfbfc] px-3 text-left transition hover:border-[#cdd2dc]"
      >
        <span className="grid h-8 w-8 place-items-center rounded-md border border-[#eceef2] bg-white text-[#89909b]">
          {icon}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] text-[#707784]">{label}</span>
          <span className="block truncate text-xs font-semibold text-[#242832]">
            {value}
            {subValue && <span className="ml-1 text-[10px] font-medium text-[#707784]">{subValue}</span>}
          </span>
        </span>
        <ChevronDown className={cn('h-4 w-4 text-[#9da3ad] transition-transform', open && 'rotate-180')} />
      </button>
    </div>
  );
}

function ImageSettingsCard({
  ratio,
  setRatio,
  resolution,
  setResolution,
  format,
  setFormat,
}: {
  ratio: ImageRatio;
  setRatio: (value: ImageRatio) => void;
  resolution: ImageResolution;
  setResolution: (value: ImageResolution) => void;
  format: ImageFormat;
  setFormat: (value: ImageFormat) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      {open && (
        <div className="absolute bottom-full left-0 z-50 mb-2 w-full min-w-[360px] rounded-xl border border-[#dfe2e8] bg-[#fbfbfc] p-3 shadow-[0_12px_28px_rgba(29,35,48,0.12)]">
          <SettingLabel>图片比例</SettingLabel>
          <div className="grid grid-cols-6 gap-2">
            {RATIOS.map((option) => (
              <OptionButton key={option} selected={option === ratio} onClick={() => setRatio(option)}>
                {option}
              </OptionButton>
            ))}
          </div>
          <SettingLabel className="mt-3">清晰度</SettingLabel>
          <div className="flex gap-2">
            {RESOLUTIONS.map((option) => (
              <OptionButton key={option} selected={option === resolution} onClick={() => setResolution(option)}>
                {option}
              </OptionButton>
            ))}
          </div>
          <SettingLabel className="mt-3">格式</SettingLabel>
          <div className="flex gap-2">
            {FORMATS.map((option) => (
              <OptionButton key={option} selected={option === format} onClick={() => setFormat(option)}>
                {option}
              </OptionButton>
            ))}
          </div>
        </div>
      )}
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="flex h-[52px] w-full items-center gap-3 rounded-lg border border-[#e4e5e9] bg-[#fbfbfc] px-3 text-left transition hover:border-[#cdd2dc]"
      >
        <span className="grid h-8 w-8 place-items-center rounded-md border border-[#eceef2] bg-white text-[#89909b]">
          <Layers3 className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] text-[#707784]">图片设置</span>
          <span className="block truncate text-xs font-semibold text-[#242832]">
            {ratio} · {resolution} · {format}
          </span>
        </span>
        <ChevronDown className={cn('h-4 w-4 text-[#9da3ad] transition-transform', open && 'rotate-180')} />
      </button>
    </div>
  );
}

function SettingLabel({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('mb-1.5 text-[10px] font-medium text-[#707784]', className)}>{children}</div>;
}

function OptionButton({
  selected,
  onClick,
  children,
}: {
  selected: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'relative flex h-8 flex-1 items-center justify-center rounded-lg border bg-white px-2 text-[11px] transition',
        selected
          ? 'border-[#20242b] font-semibold text-[#20242b]'
          : 'border-[#e7e9ed] text-[#68707c] hover:border-[#cbd3e6]'
      )}
    >
      {children}
    </button>
  );
}
