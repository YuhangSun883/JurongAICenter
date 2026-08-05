import { Bot, Coins, Menu } from 'lucide-react';
import { Sidebar } from '@/components/home/Sidebar';
import { Workbench } from '@/components/workbench/Workbench';

export default function Page() {
  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar
        bottom={
          <>
            <button
              type="button"
              className="flex w-12 flex-col items-center gap-0.5 rounded-xl py-1.5 text-fg-muted hover:text-brand"
              title="积分"
            >
              <span className="grid h-9 w-9 place-items-center rounded-xl border border-bg-line bg-bg-soft">
                <Coins className="h-4 w-4 text-brand" />
              </span>
              <span className="text-[10px]">0</span>
            </button>
            <button
              type="button"
              className="grid h-9 w-9 place-items-center rounded-full bg-brand-50 text-brand shadow-soft"
              title="助手"
            >
              <Bot className="h-4 w-4" />
            </button>
            <button
              type="button"
              className="grid h-9 w-9 place-items-center rounded-xl text-fg-muted hover:bg-bg-soft hover:text-fg"
              title="菜单"
            >
              <Menu className="h-4 w-4" />
            </button>
          </>
        }
      />
      <Workbench />
    </div>
  );
}
