'use client';

import { Suspense, type FormEvent, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Eye, EyeOff, LockKeyhole, ShieldCheck, X } from 'lucide-react';
import { consoleApi } from '@/api/console';

const CONSOLE_ROLES = new Set(['ADMIN', 'FINANCE', 'OPERATOR', 'VIEWER']);

export default function AdminLoginPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-[#050712]" />}>
      <AdminLoginPanel />
    </Suspense>
  );
}

function AdminLoginPanel() {
  const router = useRouter();
  const search = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    setLoading(true);
    try {
      const auth = await consoleApi.login({ email, password });
      if (auth.user.channel !== 'CONSOLE' || !CONSOLE_ROLES.has(auth.user.role || '')) {
        setError('该账号没有后台访问权限');
        return;
      }
      router.replace(search.get('from') || '/admin');
    } catch {
      setError('后台认证失败，请确认管理员账号和密码');
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="relative min-h-screen overflow-hidden bg-[#050712] text-white">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_50%_0%,rgba(34,211,238,0.18),transparent_34%),radial-gradient(circle_at_20%_80%,rgba(16,185,129,0.12),transparent_30%)]" />
      <div className="absolute inset-0 bg-[linear-gradient(90deg,rgba(34,211,238,0.08)_1px,transparent_1px),linear-gradient(180deg,rgba(45,212,191,0.055)_1px,transparent_1px)] bg-[size:52px_52px]" />
      <div className="relative flex min-h-screen items-center justify-center px-4">
        <form onSubmit={submit} className="w-full max-w-[430px] rounded-lg border border-cyan-300/25 bg-black/48 p-7 shadow-[0_0_80px_rgba(34,211,238,0.16)] backdrop-blur">
          <div className="mb-7 flex items-center gap-3">
            <div className="grid h-12 w-12 place-items-center rounded-lg border border-cyan-300/45 bg-cyan-300/10 text-cyan-100">
              <ShieldCheck size={22} />
            </div>
            <div>
              <h1 className="text-xl font-semibold">全域智像后台</h1>
              <p className="text-xs text-cyan-100/55">独立管理员登录入口</p>
            </div>
          </div>

          <label className="mb-4 block">
            <span className="mb-2 block text-xs text-cyan-100/60">管理员邮箱</span>
            <div className="relative">
              <input
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                className="h-11 w-full rounded-lg border border-cyan-300/20 bg-white/[0.06] px-3 pr-9 text-sm outline-none transition placeholder:text-cyan-100/25 focus:border-cyan-200"
                placeholder="请输入管理员邮箱"
                type="email"
                autoComplete="username"
              />
              {email && <ClearInputButton onClick={() => setEmail('')} />}
            </div>
          </label>

          <label className="mb-5 block">
            <span className="mb-2 block text-xs text-cyan-100/60">后台登录密码</span>
            <div className="relative">
              <input
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                className="h-11 w-full rounded-lg border border-cyan-300/20 bg-white/[0.06] px-3 pr-12 text-sm outline-none transition placeholder:text-cyan-100/25 focus:border-cyan-200"
                placeholder="请输入后台登录密码"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
              />
              <button
                type="button"
                onClick={() => setShowPassword((value) => !value)}
                className="absolute right-1 top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center text-slate-500 hover:text-slate-900"
                title={showPassword ? '隐藏密码' : '显示密码'}
              >
                {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
              </button>
            </div>
          </label>

          {error && <div className="mb-4 rounded-lg border border-rose-300/30 bg-rose-400/10 px-3 py-2 text-xs text-rose-100">{error}</div>}

          <button
            type="submit"
            disabled={loading}
            className="flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-cyan-300 text-sm font-semibold text-[#041019] transition hover:bg-cyan-200 disabled:opacity-50"
          >
            <LockKeyhole size={16} />
            {loading ? '正在认证' : '进入全域智像后台'}
          </button>
        </form>
      </div>
    </main>
  );
}

function ClearInputButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title="清空"
      className="absolute right-2 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded-md text-cyan-100/45 transition hover:bg-white/[0.08] hover:text-cyan-50"
    >
      <X size={14} />
    </button>
  );
}
