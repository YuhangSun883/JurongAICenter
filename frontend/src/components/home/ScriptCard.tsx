'use client';

import { useEffect, useState } from 'react';
import { Plus, ArrowUp, Image as ImageIcon, Video as VideoIcon, Sparkles, Bot, X } from 'lucide-react';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { InlineSelect } from '@/components/common/InlineSelect';
import { InsufficientCreditsDialog } from '@/components/common/InsufficientCreditsDialog';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
import { creationsApi, type CreationType } from '@/api/creations';
import { cn } from '@/lib/utils';

const HOME_MAX_REFS = 12;

export function ScriptCard() {
  const [text, setText] = useState('');
  const [mode, setMode] = useState<CreationType>('agent');
  const [submitting, setSubmitting] = useState(false);
  // 积分不足弹窗
  const [showInsufficient, setShowInsufficient] = useState(false);
  const [picked, setPicked] = useState<PickedMedia[]>([]);
  const [open, setOpen] = useState(false);
  // 素材库（全局共享，所有工具页面通用）
  const { materials, addMaterials, removeMaterial, dedupMaterials } = useMaterials();
  // 启动时清理历史重复项（按 name + size 去重）
  useEffect(() => {
    dedupMaterials();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const remainingPickSlots = Math.max(0, HOME_MAX_REFS - picked.length);

  return (
    <section className="mt-6">
      <div className="card flex items-stretch gap-4 p-4 pb-8 sm:p-5 sm:pb-8">
        {/* 左侧 + 上传 → 打开弹窗 */}
        {picked.length === 0 ? (
          <AddMaterialCard
            onClick={() => setOpen(true)}
            label="添加素材"
            className="h-32 w-32 flex-none sm:h-36 sm:w-36"
            iconClassName="h-10 w-10"
          />
        ) : (
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="group relative flex h-32 w-32 flex-none flex-col items-center justify-center overflow-hidden rounded-2xl bg-bg-soft text-fg-subtle transition sm:h-36 sm:w-36"
          >
            <>
              {/* 缩略图网格（最多 9 张） */}
              <div
                className={cn(
                  'absolute inset-1 grid gap-0.5 overflow-hidden',
                  picked.length === 1
                    ? 'grid-cols-1'
                    : picked.length <= 4
                    ? 'grid-cols-2'
                    : 'grid-cols-3'
                )}
              >
                {picked.slice(0, 9).map((m) => (
                  <div
                    key={m.id}
                    className="group/cell relative aspect-square overflow-hidden rounded-md border border-bg-line"
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={m.url}
                      alt={m.name}
                      className="h-full w-full object-cover"
                    />
                    {/* hover 显示删除按钮 */}
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation(); // 不触发打开弹窗
                        setPicked((prev) => prev.filter((x) => x.id !== m.id));
                      }}
                      className="absolute right-0.5 top-0.5 grid h-5 w-5 place-items-center rounded-full bg-black/60 text-white opacity-0 transition group-hover/cell:opacity-100"
                      aria-label={`删除 ${m.name}`}
                    >
                      <X className="h-3 w-3" strokeWidth={3} />
                    </button>
                  </div>
                ))}
              </div>
              {/* 右下角 + 浮按钮（hover 显示） */}
              <span className="absolute bottom-1.5 right-1.5 grid h-7 w-7 place-items-center rounded-full bg-white text-fg shadow-soft transition group-hover:scale-110">
                <Plus className="h-4 w-4" strokeWidth={2.5} />
              </span>
              {/* 下方"已选 N" */}
              <span className="absolute -bottom-5 left-0 right-0 text-center text-[11px] text-brand">
                已选 {picked.length}
              </span>
            </>
          </button>
        )}

        {/* 右侧输入区 */}
        <div className="flex min-h-[112px] flex-1 flex-col">
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="上传参考素材并输入文字,或使用 @ 引用已添加的素材。可自由组合图、文、音、视频。"
            className="block flex-1 resize-none border-0 bg-transparent text-sm leading-6 text-fg outline-none placeholder:text-fg-subtle"
            maxLength={10000}
          />

          <div className="mt-2 flex items-center justify-between border-t border-bg-line/60 pt-2">
            <InlineSelect
              value={mode}
              onChange={(v) => setMode(v as CreationType)}
              icon={<Sparkles className="h-3.5 w-3.5" />}
              popoverTitle="创作类型"
              options={[
                {
                  value: 'video',
                  label: '视频生成',
                  icon: <VideoIcon className="h-3.5 w-3.5" />,
                  description: '生成高质量短视频',
                },
                {
                  value: 'image',
                  label: '图片生成',
                  icon: <ImageIcon className="h-3.5 w-3.5" />,
                  description: '生成静态营销图',
                },
                {
                  value: 'agent',
                  label: 'Agent 模式',
                  icon: <Bot className="h-3.5 w-3.5" />,
                  description: 'AI Agent 协作完成复杂任务',
                },
              ]}
            />

            <div className="flex items-center gap-3">
              <span className="text-xs text-fg-subtle">{text.length}/10000</span>
              <button
                disabled={text.trim().length === 0 || submitting}
                onClick={async () => {
                  if (text.trim().length === 0) return;
                  setSubmitting(true);
                  try {
                    // 前置积分校验（具体校验逻辑由后端实现）
                    const action =
                      mode === 'video'
                        ? 'video-create'
                        : mode === 'image'
                        ? 'image-create'
                        : 'agent-chat';
                    const check = await creationsApi.checkCredits({ action });
                    if (check.status === 'insufficient') {
                      setShowInsufficient(true);
                      return;
                    }
                    if (mode === 'agent') {
                      // Agent 模式：调对话接口
                      const res = await creationsApi.agentChat({
                        message: text,
                        materialIds: picked.map((p) => p.id),
                      });
                      alert(`[Agent 回复] ${res.reply}`);
                    } else {
                      // 视频 / 图片：调统一创作接口
                      const task = await creationsApi.create({
                        type: mode,
                        prompt: text,
                        materialIds: picked.map((p) => p.id),
                      });
                      alert(`已提交${mode === 'video' ? '视频' : '图片'}任务: ${task.taskId}`);
                    }
                  } catch (e) {
                    console.error('submit failed', e);
                  } finally {
                    setSubmitting(false);
                  }
                }}
                className="grid h-8 w-8 place-items-center rounded-full bg-brand text-white shadow-glow transition hover:brightness-110 disabled:opacity-40"
              >
                <ArrowUp className="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <MediaPickerDialog
        open={open}
        onClose={() => setOpen(false)}
        uploadedFiles={materials}
        showMockAssets={false}
        onUploadFiles={(files): PickedMedia[] => {
          if (!files) return [];
          const arr = Array.from(files);
          // 上传前检查：同名同 size 的文件已在素材库则跳过
          // 用 f.size（实际文件大小）+ f.lastModified 作为指纹（比 url.length 更可靠）
          const existFp = new Set(
            materials.map((m) => `${m.name}_${m.size ?? m.url.length}`)
          );
          const fresh: GlobalMaterial[] = [];
          const skipped: string[] = [];
          for (const f of arr) {
            const fp = `${f.name}_${f.size}`;
            if (existFp.has(fp)) {
              skipped.push(f.name);
              continue;
            }
            // 内部循环也要去重（一次选多个同名文件）
            const innerFp = fresh.find((x) => `${x.name}_${f.size}` === fp);
            if (innerFp) {
              skipped.push(f.name);
              continue;
            }
            existFp.add(fp);
            fresh.push({
              id: `up_${Date.now()}_${f.name}_${Math.random().toString(36).slice(2, 8)}`,
              type: f.type.startsWith('video') ? 'video' : f.type.startsWith('audio') ? 'audio' : 'image',
              url: URL.createObjectURL(f),
              name: f.name,
              size: f.size,
            });
          }
          if (skipped.length > 0) {
            alert(`已跳过重复文件：${[...new Set(skipped)].join('、')}`);
          }
          addMaterials(fresh);
          return fresh;
        }}
        onRemoveUploaded={(id) => {
          removeMaterial(id);
          setPicked((prev) => prev.filter((x) => x.id !== id));
        }}
        onConfirm={(arr) => setPicked((p) => {
          // 按 name + size 去重
          const fp = new Set(p.map((x) => `${x.name}_${x.url.length || 0}`));
          const fresh = arr.filter((x) => !fp.has(`${x.name}_${x.url.length || 0}`));
          return [...p, ...fresh].slice(0, HOME_MAX_REFS);
        })}
        max={remainingPickSlots}
      />

      {/* 积分不足弹窗（复用现成的 InsufficientCreditsDialog） */}
      <InsufficientCreditsDialog
        open={showInsufficient}
        onClose={() => setShowInsufficient(false)}
        onPaid={() => {
          setShowInsufficient(false);
          // 支付成功后用户可以再次点提交重试
        }}
      />
    </section>
  );
}
