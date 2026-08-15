'use client';

import { FormEvent, useState } from 'react';
import { Eye, EyeOff, KeyRound, Loader2, Mail, UserPlus, X } from 'lucide-react';
import { authApi } from '@/api/auth';
import { ApiError } from '@/lib/http';

interface LoginDialogProps {
  onClose?: () => void;
  initialMode?: 'login' | 'register';
}

/**
 * 统一登录/注册弹窗（邮箱登录）
 * - 登录：邮箱 + 密码
 * - 注册：邮箱 + 密码 + 昵称（选填）
 * 成功后写 token 到 localStorage 并 dispatch auth-changed。
 */
export function LoginDialog({ onClose, initialMode = 'login' }: LoginDialogProps) {
  const [mode, setMode] = useState<'login' | 'register'>(initialMode);
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  function switchMode(next: 'login' | 'register') {
    setMode(next);
    setError('');
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');

    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      setError('请输入邮箱');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
      setError('邮箱格式不正确');
      return;
    }
    if (!password || password.length < 8) {
      setError('请输入至少 8 位密码');
      return;
    }

    setSubmitting(true);
    try {
      if (mode === 'login') {
        await authApi.login({
          email: trimmedEmail,
          password,
        });
      } else {
        await authApi.register({
          email: trimmedEmail,
          password,
          displayName: displayName.trim() || undefined,
        });
      }
      // auth.real.ts 已经通过 auth-store.setTokens + setUser 持久化双 token + 用户
      // 这里只需通知刷新 + 关闭弹窗
      window.dispatchEvent(new Event('auth-changed'));
      onClose?.();
    } catch (cause) {
      if (cause instanceof ApiError) {
        const payload = cause.payload as { message?: string; code?: number } | undefined;
        // 6010 ADMIN_LOGIN_FORBIDDEN_ON_USER_ENTRY：管理员账号走错入口了，
        // 给一个更醒目的提示（带建议去哪里登录）
        if (payload?.code === 6010) {
          setError(
            payload.message ||
              '管理员账号不允许在此登录，请使用管理后台专属入口',
          );
        } else {
          setError(
            payload?.message ||
              (mode === 'login' ? '登录失败，请检查邮箱或密码' : '注册失败'),
          );
        }
      } else {
        setError('网络异常，请稍后重试');
      }
    } finally {
      setSubmitting(false);
    }
  }

  const isLogin = mode === 'login';

  return (
    <div className="fixed inset-0 z-[100] grid place-items-center bg-slate-900/35 p-4 backdrop-blur-[6px]">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="login-title"
        className="relative flex w-full max-w-[672px] overflow-hidden rounded-[18px] bg-white p-2 shadow-[0_24px_80px_rgba(15,23,42,0.24)]"
      >
        <button
          type="button"
          aria-label="关闭登录窗口"
          onClick={onClose}
          className="absolute right-3 top-3 z-10 grid h-8 w-8 place-items-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
        >
          <X className="h-4 w-4" />
        </button>

        <div
          aria-hidden="true"
          className="relative hidden min-h-[440px] w-[292px] shrink-0 overflow-hidden rounded-[13px] bg-[#08121c] bg-cover bg-center sm:block"
          style={{ backgroundImage: "url('/login-visual.png')" }}
        >
          <div className="absolute inset-0 bg-gradient-to-b from-black/5 via-transparent to-black/35" />
          <div className="absolute inset-x-0 bottom-6 px-5 text-white">
            <p className="text-lg font-semibold">让创作，自然发生</p>
            <p className="mt-1 text-xs text-white/70">AI 驱动的电商内容工作台</p>
          </div>
        </div>

        <div className="flex min-h-[440px] min-w-0 flex-1 flex-col justify-center px-5 py-8 sm:px-8">
          <div className="mb-7">
            <p className="text-xs font-medium uppercase tracking-[0.22em] text-brand">
              {isLogin ? 'Welcome back' : 'Get started'}
            </p>
            <h1 id="login-title" className="mt-2 text-2xl font-bold tracking-tight text-slate-900">
              {isLogin ? '登录 JRai' : '注册 JRai'}
            </h1>
            <p className="mt-2 text-sm text-slate-500">
              {isLogin ? '用邮箱登录，继续使用你的 AI 创作工作台' : '用邮箱注册，开启 AI 创作之旅'}
            </p>
          </div>

          <form className="space-y-4" onSubmit={handleSubmit}>
            {/* 邮箱（登录注册都用这一个） */}
            <label className="block">
              <span className="mb-2 block text-xs font-medium text-slate-600">邮箱</span>
              <span className="relative block">
                <Mail className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  autoComplete="email"
                  placeholder="请输入邮箱（作为登录账号）"
                  className="h-11 w-full rounded-xl border border-slate-200 bg-slate-50 pl-10 pr-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-brand focus:bg-white focus:ring-4 focus:ring-brand/10"
                />
              </span>
            </label>

            {/* 昵称（仅注册） */}
            {!isLogin && (
              <label className="block">
                <span className="mb-2 block text-xs font-medium text-slate-600">
                  昵称 <span className="text-slate-400">（选填）</span>
                </span>
                <span className="relative block">
                  <UserPlus className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                  <input
                    value={displayName}
                    onChange={(event) => setDisplayName(event.target.value)}
                    autoComplete="nickname"
                    placeholder="请输入昵称"
                    className="h-11 w-full rounded-xl border border-slate-200 bg-slate-50 pl-10 pr-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-brand focus:bg-white focus:ring-4 focus:ring-brand/10"
                  />
                </span>
              </label>
            )}

            {/* 密码 */}
            <label className="block">
              <span className="mb-2 block text-xs font-medium text-slate-600">密码</span>
              <span className="relative block">
                <KeyRound className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete={isLogin ? 'current-password' : 'new-password'}
                  placeholder={isLogin ? '请输入密码' : '请输入密码（至少 8 位）'}
                  className="h-11 w-full rounded-xl border border-slate-200 bg-slate-50 pl-10 pr-11 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-brand focus:bg-white focus:ring-4 focus:ring-brand/10"
                />
                <button
                  type="button"
                  aria-label={showPassword ? '隐藏密码' : '显示密码'}
                  onClick={() => setShowPassword((value) => !value)}
                  className="absolute right-3 top-1/2 grid h-7 w-7 -translate-y-1/2 place-items-center rounded-md text-slate-400 transition hover:bg-slate-200 hover:text-slate-700"
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </span>
            </label>

            {isLogin && (
              <div className="flex items-center justify-between pt-1">
                <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-500">
                  <input type="checkbox" className="h-3.5 w-3.5 rounded border-slate-300 text-brand focus:ring-brand/20" />
                  记住我
                </label>
                <button type="button" className="text-xs font-medium text-brand transition hover:text-brand-dark">
                  忘记密码？
                </button>
              </div>
            )}

            {error && (
              <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600">
                {error}
              </p>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="flex h-11 w-full items-center justify-center gap-2 rounded-xl bg-slate-900 text-sm font-semibold text-white shadow-lg shadow-slate-900/15 transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
              {submitting ? (isLogin ? '登录中...' : '注册中...') : (isLogin ? '登录' : '注册')}
            </button>
          </form>

          <p className="mt-6 text-center text-xs text-slate-400">
            {isLogin ? '还没有账号？' : '已有账号？'}
            <button
              type="button"
              className="ml-1 font-medium text-brand hover:text-brand-dark"
              onClick={() => switchMode(isLogin ? 'register' : 'login')}
            >
              {isLogin ? '立即注册' : '直接登录'}
            </button>
          </p>
        </div>
      </div>
    </div>
  );
}