'use client';

import { FormEvent, useState } from 'react';
import { Eye, EyeOff, KeyRound, Loader2, UserRound, X } from 'lucide-react';
import { authApi } from '@/api/auth';
import { ApiError } from '@/lib/http';
import { TOKEN_KEY } from '@/api/config';

interface RegisterDialogProps {
  onClose?: () => void;
  /** 注册成功后回调（一般是关闭弹窗 + 跳转） */
  onSuccess?: () => void;
  /** 切换到登录弹窗 */
  onSwitchToLogin?: () => void;
}

export function RegisterDialog({ onClose, onSuccess, onSwitchToLogin }: RegisterDialogProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');

    if (!email.trim()) {
      setError('请输入邮箱');
      return;
    }
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) {
      setError('邮箱格式不正确');
      return;
    }
    if (password.length < 8) {
      setError('密码至少 8 位');
      return;
    }
    if (!displayName.trim()) {
      setError('请输入昵称');
      return;
    }

    setSubmitting(true);
    try {
      // 后端接口契约：POST /api/auth/register，参数为 { email, password, displayName }
      const result = await authApi.register({
        email: email.trim(),
        password,
        displayName: displayName.trim(),
      });
      // 注册成功：自动登录（存 token + user）
      localStorage.setItem(TOKEN_KEY, result.accessToken);
      localStorage.setItem('refreshToken', result.refreshToken);
      localStorage.setItem('user', JSON.stringify({
        id: result.userId,
        email: result.email,
        role: result.role,
      }));
      onSuccess?.();
      onClose?.();
    } catch (cause) {
      if (cause instanceof ApiError) {
        const payload = cause.payload as { message?: string } | undefined;
        setError(payload?.message || '注册失败，请稍后重试');
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
        aria-labelledby="register-title"
        className="relative flex w-full max-w-[672px] overflow-hidden rounded-[18px] bg-white p-2 shadow-[0_24px_80px_rgba(15,23,42,0.24)]"
      >
        <button
          type="button"
          aria-label="关闭注册窗口"
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
            <p className="text-lg font-semibold">加入聚融</p>
            <p className="mt-1 text-xs text-white/70">开启 AI 电商创作之旅</p>
          </div>
        </div>

        <div className="flex min-h-[440px] min-w-0 flex-1 flex-col justify-center px-5 py-8 sm:px-8">
          <h2 id="register-title" className="text-xl font-semibold text-slate-900">
            创建账号
          </h2>
          <p className="mt-1 text-sm text-slate-500">几秒钟即可完成注册，立即开始创作</p>

          <form className="mt-6 space-y-4" onSubmit={handleSubmit}>
            <label className="block">
              <span className="text-xs font-medium text-slate-600">邮箱</span>
              <div className="mt-1.5 flex items-center rounded-lg border border-slate-200 bg-white px-3 focus-within:border-brand">
                <UserRound className="h-4 w-4 text-slate-400" />
                <input
                  type="email"
                  required
                  autoComplete="email"
                  placeholder="your@email.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="ml-2 h-10 flex-1 bg-transparent text-sm outline-none placeholder:text-slate-400"
                />
              </div>
            </label>

            <label className="block">
              <span className="text-xs font-medium text-slate-600">昵称</span>
              <div className="mt-1.5 flex items-center rounded-lg border border-slate-200 bg-white px-3 focus-within:border-brand">
                <UserRound className="h-4 w-4 text-slate-400" />
                <input
                  type="text"
                  required
                  autoComplete="nickname"
                  placeholder="你的展示名"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  className="ml-2 h-10 flex-1 bg-transparent text-sm outline-none placeholder:text-slate-400"
                />
              </div>
            </label>

            <label className="block">
              <span className="text-xs font-medium text-slate-600">密码（至少 8 位）</span>
              <div className="mt-1.5 flex items-center rounded-lg border border-slate-200 bg-white px-3 focus-within:border-brand">
                <KeyRound className="h-4 w-4 text-slate-400" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  required
                  autoComplete="new-password"
                  placeholder="请输入密码"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="ml-2 h-10 flex-1 bg-transparent text-sm outline-none placeholder:text-slate-400"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((s) => !s)}
                  className="ml-1 text-slate-400 hover:text-slate-600"
                  aria-label={showPassword ? '隐藏密码' : '显示密码'}
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </label>

            {error && (
              <p className="rounded-md bg-rose-50 px-3 py-2 text-xs text-rose-600">
                {error}
              </p>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="mt-2 flex h-11 w-full items-center justify-center rounded-lg bg-brand text-sm font-medium text-white transition hover:bg-brand-dark disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  注册中…
                </>
              ) : (
                '注册并登录'
              )}
            </button>
          </form>

          <p className="mt-6 text-center text-xs text-slate-400">
            已有账号？
            <button
              type="button"
              onClick={onSwitchToLogin}
              className="ml-1 font-medium text-brand hover:text-brand-dark"
            >
              立即登录
            </button>
          </p>
        </div>
      </div>
    </div>
  );
}