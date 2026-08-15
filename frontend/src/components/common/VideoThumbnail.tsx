'use client';

import { useEffect, useRef, useState } from 'react';
import { Film, Play } from 'lucide-react';
import { cn } from '@/lib/utils';

interface VideoThumbnailProps {
  /** V26：改成传 presign URL（asset.url）。
   *  之前用 stream URL 带 token，URL 每次变 → 浏览器缓存不命中 → 视频缩略图每次都重新下载。 */
  url: string;
  onPlay: () => void;
  /** "compact" 用更小的 hover 按钮；默认与卡片一致 */
  size?: 'default' | 'compact';
  /**
   * 2026-08-15：多选态下点击应交由父卡片处理（toggle 选择），不再触发播放
   * - true：禁用自身的 onClick，播放按钮也隐藏
   * - false/undefined：保持原行为
   */
  disabled?: boolean;
}

/**
 * 视频卡片缩略图：
 * - 通过 IntersectionObserver 只在进入视口时才加载视频元数据
 * - 加载完成立刻 pause() 停在第一帧，展示真实视频首帧
 * - 出错时降级为 Film 图标
 * - 鼠标悬停显示 Play 按钮
 */
export function VideoThumbnail({ url, onPlay, size = 'default', disabled = false }: VideoThumbnailProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const [inView, setInView] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);

  // 进入视口才挂载 <video>，避免一次性给所有卡片发流式请求
  useEffect(() => {
    const el = containerRef.current;
    if (!el || inView) return;
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setInView(true);
            io.disconnect();
            break;
          }
        }
      },
      { rootMargin: '200px', threshold: 0.1 }
    );
    io.observe(el);
    return () => io.disconnect();
  }, [inView]);

  function handleLoaded() {
    const v = videoRef.current;
    if (!v) return;
    // 加载完元数据后停在第一帧（用 video 元素渲染首帧作为缩略图）
    v.currentTime = 0;
    v.pause();
    setLoaded(true);
  }

  function handleError() {
    setFailed(true);
  }

  return (
    <div
      ref={containerRef}
      onClick={disabled ? undefined : onPlay}
      className={cn(
        'relative grid h-full w-full place-items-center overflow-hidden bg-[#f1f5f9]',
        disabled ? 'cursor-default' : 'cursor-pointer'
      )}
    >
      {/* 占位图（视频还没加载好 / 加载失败时显示） */}
      {(!loaded || failed) && (
        <Film
          className={cn(
            'text-[#cbd5e1]',
            size === 'compact' ? 'h-6 w-6' : 'h-9 w-9'
          )}
          strokeWidth={1.5}
        />
      )}

      {/* 真正进入视口才挂载的 video 元素 */}
      {inView && !failed && (
        <video
          ref={videoRef}
          src={url}
          className={cn(
            'absolute inset-0 h-full w-full object-cover transition-opacity',
            loaded ? 'opacity-100' : 'opacity-0'
          )}
          preload="metadata"
          muted
          playsInline
          onLoadedData={handleLoaded}
          onError={handleError}
        />
      )}

      {/* 播放按钮覆盖层：disabled 态下整层不渲染，避免误导用户点击会播放 */}
      {!disabled && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/0 transition hover:bg-black/20">
          <div
            className={cn(
              'flex items-center justify-center rounded-full bg-black/50 text-white opacity-0 transition group-hover:opacity-100',
              size === 'compact' ? 'h-9 w-9' : 'h-12 w-12'
            )}
          >
            <Play
              className={cn(size === 'compact' ? 'h-4 w-4' : 'ml-0.5 h-6 w-6')}
              fill="white"
            />
          </div>
        </div>
      )}
    </div>
  );
}