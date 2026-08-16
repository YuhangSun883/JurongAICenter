'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { ChevronLeft, Plus, ChevronDown, ChevronLeft as L, ChevronRight as R, ChevronRight, Loader2, Trash2, Download, Maximize2, Copy, Sparkles, RotateCw, Image as ImageIcon } from 'lucide-react';
import { Sidebar } from '@/components/home/Sidebar';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { StyledSelect, type StyledSelectOption } from '@/components/common/StyledSelect';
import { SettingsPopover } from '@/components/common/SettingsPopover';
import { cn } from '@/lib/utils';
import { productImageApi } from '@/api/product-image';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
import type {
  CreateProductImageRequest,
  FormatOption,
  ProductImageAnalysisItem,
  ProductImageAnalysisTask,
  ProductImageExample,
  ProductImageFormat,
  ProductImageModel,
  ProductImageResolution,
  ProductImageTask,
  ResolutionOption,
} from '@/types/product-image';

const LANGS = ['中文', 'English'] as const;
const COUNTS = ['4 张', '8 张', '12 张'] as const;

/** 分析结果 sessionStorage 恢复键（切去其他功能再回来不丢分析卡片） */
const ANALYSIS_RESTORE_KEY = 'product-image.analysisRestore.v1';
/** 进行中的分析任务快照（提交时写入，完成/失败后清除）：等待期间切去其它功能，返回后续接轮询恢复进度环 */
const ANALYSIS_PENDING_KEY = 'product-image.analysisPending.v1';
/** 页面配置状态快照（上传图片/补充说明/语言张数/模型分辨率格式/进度环）：切去其它功能再回来原样恢复 */
const PAGE_STATE_KEY = 'product-image.pageState.v1';

/** 分析等待的阶段文案（进度环下方滚动展示） */
const ANALYSIS_STEPS = [
  '正在读取商品图片',
  '正在识别商品主体',
  '正在比对平台规范',
  '正在梳理细节要素',
  '正在生成设计分析',
] as const;

