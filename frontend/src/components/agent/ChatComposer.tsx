'use client';

import { useState, type DragEvent } from 'react';
import { Plus, ArrowUp } from 'lucide-react';
import { nanoid } from 'nanoid';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
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
  /** 点击发送：参数 = (内容, 素材id 列表) */
  onSend?: (content: string, attachmentIds: string[]) => void | Promise<void>;
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

  function handleUploadFiles(files: FileList | null): PickedMedia[] {
    if (!files) return [];
    const existingFingerprints = new Set(materials.map((material) => `${material.name}_${material.size ?? material.url.length}`));
    const fresh: GlobalMaterial[] = [];

    Array.from(files).forEach((file) => {
      const fingerprint = `${file.name}_${file.size}`;
      if (existingFingerprints.has(fingerprint)) return;
      existingFingerprints.add(fingerprint);

      fresh.push({
        id: nanoid(10),
        type: file.type.startsWith('video') ? 'video' : file.type.startsWith('audio') ? 'audio' : 'image',
        url: URL.createObjectURL(file),
        name: file.name,
        size: file.size,
      });
    });

    addMaterials(fresh);
    return fresh;
  }

  function onDrop(e: DragEvent) {
    e.preventDefault();
    setDragOver(false);
    // 接入接口时：把文件上传到后端，再把 fileRef 塞进消息体
  }

  function handleSend() {
    if (!text.trim() || sending) return;
    onSend?.(text.trim(), picked.map((m) => m.id));
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
                className="relative h-14 w-14 overflow-hidden rounded-lg border border-bg-line"
                title={m.name}
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={m.url} alt={m.name} className="h-full w-full object-cover" />
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
              placeholder="结合参考、输入文字或 @ 主体，说说今天想做什么。"
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
