'use client';

import { useCallback, useEffect, useState } from 'react';
import { LogOut, Coins } from 'lucide-react';
import { authApi } from '@/api/auth';
import { usersApi, type UserMeResponse } from '@/api/users';

/** 从 localStorage 读取降级用的基本用户信息（getMe 失败时使用） */
function getFallbackUser(): Partial<UserMeResponse> | null {
  if (typeof window === 'undefined') return null;
  const raw = localStorage.getItem('user');
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Partial<UserMeResponse>;
  } catch {
    return null;
  }
}

export function SidebarUserMenu() {
  const [user, setUser] = useState<UserMeResponse | null>(null);
  const [credits, setCredits] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [hovered, setHovered] = useState(false);

  const fetchUser = useCallback(async () => {
    try {
      const me = await usersApi.getMe();
      setUser(me);
    } catch {
      const fallback = getFallbackUser();
      if (fallback) {
        setUser(fallback as UserMeResponse);
      }
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

  // 监听 auth-changed 事件
  useEffect(() => {
    const handler = () => {
      const token = localStorage.getItem('token') || localStorage.getItem('accessToken');
      if (!token) {
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

  const handleLogout = async () => {
    setHovered(false);
    await authApi.logout();
  };

  if (loading) {
    return <div className="h-10 w-10 animate-pulse rounded-full bg-bg-soft" />;
  }

  const displayUser = user || getFallbackUser();
  if (!displayUser) {
    return null;
  }

  const displayName = displayUser.displayName || displayUser.email?.split('@')[0] || '用户';
  const isAdmin = displayUser.role === 'ADMIN';
  const initial = displayName.charAt(0).toUpperCase();

  return (
    <div className="flex flex-col items-center gap-2">
      {/* 积分 */}
      {credits !== null && (
        <div className="flex h-7 items-center gap-1 rounded-lg bg-bg-soft px-2 text-xs text-fg-muted">
          <Coins className="h-3 w-3 text-amber-500" />
          <span className="font-semibold text-fg">{credits}</span>
        </div>
      )}

      {/* 用户头像 + 登出按钮（用 state 控制 hover，避免间隙问题） */}
      <div
        className="relative flex flex-col items-center"
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
      >
        {/* 下拉菜单 — 放在头像上方，用 padding 填补间隙 */}
        {hovered && (
          <div className="absolute -top-12 left-1/2 -translate-x-1/2 pb-1">
            <button
              type="button"
              onClick={handleLogout}
              className="flex items-center gap-1 rounded-lg bg-white px-3 py-1.5 text-xs text-red-600 shadow-lg ring-1 ring-black/5 hover:bg-red-50"
            >
              <LogOut className="h-3 w-3" />
              退出
            </button>
            {/* 透明桥接层：填补菜单和头像之间的间隙，确保鼠标移动时不会丢失 hover */}
            <div className="h-2 w-full" />
          </div>
        )}

        {/* 头像 */}
        <div
          className={`grid h-10 w-10 cursor-pointer place-items-center rounded-full text-sm font-semibold text-white ${
            isAdmin ? 'bg-purple-500' : 'bg-slate-500'
          } ${hovered ? 'ring-2 ring-brand ring-offset-2 ring-offset-bg' : ''}`}
          title={displayName}
        >
          {initial}
        </div>
      </div>
    </div>
  );
}