'use client';

import { useEffect, useState } from 'react';
import { LoginDialog } from './LoginDialog';
import { RegisterDialog } from './RegisterDialog';

export function LoginGate() {
  // null=关闭，'login'=登录，'register'=注册
  const [mode, setMode] = useState<'login' | 'register' | null>(null);

  // 检查当前 token 状态
  const checkAuth = () => {
    if (typeof window === 'undefined') return;
    const hasToken = !!(localStorage.getItem('token') || localStorage.getItem('accessToken'));
    setMode(hasToken ? null : 'login');
  };

  // 初始检查
  useEffect(() => {
    checkAuth();
  }, []);

  // 监听 auth-changed 事件（登出、refresh 失败等）
  useEffect(() => {
    const handler = () => checkAuth();
    window.addEventListener('auth-changed', handler);
    return () => window.removeEventListener('auth-changed', handler);
  }, []);

  if (!mode) return null;

  if (mode === 'register') {
    return (
      <RegisterDialog
        onClose={() => setMode(null)}
        onSuccess={() => {
          // 注册成功 → 关闭弹窗，触发全局刷新
          window.dispatchEvent(new Event('auth-changed'));
          setMode(null);
        }}
        onSwitchToLogin={() => setMode('login')}
      />
    );
  }

  return (
    <LoginDialog
      onClose={() => setMode(null)}
      onSwitchToRegister={() => setMode('register')}
    />
  );
}