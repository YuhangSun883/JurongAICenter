'use client';

import { Music } from 'lucide-react';

export type ThumbnailType = 'image' | 'video' | 'audio';

export interface ThumbnailMedia {
  url: string;
  name: string;
  type: ThumbnailType;
}

/**
 * 缩略图渲染：根据 type 分支
 * - image: <img>
 * - video: <video>（跳到 0.1s 截一帧，避免黑屏）
 * - audio: 紫色渐变 + 白色圆盘 + 声波条
 *
 * 任何类型都要走这个组件，不要直接用 <img>——否则视频/音频类型的素材会显示不出来。
 */
export function ReferenceMediaThumbnail({ media, index }: { media: ThumbnailMedia; index?: number }) {
  if (media.type === 'video') {
    return (
      <video
        src={media.url}
        className="h-full w-full object-cover"
        muted
        playsInline
        preload="metadata"
        onLoadedData={(e) => {
          const v = e.currentTarget;
          if (v.currentTime === 0) v.currentTime = 0.1;
        }}
      />
    );
  }

  if (media.type === 'audio') {
    return (
      <div className="relative h-full w-full overflow-hidden bg-gradient-to-br from-[#7c5cff] via-[#6f4cff] to-[#5b3fe0]">
        <div className="absolute -left-3 -top-3 h-12 w-12 rounded-full bg-white/15 blur-xl" />
        <div className="absolute -bottom-3 -right-3 h-14 w-14 rounded-full bg-white/10 blur-xl" />
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="grid h-7 w-7 place-items-center rounded-full bg-white/95 shadow-[0_2px_8px_rgba(0,0,0,0.18)]">
            <Music className="h-3.5 w-3.5 text-[#6f4cff]" strokeWidth={2.5} />
          </div>
        </div>
        {typeof index === 'number' && (
          <div className="absolute bottom-1.5 left-0 right-0 flex items-end justify-center gap-[2px] px-2">
            {[4, 8, 6, 10, 7, 5, 9, 6].map((h, i) => (
              <span
                key={i}
                className="w-[1.5px] rounded-full bg-white/55"
                style={{ height: `${h}px` }}
              />
            ))}
          </div>
        )}
      </div>
    );
  }

  // 默认按图片渲染
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={media.url} alt={media.name} className="h-full w-full object-cover" />
  );
}
