'use client';

import Link from 'next/link';
import { ArrowRight, Eraser, Type, Wand2, Flame, User, Shirt } from 'lucide-react';

const TOOLS = [
  {
    href: '/tools/watermark-remover',
    title: '水印擦除',
    desc: '智能擦除视频水印与遮挡元素',
    icon: Eraser,
    bg: 'from-[#e0e7ff] to-[#eef2ff]',
  },
  {
    href: '/tools/subtitle-remover',
    title: '字幕擦除',
    desc: '一键去除视频字幕与画面文字',
    icon: Type,
    bg: 'from-[#e0e7ff] to-[#eef2ff]',
  },
  {
    href: '/tools/image-enhancer',
    title: '画质增强',
    desc: '提升视频清晰度与画面质感',
    icon: Wand2,
    bg: 'from-[#eef2ff] to-[#e0e7ff]',
  },
  {
    href: '/tools/viral-video',
    title: '爆款裂变',
    desc: '快速裂变多条爆款投流视频',
    icon: Flame,
    bg: 'from-[#eef2ff] to-[#e0e7ff]',
  },
  {
    href: '/tools/digital-human',
    title: '数字分身',
    desc: '敬请期待',
    icon: User,
    bg: 'from-[#e0e7ff] to-[#eef2ff]',
  },
  {
    href: '/tools/model-swap',
    title: '模特换衣',
    desc: '敬请期待',
    icon: Shirt,
    bg: 'from-[#eef2ff] to-[#e0e7ff]',
  },
] as const;

export function ToolGrid() {
  return (
    <section className="mt-6 grid grid-cols-12 gap-4">
      {/* 左侧大卡：商详套图 */}
      <Link
        href="/tools/product-image"
        className="group relative col-span-12 overflow-hidden rounded-2xl border border-bg-line bg-gradient-to-br from-[#eef2ff] via-white to-[#e0e7ff] p-6 shadow-soft transition hover:shadow-glow md:col-span-5"
      >
        <div className="relative z-10">
          <div className="text-2xl font-semibold text-fg">商详套图</div>
          <p className="mt-2 text-sm text-fg-muted">一键生成专业电商详情套图</p>
          <button className="mt-6 inline-flex items-center gap-2 rounded-full bg-fg px-4 py-2 text-sm font-medium text-white transition group-hover:brightness-110">
            立即创作 <ArrowRight className="h-4 w-4" />
          </button>
        </div>
        {/* 装饰图 */}
        <div className="absolute -right-6 bottom-0 hidden h-44 w-56 sm:block">
          <div className="absolute right-10 top-4 h-32 w-24 rotate-6 rounded-xl bg-gradient-to-br from-amber-200 to-amber-400 shadow-soft" />
          <div className="absolute right-2 top-10 h-32 w-24 -rotate-3 rounded-xl bg-gradient-to-br from-rose-200 to-rose-400 shadow-soft" />
          <div className="absolute right-16 top-12 h-32 w-24 -rotate-6 rounded-xl bg-gradient-to-br from-amber-100 to-amber-300 shadow-soft" />
        </div>
      </Link>

      {/* 右侧 2x3 小卡 */}
      <div className="col-span-12 grid grid-cols-2 gap-4 md:col-span-7 md:grid-cols-3">
        {TOOLS.map((t) => {
          const Icon = t.icon;
          return (
            <Link
              key={t.href}
              href={t.href}
              className={`group relative overflow-hidden rounded-2xl border border-bg-line bg-gradient-to-br ${t.bg} p-4 shadow-soft transition hover:shadow-glow`}
            >
              <div className="flex items-start gap-3">
                <div className="grid h-10 w-10 flex-none place-items-center rounded-xl bg-white/80 text-brand shadow-soft">
                  <Icon className="h-5 w-5" />
                </div>
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold text-fg">{t.title}</div>
                  <div className="mt-0.5 line-clamp-1 text-[11px] text-fg-muted">{t.desc}</div>
                </div>
              </div>
            </Link>
          );
        })}
      </div>
    </section>
  );
}
