'use client';

import { Plus } from 'lucide-react';
import { useWorkbenchStore } from '@/store/workbench';

export function TopBar() {
  const resetAll = useWorkbenchStore((s) => s.setScript); // 新建 = 清空脚本
  return (
    <header className="border-b border-bg-line/80 bg-bg/80 backdrop-blur-md">
      <div className="container mx-auto flex h-14 items-center justify-between px-4">
        <div className="flex items-center gap-4">
          <h1 className="text-base font-semibold text-fg">AI 视频</h1>
        </div>
        <button
          type="button"
          onClick={() => resetAll('')}
          className="flex items-center gap-1 rounded-lg px-3 py-1.5 text-sm text-fg-muted hover:bg-bg-soft hover:text-fg"
        >
          <Plus className="h-4 w-4" />
          新建
        </button>
      </div>
    </header>
  );
}