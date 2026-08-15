'use client';

import { Activity, ClipboardList, Coins, Database, FileClock, Image, LogOut, Settings, ShieldCheck, UserCog, Users } from 'lucide-react';
import { useRouter } from 'next/navigation';
import type { ReactNode } from 'react';
import { authApi } from '@/api/auth';
import { getUser } from '@/lib/auth-store';
import type { UserInfo } from '@/types/user';

interface ConsoleShellProps {
  active: string;
  onActiveChange: (key: string) => void;
  children: ReactNode;
}

const navItems = [
  { key: 'overview', label: '经营总览', icon: Activity },
  { key: 'users', label: '用户与权限', icon: Users },
  { key: 'admins', label: '后台账号', icon: UserCog },
  { key: 'orders', label: '财务订单', icon: ClipboardList },
  { key: 'billings', label: '积分流水', icon: Coins },
  { key: 'pricing', label: '计费规则', icon: Database },
  { key: 'jobs', label: '生成任务', icon: FileClock },
  { key: 'assets', label: '素材资产', icon: Image },
  { key: 'settings', label: '系统配置', icon: Settings },
  { key: 'audits', label: '操作审计', icon: ShieldCheck },
];

export function ConsoleShell({ active, onActiveChange, children }: ConsoleShellProps) {
  const router = useRouter();
  const user = getUser<UserInfo>();

  async function handleLogout() {
    await authApi.logout();
    router.replace('/admin/login');
  }

  return (
    <main className="min-h-screen overflow-hidden bg-[#050814] text-[#e7fbff]">
      <div className="pointer-events-none fixed inset-0 bg-[linear-gradient(90deg,rgba(34,211,238,0.06)_1px,transparent_1px),linear-gradient(180deg,rgba(20,184,166,0.045)_1px,transparent_1px)] bg-[size:48px_48px]" />
      <div className="relative grid h-screen min-h-screen grid-cols-[260px_1fr]">
        <aside className="sticky top-0 h-screen overflow-y-auto border-r border-cyan-300/15 bg-black/35 px-4 py-5 backdrop-blur">
          <div className="flex items-center gap-3 border-b border-cyan-300/15 pb-5">
            <div className="grid h-10 w-10 shrink-0 place-items-center rounded-lg border border-cyan-300/40 bg-cyan-300/10 text-cyan-200 shadow-[0_0_28px_rgba(34,211,238,0.18)]">
              <ShieldCheck size={20} />
            </div>
            <div className="min-w-0">
              <div className="truncate text-base font-semibold text-cyan-50">全域智像后台</div>
              <div className="text-xs text-cyan-100/50">独立管理端</div>
            </div>
          </div>

          <nav className="mt-5 space-y-1">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <button
                  key={item.key}
                  type="button"
                  onClick={() => onActiveChange(item.key)}
                  className={[
                    'flex h-10 w-full items-center gap-3 rounded-lg px-3 text-sm transition',
                    active === item.key
                      ? 'border border-cyan-300/40 bg-cyan-300/12 text-cyan-100 shadow-[0_0_24px_rgba(34,211,238,0.16)]'
                      : 'border border-transparent text-cyan-100/62 hover:border-cyan-300/20 hover:bg-white/5 hover:text-cyan-100',
                  ].join(' ')}
                >
                  <Icon size={16} />
                  <span className="flex-1 text-left">{item.label}</span>
                  <span className="h-1.5 w-1.5 rounded-full bg-current opacity-60" />
                </button>
              );
            })}
          </nav>
        </aside>

        <section className="relative min-w-0 overflow-y-auto px-6 py-5">
          <header className="mb-5 flex h-14 items-center justify-between border-b border-cyan-300/15">
            <div>
              <h1 className="text-xl font-semibold text-white">后台控制中心</h1>
              <p className="text-xs text-cyan-100/50">用户、账号、任务、素材、积分和审计统一管理</p>
            </div>
            <div className="flex items-center gap-3">
              <div className="max-w-[260px] truncate rounded-lg border border-emerald-300/25 bg-emerald-300/10 px-3 py-2 text-xs text-emerald-100">
                {user?.email || '后台管理员'}
              </div>
              <button
                type="button"
                onClick={handleLogout}
                title="退出后台"
                className="grid h-9 w-9 place-items-center rounded-lg border border-cyan-300/20 bg-white/5 text-cyan-100/70 hover:text-white"
              >
                <LogOut size={16} />
              </button>
            </div>
          </header>
          {children}
        </section>
      </div>
    </main>
  );
}
