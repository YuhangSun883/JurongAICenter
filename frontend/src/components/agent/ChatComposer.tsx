'use client';

import { useState, type DragEvent } from 'react';
import { Plus, ArrowUp, X } from 'lucide-react';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
import { mediaApi } from '@/api/media';
import { cn } from '@/lib/utils';

interface ChatComposerProps {
  /**
   * 已消耗的积分。后端在以下时机回传：
   *   - 加载历史对话时：上一轮已经消耗的累计值
   *   - 发送新消息成功后：本轮预估消耗
   *   - 流式生成结束时：实际消耗（可能与预估不同）
   * 前端不要本地计算，**始终以接口返回为准**。
   */
  creditsUsed?: number;
  /** 预估积分（仅用于"发送中"的提示，可选） */
  creditsEstimated?: number;
  /** 点击发送：参数 = (内容, 已选素材) —— 传完整 PickedMedia 列表(含 url,名称等),
   *  让 page.tsx 不用再从 materials 里反查(避免闭包陷阱:addMaterials 是异步 setState,
   *  触发 handleSend 时 page.tsx 的 materials 还是旧值) */
  onSend?: (content: string, attachments: PickedMedia[]) => void | Promise<void>;
  /** 发送中：禁用按钮 + 显示 spinner */
  sending?: boolean;
}

const AGENT_MAX_REFS = 12;

