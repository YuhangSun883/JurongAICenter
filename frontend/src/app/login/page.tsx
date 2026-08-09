'use client';

import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { LoginDialog } from '@/components/common/LoginDialog';
import { isLoggedIn } from '@/lib/auth-store';
import { onAuthChange } from '@/lib/auth-store';

/**
 * /login 页：
 * - 已登录 → 直接跳到 from（或 '/'）
 * - 未登录 → 弹 LoginDialog（fullscreen 风格）
 * - 登录成功 → 跳到 from
 */
export default function LoginPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const from = searchParams?.get('from') || '/';
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  // 已登录：直接跳走
  useEffect(() => {
    if (!mounted) return;
    if (isLoggedIn()) {
      router.replace(from);
    }
  }, [mounted, router, from]);

  // 监听登录成功事件：跳回 from
  useEffect(() => {
    return onAuthChange((event) => {
      if (event === 'login') {
        router.replace(from);
      }
    });
  }, [router, from]);

  return (
    <main className="relative min-h-screen bg-[#f1f2f4]">
      <LoginDialog onClose={() => router.replace(from)} initialMode="login" />
    </main>
  );
}