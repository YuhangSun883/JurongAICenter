'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, CreditCard, Loader2, CheckCircle2 } from 'lucide-react';
import { agentApi } from '@/api/agent';
import { cn } from '@/lib/utils';

/**
 * 兑换充值卡弹窗
 * 流程：
 *   1) 用户输入卡密 → 点"确认兑换"
 *   2) 调 agentApi.redeemCard → 成功后端入账积分
 *   3) 弹窗显示成功态 + 入账积分数
 *   4) 点"完成"关闭，业务侧 onPaid 刷新积分
 *
 * Props:
 *   - open: 是否显示
 *   - onClose: 关闭
 *   - onPaid: 兑换成功后的回调（业务侧刷新积分）
 */

interface Props {
  open: boolean;
  onClose: () => void;
  onPaid?: (creditsAdded: number) => void;
}

export function RedeemCardDialog({ open, onClose, onPaid }: Props) {
  const [mounted, setMounted] = useState(false);
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<{ creditsAdded: number; validDays: number } | null>(null);

  useEffect(() => setMounted(true), []);

  // 重新打开时重置
  useEffect(() => {
    if (open) {
      setCode('');
      setError(null);
      setSuccess(null);
      setSubmitting(false);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [open, onClose]);

  if (!open || !mounted) return null;

  async function submit() {
    if (!code.trim() || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      // 自动转大写（卡密一般是大写）
      const normalized = code.trim().toUpperCase();
      const r = await agentApi.redeemCard({ code: normalized });
      setSuccess({ creditsAdded: r.creditsAdded, validDays: r.validDays });
      onPaid?.(r.creditsAdded);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '兑换失败，请稍后再试';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return createPortal(
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-[480px] rounded-2xl bg-white p-6 shadow-2xl"
      >
        <button
          onClick={onClose}
          aria-label="关闭"
          className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-lg text-fg-muted hover:bg-bg-soft hover:text-fg"
        >
          <X className="h-4 w-4" />
        </button>

        {/* 标题 */}
        <div className="flex items-center gap-2">
          <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand-50 text-brand">
            <CreditCard className="h-4 w-4" />
          </span>
          <h2 className="text-base font-semibold text-fg">兑换充值卡</h2>
        </div>

        {/* 成功态 */}
        {success ? (
          <div className="mt-5 flex flex-col items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50/50 px-4 py-6 text-center">
            <CheckCircle2 className="h-10 w-10 text-emerald-500" />
            <p className="text-sm font-medium text-fg">兑换成功！</p>
            <p className="text-xs text-fg-muted">
              入账 <b className="text-fg">{success.creditsAdded}</b> 积分
              {success.validDays > 0 && <> · 有效期 {success.validDays} 天</>}
            </p>
          </div>
        ) : (
          <>
            <label className="mt-5 block text-sm font-medium text-fg">
              充值卡卡密
            </label>
            <input
              type="text"
              value={code}
              onChange={(e) => {
                setCode(e.target.value);
                if (error) setError(null);
              }}
              onKeyDown={(e) => e.key === 'Enter' && submit()}
              placeholder="XX-XXXX-XXXX-XXXX"
              maxLength={20}
              autoFocus
              className={cn(
                'mt-2 block h-11 w-full rounded-xl border-2 bg-white px-3 text-sm font-mono uppercase tracking-wide outline-none transition',
                'placeholder:text-fg-subtle',
                error
                  ? 'border-rose-400 focus:border-rose-500'
                  : 'border-bg-line focus:border-brand'
              )}
            />
            {error && (
              <p className="mt-2 text-xs text-rose-500">{error}</p>
            )}
            <p className="mt-2 text-xs text-fg-subtle">
              卡密示例：JR-ABCD-EFGH-JKLM（区分大小写会自动转大写）
            </p>
          </>
        )}

        {/* 底部按钮 */}
        <div className="mt-6 flex items-center justify-end gap-2">
          <button
            onClick={onClose}
            className="h-9 rounded-xl border border-bg-line bg-white px-4 text-sm text-fg-muted hover:border-brand/50 hover:text-brand"
          >
            {success ? '完成' : '关闭'}
          </button>
          {!success && (
            <button
              onClick={submit}
              disabled={!code.trim() || submitting}
              className="inline-flex h-9 items-center gap-2 rounded-xl bg-fg px-4 text-sm font-medium text-white hover:brightness-110 disabled:opacity-50"
            >
              {submitting && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
              确认兑换
            </button>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}