/** 整理 LLM 分析文案：去除 markdown 符号、压缩多余空白与换行 */
function cleanSectionValue(v: string): string {
  return v
    .replace(/[*_`#]+/g, '')
    .replace(/[ \t]+/g, ' ')
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .join(' ')
    .trim();
}

/** 格式化任务时间：08/02 12:05 */
function formatTaskDate(ts: number): string {
  const d = new Date(ts);
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mi = String(d.getMinutes()).padStart(2, '0');
  return `${mm}/${dd} ${hh}:${mi}`;
}

interface PickedAsset {
  id: string;
  url: string;
  name: string;
}

export default function ProductImageWorkbench() {
  // ===== 素材库：使用全局 MaterialsContext（跨页面共享） =====
  const { materials: gMaterials, addMaterials: gAddMaterials, removeMaterial: gRemoveMaterial } = useMaterials();
  const materials = gMaterials; // 适配当前页面引用
  // ===== 本次任务选中的图（主页面缩略图，引用自 materials） =====
  const [assets, setAssets] = useState<PickedAsset[]>([]);
  // 新建确认弹窗（已有编辑中的任务时弹）
  const [pendingNewTask, setPendingNewTask] = useState(false);
  // 任务队列收起状态
  const [queueCollapsed, setQueueCollapsed] = useState(false);
  // ===== 数据 =====
  const [models, setModels] = useState<ProductImageModel[]>([]);
  const [resolutions, setResolutions] = useState<ResolutionOption[]>([]);
  const [formats, setFormats] = useState<FormatOption[]>([]);
  const [examples, setExamples] = useState<ProductImageExample[]>([]);
  const [exIdx, setExIdx] = useState(0);

  // ===== 配置 =====
  const [lang, setLang] = useState<(typeof LANGS)[number]>('中文');
  const [count, setCount] = useState<(typeof COUNTS)[number]>('8 张');
  const [brief, setBrief] = useState('');
  const [modelKey, setModelKey] = useState('premium');
  const [resolution, setResolution] = useState<ProductImageResolution>('1K');
  const [format, setFormat] = useState<ProductImageFormat>('JPEG');

  // ===== 任务队列 =====
  const [tasks, setTasks] = useState<ProductImageTask[]>([]);

  // ===== 弹窗 =====
  const [pickerOpen, setPickerOpen] = useState(false);
  // ===== 中间区域标签页：分析结果（参考示例）/ 生成结果 =====
  const [resultTab, setResultTab] = useState<'examples' | 'results'>('examples');
  // ===== 分析结果（商品详解，LLM 多模态生成） =====
  const [roleOptions, setRoleOptions] = useState<string[]>([]);
  const [analysisTask, setAnalysisTask] = useState<ProductImageAnalysisTask | null>(null);
  /** 放大视图：分析卡片点放大后全文大字展示（文字可编辑） */
  const [enlargedItem, setEnlargedItem] = useState<ProductImageAnalysisItem | null>(null);
  /** 放大视图对应的分析条目下标（保存时定位要更新的 item） */
  const [enlargedIdx, setEnlargedIdx] = useState(-1);
  /** 放大视图编辑草稿：各 section 的 value（未改动过时为 null） */
  const [enlargedDraft, setEnlargedDraft] = useState<string[] | null>(null);
  /** 打开放大视图：同时初始化编辑草稿 */
  function openEnlarged(item: ProductImageAnalysisItem, idx: number) {
    setEnlargedItem(item);
    setEnlargedIdx(idx);
    setEnlargedDraft(item.sections.map((s) => cleanSectionValue(s.value)));
  }
  /** 取消编辑：丢弃草稿直接关闭 */
  function cancelEnlarged() {
    setEnlargedItem(null);
    setEnlargedIdx(-1);
    setEnlargedDraft(null);
  }
  /** 保存编辑：写回分析任务（卡片展示/复制/重新生成均用新文案），并同步恢复快照 */
  function saveEnlarged() {
    if (!enlargedItem || !analysisTask || enlargedIdx < 0) { cancelEnlarged(); return; }
    const draft = enlargedDraft ?? [];
    const items = (analysisTask.items ?? []).map((it, i) => (i === enlargedIdx
      ? { ...it, sections: it.sections.map((s, si) => ({ key: s.key, value: draft[si] ?? cleanSectionValue(s.value) })) }
      : it));
    const next = { ...analysisTask, items };
    setAnalysisTask(next);
    // 同步更新 sessionStorage 恢复快照，避免切走再回来后编辑被旧文案覆盖
    try {
      sessionStorage.setItem(ANALYSIS_RESTORE_KEY, JSON.stringify({ task: next, refAssets: analysisRefSnapshotsRef.current[analysisTask.taskId] ?? [] }));
    } catch { /* 忽略 */ }
    showToast('分析文案已保存');
    cancelEnlarged();
  }
  /** 分析卡片类型下拉的本地覆盖（taskId-index → role） */
  const [roleSel, setRoleSel] = useState<Record<string, string>>({});
  /** 分析卡片比例下拉的本地覆盖（taskId-index → ratio） */
  const [ratioSel, setRatioSel] = useState<Record<string, string>>({});
  /** 每个分析任务提交时的商品图快照（taskId → 提交时上传列表）：删除上传图片后，分析卡引用图与重新生成仍可用 */
  const [analysisRefSnapshots, setAnalysisRefSnapshots] = useState<Record<string, PickedAsset[]>>({});
  /** 快照的 ref 镜像：轮询闭包是提交时的旧闭包，读不到后续更新的 state，持久化时改读这里 */
  const analysisRefSnapshotsRef = useRef<Record<string, PickedAsset[]>>({});
  // ===== 分析等待动效：模拟进度环 + 阶段文案 =====
  const [analysisPercent, setAnalysisPercent] = useState(0);
  const [analysisStepIdx, setAnalysisStepIdx] = useState(0);
  /** 分析卡片「重新生成」加载态（selKey → loading） */
  const [genLoading, setGenLoading] = useState<Record<string, boolean>>({});
  /** 全部生成：并发提交全部分析条目的整体加载态 */
  const [bulkGenerating, setBulkGenerating] = useState(false);
  /** 已自动触发过并发生成的分析任务 id（防止轮询重复触发） */
  const autoGenDoneRef = useRef<Set<string>>(new Set());
  // ===== 图片预览 =====
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  // ===== Toast 提示 =====
  const [toast, setToast] = useState<string | null>(null);
  function showToast(msg: string) {
    setToast(msg);
    setTimeout(() => setToast(null), 2400);
  }
  // ===== 待删除确认 =====
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);
  // ===== 任务队列批量删除 =====
  /** 是否处于批量删除选择模式 */
  const [selectMode, setSelectMode] = useState(false);
  /** 已勾选待删除的任务 id */
  const [selectedTaskIds, setSelectedTaskIds] = useState<string[]>([]);
  /** 批量删除整体加载态 */
  const [bulkDeleting, setBulkDeleting] = useState(false);
  /** 已删除的任务 id（对应轮询检测到后自行停止） */
  const deletedTaskIdsRef = useRef<Set<string>>(new Set());

  // ===== 拉数据 =====
  useEffect(() => {
    // 恢复页面配置状态（上传图片/补充说明/语言张数/模型分辨率格式/进度环）：切去其它功能再回来原样继续
    let restoredModelKey: string | null = null;
    try {
      const rawState = sessionStorage.getItem(PAGE_STATE_KEY);
      if (rawState) {
        const s = JSON.parse(rawState) as {
          assets?: PickedAsset[]; brief?: string; lang?: string; count?: string;
          modelKey?: string; resolution?: string; format?: string;
          analysisPercent?: number; analysisStepIdx?: number;
        };
        if (Array.isArray(s.assets)) setAssets(s.assets);
        if (typeof s.brief === 'string') setBrief(s.brief);
        if (s.lang && (LANGS as readonly string[]).includes(s.lang)) setLang(s.lang as (typeof LANGS)[number]);
        if (s.count && (COUNTS as readonly string[]).includes(s.count)) setCount(s.count as (typeof COUNTS)[number]);
        if (s.modelKey) { setModelKey(s.modelKey); restoredModelKey = s.modelKey; }
        if (s.resolution) setResolution(s.resolution as ProductImageResolution);
        if (s.format) setFormat(s.format as ProductImageFormat);
        // 进度环：仅在有进行中分析时生效（analyzing 的动效 effect 会在非分析态自动归零）
        if (typeof s.analysisPercent === 'number') setAnalysisPercent(s.analysisPercent);
        if (typeof s.analysisStepIdx === 'number') setAnalysisStepIdx(s.analysisStepIdx);
      }
    } catch (e) {
      console.warn('[product-image] restore page state failed', e);
    }
    productImageApi.listModels().then((arr) => {
      setModels(arr);
      // 优先沿用恢复的模型，没有恢复值才回退默认 premium
      if (restoredModelKey && arr.some((m) => m.key === restoredModelKey)) return;
      const def = arr.find((m) => m.key === 'premium') ?? arr[0];
      if (def) setModelKey(def.key);
    });
    productImageApi.listResolutions().then(setResolutions);
    productImageApi.listFormats().then(setFormats);
    productImageApi.listExamples().then(setExamples);
    productImageApi.listRoles().then(setRoleOptions);
    // 恢复分析结果（sessionStorage 快照）；标记已自动生成过，避免恢复后重复触发并发生成消耗积分
    let restoredTaskId: string | null = null;
    try {
      const raw = sessionStorage.getItem(ANALYSIS_RESTORE_KEY);
      if (raw) {
        const saved = JSON.parse(raw) as { task?: ProductImageAnalysisTask; refAssets?: PickedAsset[] };
        if (saved.task && saved.task.status === 'success' && (saved.task.items?.length ?? 0) > 0) {
          setAnalysisTask(saved.task);
          setAnalysisRefSnapshots((prev) => ({ ...prev, [saved.task!.taskId]: saved.refAssets ?? [] }));
          analysisRefSnapshotsRef.current[saved.task.taskId] = saved.refAssets ?? [];
          autoGenDoneRef.current.add(saved.task.taskId);
          restoredTaskId = saved.task.taskId;
        }
      }
    } catch (e) {
      console.warn('[product-image] restore analysis failed', e);
    }
    // 恢复进行中的分析：等待期间切去其它功能再回来时，向后端查最新状态——
    // 仍在分析则续接轮询（重新展示进度环）；离开期间已完成则恢复卡片并补触发自动并发生成
    try {
      const rawPending = sessionStorage.getItem(ANALYSIS_PENDING_KEY);
      if (rawPending) {
        const pending = JSON.parse(rawPending) as { taskId?: string; refAssets?: PickedAsset[] };
        if (pending.taskId && pending.taskId !== restoredTaskId) {
          const refAssets = pending.refAssets ?? [];
          productImageApi.getAnalysis(pending.taskId).then((t) => {
            if (t.status === 'running') {
              setAnalysisTask(t);
              setAnalysisRefSnapshots((prev) => ({ ...prev, [t.taskId]: refAssets }));
              analysisRefSnapshotsRef.current[t.taskId] = refAssets;
              pollAnalysis(t.taskId);
            } else if (t.status === 'success' && (t.items?.length ?? 0) > 0) {
              setAnalysisTask(t);
              setAnalysisRefSnapshots((prev) => ({ ...prev, [t.taskId]: refAssets }));
              analysisRefSnapshotsRef.current[t.taskId] = refAssets;
              // 与页面停留时完成同行为：写完成快照，并自动并发提交全部条目的生成（只触发一次）
              try {
                sessionStorage.setItem(ANALYSIS_RESTORE_KEY, JSON.stringify({ task: t, refAssets }));
                sessionStorage.removeItem(ANALYSIS_PENDING_KEY);
              } catch (err) {
                console.warn('[product-image] persist restored analysis failed', err);
              }
              if (!autoGenDoneRef.current.has(t.taskId)) {
                autoGenDoneRef.current.add(t.taskId);
                generateAllFromAnalysis(t.items ?? [], t.taskId);
              }
            } else {
              // 失败 / 空结果 / 任务已不存在：清理进行中快照
              sessionStorage.removeItem(ANALYSIS_PENDING_KEY);
            }
          }).catch(() => {
            try { sessionStorage.removeItem(ANALYSIS_PENDING_KEY); } catch { /* 忽略 */ }
          });
        }
      }
    } catch (e) {
      console.warn('[product-image] restore pending analysis failed', e);
    }
    // 恢复任务队列：拉后端任务列表（含已出图的结果），仍在生成中的任务续接轮询
    productImageApi.listTasks().then((list) => {
      if (list.length === 0) return;
      setTasks((prev) => {
        const exist = new Set(prev.map((t) => t.taskId));
        return [...list.filter((t) => !exist.has(t.taskId)), ...prev];
      });
      list.filter((t) => t.status === 'running').forEach((t) => pollTask(t.taskId));
    }).catch((e) => console.error('[product-image] listTasks failed', e));
  }, []);

  // ===== 持久化页面配置状态：切去其它功能再回来时原样恢复（进行中分析的进度环也一并保留） =====
  useEffect(() => {
    try {
      sessionStorage.setItem(PAGE_STATE_KEY, JSON.stringify({
        assets, brief, lang, count, modelKey, resolution, format, analysisPercent, analysisStepIdx,
      }));
    } catch { /* 超出容量等异常忽略，不影响主流程 */ }
  }, [assets, brief, lang, count, modelKey, resolution, format, analysisPercent, analysisStepIdx]);

  // ===== 轮播 =====
  function prevEx() {
    setExIdx((i) => (examples.length === 0 ? 0 : (i - 1 + examples.length) % examples.length));
  }
  function nextEx() {
    setExIdx((i) => (examples.length === 0 ? 0 : (i + 1) % examples.length));
  }

  // ===== 任务 =====
  /** 把商品图 URL（blob/MinIO/外链）转成 base64 data URI，与 AI 图片工作台同一做法 */
  async function urlToBase64(url: string): Promise<string> {
    if (url.startsWith('data:')) return url;
    const response = await fetch(url);
    const blob = await response.blob();
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => resolve(reader.result as string);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  /** 下载单张图：fetch → blob → 另存；跨域被拦时退化为新标签页打开 */
  async function downloadImage(url: string, filename: string) {
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(String(res.status));
      const blob = await res.blob();
      const href = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = href;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(href), 5000);
    } catch (e) {
      console.warn('[product-image] download fallback', e);
      window.open(url, '_blank');
    }
  }

  /** 批量下载当前展示的全部结果图（跨任务合并后，逐张间隔 300ms，避免浏览器拦截） */
  async function batchDownloadAll() {
    if (resultImages.length === 0) return;
    showToast(`正在下载 ${resultImages.length} 张图片…`);
    for (let i = 0; i < resultImages.length; i++) {
      const { url, role } = resultImages[i];
      await downloadImage(url, `商详套图-${String(i + 1).padStart(2, '0')}-${role}.png`);
      await new Promise((r) => setTimeout(r, 300));
    }
  }

  function removeTask(taskId: string) {
    deletedTaskIdsRef.current.add(taskId);
    setTasks((prev) => prev.filter((t) => t.taskId !== taskId));
    // 后端任务同步调删除接口（与资产删除 API 同语义：直接删数据库数据）；本地「编辑中」任务只删本地
    if (!taskId.startsWith('task_')) {
      productImageApi.batchDeleteTasks([taskId]).catch((e) => console.error('[product-image] deleteTask failed', e));
    }
  }

  /** 勾选/取消勾选某个任务 */
  function toggleTaskSelect(taskId: string) {
    setSelectedTaskIds((prev) => (prev.includes(taskId) ? prev.filter((x) => x !== taskId) : [...prev, taskId]));
  }

  /** 批量删除所选任务：与资产批量删除 API 同语义，直接删除数据库数据（含生成结果图），确认后不可恢复 */
  async function batchDeleteSelected() {
    if (selectedTaskIds.length === 0 || bulkDeleting) return;
    if (!window.confirm(`确认删除所选 ${selectedTaskIds.length} 个任务？其生成的结果图将一并从数据库中删除，无法恢复。`)) return;
    const ids = [...selectedTaskIds];
    setBulkDeleting(true);
    try {
      ids.forEach((id) => deletedTaskIdsRef.current.add(id));
      // 本地「编辑中」任务未进后端，只删本地；后端任务走批量删除接口
      const backendIds = ids.filter((id) => !id.startsWith('task_'));
      if (backendIds.length > 0) {
        await productImageApi.batchDeleteTasks(backendIds);
      }
      setTasks((prev) => prev.filter((t) => !ids.includes(t.taskId)));
      setSelectedTaskIds([]);
      setSelectMode(false);
      showToast(`已删除 ${ids.length} 个任务`);
    } catch (e) {
      console.error('[product-image] batchDeleteTasks failed', e);
      showToast('删除失败，请稍后重试');
    } finally {
      setBulkDeleting(false);
    }
  }

  /** 点"新建"按钮：检查是否有正在编辑的任务或已有资产，有则弹确认，无则直接创建 */
  function handleNewClick() {
    const hasEditing = tasks.some((t) => t.status === 'editing');
    const hasAssets = assets.length > 0;
    // 有 editing 任务 或 有 assets → 弹窗确认
    if (hasEditing || hasAssets) {
      setPendingNewTask(true);
    } else {
      createNewEditingTask();
    }
  }

  /** 直接创建一个新的"编辑中"任务（覆盖旧的 editing，只留一个） */
  function createNewEditingTask() {
    setAssets([]);
    // 删除所有"编辑中"任务（保留其他状态的任务），只留新的这一个
    setTasks((prev) => prev.filter((t) => t.status !== 'editing'));
    const t: ProductImageTask = {
      taskId: `task_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      status: 'editing',
      creditsCost: 200,
      createdAt: Date.now(),
      previewUrls: [],
    };
    setTasks((prev) => [t, ...prev]);
  }

  function pollTask(id: string) {
    let n = 0;
    const tick = async () => {
      if (deletedTaskIdsRef.current.has(id)) return; // 任务已删除，停止轮询
      try {
        const t = await productImageApi.getTask(id);
        setTasks((prev) => prev.map((x) => (x.taskId === id ? t : x)));
        // 套图逐张生成耗时较长（可达几分钟），轮询窗口放大到 ~8 分钟
        if (t.status === 'editing' || t.status === 'running') {
          if (n++ < 240) setTimeout(tick, 2000);
        }
      } catch (e) {
        console.error('poll failed', e);
      }
    };
    setTimeout(tick, 1200);
  }

  /** 生成商品详解（「分析结果」标签）：提交多模态 LLM 分析任务并轮询；
   *  无论当前停留在「分析结果」还是「生成结果」页，点击都会刷新页面状态并开始新的分析任务 */
  async function generateAnalysis() {
    if (assets.length === 0 || analyzing) return;
    try {
      showToast('正在提交分析任务…');
      // 刷新为新任务：回到「分析结果」页、清除上一轮分析结果与恢复快照（避免旧卡片残留）
      setResultTab('examples');
      setAnalysisTask(null);
      try {
        sessionStorage.removeItem(ANALYSIS_RESTORE_KEY);
      } catch (err) {
        console.warn('[product-image] clear analysis restore failed', err);
      }
      // 重置等待动效（进度归零，阶段文案从头播）
      setAnalysisPercent(0);
      setAnalysisStepIdx(0);
      // 先快照当前上传的商品图：之后即使用户删除上传图片，分析卡引用图仍按分析时的图展示
      const snapshot = [...assets];
      const images = await Promise.all(assets.map((a) => urlToBase64(a.url)));
      const created = await productImageApi.createAnalysis({
        assetIds: assets.map((a) => a.id),
        images,
        lang,
        count,
        brief: brief.trim() || undefined,
        modelKey,
        settingKey: `${resolution}-${format}`,
        resolution,
        format,
      });
      setAnalysisTask(created);
      setAnalysisRefSnapshots((prev) => ({ ...prev, [created.taskId]: snapshot }));
      analysisRefSnapshotsRef.current[created.taskId] = snapshot;
      // 持久化进行中快照：等待期间切去其它功能再回来时可续接轮询恢复（完成/失败后由轮询清理）
      try {
        sessionStorage.setItem(ANALYSIS_PENDING_KEY, JSON.stringify({ taskId: created.taskId, refAssets: snapshot }));
      } catch (err) {
        console.warn('[product-image] persist pending analysis failed', err);
      }
      pollAnalysis(created.taskId);
    } catch (e) {
      console.error('[product-image] createAnalysis failed', e);
      showToast('分析提交失败，请稍后重试');
    }
  }

  /** 解析分析条目对应的引用图：优先提交分析时的快照（与分析文案绑定），没有再退回当前上传列表；
   *  保证删除上传的商品图后，分析卡预览图与「重新生成」仍可用 */
  function resolveRefAsset(refIdx: number): PickedAsset | undefined {
    const snap = analysisRefSnapshots[analysisTask?.taskId ?? ''] ?? [];
    return snap[refIdx] ?? assets[refIdx] ?? snap[0] ?? assets[0];
  }

  /** 提交单条分析条目的生成任务（纯提交，不管 loading/toast）：分析文案 + 引用图 + 类型走单张生成模式 */
  async function submitGenerateItem(item: ProductImageAnalysisItem, selKey: string): Promise<ProductImageTask> {
    const refIdx = parseInt(item.refLabel.replace(/[^0-9]/g, '') || '1', 10) - 1;
    const refAsset = resolveRefAsset(refIdx);
    if (!refAsset) {
      throw new Error('缺少引用图');
    }
    const role = roleSel[selKey] ?? item.role;
    const image = await urlToBase64(refAsset.url);
    const prompt = item.sections
      .map((s) => `${s.key}: ${cleanSectionValue(s.value)}`)
      .join('\n');
    return productImageApi.createTask({
      assetIds: [refAsset.id],
      images: [image],
      lang,
      count: '4 张', // 后端单张生成模式会忽略此值
      brief: brief.trim() || undefined,
      modelKey,
      settingKey: `${resolution}-${format}`,
      resolution,
      format,
      prompt,
      role,
    });
  }

  /** 把更新后的分析条目写回：卡片同步刷新，并同步 sessionStorage 恢复快照（避免切走再回来被旧文案覆盖） */
  function updateAnalysisItem(idx: number, updated: ProductImageAnalysisItem) {
    setAnalysisTask((prev) => {
      if (!prev) return prev;
      const items = (prev.items ?? []).map((it, i) => (i === idx ? updated : it));
      const next = { ...prev, items };
      try {
        sessionStorage.setItem(ANALYSIS_RESTORE_KEY, JSON.stringify({ task: next, refAssets: analysisRefSnapshotsRef.current[prev.taskId] ?? [] }));
      } catch { /* 忽略 */ }
      return next;
    });
  }

  /** 分析卡片「重新生成」：定位/比例有变动时先调多模态 LLM 按新定位与比例重写分析文案（卡片同步更新），
   *  再用新文案 + 引用图 + 类型走图生图链路，新结果追加进「生成结果」 */
  async function generateFromAnalysis(item: ProductImageAnalysisItem, selKey: string, idx: number) {
    if (genLoading[selKey] || bulkGenerating) return;
    setGenLoading((prev) => ({ ...prev, [selKey]: true }));
    try {
      const role = roleSel[selKey] ?? item.role;
      const ratio = ratioSel[selKey] ?? item.ratio;
      const refIdx = parseInt(item.refLabel.replace(/[^0-9]/g, '') || '1', 10) - 1;
      const refAsset = resolveRefAsset(refIdx);
      if (!refAsset) {
        showToast('缺少引用图，请先上传商品图');
        return;
      }
      // 1) 定位或比例有变动：先按新定位 + 比例重写分析文案，并同步刷新卡片（未变动则跳过，省一次 LLM 调用）
      let nextItem = item;
      if (role !== item.role || ratio !== item.ratio) {
        showToast(`正在按「${role} ${ratio}」重写分析文案…`);
        const image = await urlToBase64(refAsset.url);
        const refined = await productImageApi.refineAnalysisItem({
          image,
          refLabel: item.refLabel,
          role,
          ratio,
          lang,
          resolution,
          brief: brief.trim() || undefined,
        });
        nextItem = {
          ...item,
          role,
          ratio,
          sections: (refined.sections?.length ?? 0) > 0 ? refined.sections : item.sections,
        };
        updateAnalysisItem(idx, nextItem);
      }
      // 2) 用新文案提交图生图
      const created = await submitGenerateItem(nextItem, selKey);
      setTasks((prev) => [created, ...prev.filter((t) => t.status !== 'editing')]);
      pollTask(created.taskId);
      showToast(`正在重新生成「${role}」，完成后可在生成结果查看`);
    } catch (e) {
      console.error('[product-image] generateFromAnalysis failed', e);
      showToast(e instanceof Error && e.message === '缺少引用图' ? '缺少引用图，请先上传商品图' : '重新生成失败，请稍后重试');
    } finally {
      setGenLoading((prev) => ({ ...prev, [selKey]: false }));
    }
  }

  /** 全部生成：并发提交所有分析条目的单张生成任务（后端任务池自动排队限流），结果逐条进「生成结果」 */
  async function generateAllFromAnalysis(items?: ProductImageAnalysisItem[], taskIdOverride?: string) {
    const list = items ?? analysisItems;
    if (list.length === 0 || bulkGenerating) return;
    // 轮询回调闭包里的 analysisTask 可能是旧值，优先用显式传入的 taskId
    const taskIdPrefix = taskIdOverride ?? analysisTask?.taskId ?? 'local';
    setBulkGenerating(true);
    // 所有卡片进入「提交中」态，禁止单条重复点击
    setGenLoading((prev) => {
      const next = { ...prev };
      list.forEach((_, i) => { next[`${taskIdPrefix}-${i}`] = true; });
      return next;
    });
    showToast(`分析完成，正在并发提交 ${list.length} 个生成任务…`);
    try {
      const results = await Promise.allSettled(list.map((item, i) => submitGenerateItem(item, `${taskIdPrefix}-${i}`)));
      const created: ProductImageTask[] = [];
      let failed = 0;
      results.forEach((r) => {
        if (r.status === 'fulfilled') created.push(r.value);
        else failed++;
      });
      if (created.length > 0) {
        // 后提交的排前面，与单条生成的展示顺序一致
        setTasks((prev) => [[...created].reverse(), ...prev.filter((t) => t.status !== 'editing')].flat());
        created.forEach((t) => pollTask(t.taskId));
      }
      if (failed === 0) {
        showToast(`${created.length} 个任务已提交，生成中，可在生成结果查看`);
      } else {
        showToast(`已提交 ${created.length} 个任务，${failed} 个提交失败`);
      }
    } catch (e) {
      console.error('[product-image] generateAllFromAnalysis failed', e);
      showToast('生成提交失败，请稍后重试');
    } finally {
      setGenLoading((prev) => {
        const next = { ...prev };
        list.forEach((_, i) => { next[`${taskIdPrefix}-${i}`] = false; });
        return next;
      });
      setBulkGenerating(false);
    }
  }

  function pollAnalysis(id: string) {
    let n = 0;
    const tick = async () => {
      try {
        const t = await productImageApi.getAnalysis(id);
        setAnalysisTask(t);
        // 分析到达终态（成功/失败）：清理进行中快照（成功分支会另写入完成恢复快照）
        if (t.status !== 'running') {
          try { sessionStorage.removeItem(ANALYSIS_PENDING_KEY); } catch { /* 忽略 */ }
        }
        // LLM 多模态分析：后端超时 10 分钟 + 自动重试 1 次，轮询窗口放大到 ~22 分钟
        if (t.status === 'running' && n++ < 660) {
          setTimeout(tick, 2000);
        } else if (t.status === 'success' && (t.items?.length ?? 0) > 0 && !autoGenDoneRef.current.has(id)) {
          // 分析完成：持久化到 sessionStorage，切去其他功能再回来时可恢复分析卡片（快照从 ref 读，轮询闭包里的 state 是旧值）
          try {
            sessionStorage.setItem(ANALYSIS_RESTORE_KEY, JSON.stringify({ task: t, refAssets: analysisRefSnapshotsRef.current[t.taskId] ?? [] }));
          } catch (err) {
            console.warn('[product-image] persist analysis failed', err);
          }
          // 分析完成后立即并发提交全部条目的图片生成（每个分析任务只自动触发一次）
          autoGenDoneRef.current.add(id);
          generateAllFromAnalysis(t.items ?? [], t.taskId);
        }
      } catch (e) {
        console.error('poll analysis failed', e);
      }
    };
    setTimeout(tick, 1500);
  }

  /** 复制单张分析文本到剪贴板（整理后） */
  function copyAnalysis(item: ProductImageAnalysisItem) {
    const text = item.sections.map((s) => `${s.key}: ${cleanSectionValue(s.value)}`).join('\n');
    navigator.clipboard.writeText(text).then(
      () => showToast('已复制到剪贴板'),
      () => showToast('复制失败')
    );
  }

  const currentEx = examples[exIdx];
  const editing = tasks.filter((t) => t.status === 'editing').length;
  const running = tasks.filter((t) => t.status === 'running').length;
  /** 生成结果合并列表：同一类型（role）只保留最新一次生成的图，「重新生成」后旧图不再展示 */
  const resultImages = (() => {
    // tasks 新任务在前：逐任务扫描，记录每个类型最新由哪个任务产出，旧任务的同类型图被替换
    const ownerByRole = new Map<string, string>();
    const out: { taskId: string; url: string; role: string }[] = [];
    for (const t of tasks) {
      if (t.status !== 'success' || (t.imageUrls?.length ?? 0) === 0) continue;
      (t.imageUrls ?? []).forEach((url, i) => {
        const role = t.imageRoles?.[i] ?? `图 ${i + 1}`;
        const owner = ownerByRole.get(role);
        // 还没有更新的任务产出过该类型；或同一任务内的多张同类型图（套图任务）完整保留
        if (!owner || owner === t.taskId) {
          ownerByRole.set(role, t.taskId);
          out.push({ taskId: t.taskId, url, role });
        }
      });
    }
    return out.reverse(); // 反转为提交顺序（先生成的排前面）
  })();
  const hasResults = resultImages.length > 0;
  /** 新任务出图后自动切到“生成结果”标签 */
  useEffect(() => {
    if (hasResults) setResultTab('results');
  }, [hasResults]);
  /** 任务队列点击定位：当前闪烁高亮的图片（优先按 taskId，被同类型更新任务替换后回退按类型定位） */
  const [flashSel, setFlashSel] = useState<{ taskId: string; roles: string[]; nonce: number } | null>(null);
  const resultGridRef = useRef<HTMLDivElement | null>(null);
  /** 闪烁触发后：等标签切换渲染完成再滚动到对应图片，动画播完自动清除高亮 */
  useEffect(() => {
    if (!flashSel) return;
    const scrollTimer = setTimeout(() => {
      const grid = resultGridRef.current;
      if (!grid) return;
      const el = grid.querySelector<HTMLElement>(`[data-task-id="${flashSel.taskId}"]`)
        ?? (flashSel.roles.length > 0
          ? grid.querySelector<HTMLElement>(flashSel.roles.map((r) => `[data-role="${CSS.escape(r)}"]`).join(','))
          : null);
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 80);
    const clearTimer = setTimeout(() => setFlashSel(null), 2400);
    return () => { clearTimeout(scrollTimer); clearTimeout(clearTimer); };
  }, [flashSel]);
  /** 判断结果图是否处于当前闪烁高亮中：本任务产出，或任务图已被替换时同类型的最新图 */
  function isFlashing(img: { taskId: string; role: string }): boolean {
    return !!flashSel && (img.taskId === flashSel.taskId || flashSel.roles.includes(img.role));
  }
  /** 任务队列点击已完成任务：切到生成结果，滚动定位到该任务产出的图片并闪烁高亮 */
  function locateResultTask(t: ProductImageTask) {
    setResultTab('results');
    setFlashSel({ taskId: t.taskId, roles: [...new Set(t.imageRoles ?? [])], nonce: Date.now() });
  }
  const analyzing = analysisTask?.status === 'running';
  const analysisItems = analysisTask?.status === 'success' ? analysisTask.items ?? [] : [];

  // ===== 分析中动效：进度环每 300ms 微增（越接近 92% 越慢），阶段文案每 3.5s 滚动 =====
  /** 只在「从分析态退出」时归零：首次挂载不清零，保留从快照恢复的进度 */
  const prevAnalyzingRef = useRef(false);
  useEffect(() => {
    const wasAnalyzing = prevAnalyzingRef.current;
    prevAnalyzingRef.current = analyzing;
    if (!analyzing) {
      if (!wasAnalyzing) return;
      setAnalysisPercent(0);
      setAnalysisStepIdx(0);
      return;
    }
    const pTimer = setInterval(() => {
      setAnalysisPercent((p) => (p >= 92 ? p : Math.min(92, p + (92 - p) * 0.04 + 0.6)));
    }, 300);
    const sTimer = setInterval(() => {
      setAnalysisStepIdx((i) => Math.min(i + 1, ANALYSIS_STEPS.length - 1));
    }, 3500);
    return () => {
      clearInterval(pTimer);
      clearInterval(sTimer);
    };
  }, [analyzing]);
  /** 分析完成后停留在/切回“分析结果”标签 */
  useEffect(() => {
    if (analysisItems.length > 0) setResultTab('examples');
  }, [analysisTask?.taskId]);
  const viewTab = resultTab === 'results' && hasResults ? 'results' : 'examples';

  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar />
      <main className="mx-auto w-full max-w-[1600px] px-4 py-6 sm:px-6">
        {/* ===== 顶部 ===== */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Link href="/" className="grid h-8 w-8 place-items-center rounded-lg text-fg-muted hover:bg-bg-soft hover:text-fg">
              <ChevronLeft className="h-4 w-4" />
            </Link>
            <h1 className="text-lg font-medium text-fg">商详套图</h1>
          </div>
          <button
            type="button"
            onClick={handleNewClick}
            className="inline-flex h-8 items-center gap-1 rounded-lg px-2 text-sm text-fg-muted hover:bg-bg-soft hover:text-fg"
          >
            <Plus className="h-4 w-4" />
            新建
          </button>
        </div>

        {/* ===== 三栏布局：左固定 360 / 中弹性 / 右固定窄栏（展开 200，收起 64） ===== */}
        <div className="mt-4 grid grid-cols-[360px_minmax(0,1fr)_auto] gap-4">
          {/* ===== 左侧：配置栏（固定 360px，保证模型/图片设置文字完整显示；最小高度锁定，右侧收起时底部按钮不上移） ===== */}
          <aside className="flex min-h-[calc(100vh-96px)] w-[360px] flex-col gap-2">
            {/* 上传商品图：点击弹 MediaPickerDialog，里面可上传本地文件 */}
            <section className="card p-3">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-medium text-fg">上传商品图</h3>
                <span className="text-xs text-fg-muted">{assets.length}/5</span>
              </div>
              {/* 上传区 + 已选缩略图：横向并排 */}
              <div className="mt-2 flex gap-2">
                {/* 已选缩略图 */}
                {assets.map((a) => (
                  <div
                    key={a.id}
                    data-asset-id={a.id}
                    className="group relative h-20 w-20 flex-none cursor-zoom-in overflow-hidden rounded-md border border-bg-line"
                    onClick={() => setPreviewUrl(a.url)}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={a.url} alt={a.name} className="h-full w-full object-cover" />
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        e.preventDefault();
                        setPendingDeleteId(a.id);
                      }}
                      className="absolute left-0.5 top-0.5 z-10 grid h-4 w-4 place-items-center rounded-full bg-black/60 text-[10px] text-white transition hover:bg-rose-600"
                      aria-label="删除"
                    >×</button>
                  </div>
                ))}

                {/* 触发按钮：未上传时显示完整版，已上传后只显示 + */}
                {assets.length < 5 && (
                  <AddMaterialCard
                    onClick={() => setPickerOpen(true)}
                    className={cn(
                      'flex-none rounded-md',
                      assets.length === 0
                        ? 'h-20 w-full flex-1'   // 没图：占满剩余宽度（带图标 + 文字）
                        : 'h-20 w-20'            // 有图：只显示 + 号
                    )}
                    label="选择或上传商品图"
                    iconOnly={assets.length > 0}
                    iconClassName={assets.length === 0 ? 'h-4 w-4' : 'h-5 w-5'}
                    labelClassName="mt-1 text-[11px]"
                  />
                )}
              </div>
            </section>

            {/* 商详配置 */}
            <section className="card p-3">
              <h3 className="text-sm font-medium text-fg">商详配置</h3>
              <div className="mt-2 grid grid-cols-2 gap-2">
                <StyledSelect value={lang} options={LANGS as readonly string[]} onChange={(v) => setLang(v as typeof lang)} />
                <StyledSelect value={count} options={COUNTS as readonly string[]} onChange={(v) => setCount(v as typeof count)} />
              </div>

              <p className="mt-2 text-xs font-medium text-fg-muted">补充说明（选填）</p>
              <textarea
                value={brief}
                onChange={(e) => setBrief(e.target.value)}
                placeholder="卖点方向、目标人群、禁用元素、希望呈现的风格"
                className="mt-1 h-28 w-full resize-none rounded-xl border border-bg-line bg-bg-soft/40 p-2.5 text-xs outline-none placeholder:text-fg-subtle focus:border-brand/60"
                maxLength={500}
              />
            </section>

            {/* 操作指引（4 步，单行紧凑） */}
            <section className="card p-3">
              <h3 className="text-sm font-medium text-fg">操作指引</h3>
              <ol className="mt-2 space-y-1.5 text-[11px] text-fg-muted">
                <li className="flex gap-1.5">
                  <span className="grid h-4 w-4 flex-none place-items-center rounded bg-bg-soft text-[10px] font-medium text-fg">1</span>
                  <span><b className="text-fg">选择商品图</b>：上传或从资产库选择 1 到 5 张商品图，系统会按 @图片 1 等标签引用。</span>
                </li>
                <li className="flex gap-1.5">
                  <span className="grid h-4 w-4 flex-none place-items-center rounded bg-bg-soft text-[10px] font-medium text-fg">2</span>
                  <span><b className="text-fg">配置套图方案</b>：选择输出语言和套图张数，可补充卖点、人群、风格和禁用元素。</span>
                </li>
                <li className="flex gap-1.5">
                  <span className="grid h-4 w-4 flex-none place-items-center rounded bg-bg-soft text-[10px] font-medium text-fg">3</span>
                  <span><b className="text-fg">分析并调整预览</b>：完成或可编辑预览效果后，再检查每张图的定位、引用图、比例和提示词。</span>
                </li>
                <li className="flex gap-1.5">
                  <span className="grid h-4 w-4 flex-none place-items-center rounded bg-bg-soft text-[10px] font-medium text-fg">4</span>
                  <span><b className="text-fg">生成商详图</b>：确认预览后提交，系统会逐张生成并在中间区域展示结果。</span>
                </li>
              </ol>
            </section>

            {/* 模型 + 图片设置（并排） */}
            <section className="card p-3">
              <div className="grid grid-cols-2 gap-2">
                <StyledSelect
                  label="模型"
                  value={
                    (() => {
                      const m = models.find((x) => x.key === modelKey);
                      if (!m) return '加载中…';
                      return m.badge ? `${m.label} ${m.badge}` : m.label;
                    })()
                  }
                  options={models.map<StyledSelectOption>((m) => ({
                    label: m.label,
                    hint: m.badge,
                  }))}
                  onChange={(v) => {
                    const m = models.find((x) => x.label === v);
                    if (m) setModelKey(m.key);
                  }}
                />
                <SettingsPopover
                  triggerLabel="图片设置"
                  triggerValue={`${resolutions.find((r) => r.key === resolution)?.short ?? resolution} · ${format}`}
                  groups={[
                    {
                      label: '分辨率',
                      valueKey: resolution,
                      options: resolutions,
                      onChange: (k) => setResolution(k as ProductImageResolution),
                      getKey: (o: ResolutionOption) => o.key,
                      getLabel: (o: ResolutionOption) => o.label,
                    },
                    {
                      label: '输出格式',
                      valueKey: format,
                      options: formats,
                      onChange: (k) => setFormat(k as ProductImageFormat),
                      getKey: (o: FormatOption) => o.key,
                      getLabel: (o: FormatOption) => o.label,
                    },
                  ]}
                />
              </div>
            </section>

            {/* 提交按钮（贴在底部）：始终为「生成商品详解」——点击刷新页面状态并开启新的分析任务（分析完成后自动并发生成） */}
            <button
              onClick={generateAnalysis}
              disabled={assets.length === 0 || analyzing}
              className="mt-auto inline-flex h-10 w-full items-center justify-center gap-2 rounded-xl bg-fg text-sm font-medium text-white transition hover:brightness-110 disabled:opacity-50"
            >
              {analyzing && <Loader2 className="h-4 w-4 animate-spin" />}
              <span>生成商品详解</span>
              <span className="text-white/60 text-xs">详解语言：{lang}</span>
            </button>
          </aside>

          {/* ===== 中间：分析结果（参考示例）/ 生成结果；弹性填满剩余宽度 ===== */}
          <section className="card relative flex min-h-0 min-w-0 flex-col p-6" style={{ maxHeight: 'calc(100vh - 96px)' }}>
            {/* 顶部：标签页 + 批量下载 */}
            <div className="flex items-center justify-between border-b border-bg-line">
              <div className="flex items-center gap-6 text-sm">
                <button
                  type="button"
                  onClick={() => setResultTab('examples')}
                  className={cn(
                    'border-b-2 pb-2 font-medium transition',
                    viewTab !== 'results'
                      ? 'border-fg text-fg'
                      : 'border-transparent text-fg-muted hover:text-fg'
                  )}
                >
                  分析结果
                </button>
                <button
                  type="button"
                  onClick={() => hasResults && setResultTab('results')}
                  disabled={!hasResults}
                  className={cn(
                    'border-b-2 pb-2 font-medium transition',
                    viewTab === 'results'
                      ? 'border-fg text-fg'
                      : 'border-transparent text-fg-muted hover:text-fg',
                    !hasResults && 'cursor-not-allowed opacity-40 hover:text-fg-muted'
                  )}
                >
                  生成结果
                </button>
              </div>
              {viewTab === 'results' && hasResults && (
                <button
                  type="button"
                  onClick={() => batchDownloadAll()}
                  className="mb-2 inline-flex items-center gap-1.5 rounded-lg bg-fg px-3 py-1.5 text-xs font-medium text-white transition hover:brightness-110"
                >
                  <Download className="h-3.5 w-3.5" />
                  批量下载（{resultImages.length}）
                </button>
              )}
              {/* 全部生成：分析完成后一键并发提交所有条目（也会在分析完成时自动触发） */}
              {viewTab !== 'results' && analysisItems.length > 0 && (
                <button
                  type="button"
                  onClick={() => generateAllFromAnalysis()}
                  disabled={bulkGenerating}
                  className="mb-2 inline-flex items-center gap-1.5 rounded-lg bg-fg px-3 py-1.5 text-xs font-medium text-white transition hover:brightness-110 disabled:opacity-60"
                >
                  {bulkGenerating ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <Sparkles className="h-3.5 w-3.5" />
                  )}
                  {bulkGenerating ? '提交中…' : `全部生成（${analysisItems.length}）`}
                </button>
              )}
            </div>

            {viewTab === 'results' && hasResults ? (
              <div ref={resultGridRef} className="mt-4 grid min-h-0 flex-1 auto-rows-min grid-cols-2 gap-4 overflow-y-auto scrollbar-slim">
                {resultImages.map((img, i) => {
                  const sub = examples.find((e) => e.title === img.role)?.subtitle;
                  return (
                    <div
                      key={`${img.taskId}-${i}`}
                      data-task-id={img.taskId}
                      data-role={img.role}
                      className={cn(
                        'group relative aspect-square cursor-zoom-in overflow-hidden rounded-xl border border-bg-line bg-bg-soft',
                        isFlashing(img) && 'animate-[flash-highlight_.6s_ease-in-out_3] ring-2 ring-[#4f7cff]'
                      )}
                      onClick={() => setPreviewUrl(img.url)}
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={img.url} alt={`${img.role} ${i + 1}`} className="h-full w-full object-cover transition group-hover:scale-[1.02]" />
                      {/* 序号 + 类型标签（跨任务连续编号） */}
                      <span className="absolute left-3 top-3 rounded-md bg-white/90 px-2 py-1 text-xs font-medium text-fg shadow-soft backdrop-blur">
                        {String(i + 1).padStart(2, '0')} {img.role}{sub ? ` / ${sub}` : ''}
                      </span>
                      {/* 单张下载 */}
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          downloadImage(img.url, `商详套图-${String(i + 1).padStart(2, '0')}-${img.role}.png`);
                        }}
                        className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-lg bg-fg text-white transition hover:brightness-110"
                        aria-label="下载"
                      >
                        <Download className="h-4 w-4" />
                      </button>
                    </div>
                  );
                })}
              </div>
            ) : analyzing ? (
              /* 分析中：进度环 + 阶段文案（模拟进度，完成时自动切到结果） */
              (() => {
                const R = 72;
                const C = 2 * Math.PI * R;
                const pct = Math.round(analysisPercent);
                const cur = ANALYSIS_STEPS[analysisStepIdx];
                const next = ANALYSIS_STEPS[Math.min(analysisStepIdx + 1, ANALYSIS_STEPS.length - 1)];
                return (
                  <div className="flex flex-1 flex-col items-center justify-center gap-6">
                    {/* 进度环 */}
                    <div className="relative">
                      <svg width="168" height="168" viewBox="0 0 168 168" className="-rotate-90">
                        <circle cx="84" cy="84" r={R} fill="none" stroke="currentColor" strokeWidth="7" className="text-bg-line" />
                        <circle
                          cx="84" cy="84" r={R} fill="none" stroke="currentColor" strokeWidth="7"
                          strokeLinecap="round" strokeDasharray={C} strokeDashoffset={C * (1 - pct / 100)}
                          className="text-fg transition-all duration-300 ease-out"
                        />
                      </svg>
                      <div className="absolute inset-0 flex flex-col items-center justify-center">
                        <span className="text-3xl font-semibold text-fg tabular-nums">{pct}%</span>
                        <span className="mt-1 text-xs font-medium text-fg-muted">深度分析</span>
                      </div>
                    </div>
                    {/* 阶段文案：当前步骤 + 下一步，带切换过渡 */}
                    <div className="text-center">
                      <p key={analysisStepIdx} className="animate-[fadeIn_.4s_ease] text-sm font-medium text-fg">{cur}</p>
                      {analysisStepIdx < ANALYSIS_STEPS.length - 1 && (
                        <p className="mt-1 text-xs text-fg-subtle">{next}…</p>
                      )}
                      <p className="mt-3 text-xs text-fg-subtle">AI 正在识别图片中的商品信息，请勿关闭页面</p>
                    </div>
                  </div>
                );
              })()
            ) : analysisItems.length > 0 ? (
              /* 分析结果卡片列表（每张商详图一张卡片，超高时内部滚动 + 底部渐隐提示） */
              <div className="relative mt-4 min-h-0 flex-1">
              <div className="h-full space-y-3 overflow-y-auto pr-1 scrollbar-slim">
                {analysisItems.map((item, i) => {
                  const selKey = `${analysisTask?.taskId ?? 'local'}-${i}`;
                  const refIdx = parseInt(item.refLabel.replace(/[^0-9]/g, '') || '1', 10) - 1;
                  // 优先用提交分析时的快照：删除上传的商品图后引用图仍正常显示
                  const refAsset = resolveRefAsset(refIdx);
                  return (
                    <div key={selKey} className="rounded-xl border border-bg-line bg-white p-4 shadow-soft">
                      <div className="flex items-start gap-3">
                        {/* 引用图缩略（不再叠加 @图片N 标签） */}
                        {refAsset ? (
                          <div
                            className="relative h-14 w-14 flex-none cursor-zoom-in overflow-hidden rounded-lg border border-bg-line"
                            onClick={() => setPreviewUrl(refAsset.url)}
                          >
                            {/* eslint-disable-next-line @next/next/no-img-element */}
                            <img src={refAsset.url} alt={item.refLabel} className="h-full w-full object-cover" />
                          </div>
                        ) : (
                          <div className="grid h-14 w-14 flex-none place-items-center rounded-lg border border-bg-line bg-bg-soft">
                            <ImageIcon className="h-4 w-4 text-[#a4aab5]" />
                          </div>
                        )}
                        {/* 分析要点列表（点击整卡可放大） */}
                        <ul
                          className="min-w-0 flex-1 cursor-zoom-in space-y-1.5 text-xs leading-relaxed text-fg"
                          onClick={() => openEnlarged(item, i)}
                        >
                          {item.sections.map((s, si) => (
                            <li key={si} className="flex gap-1">
                              <span className="flex-none text-fg-subtle">•</span>
                              <span className="min-w-0"><b className="font-medium">{s.key}</b>：{cleanSectionValue(s.value)}</span>
                            </li>
                          ))}
                        </ul>
                        {/* 右上角操作：放大 */}
                        <button
                          type="button"
                          onClick={() => openEnlarged(item, i)}
                          title="放大"
                          className="grid h-7 w-7 flex-none place-items-center rounded-md text-fg-muted transition hover:bg-bg-soft hover:text-fg"
                        >
                          <Maximize2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                      {/* 底部：类型下拉 + 比例下拉 + 复制 */}
                      <div className="mt-3 flex items-center gap-2 border-t border-bg-line pt-3">
                        <div className="relative">
                          <select
                            value={roleSel[selKey] ?? item.role}
                            onChange={(e) => setRoleSel((prev) => ({ ...prev, [selKey]: e.target.value }))}
                            className="h-8 appearance-none rounded-lg border border-bg-line bg-white pl-3 pr-8 text-xs text-fg outline-none hover:border-brand/40"
                          >
                            {(roleOptions.length > 0 ? roleOptions : [item.role]).map((r) => (
                              <option key={r} value={r}>{r}</option>
                            ))}
                          </select>
                          <ChevronDown className="pointer-events-none absolute right-2 top-1/2 h-3 w-3 -translate-y-1/2 text-fg-subtle" />
                        </div>
                        <div className="relative">
                          <select
                            value={ratioSel[selKey] ?? item.ratio}
                            onChange={(e) => setRatioSel((prev) => ({ ...prev, [selKey]: e.target.value }))}
                            className="h-8 appearance-none rounded-lg border border-bg-line bg-white pl-3 pr-8 text-xs text-fg outline-none hover:border-brand/40"
                          >
                            <option value="1:1">1:1</option>
                            <option value="4:5">4:5</option>
                          </select>
                          <ChevronDown className="pointer-events-none absolute right-2 top-1/2 h-3 w-3 -translate-y-1/2 text-fg-subtle" />
                        </div>
                        <button
                          type="button"
                          onClick={() => copyAnalysis(item)}
                          title="复制"
                          className="ml-auto grid h-7 w-7 place-items-center rounded-md text-fg-muted transition hover:bg-bg-soft hover:text-fg"
                        >
                          <Copy className="h-3.5 w-3.5" />
                        </button>
                        {/* 重新生成：先按新定位/比例重写分析文案，再用新文案 + 引用图 + 类型走图生图链路，新结果追加进「生成结果」 */}
                        <button
                          type="button"
                          onClick={() => generateFromAnalysis(item, selKey, i)}
                          disabled={genLoading[selKey] || bulkGenerating}
                          className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-fg px-3 text-xs font-medium text-white transition hover:brightness-110 disabled:opacity-60"
                        >
                          {genLoading[selKey] ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            <RotateCw className="h-3.5 w-3.5" />
                          )}
                          {genLoading[selKey] ? '提交中' : '重新生成'}
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
              {/* 底部渐隐：提示下方还有内容可滚动 */}
              <div className="pointer-events-none absolute inset-x-0 bottom-0 h-10 bg-gradient-to-t from-bg-card to-transparent" />
              </div>
            ) : analysisTask?.status === 'failed' ? (
              /* 分析失败 */
              <div className="flex flex-1 flex-col items-center justify-center gap-2 text-rose-600">
                <p className="text-sm">分析失败</p>
                <p className="max-w-md text-center text-xs text-fg-muted">{analysisTask.failReason ?? '请稍后重试'}</p>
              </div>
            ) : (
            <div className="flex flex-1 flex-col items-center justify-center">
              {currentEx && (
                <>
                  {/* 固定标题：所有示例共用同一段文案 */}
                  <div className="text-center">
                    <h2 className="text-2xl font-semibold text-fg">商详套图参考示例</h2>
                    <p className="mt-2 text-sm text-fg-muted">
                      按 <b className="text-fg">图片类型</b> 串联完整商详图结构，帮助你快速理解生成方向
                    </p>
                  </div>

                  <div className="relative mt-6 w-full max-w-[480px]">
                    <div className="aspect-square overflow-hidden rounded-2xl border border-bg-line bg-bg-soft">
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={currentEx.imageUrl} alt={currentEx.title} className="h-full w-full object-cover" />
                    </div>
                    <span className="absolute left-3 top-3 rounded bg-black/60 px-2 py-0.5 text-[10px] text-white">
                      {currentEx.subtitle}
                    </span>
                  </div>

                  <div className="mt-3 flex items-center gap-1">
                    {examples.map((_, i) => (
                      <button
                        key={i}
                        onClick={() => setExIdx(i)}
                        className={cn(
                          'h-1.5 rounded-full transition-all',
                          i === exIdx ? 'w-6 bg-fg' : 'w-1.5 bg-bg-line hover:bg-fg-subtle'
                        )}
                      />
                    ))}
                  </div>
                  {/* 描述：跟随图片切换 */}
                    <p className="mt-3 max-w-[480px] text-center text-xs text-fg-muted">
                      {currentEx.description}
                    </p>
                </>
              )}
            </div>
            )}

            {/* 左右切换箭头：仅示例页实际展示时显示（分析中/分析卡片/生成结果页不显示），垂直居中，离边框远一点 */}
            {viewTab !== 'results' && !analyzing && analysisItems.length === 0
              && analysisTask?.status !== 'failed' && examples.length > 1 && (
              <>
                <button
                  onClick={prevEx}
                  className="absolute left-8 top-1/2 z-10 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/80 text-fg shadow-soft backdrop-blur transition hover:bg-white"
                  aria-label="上一张"
                >
                  <L className="h-5 w-5" />
                </button>
                <button
                  onClick={nextEx}
                  className="absolute right-8 top-1/2 z-10 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/80 text-fg shadow-soft backdrop-blur transition hover:bg-white"
                  aria-label="下一张"
                >
                  <R className="h-5 w-5" />
                </button>
              </>
            )}
          </section>

          {/* ===== 右侧：任务队列（紧凑条卡样式；固定窄宽 200px，与参考截图一致） ===== */}
          <aside
            className={cn(
              'transition-all',
              queueCollapsed ? 'w-16' : 'flex min-h-0 w-[200px] flex-col gap-3'
            )}
          >
            {queueCollapsed ? (
              /* 收起态：紧凑竖条（贴栏顶部，高度随内容；栏本身撑满行高，下方不做向上回收） */
              <div className="card flex flex-col items-center gap-3 self-start p-3">
                <button
                  type="button"
                  onClick={() => setQueueCollapsed(false)}
                  className="grid h-7 w-7 place-items-center rounded-md bg-bg-soft text-fg-muted hover:bg-bg-line hover:text-fg"
                  aria-label="展开任务队列"
                >
                  <ChevronRight className="h-4 w-4" />
                </button>
                <div className="flex flex-col items-center gap-2 rounded-xl border border-[#eceef2] bg-white px-3 py-2.5 shadow-sm">
                  <div className="flex flex-col items-center">
                    <span className="text-[10px] text-[#9ca2ad]">排队中</span>
                    <span className="text-xl font-semibold leading-6 text-fg">{editing}</span>
                  </div>
                  <div className="h-px w-8 bg-[#eceef2]" />
                  <div className="flex flex-col items-center">
                    <span className="text-[10px] text-[#9ca2ad]">生成中</span>
                    <span className="text-xl font-semibold leading-6 text-fg">{running}</span>
                  </div>
                </div>
              </div>
            ) : (
              <>
                {/* 头部（标题 + 批量删除 + 收起文字按钮） */}
                <div className="flex items-center justify-between px-1">
                  <h3 className="text-sm font-medium text-fg">任务队列</h3>
                  <div className="flex items-center gap-2">
                    {tasks.length > 0 && (
                      <button
                        type="button"
                        onClick={() => {
                          setSelectMode((v) => !v);
                          setSelectedTaskIds([]);
                        }}
                        className={cn(
                          'text-xs transition',
                          selectMode ? 'font-medium text-rose-600' : 'text-fg-muted hover:text-rose-600'
                        )}
                      >
                        {selectMode ? '取消' : '批量删除'}
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={() => setQueueCollapsed(true)}
                      className="text-xs text-fg-muted hover:text-fg"
                    >
                      收起
                    </button>
                  </div>
                </div>
                {/* 统计条：与 AI 生成图片状态栏同款样式（小灰标签在上、大号数字在下） */}
                <div className="flex gap-2 rounded-lg border border-[#eceef2] bg-white px-2.5 py-2.5 text-center shadow-sm">
                  <div className="flex-1">
                    <div className="text-[10px] text-[#9ca2ad]">排队中</div>
                    <div className="text-base font-semibold leading-5 text-fg">{editing}</div>
                  </div>
                  <div className="w-px bg-[#eceef2]" />
                  <div className="flex-1">
                    <div className="text-[10px] text-[#9ca2ad]">生成中</div>
                    <div className="text-base font-semibold leading-5 text-fg">{running}</div>
                  </div>
                </div>
                {/* 批量删除操作栏：选择模式下显示（全选 + 删除按钮，红色危险风格） */}
                {selectMode && (
                  <div className="flex items-center justify-between rounded-lg border border-rose-100 bg-rose-50/60 px-2.5 py-1.5">
                    <label className="flex cursor-pointer select-none items-center gap-1.5 text-[11px] text-fg-muted">
                      <input
                        type="checkbox"
                        checked={tasks.length > 0 && selectedTaskIds.length === tasks.length}
                        onChange={(e) => setSelectedTaskIds(e.target.checked ? tasks.map((t) => t.taskId) : [])}
                        className="h-3.5 w-3.5 accent-rose-600"
                      />
                      全选 {selectedTaskIds.length}/{tasks.length}
                    </label>
                    <button
                      type="button"
                      onClick={batchDeleteSelected}
                      disabled={selectedTaskIds.length === 0 || bulkDeleting}
                      className="inline-flex items-center gap-1 rounded-md bg-rose-600 px-2 py-1 text-[11px] font-medium text-white transition hover:bg-rose-700 disabled:opacity-50"
                    >
                      {bulkDeleting ? <Loader2 className="h-3 w-3 animate-spin" /> : <Trash2 className="h-3 w-3" />}
                      删除{selectedTaskIds.length > 0 ? `(${selectedTaskIds.length})` : ''}
                    </button>
                  </div>
                )}
                {/* 任务列表：紧凑条卡（小图标 + 状态 + 日期时间），失败原因悬停查看 */}
                <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto scrollbar-slim">
                  {tasks.length === 0 && (
                    <p className="pt-8 text-center text-[10px] text-[#c2c6cf]">暂无任务</p>
                  )}
                  {tasks.map((t) => {
                    const done = t.status === 'success' && (t.imageUrls?.length ?? 0) > 0;
                    // 已出图的任务都可点击切到生成结果（合并展示全部结果）；选择模式下点击 = 勾选/取消
                    const isSelected = selectedTaskIds.includes(t.taskId);
                    const isCurrent = done && viewTab === 'results';
                    const thumb = done
                      ? t.imageUrls![0]
                      : t.previewUrls && t.previewUrls.length > 0
                        ? t.previewUrls[0]
                        : undefined;
                    return (
                      <div
                        key={t.taskId}
                        className={cn(
                          'group relative flex w-full flex-none items-center gap-2 rounded-lg border bg-[#f6f7f9] p-2 transition',
                          done || selectMode ? 'cursor-pointer' : '',
                          isSelected
                            ? 'border-rose-500 ring-1 ring-rose-500/30'
                            : isCurrent
                              ? 'border-[#4f7cff] ring-1 ring-[#4f7cff]/30'
                              : 'border-[#eef0f3] hover:border-[#cbd3e6]'
                        )}
                        onClick={selectMode ? () => toggleTaskSelect(t.taskId) : done ? () => locateResultTask(t) : undefined}
                      >
                        {/* 批量删除选择框：选择模式下显示 */}
                        {selectMode && (
                          <input
                            type="checkbox"
                            checked={isSelected}
                            onChange={() => toggleTaskSelect(t.taskId)}
                            onClick={(e) => e.stopPropagation()}
                            className="h-3.5 w-3.5 flex-none accent-rose-600"
                          />
                        )}
                        {/* 小缩略图：完成图 / 商品图 / 占位图标；生成中叠加旋转图标 + 蓝色进度条 */}
                        <div className="relative h-10 w-10 flex-none overflow-hidden rounded-md bg-[#f4f5f7]">
                          {thumb ? (
                            <>
                              {/* eslint-disable-next-line @next/next/no-img-element */}
                              <img src={thumb} alt="" className="h-full w-full object-cover" />
                            </>
                          ) : (
                            <div className="grid h-full w-full place-items-center">
                              <ImageIcon className="h-4 w-4 text-[#a4aab5]" />
                            </div>
                          )}
                          {t.status === 'running' && (
                            <div className="absolute inset-0 grid place-items-center bg-black/25">
                              <Loader2 className="h-3.5 w-3.5 animate-spin text-white" />
                            </div>
                          )}
                          {t.status === 'running' && <div className="absolute bottom-0 left-0 h-0.5 w-full bg-[#4f7cff]" />}
                        </div>
                        {/* 状态 + 时间（失败原因截断，悬停看全文） */}
                        <div className="min-w-0 flex-1">
                          <div className={cn(
                            'text-xs font-medium',
                            t.status === 'success' && 'text-emerald-600',
                            t.status === 'failed' && 'text-rose-600',
                            t.status === 'running' && 'text-amber-600',
                            t.status === 'editing' && 'text-[#4f7cff]'
                          )}>
                            {t.status === 'editing' && '编辑中'}
                            {t.status === 'running' && '生成中'}
                            {t.status === 'success' && '已完成'}
                            {t.status === 'failed' && '失败'}
                          </div>
                          <div
                            className="truncate text-[10px] text-[#9ca2ad]"
                            title={t.status === 'failed' && t.failReason
                              ? t.failReason
                              : t.status === 'success' && (t.imageRoles?.length ?? 0) > 0
                                ? [...new Set(t.imageRoles)].join(' · ')
                                : undefined}
                          >
                            {/* 已完成任务显示生成图类型（主视图/卖点图…，去重）代替日期；无类型信息时回退显示日期 */}
                            {t.status === 'success' && (t.imageRoles?.length ?? 0) > 0
                              ? [...new Set(t.imageRoles)].join(' · ')
                              : formatTaskDate(t.createdAt)}
                            {t.status === 'failed' && t.failReason ? ` · ${t.failReason}` : ''}
                          </div>
                        </div>
                        {/* 删除按钮（悬停显示；选择模式下隐藏，改用勾选批量删） */}
                        {!selectMode && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            removeTask(t.taskId);
                          }}
                          className="hidden h-6 w-6 flex-none place-items-center rounded-md text-[#a6abb4] transition hover:bg-rose-50 hover:text-rose-600 group-hover:grid"
                          aria-label="删除任务"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                        )}
                      </div>
                    );
                  })}
                </div>
              </>
            )}
          </aside>
        </div>
      </main>

      {/* Toast 提示 */}
      {toast && (
        <div className="fixed bottom-8 left-1/2 z-[1200] -translate-x-1/2 rounded-xl bg-fg/90 px-4 py-2.5 text-sm text-white shadow-2xl backdrop-blur">
          {toast}
        </div>
      )}

      {/* 选图弹窗：内部"上传文件"按钮触发系统文件框 */}
      <MediaPickerDialog
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        uploadedFiles={materials.map((a) => ({ id: a.id, type: 'image' as const, url: a.url, name: a.name }))}
        showMockAssets={false}
        onRemoveUploaded={(id) => {
          // 弹窗里删除 = 从素材库永久删除 + 从 assets 移除
          gRemoveMaterial(id);
          setAssets((prev) => prev.filter((x) => x.id !== id));
        }}
        onUploadFiles={(files): PickedMedia[] => {
          if (!files) return [];
          const arr = Array.from(files);
          const remain = 5 - assets.length;
          if (remain <= 0) {
            showToast('最多只能上传 5 张图片');
            return [];
          }
          // 上传前按 name + size 去重（避免重复文件进素材库）
          const existFp = new Set(materials.map((m) => `${m.name}_${m.size ?? m.url.length}`));
          const fresh: GlobalMaterial[] = [];
          const skipped: string[] = [];
          for (const f of arr) {
            const fp = `${f.name}_${f.size}`;
            if (existFp.has(fp)) {
              skipped.push(f.name);
              continue;
            }
            const innerDup = fresh.find((x) => `${x.name}_${f.size}` === fp);
            if (innerDup) {
              skipped.push(f.name);
              continue;
            }
            existFp.add(fp);
            fresh.push({
              id: `up_${Date.now()}_${f.name}_${Math.random().toString(36).slice(2, 10)}`,
              type: f.type.startsWith('video') ? 'video' : f.type.startsWith('audio') ? 'audio' : 'image',
              url: URL.createObjectURL(f),
              name: f.name,
              size: f.size,
            });
          }
          if (skipped.length > 0) {
            showToast(`已跳过重复文件：${[...new Set(skipped)].join('、')}`);
          }
          const sliced = fresh.slice(0, remain);
          if (fresh.length > remain) {
            showToast(`已选 ${fresh.length} 张，超出 ${fresh.length - remain} 张，仅添加 ${remain} 张`);
          }
          // 写入全局素材库
          gAddMaterials(sliced);
          // 转换为 PickedMedia 返回
          return sliced.map((m) => ({
            id: m.id,
            type: m.type,
            url: m.url,
            name: m.name,
          }));
        }}
        onConfirm={(picked: PickedMedia[]) => {
          // picked 是 mock 资产（角色库等），不含已上传的（已上传的直接在 assets 里）
          setAssets((prev) => {
            const remain = 5 - prev.length;
            if (remain <= 0) {
              showToast('最多只能选择 5 张图片');
              setPickerOpen(false);
              return prev;
            }
            if (picked.length > remain) {
              showToast(`已选 ${picked.length} 张，超出 ${picked.length - remain} 张，仅添加 ${remain} 张`);
            }
            const arr = picked.slice(0, remain).map((m) => ({
              id: m.id,
              name: m.name,
              url: m.url,
            }));
            // 按 name + url.length 去重（避免同文件被多次加入）
            const fpSet = new Set(prev.map((x) => `${x.name}_${x.url.length || 0}`));
            const fresh = arr.filter((x) => !fpSet.has(`${x.name}_${x.url.length || 0}`));
            return [...prev, ...fresh].slice(0, 5);
          });
          // 选中资产也自动创建一个"编辑中"任务（已有 editing 则覆盖，只留一个）
          if (picked.length > 0) {
            // 先删除所有 editing
            setTasks((prevTasks) => prevTasks.filter((x) => x.status !== 'editing'));
            const t: ProductImageTask = {
              taskId: `task_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
              status: 'editing',
              creditsCost: 200,
              createdAt: Date.now(),
              previewUrls: picked.map((m) => m.url),
            };
            setTasks((prevTasks) => [t, ...prevTasks]);
          }
          setPickerOpen(false);
        }}
        max={5}
      />

      {/* 图片预览：点击缩略图触发 */}
      {previewUrl && (
        <div
          className="fixed inset-0 z-[1100] grid place-items-center bg-black/80 p-8 backdrop-blur-sm"
          onClick={() => setPreviewUrl(null)}
          role="dialog"
          aria-modal="true"
        >
          <button
            className="absolute right-6 top-6 grid h-10 w-10 place-items-center rounded-full bg-white/10 text-2xl text-white hover:bg-white/20"
            onClick={(e) => { e.stopPropagation(); setPreviewUrl(null); }}
            aria-label="关闭"
          >×</button>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={previewUrl}
            alt="预览"
            className="max-h-[85vh] max-w-[85vw] rounded-xl object-contain shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}

      {/* 新建确认弹窗（已有编辑中的任务时） */}
      {pendingNewTask && (
        <div
          className="fixed inset-0 z-[1100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          onClick={() => setPendingNewTask(false)}
          role="dialog"
          aria-modal="true"
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="card relative w-full max-w-sm bg-white p-6 shadow-2xl"
          >
            <div className="flex items-start gap-3">
              <div className="grid h-10 w-10 flex-none place-items-center rounded-full bg-amber-100 text-amber-600">
                <span className="text-lg">?</span>
              </div>
              <div>
                <div className="text-base font-medium text-fg">已有正在编辑的任务</div>
                <p className="mt-1 text-sm text-fg-muted">
                  新建会覆盖当前编辑内容，可继续编辑或新建
                </p>
              </div>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <button
                onClick={() => setPendingNewTask(false)}
                className="rounded-xl border border-bg-line bg-white px-4 py-2 text-sm text-fg hover:bg-bg-soft"
              >
                继续编辑
              </button>
              <button
                onClick={() => {
                  setPendingNewTask(false);
                  createNewEditingTask();
                }}
                className="rounded-xl bg-fg px-4 py-2 text-sm font-medium text-white transition hover:brightness-110"
              >
                新建
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 主页删除确认弹窗 */}
      {pendingDeleteId && (
        <div
          className="fixed inset-0 z-[1100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          onClick={() => setPendingDeleteId(null)}
          role="dialog"
          aria-modal="true"
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="card relative w-full max-w-sm bg-white p-6 shadow-2xl"
          >
            <div className="flex items-start gap-3">
              <div className="grid h-10 w-10 flex-none place-items-center rounded-full bg-rose-100 text-rose-600">
                <span className="text-lg">×</span>
              </div>
              <div>
                <div className="text-base font-medium text-fg">确认移除该图片？</div>
                <p className="mt-1 text-sm text-fg-muted">
                  移除后仅从本次任务中删除，素材库中仍保留。
                </p>
              </div>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <button
                onClick={() => setPendingDeleteId(null)}
                className="btn-ghost h-9 px-4"
              >
                取消
              </button>
              <button
                onClick={() => {
                  const id = pendingDeleteId;
                  setPendingDeleteId(null);
                  if (id) {
                    // 主页 × 只从 assets 移除（不删素材库），下次再选还能从素材库选
                    // 只删一次（避免重复 id 导致批量删除）
                    setAssets((arr) => {
                      const idx = arr.findIndex((x) => x.id === id);
                      if (idx === -1) return arr;
                      return [...arr.slice(0, idx), ...arr.slice(idx + 1)];
                    });
                  }
                }}
                className="rounded-xl bg-rose-600 px-4 py-2 text-sm font-medium text-white transition hover:brightness-110"
              >
                移除
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 分析结果放大视图：点分析卡片的放大按钮/文字区域，全文大字展示；引用图显示缩略图，文字可编辑，底部保存/取消 */}
      {enlargedItem && (
        <div
          className="fixed inset-0 z-[1100] grid place-items-center bg-black/80 p-8 backdrop-blur-sm"
          onClick={cancelEnlarged}
          role="dialog"
          aria-modal="true"
        >
          <button
            className="absolute right-6 top-6 grid h-10 w-10 place-items-center rounded-full bg-white/10 text-2xl text-white hover:bg-white/20"
            onClick={(e) => { e.stopPropagation(); cancelEnlarged(); }}
            aria-label="关闭"
          >×</button>
          <div
            className="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-white p-8 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2 border-b border-bg-line pb-3">
              {/* 引用图：展示缩略图（代替原 @图片N 文字标签），点击可预览大图 */}
              {(() => {
                const refIdx = parseInt(enlargedItem.refLabel.replace(/[^0-9]/g, '') || '1', 10) - 1;
                const refAsset = resolveRefAsset(refIdx);
                return refAsset ? (
                  <div
                    className="h-12 w-12 flex-none cursor-zoom-in overflow-hidden rounded-lg border border-bg-line"
                    onClick={() => setPreviewUrl(refAsset.url)}
                    title={enlargedItem.refLabel}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={refAsset.url} alt={enlargedItem.refLabel} className="h-full w-full object-cover" />
                  </div>
                ) : (
                  <span className="rounded-md bg-bg-soft px-2 py-1 text-sm font-medium text-fg">{enlargedItem.refLabel}</span>
                );
              })()}
              <span className="text-sm font-medium text-fg">{enlargedItem.role}</span>
              <span className="text-xs text-fg-muted">{enlargedItem.ratio}</span>
            </div>
            {/* 文字区域：可编辑（每个要点一个文本框），保存后写回分析结果 */}
            <ul className="mt-4 space-y-4 text-base leading-relaxed text-fg">
              {enlargedItem.sections.map((s, si) => (
                <li key={si}>
                  <b className="font-semibold">{s.key}</b>：
                  <textarea
                    value={enlargedDraft?.[si] ?? cleanSectionValue(s.value)}
                    onChange={(e) => {
                      const v = e.target.value;
                      setEnlargedDraft((prev) => {
                        const base = prev ?? enlargedItem.sections.map((x) => cleanSectionValue(x.value));
                        const next = [...base];
                        next[si] = v;
                        return next;
                      });
                    }}
                    rows={Math.min(6, Math.max(2, Math.ceil((enlargedDraft?.[si] ?? cleanSectionValue(s.value)).length / 40)))}
                    className="mt-1 w-full resize-y rounded-lg border border-bg-line bg-bg-soft/60 p-2.5 text-sm leading-relaxed text-fg outline-none transition focus:border-brand/50 focus:bg-white"
                  />
                </li>
              ))}
            </ul>
            {/* 底部操作：保存（写回分析结果）/ 取消（丢弃编辑关闭） */}
            <div className="mt-5 flex items-center justify-end gap-2 border-t border-bg-line pt-4">
              <button
                type="button"
                onClick={cancelEnlarged}
                className="h-9 rounded-lg border border-bg-line px-4 text-sm text-fg-muted transition hover:bg-bg-soft hover:text-fg"
              >取消</button>
              <button
                type="button"
                onClick={saveEnlarged}
                className="h-9 rounded-lg bg-fg px-5 text-sm font-medium text-white transition hover:brightness-110"
              >保存</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function SelectField({
  value, options, onChange, hint,
}: {
  value: string;
  options: readonly string[];
  onChange: (v: string) => void;
  hint?: string;
}) {
  return (
    <div>
      <button className="inline-flex h-9 w-full items-center justify-between rounded-lg border border-bg-line bg-white px-3 text-sm text-fg hover:border-brand/40">
        <span className="truncate">{value}</span>
        <ChevronDown className="h-3.5 w-3.5 text-fg-subtle" />
      </button>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="sr-only"
      >
        {options.map((o) => <option key={o} value={o}>{o}</option>)}
      </select>
      {hint && <p className="mt-1 text-[10px] text-fg-muted">{hint}</p>}
    </div>
  );
}
