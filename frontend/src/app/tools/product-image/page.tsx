'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { ChevronLeft, Plus, ChevronDown, ChevronLeft as L, ChevronRight as R, Loader2, Trash2 } from 'lucide-react';
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
  ProductImageExample,
  ProductImageFormat,
  ProductImageModel,
  ProductImageResolution,
  ProductImageTask,
  ResolutionOption,
} from '@/types/product-image';

const LANGS = ['中文', 'English'] as const;
const COUNTS = ['4 张', '8 张', '12 张'] as const;

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
  const [submitting, setSubmitting] = useState(false);

  // ===== 弹窗 =====
  const [pickerOpen, setPickerOpen] = useState(false);
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

  // ===== 拉数据 =====
  useEffect(() => {
    productImageApi.listModels().then((arr) => {
      setModels(arr);
      const def = arr.find((m) => m.key === 'premium') ?? arr[0];
      if (def) setModelKey(def.key);
    });
    productImageApi.listResolutions().then(setResolutions);
    productImageApi.listFormats().then(setFormats);
    productImageApi.listExamples().then(setExamples);
  }, []);

  const model = models.find((m) => m.key === modelKey) ?? models[0];
  const creditsCost = model?.creditsCost ?? 0;

  // ===== 轮播 =====
  function prevEx() {
    setExIdx((i) => (examples.length === 0 ? 0 : (i - 1 + examples.length) % examples.length));
  }
  function nextEx() {
    setExIdx((i) => (examples.length === 0 ? 0 : (i + 1) % examples.length));
  }

  // ===== 任务 =====
  async function submit() {
    if (assets.length === 0 || submitting) return;
    setSubmitting(true);
    // 点"立即分析" → 把所有"编辑中"任务变"生成中"
    setTasks((prev) => prev.map((t) =>
      t.status === 'editing' ? { ...t, status: 'running' } : t
    ));
    setSubmitting(false);
  }

  function removeTask(taskId: string) {
    setTasks((prev) => prev.filter((t) => t.taskId !== taskId));
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
      try {
        const t = await productImageApi.getTask(id);
        setTasks((prev) => prev.map((x) => (x.taskId === id ? t : x)));
        if (t.status === 'editing' || t.status === 'running') {
          if (n++ < 10) setTimeout(tick, 1500);
        }
      } catch (e) {
        console.error('poll failed', e);
      }
    };
    setTimeout(tick, 1200);
  }

  const currentEx = examples[exIdx];
  const editing = tasks.filter((t) => t.status === 'editing').length;
  const running = tasks.filter((t) => t.status === 'running').length;

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

        {/* ===== 三栏布局 ===== */}
        <div className="mt-4 grid grid-cols-12 gap-4">
          {/* ===== 左侧：配置栏（col-span-3，并排触发器） ===== */}
          <aside className="col-span-3 flex flex-col gap-2">
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

            {/* 提交按钮（贴在底部） */}
            <button
              onClick={submit}
              disabled={assets.length === 0 || submitting}
              className="mt-auto inline-flex h-10 w-full items-center justify-center gap-2 rounded-xl bg-fg text-sm font-medium text-white transition hover:brightness-110 disabled:opacity-50"
            >
              {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
              <span>立即分析商品图</span>
              <span className="text-white/60 text-xs">预计 -{creditsCost} 积分</span>
            </button>
          </aside>

          {/* ===== 中间：参考示例轮播 ===== */}
          <section className="col-span-6 card relative flex flex-col p-6">
            {/* 顶部标签：绝对定位，不占据 flex 空间 */}
            <div className="absolute left-6 top-6 flex items-center gap-2 text-sm font-medium text-fg">
              <span className="h-px w-6 bg-fg" />
              参考示例
            </div>

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

            {/* 左右切换箭头：section 垂直居中，离边框远一点 */}
            {examples.length > 1 && (
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

          {/* ===== 右侧：任务队列 ===== */}
          <aside
            className={cn(
              'space-y-3 transition-all',
              queueCollapsed ? 'col-span-1' : 'col-span-3'
            )}
          >
            {queueCollapsed ? (
              /* 收起态：竖排小卡片 */
              <div className="card flex flex-col items-center gap-3 p-3">
                <button
                  type="button"
                  onClick={() => setQueueCollapsed(false)}
                  className="grid h-7 w-7 place-items-center rounded-md bg-bg-soft text-fg-muted hover:bg-bg-line hover:text-fg"
                  aria-label="展开任务队列"
                >
                  <ChevronLeft className="h-4 w-4" />
                </button>
                <div className="flex flex-col items-center gap-2 rounded-xl border border-bg-line px-2 py-2">
                  <div className="flex flex-col items-center">
                    <span className="text-[10px] text-fg-muted">排队中</span>
                    <span className="text-base font-semibold text-fg">{editing}</span>
                  </div>
                  <div className="h-px w-6 bg-bg-line" />
                  <div className="flex flex-col items-center">
                    <span className="text-[10px] text-fg-muted">生成中</span>
                    <span className="text-base font-semibold text-fg">{running}</span>
                  </div>
                </div>
                {tasks.length > 0 && (
                  <div className="grid h-12 w-12 place-items-center rounded-md border border-bg-line bg-white">
                    <Plus className="h-4 w-4 text-fg-muted" />
                  </div>
                )}
              </div>
            ) : (
              /* 展开态 */
              <div className="card p-4">
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-medium text-fg">任务队列</h3>
                  <button
                    type="button"
                    onClick={() => setQueueCollapsed(true)}
                    className="text-xs text-fg-muted hover:text-fg"
                  >
                    收起
                  </button>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-3">
                  <div className="rounded-xl border border-bg-line p-3 text-center">
                    <div className="text-xs text-fg-muted">排队中</div>
                    <div className="mt-1 text-2xl font-semibold text-fg">{editing}</div>
                  </div>
                  <div className="rounded-xl border border-bg-line p-3 text-center">
                    <div className="text-xs text-fg-muted">生成中</div>
                    <div className="mt-1 text-2xl font-semibold text-fg">{running}</div>
                  </div>
                </div>
              </div>
            )}

            {tasks.length === 0 ? (
              <div className="card grid place-items-center p-6 text-center text-xs text-fg-subtle">
                暂无任务
              </div>
            ) : (
              tasks.map((t) => (
                <div key={t.taskId} className="card relative overflow-hidden p-3">
                  {/* 渐变背景 + 缩略图拼接 */}
                  <div className="flex h-20 items-center gap-2 overflow-hidden rounded-lg bg-gradient-to-br from-bg-soft to-bg-line/40 p-2">
                    <div className="grid h-12 w-12 flex-none place-items-center rounded-md bg-white shadow-soft">
                      <Plus className="h-5 w-5 text-fg-muted" />
                    </div>
                    <div className="flex min-w-0 flex-1 flex-col">
                      <span className={cn(
                        'text-xs font-medium',
                        t.status === 'success' && 'text-emerald-600',
                        t.status === 'failed' && 'text-rose-600',
                        t.status === 'running' && 'text-amber-600',
                        t.status === 'editing' && 'text-brand'
                      )}>
                        {t.status === 'editing' && '编辑中'}
                        {t.status === 'running' && '生成中'}
                        {t.status === 'success' && '已完成'}
                        {t.status === 'failed' && '失败'}
                      </span>
                      <span className="text-[10px] text-fg-subtle">
                        {formatTaskDate(t.createdAt)}
                      </span>
                    </div>
                  </div>
                  {/* 删除按钮（右下） */}
                  <button
                    type="button"
                    onClick={() => removeTask(t.taskId)}
                    className="absolute bottom-1.5 right-1.5 grid h-6 w-6 place-items-center rounded-md bg-white/80 text-fg-muted transition hover:bg-rose-50 hover:text-rose-600"
                    aria-label="删除任务"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              ))
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
