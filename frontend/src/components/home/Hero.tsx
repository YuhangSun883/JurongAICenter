'use client';

import Link from 'next/link';
import { ArrowRight, Sparkles, Zap } from 'lucide-react';

export function Hero() {
  return (
    <section className="pt-8">
      {/* 主标题 */}
      <h1 className="text-center text-3xl font-bold leading-tight sm:text-5xl">
        <span className="text-gradient">全域智像</span>
      </h1>

      {/* 强调副标题：混排高亮关键词 */}
      <p className="mt-4 text-center text-lg font-medium text-fg sm:text-xl">
        <span className="text-gradient">AI 一键生成</span>
        <span className="mx-2 text-fg-subtle">·</span>
        <span className="inline-flex items-baseline gap-1">
          <span className="bg-gradient-to-r from-amber-500 to-rose-500 bg-clip-text text-transparent text-2xl font-bold sm:text-3xl">
            30 秒
          </span>
          <span className="text-fg">出片</span>
        </span>
      </p>

      {/* 简短说明：三个场景 */}
      <div className="mt-3 flex flex-wrap items-center justify-center gap-x-3 gap-y-1 text-sm text-fg-muted">
        <span className="inline-flex items-center gap-1">
          <Sparkles className="h-3.5 w-3.5 text-brand" />
          投流视频
        </span>
        <span className="text-fg-subtle">/</span>
        <span className="inline-flex items-center gap-1">
          <Sparkles className="h-3.5 w-3.5 text-brand" />
          详情套图
        </span>
        <span className="text-fg-subtle">/</span>
        <span className="inline-flex items-center gap-1">
          <Sparkles className="h-3.5 w-3.5 text-brand" />
          数字分身
        </span>
        <span className="ml-1 text-fg-subtle">· 电商全场景</span>
      </div>

      {/* 两个胶囊入口 */}
      <div className="mt-7 flex flex-wrap items-center justify-center gap-4">
        <PillCard
          href="/ai-video"
          bg="from-[#e0e7ff] to-[#dbeafe]"
          leftIcon={<Zap className="h-5 w-5 text-brand" />}
          title="「帮我写」一键生成投流视频"
          hint="30 秒出片"
        />
        <PillCard
          href="/tools/product-image"
          bg="from-[#e0e7ff] to-[#e0e7ff]"
          leftIcon={<Sparkles className="h-5 w-5 text-brand" />}
          title="「一张图」生成一套商品详情图"
          hint="9 图自动出"
        />
      </div>
    </section>
  );
}

function PillCard({
  href, title, leftIcon, hint, bg,
}: {
  href: string;
  title: string;
  leftIcon: React.ReactNode;
  hint: string;
  bg: string;
}) {
  return (
    <Link
      href={href}
      className={`group flex items-center gap-3 rounded-full bg-gradient-to-r ${bg} py-1 pl-1 pr-1 shadow-soft transition hover:shadow-glow`}
    >
      <span className="grid h-12 w-12 place-items-center rounded-full bg-white/80 shadow-soft">
        {leftIcon}
      </span>
      <span className="flex flex-col px-1 leading-tight">
        <span className="text-sm font-medium text-fg">{title}</span>
        <span className="text-[10px] text-fg-muted">{hint}</span>
      </span>
      <span className="ml-1 grid h-8 w-8 place-items-center rounded-full bg-white text-brand shadow-soft transition group-hover:translate-x-0.5">
        <ArrowRight className="h-4 w-4" />
      </span>
    </Link>
  );
}
