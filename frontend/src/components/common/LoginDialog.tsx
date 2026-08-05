'use client';

import { FormEvent, useState } from 'react';
import { Eye, EyeOff, KeyRound, Loader2, UserRound, X } from 'lucide-react';
import { authApi } from '@/api/auth';
import { ApiError } from '@/lib/http';
import { TOKEN_KEY } from '@/api/config';

interface LoginDialogProps {
  onClose?: () => void;
  /** 切换到注册弹窗 */
  onSwitchToRegister?: () => void;
}

export function LoginDialog({ onClose, onSwitchToRegister }: LoginDialogProps) {
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');

    if (!account.trim()) {
      setError('请输入账号');
      return;
    }
    if (!password) {
      setError('请输入密码');
      return;
    }

    setSubmitting(true);
    try {
      // 后端接口契约：POST /api/auth/login，参数为 { email, password }。
      const result = await authApi.login({
        email: account.trim(),
        password,
      });
      localStorage.setItem(TOKEN_KEY, result.accessToken);
      localStorage.setItem('refreshToken', result.refreshToken);
      localStorage.setItem('user', JSON.stringify({
        id: result.userId,
        email: result.email,
        role: result.role,
      }));
      // 通知所有监听者（SidebarUserMenu、TopBar 等）刷新用户信息
      window.dispatchEvent(new Event('auth-changed'));
      onClose?.();
    } catch (cause) {
      if (cause instanceof ApiError) {
        const payload = cause.payload as { message?: string } | undefined;
        setError(payload?.message || '登录失败，请检查账号或密码');
      } else {
        setError('网络异常，请稍后重试');
      }
    } finally {
      setSubmitting(false);
    }
  }

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
          className="relative hidden min-h-[394px] w-[292px] shrink-0 overflow-hidden rounded-[13px] bg-[#08121c] bg-cover bg-center sm:block"
          style={{ backgroundImage: "url('/login-visual.png')" }}
        >
          <div className="absolute inset-0 bg-gradient-to-b from-black/5 via-transparent to-black/35" />
          <div className="absolute inset-x-0 bottom-6 px-5 text-white">
            <p className="text-lg font-semibold">让创作，自然发生</p>
            <p className="mt-1 text-xs text-white/70">AI 驱动的电商内容工作台</p>
          </div>
        </div>

        <div className="flex min-h-[394px] min-w-0 flex-1 flex-col justify-center px-5 py-8 sm:px-8">
          <div className="mb-7">
            <p className="text-xs font-medium uppercase tracking-[0.22em] text-brand">Welcome back</p>
            <h1 id="login-title" className="mt-2 text-2xl font-bold tracking-tight text-slate-900">
              登录 JRai
            </h1>
            <p className="mt-2 text-sm text-slate-500">登录后继续使用你的 AI 创作工作台</p>
          </div>

          <form className="space-y-4" onSubmit={handleSubmit}>
            <label className="block">
              <span className="mb-2 block text-xs font-medium text-slate-600">账号</span>
              <span className="relative block">
                <UserRound className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  value={account}
                  onChange={(event) => setAccount(event.target.value)}
                  autoComplete="username"
                  placeholder="请输入账号或邮箱"
                  className="h-11 w-full rounded-xl border border-slate-200 bg-slate-50 pl-10 pr-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-brand focus:bg-white focus:ring-4 focus:ring-brand/10"
                />
              </span>
            </label>

            <label className="block">
              <span className="mb-2 block text-xs font-medium text-slate-600">密码</span>
              <span className="relative block">
                <KeyRound className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="current-password"
                  placeholder="请输入密码"
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

            <div className="flex items-center justify-between pt-1">
              <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-500">
                <input type="checkbox" className="h-3.5 w-3.5 rounded border-slate-300 text-brand focus:ring-brand/20" />
                记住我
              </label>
              <button type="button" className="text-xs font-medium text-brand transition hover:text-brand-dark">
                忘记密码？
              </button>
            </div>

            {error ? (
              <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600">
                {error}
              </p>
            ) : null}

            <button
              type="submit"
              disabled={submitting}
              className="flex h-11 w-full items-center justify-center gap-2 rounded-xl bg-slate-900 text-sm font-semibold text-white shadow-lg shadow-slate-900/15 transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
              {submitting ? '登录中...' : '登录'}
            </button>
          </form>

          <p className="mt-6 text-center text-xs text-slate-400">
            还没有账号？
            <button
              type="button"
              onClick={onSwitchToRegister}
              className="ml-1 font-medium text-brand hover:text-brand-dark"
            >
              立即注册
            </button>
          </p>
        </div>
      </div>
    </div>
  );
}
