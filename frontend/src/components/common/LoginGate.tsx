'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { onAuthFailure } from '@/lib/http';
import {
  bindActivityRefresh,
  bootstrapConsoleTokens,
  bootstrapTokens,
  getConsoleUser,
  isConsoleLoggedIn,
  startAuthWatchdog,
} from '@/lib/auth-store';
import { bootstrapAuth } from '@/api/auth.real';
import type { UserInfo } from '@/types/user';

const CONSOLE_ROLES = new Set(['ADMIN', 'FINANCE', 'OPERATOR', 'VIEWER']);

/**
 * Route guard shared by the app shell.
 * Front-office pages use /login, while the isolated console uses /admin/login.
 */
export function LoginGate({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [bootstrapped, setBootstrapped] = useState(false);

  const isAdminPath = pathname?.startsWith('/admin') ?? false;
  const isAuthPage = pathname === '/login' || pathname === '/admin/login';
  const loginPath = isAdminPath ? '/admin/login' : '/login';

  useEffect(() => {
    let cancelled = false;

    async function run() {
      bootstrapTokens();
      bootstrapConsoleTokens();

      if (isAuthPage) {
        setBootstrapped(true);
        return;
      }

      if (isAdminPath) {
        if (!isConsoleLoggedIn()) {
          router.replace(`${loginPath}?from=${encodeURIComponent(pathname || '/admin')}`);
          return;
        }

        const user = getConsoleUser<UserInfo>();
        if (user?.channel !== 'CONSOLE' || !CONSOLE_ROLES.has(user?.role || '')) {
          router.replace(`/admin/login?from=${encodeURIComponent(pathname || '/admin')}`);
          return;
        }

        setBootstrapped(true);
        return;
      }

      const ok = await bootstrapAuth();
      if (cancelled) return;

      if (!ok) {
        router.replace(`${loginPath}?from=${encodeURIComponent(pathname || '/')}`);
        return;
      }

      setBootstrapped(true);
    }

    run();
    return () => {
      cancelled = true;
    };
  }, [isAdminPath, isAuthPage, loginPath, pathname, router]);

  useEffect(() => {
    if (isAdminPath) return;
    startAuthWatchdog();
    return bindActivityRefresh();
  }, [isAdminPath]);

  useEffect(() => {
    return onAuthFailure(() => {
      if (!isAuthPage) {
        router.replace(`${loginPath}?from=${encodeURIComponent(pathname || '/')}`);
      }
    });
  }, [isAuthPage, loginPath, pathname, router]);

  useEffect(() => {
    function onStorage(event: StorageEvent) {
      const watchedKey = isAdminPath ? 'consoleAccessToken' : 'accessToken';
      if (event.key === watchedKey && !event.newValue && !isAuthPage) {
        router.replace(`${loginPath}?from=${encodeURIComponent(pathname || '/')}`);
      }
    }

    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [isAuthPage, loginPath, pathname, router]);

  if (!bootstrapped) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#080b12] text-sm text-cyan-100/70">
        正在初始化访问权限...
      </div>
    );
  }

  return <>{children}</>;
}
