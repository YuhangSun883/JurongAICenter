'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronDown, LogOut, Plus, Settings, UserRound, Coins } from 'lucide-react';
import { useWorkbenchStore } from '@/store/workbench';
import { authApi } from '@/api/auth';
import { usersApi, type UserMeResponse } from '@/api/users';
import { ApiError } from '@/lib/http';

export function TopBar() {
  const resetAll = useWorkbenchStore((s) => s.setScript);
  const [menuOpen, setMenuOpen] = useState(false);
  const [user, setUser] = useState<UserMeResponse | null>(null);
  const [credits, setCredits] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const menuRef = useRef<HTMLDivElement>(null);

  const fetchUser = useCallback(async () => {
    try {
      const me = await usersApi.getMe();
      setUser(me);
    } catch {
      // 静默忽略：可能 token 过期，LoginGate 会处理
    }
  }, []);

  const fetchQuota = useCallback(async () => {
    try {
      const quota = await usersApi.getMyQuota();
      setCredits(quota.credits);
    } catch {
      // 静默忽略
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchUser();
    fetchQuota();
  }, [fetchUser, fetchQuota]);

  // 监听 auth-changed 事件：登录/登出/refresh 成功后刷新
  useEffect(() => {
    const handler = () => {
      // 登出后清空
      if (!localStorage.getItem('token') && !localStorage.getItem('accessToken')) {
        setUser(null);
        setCredits(null);
        setLoading(false);
        return;
      }
      setLoading(true);
      fetchUser();
      fetchQuota();
    };
    window.addEventListener('auth-changed', handler);
    return () => window.removeEventListener('auth-changed', handler);
  }, [fetchUser, fetchQuota]);

  // 点击外部关闭菜单
  useEffect(() => {
    if (!menuOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [menuOpen]);

  const handleLogout = async () => {
    try {
      await authApi.logout();
    } catch (e) {
      if (e instanceof ApiError) {
        console.warn('logout api failed', e.status);
      }
    }
    setMenuOpen(false);
    setUser(null);
    setCredits(null);
    // LoginGate 会自动检测 auth-changed 事件并弹登录框
    window.dispatchEvent(new Event('auth-changed'));
  };

  const displayName = user?.displayName || user?.email?.split('@')[0] || '用户';
  const isAdmin = user?.role === 'ADMIN';
  const initial = displayName.charAt(0).toUpperCase();

  return (
    <header className="border-b border-bg-line/80 bg-bg/80 backdrop-blur-md">
      <div className="container mx-auto flex h-14 items-center justify-between px-4">
        <div className="flex items-center gap-4">
          <h1 className="text-base font-semibold text-fg">AI 视频</h1>
        </div>
        <div className="flex items-center gap-2">
          {/* 积分展示 */}
          {loading ? (
            <div className="flex h-8 w-24 animate-pulse items-center justify-center rounded-lg bg-bg-soft text-sm text-fg-muted">
              ...
            </div>
          ) : credits !== null ? (
            <div className="flex h-8 items-center gap-1 rounded-lg bg-bg-soft px-3 text-sm text-fg-muted">
              <Coins className="h-4 w-4 text-amber-500" />
              <span className="font-semibold text-fg">{credits}</span>
              <span className="text-xs">积分</span>
            </div>
          ) : null}

          {/* 新建按钮 */}
          <button
            type="button"
            onClick={() => resetAll('')}
            className="flex items-center gap-1 rounded-lg px-3 py-1.5 text-sm text-fg-muted hover:bg-bg-soft hover:text-fg"
          >
            <Plus className="h-4 w-4" />
            新建
          </button>

          {/* 用户菜单 */}
          <div className="relative" ref={menuRef}>
            <button
              type="button"
              onClick={() => setMenuOpen((v) => !v)}
              className="flex items-center gap-2 rounded-lg px-2 py-1.5 hover:bg-bg-soft"
            >
              <div className={`grid h-8 w-8 place-items-center rounded-full text-sm font-semibold text-white ${isAdmin ? 'bg-purple-500' : 'bg-slate-500'}`}>
                {initial}
              </div>
              <div className="hidden text-left sm:block">
                <div className="text-sm font-medium leading-tight text-fg">{displayName}</div>
                <div className="flex items-center gap-1 text-xs text-fg-muted">
                  {isAdmin ? (
                    <span className="rounded bg-purple-100 px-1.5 py-0.5 text-[10px] font-medium text-purple-700">ADMIN</span>
                  ) : (
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-600">USER</span>
                  )}
                </div>
              </div>
              <ChevronDown className={`h-4 w-4 text-fg-muted transition-transform ${menuOpen ? 'rotate-180' : ''}`} />
            </button>

            {/* 下拉菜单 */}
            {menuOpen && (
              <div className="absolute right-0 top-full z-50 mt-2 w-64 rounded-xl border border-bg-line bg-white p-2 shadow-lg">
                {/* 用户信息 */}
                <div className="mb-2 rounded-lg bg-bg-soft p-3">
                  <div className="flex items-center gap-3">
                    <div className={`grid h-10 w-10 place-items-center rounded-full text-base font-semibold text-white ${isAdmin ? 'bg-purple-500' : 'bg-slate-500'}`}>
                      {initial}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-medium text-fg">{displayName}</div>
                      <div className="truncate text-xs text-fg-muted">{user?.email}</div>
                    </div>
                  </div>
                  {credits !== null && (
                    <div className="mt-2 flex items-center justify-between text-xs text-fg-muted">
                      <span>剩余积分</span>
                      <span className="font-semibold text-amber-500">{credits}</span>
                    </div>
                  )}
                </div>

                {/* 菜单选项 */}
                <div className="space-y-1">
                  <button
                    type="button"
                    onClick={() => {
                      setMenuOpen(false);
                      // 跳转到用户中心（占位，可后续实现 settings 页面）
                      alert('用户中心页面待开发');
                    }}
                    className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-fg hover:bg-bg-soft"
                  >
                    <UserRound className="h-4 w-4" />
                    个人资料
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setMenuOpen(false);
                      alert('设置页面待开发');
                    }}
                    className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-fg hover:bg-bg-soft"
                  >
                    <Settings className="h-4 w-4" />
                    设置
                  </button>
                </div>

                {/* 分割线 */}
                <div className="my-2 border-t border-bg-line" />

                {/* 登出 */}
                <button
                  type="button"
                  onClick={handleLogout}
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-red-600 hover:bg-red-50"
                >
                  <LogOut className="h-4 w-4" />
                  退出登录
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </header>
  );
}