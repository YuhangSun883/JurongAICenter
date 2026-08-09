'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Home, MessageSquare, Video, Image as ImageIcon, Grid3x3, FolderOpen } from 'lucide-react';
import { cn } from '@/lib/utils';

const NAV = [
  { href: '/', label: '首页', icon: Home },
  { href: '/agent', label: 'Agent', icon: MessageSquare },
  { href: '/ai-video', label: 'AI视频', icon: Video },
  { href: '/ai-image', label: 'AI图片', icon: ImageIcon },
  { href: '/canvas', label: '画布', icon: Grid3x3 },
  { href: '/assets', label: '资产', icon: FolderOpen },
];

interface SidebarProps {
  /** 渲染在底部的额外内容（如积分、设置） */
  bottom?: React.ReactNode;
}

export function Sidebar({ bottom }: SidebarProps) {
  const pathname = usePathname();
  return (
    <aside className="fixed left-0 top-0 z-30 flex h-screen w-[72px] flex-col items-center border-r border-bg-line bg-bg-card/80 py-5 backdrop-blur-md">
      <Link
        href="/"
        className="mb-8 grid h-10 w-10 place-items-center rounded-2xl bg-gradient-to-br from-brand to-[#8b8cff] text-white shadow-glow"
      >
        <span className="text-base font-bold">J</span>
      </Link>
      <nav className="flex flex-1 flex-col items-center gap-2">
        {NAV.map((item) => {
          const active = pathname === item.href;
          const Icon = item.icon;
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                'group flex w-12 flex-col items-center gap-1 rounded-xl py-2 text-fg-subtle transition hover:text-brand',
                active && 'text-brand'
              )}
            >
              <span
                className={cn(
                  'grid h-9 w-9 place-items-center rounded-xl transition',
                  active
                    ? 'bg-brand-50 text-brand'
                    : 'group-hover:bg-brand-50 group-hover:text-brand'
                )}
              >
                <Icon className="h-4 w-4" />
              </span>
              <span className="text-[10px]">{item.label}</span>
            </Link>
          );
        })}
      </nav>
      {bottom ? (
        <div className="mt-2 flex w-full flex-col items-center gap-2">{bottom}</div>
      ) : (
        <div className="text-[10px] text-fg-subtle">v0.1</div>
      )}
    </aside>
  );
}
