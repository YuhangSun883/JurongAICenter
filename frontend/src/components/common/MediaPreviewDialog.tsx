'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Music, X } from 'lucide-react';

type PreviewableMedia = {
  id: string;
  type: 'image' | 'video' | 'audio';
  url: string;
  name: string;
};

/**
 * 通用素材预览弹窗(图片/视频/音频)
 * - 用 createPortal 挂到 body,不受父级 transform/overflow/stacking context 影响
 * - 点背景或 X 关闭;ESC 也能关
 * - 媒体内容区 stopPropagation,防止误关
 * - 接受 PickedMedia 或 ReferenceMedia(都有 id/type/url/name 字段)
 *
 * 用法:
 *   const [preview, setPreview] = useState<PickedMedia | null>(null);
 *   <MediaPreviewDialog media={preview} onClose={() => setPreview(null)} />
 */
export function MediaPreviewDialog({
  media,
  onClose,
}: {
  media: PreviewableMedia | null;
  onClose: () => void;
}) {
  // 仅在客户端渲染,避免 SSR 阶段访问 document
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  // ESC 关闭
  useEffect(() => {
    if (!media) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [media, onClose]);

  if (!mounted || !media) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/80 p-6"
      onClick={onClose}
    >
      {/* 关闭按钮 */}
      <button
        type="button"
        onClick={onClose}
        className="absolute right-4 top-4 grid h-9 w-9 place-items-center rounded-full bg-white/10 text-white transition hover:bg-white/20"
        aria-label="关闭预览"
        title="关闭"
      >
        <X className="h-5 w-5" />
      </button>
      {/* 文件名(顶部) */}
      <div className="absolute left-1/2 top-4 -translate-x-1/2 max-w-[80vw] truncate rounded-md bg-black/40 px-3 py-1 text-xs text-white/90">
        {media.name}
      </div>
      {/* 媒体内容(阻止冒泡,避免点内容区关闭弹窗) */}
      <div
        className="relative max-h-[88vh] max-w-[92vw]"
        onClick={(e) => e.stopPropagation()}
      >
        {media.type === 'image' && (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={media.url}
            alt={media.name}
            className="max-h-[88vh] max-w-[92vw] rounded-lg object-contain shadow-2xl"
          />
        )}
        {media.type === 'video' && (
          <video
            src={media.url}
            controls
            autoPlay
            className="max-h-[88vh] max-w-[92vw] rounded-lg shadow-2xl"
          />
        )}
        {media.type === 'audio' && (
          <div className="flex min-w-[480px] flex-col items-center gap-6 rounded-2xl bg-gradient-to-br from-[#7c5cff] via-[#6f4cff] to-[#5b3fe0] p-12 shadow-2xl">
            {/* 居中大圆盘 + 音频图标 */}
            <div className="relative">
              <div className="absolute -left-8 -top-8 h-32 w-32 rounded-full bg-white/15 blur-2xl" />
              <div className="grid h-32 w-32 place-items-center rounded-full bg-white/95 shadow-[0_12px_40px_rgba(0,0,0,0.3)]">
                <Music className="h-16 w-16 text-[#6f4cff]" strokeWidth={2} />
              </div>
            </div>
            {/* 声波条装饰 */}
            <div className="flex items-end gap-1.5">
              {[14, 24, 18, 32, 22, 16, 28, 18, 22, 14].map((h, i) => (
                <span
                  key={i}
                  className="w-1.5 rounded-full bg-white/55"
                  style={{ height: `${h}px` }}
                />
              ))}
            </div>
            <audio src={media.url} controls autoPlay className="w-full max-w-md" />
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}