export function ChatComposer({
  creditsUsed = 0,
  creditsEstimated,
  onSend,
  sending = false,
}: ChatComposerProps) {
  const [text, setText] = useState('');
  const [dragOver, setDragOver] = useState(false);
  const [picked, setPicked] = useState<PickedMedia[]>([]);
  const [open, setOpen] = useState(false);
  const { materials, addMaterials, removeMaterial } = useMaterials();
  const remainingPickSlots = Math.max(0, AGENT_MAX_REFS - picked.length);

  /** 异步上传到后端。返回本地上传中的占位，实际项在 upload() 解决后由 addMaterials 加进去 */
  async function handleUploadFiles(files: FileList | null): Promise<PickedMedia[]> {
    if (!files) return [];
    const existingFingerprints = new Set(
      materials.map((m) => `${m.name}_${m.size ?? m.url.length}`)
    );
    const queued: File[] = [];
    Array.from(files).forEach((file) => {
      const fingerprint = `${file.name}_${file.size}`;
      if (existingFingerprints.has(fingerprint)) return;
      existingFingerprints.add(fingerprint);
      queued.push(file);
    });

    //  同步发起上传（并行）
    const results = await Promise.allSettled(
      queued.map(async (file) => {
        const res = await mediaApi.upload(file);
        const mat: GlobalMaterial = {
          id: String(res.id),
          type: (res.type as 'image' | 'video' | 'audio'),
          url: res.url,
          name: res.name,
          size: res.sizeBytes,
        };
        addMaterials([mat]);
        return {
          id: mat.id,
          type: mat.type,
          url: mat.url,
          name: mat.name,
        } satisfies PickedMedia;
      })
    );

    // 返回成功上传的项（忽略失败的）
    const picked: PickedMedia[] = [];
    results.forEach((r) => {
      if (r.status === 'fulfilled') picked.push(r.value);
    });
    return picked;
  }

  function onDrop(e: DragEvent) {
    e.preventDefault();
    setDragOver(false);
    // 接入接口时：把文件上传到后端，再把 fileRef 塞进消息体
  }

  function handleSend() {
    if (!text.trim() || sending) return;
    // 2026-08-14:传完整 PickedMedia 列表(不只传 id),避免 page.tsx 闭包陷阱
    onSend?.(text.trim(), picked.slice());
    setText('');
    setPicked([]);
  }

  return (
    <>
      <div
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
        className={cn(
          'relative mx-auto w-full max-w-[920px] rounded-2xl border bg-bg-card shadow-soft transition',
          dragOver ? 'border-brand shadow-glow' : 'border-bg-line'
        )}
      >
        {/* 已选素材缩略图条 */}
        {picked.length > 0 && (
          <div className="flex flex-wrap gap-2 border-b border-bg-line/60 px-4 pt-3">
            {picked.map((m) => (
              <div
                key={m.id}
                className="group relative h-14 w-14 overflow-hidden rounded-lg border border-bg-line bg-[#f1f5f9]"
                title={m.name}
              >
                {/* V26+：视频类型用 <video> 标签取首帧,图片用 <img>。
                   之前不管类型都用 <img>,视频文件被当成图片加载 → 一直破图。 */}
                {m.type === 'video' ? (
                  <video
                    src={m.url}
                    className="h-full w-full object-cover"
                    muted
                    playsInline
                    preload="metadata"
                    onLoadedData={(e) => {
                      // 加载完元数据后跳到第 0.1s 截一帧作为缩略图,避免黑屏
                      const v = e.currentTarget;
                      v.currentTime = 0.1;
                    }}
                  />
                ) : (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={m.url} alt={m.name} className="h-full w-full object-cover" />
                )}
                {/* V26：删除按钮 —— 悬停时显示，点击从已选列表移除 */}
                <button
                  type="button"
                  onClick={() => setPicked((current) => current.filter((item) => item.id !== m.id))}
                  className="absolute right-0.5 top-0.5 grid h-4 w-4 place-items-center rounded-full bg-black/60 text-white opacity-0 transition group-hover:opacity-100 hover:bg-red-500"
                  title="移除"
                >
                  <X className="h-3 w-3" strokeWidth={2.5} />
                </button>
              </div>
            ))}
            <button
              onClick={() => setOpen(true)}
              disabled={remainingPickSlots === 0}
              className="grid h-14 w-14 place-items-center rounded-lg border border-dashed border-bg-line text-fg-subtle hover:border-brand/50 hover:text-brand"
              title="继续添加"
            >
              <Plus className="h-4 w-4" />
            </button>
          </div>
        )}

        <div className="flex items-center gap-3 px-4 py-3.5">
          {/* 装饰性 + 号：放大、左侧、垂直居中 */}
          <AddMaterialCard
            onClick={() => setOpen(true)}
            iconOnly
            className="h-20 w-20 flex-none border-solid border-transparent bg-gradient-to-br from-brand-50 to-brand-100 text-brand shadow-soft hover:border-transparent hover:from-brand hover:to-brand hover:text-white hover:shadow-glow"
            iconClassName="h-12 w-12 stroke-[3]"
            title="上传素材"
          />

          {/* 输入框：纵向撑开，与按钮垂直居中 */}
          <div className="flex flex-1 items-center self-stretch">
            <textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="结合参考、输入文字，说说今天想做什么。"
              rows={1}
              className="block w-full resize-none border-0 bg-transparent py-2 text-sm leading-6 text-fg outline-none placeholder:text-fg-subtle"
            />
          </div>
        </div>

        {/* 底部：积分 + 发送按钮 */}
        <div className="flex items-center justify-end gap-3 border-t border-bg-line/60 px-4 py-2.5">
          <span className="text-xs text-fg-subtle">
            已消耗 {creditsUsed} 积分
            {creditsEstimated !== undefined && creditsEstimated > creditsUsed && (
              <span className="ml-1 text-fg-subtle">
                · 预估 +{creditsEstimated - creditsUsed}
              </span>
            )}
          </span>
          <button
            onClick={handleSend}
            disabled={text.trim().length === 0 || sending}
            className="grid h-9 w-9 place-items-center rounded-full bg-brand text-white shadow-glow transition hover:brightness-110 disabled:opacity-40"
          >
            {sending ? (
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white" />
            ) : (
              <ArrowUp className="h-4 w-4" />
            )}
          </button>
        </div>
      </div>

      <MediaPickerDialog
        open={open}
        onClose={() => setOpen(false)}
        onConfirm={(arr) => setPicked((current) => {
          const existingIds = new Set(current.map((item) => item.id));
          const existingFingerprints = new Set(current.map((item) => `${item.name}_${item.url.length}`));
          const fresh = arr.filter((item) => {
            const fingerprint = `${item.name}_${item.url.length}`;
            if (existingIds.has(item.id) || existingFingerprints.has(fingerprint)) return false;
            existingIds.add(item.id);
            existingFingerprints.add(fingerprint);
            return true;
          });
          return [...current, ...fresh].slice(0, AGENT_MAX_REFS);
        })}
        uploadedFiles={materials}
        onUploadFiles={handleUploadFiles}
        onRemoveUploaded={(id) => {
          removeMaterial(id);
          setPicked((current) => current.filter((item) => item.id !== id));
        }}
        showMockAssets={false}
        max={remainingPickSlots}
      />
    </>
  );
}
