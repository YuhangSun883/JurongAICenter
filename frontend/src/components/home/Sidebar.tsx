'use client';

import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import {
  Bot,
  ChevronRight,
  FolderOpen,
  Grid3x3,
  Home,
  Image as ImageIcon,
  LogOut,
  MessageSquare,
  Sparkles,
  UserCircle,
  Video,
  X,
} from 'lucide-react';
import { clearTokens, getUser, onAuthChange } from '@/lib/auth-store';
import { cn } from '@/lib/utils';
import type { UserInfo } from '@/types/user';

const NAV = [
  { href: '/', label: '首页', icon: Home },
  { href: '/agent', label: 'Agent', icon: MessageSquare },
  { href: '/ai-video', label: 'AI视频', icon: Video },
  { href: '/ai-image', label: 'AI图片', icon: ImageIcon },
  { href: '/canvas', label: '画布', icon: Grid3x3 },
  { href: '/assets', label: '资产', icon: FolderOpen },
];

interface SidebarProps {
  /** 保留旧参数兼容老页面，侧边栏底部现在统一由 Sidebar 自己渲染。 */
  bottom?: ReactNode;
}

function getDisplayName(user: UserInfo | null) {
  if (!user) return '用户0840';
  if (user.nickname?.trim()) return user.nickname.trim();
  if (user.email?.trim()) return user.email.split('@')[0];
  if (user.id) return `用户${String(user.id).slice(-4).padStart(4, '0')}`;
  return '用户0840';
}

function formatRegisterDate(createdAt?: string) {
  if (!createdAt) return '未记录';
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return createdAt;
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
  });
}

export function Sidebar(_props: SidebarProps = {}) {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = useState<UserInfo | null>(null);
  const [profileOpen, setProfileOpen] = useState(false);

  useEffect(() => {
    setUser(getUser<UserInfo>());
    return onAuthChange(() => setUser(getUser<UserInfo>()));
  }, []);

  useEffect(() => {
    if (!profileOpen) return;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') setProfileOpen(false);
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [profileOpen]);

  const displayName = useMemo(() => getDisplayName(user), [user]);

  function handleLogout() {
    clearTokens();
    setProfileOpen(false);
    router.push('/login');
  }

  return (
    <>
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
                <span className="text-[10px] leading-none">{item.label}</span>
              </Link>
            );
          })}
        </nav>

        <div className="mt-3 flex w-full flex-col items-center gap-3">
          <div className="group/account relative">
            <button
              type="button"
              className="grid h-10 w-10 place-items-center rounded-full bg-[#e9fbf6] text-[#12a985] shadow-soft transition hover:scale-105 hover:bg-[#dff8f0]"
              title="账户"
            >
              <Bot className="h-5 w-5" />
            </button>

            <div className="pointer-events-none absolute bottom-[-10px] left-[44px] z-50 hidden w-[236px] rounded-[18px] border border-[#e9edf3] bg-white p-3 text-left shadow-[0_18px_42px_rgba(17,24,39,0.14)] group-hover/account:block group-focus-within/account:block">
              <div className="pointer-events-auto">
                <div className="mb-3 flex items-center gap-3">
                  <div className="grid h-10 w-10 place-items-center rounded-full bg-[#e9fbf6] text-[#12a985]">
                    <Bot className="h-5 w-5" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      <span className="truncate text-sm font-semibold text-[#1f2937]">{displayName}</span>
                      <span className="shrink-0 rounded-full bg-[#f0fbf7] px-2 py-0.5 text-[10px] font-medium text-[#11a37f]">
                        个人版
                      </span>
                    </div>
                    <div className="mt-0.5 text-xs text-[#9aa3af]">已登录账户</div>
                  </div>
                </div>

                <div className="space-y-1 border-y border-[#eef1f5] py-2">
                  <button
                    type="button"
                    onClick={() => router.push('/credits')}
                    className="flex h-9 w-full items-center justify-between rounded-xl px-2 text-sm text-[#344054] transition hover:bg-[#f6f8fb]"
                  >
                    <span className="flex items-center gap-2">
                      <Sparkles className="h-4 w-4 text-brand" />
                      我的积分
                    </span>
                    <ChevronRight className="h-4 w-4 text-[#b5bdc8]" />
                  </button>
                  <button
                    type="button"
                    onClick={() => setProfileOpen(true)}
                    className="flex h-9 w-full items-center justify-between rounded-xl px-2 text-sm text-[#344054] transition hover:bg-[#f6f8fb]"
                  >
                    <span className="flex items-center gap-2">
                      <UserCircle className="h-4 w-4 text-[#64748b]" />
                      个人信息
                    </span>
                    <ChevronRight className="h-4 w-4 text-[#b5bdc8]" />
                  </button>
                </div>

                <button
                  type="button"
                  onClick={handleLogout}
                  className="mt-2 flex h-9 w-full items-center gap-2 rounded-xl px-2 text-sm text-[#667085] transition hover:bg-[#fff1f1] hover:text-[#d92d20]"
                >
                  <LogOut className="h-4 w-4" />
                  退出登录
                </button>
              </div>
            </div>
          </div>

        </div>
      </aside>

      {profileOpen && (
        <div
          className="fixed inset-0 z-[70] grid place-items-center bg-slate-900/35 p-4 backdrop-blur-[3px]"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setProfileOpen(false);
          }}
        >
          <div className="w-full max-w-[384px] rounded-[18px] bg-white px-6 pb-8 pt-5 text-[#111827] shadow-[0_24px_80px_rgba(15,23,42,0.24)]">
            <div className="mb-8 flex items-center justify-between">
              <h3 className="text-base font-semibold">个人信息</h3>
              <button
                type="button"
                onClick={() => setProfileOpen(false)}
                className="grid h-8 w-8 place-items-center rounded-full text-[#6b7280] transition hover:bg-slate-100 hover:text-[#111827]"
                title="关闭"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="divide-y divide-[#e5e7eb]">
              <ProfileRow label="邮箱" value={user?.email || '立即绑定'} strong={!user?.email} />
              <ProfileRow label="昵称" value={displayName} />
              <ProfileRow label="注册时间" value={formatRegisterDate(user?.createdAt)} />
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function ProfileRow({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex h-12 items-center justify-between gap-4 text-sm">
      <span className="shrink-0 text-[#6b7280]">{label}</span>
      <span className={cn('min-w-0 truncate text-right text-[#111827]', strong && 'font-semibold')}>
        {value}
      </span>
    </div>
  );
}
