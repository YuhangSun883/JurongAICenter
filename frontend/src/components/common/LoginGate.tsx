'use client';

import { useEffect, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { LoginDialog } from './LoginDialog';
import { onAuthFailure } from '@/lib/http';
import {
  bootstrapTokens, isLoggedIn,
  startAuthWatchdog, bindActivityRefresh,
} from '@/lib/auth-store';
import { bootstrapAuth } from '@/api/auth.real';

/**
 * 登录门控组件。
 *
 * 行业标准流程：
 *   1. 启动时从 localStorage 恢复 tokens；如已过期则 silent refresh
 *   2. 用户活跃（点击/键盘/滚动）→ 滑动窗口：如果 access token < 5 分钟过期，静默 refresh
 *   3. 周期性 watchdog：每 60 秒检查一次 token 状态
 *   4. silent refresh 失败（refresh token 也过期）→ 清 token + 跳 /login
 *   5. 任意 API 返回 401 且 refresh 失败 → 跳 /login
 *
 * 用法：
 *   在受保护的页面外层包上：
 *     <LoginGate><CanvasPage /></LoginGate>
 */
export function LoginGate({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [bootstrapped, setBootstrapped] = useState(false);
  const [showDialog, setShowDialog] = useState(false);

  // 启动时：bootstrap auth（恢复 token + 必要时 silent refresh）
  useEffect(() => {
    let cancelled = false;
    (async () => {
      bootstrapTokens();
      // 在登录页本身就不需要校验
      if (pathname === '/login') {
        setBootstrapped(true);
        return;
      }
      const ok = await bootstrapAuth();
      if (cancelled) return;
      if (!ok) {
        // 没登录或 refresh 失败 → 跳登录页
        router.replace(`/login?from=${encodeURIComponent(pathname || '/')}`);
        return;
      }
      setBootstrapped(true);
    })();
    return () => { cancelled = true; };
  }, [router, pathname]);

  // 启动周期性 watchdog（每 60 秒检查 token）
  useEffect(() => {
    startAuthWatchdog();
    const unbind = bindActivityRefresh();
    return () => {
      unbind();
      // 注意：watchdog 全局共享，不在 unmount 时停（否则切换 tab 会失效）
    };
  }, []);

  // 订阅全局 401 事件：refresh 失败 → 跳登录页
  useEffect(() => {
    return onAuthFailure(() => {
      if (pathname !== '/login') {
        router.replace(`/login?from=${encodeURIComponent(pathname || '/')}`);
      }
    });
  }, [router, pathname]);

  // storage 事件：跨 tab 同步登录状态
  useEffect(() => {
    function onStorage(event: StorageEvent) {
      if (event.key === 'accessToken') {
        if (!event.newValue && pathname !== '/login') {
          router.replace(`/login?from=${encodeURIComponent(pathname || '/')}`);
        }
      }
    }
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [router, pathname]);

  // bootstrap 没完成前显示空白（避免未鉴权页面闪一下）
  if (!bootstrapped) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#f1f2f4] text-sm text-[#8a96a8]">
        加载中…
      </div>
    );
  }

  return (
    <>
      {children}
      {showDialog && <LoginDialog onClose={() => setShowDialog(false)} />}
    </>
  );
}