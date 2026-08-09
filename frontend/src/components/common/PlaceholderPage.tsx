// 通用占位页：标题 + 描述 + 虚线卡片「功能开发中」
// 侧边栏的 6 个入口、工具宫的 7 个工具，都用这个组件。

import { Sparkles } from 'lucide-react';

export function PlaceholderPage({
  title,
  desc,
  emoji = '✨',
}: {
  title: string;
  desc: string;
  emoji?: string;
}) {
  return (
    <div className="grid min-h-[60vh] place-items-center">
      <div className="card w-full max-w-xl p-10 text-center">
        <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-brand-50 text-2xl">
          {emoji}
        </div>
        <h1 className="mt-4 text-xl font-semibold text-fg">{title}</h1>
        <p className="mt-1 text-sm text-fg-muted">{desc}</p>
        <div className="mx-auto mt-6 flex w-fit items-center gap-1.5 rounded-full border border-dashed border-bg-line bg-bg-soft px-3 py-1.5 text-xs text-fg-muted">
          <Sparkles className="h-3.5 w-3.5 text-brand" />
          功能开发中 · 接口就绪后接入
        </div>
      </div>
    </div>
  );
}
