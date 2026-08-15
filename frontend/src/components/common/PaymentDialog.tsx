'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Check, Loader2, Smartphone, CreditCard } from 'lucide-react';
import { agentApi } from '@/api/agent';
import { cn } from '@/lib/utils';
import type { PayMethod, OrderStatus } from '@/types/agent';

/**
 * 通用支付弹窗
 * 流程：
 *   1) 后端创建订单（agentApi.createPlanOrder）→ 拿到 qrCodeUrl / expireAt
 *   2) 弹窗显示二维码 + 倒计时
 *   3) 前端轮询 agentApi.queryOrder → status=paid 时显示成功
 *   4) 倒计时归零 / 状态 expired → 提示用户重新下单
 */

interface PaymentDialogProps {
  open: boolean;
  onClose: () => void;
  onSuccess?: (orderId: string) => void;
  /** 调用方传过来的订单信息（一般是 createPlanOrder 的返回值） */
  order: {
    orderId: string;
    qrCodeUrl?: string;
    qrCodeContent?: string;
    amount: number;
    expireAt: number;
    payMethod: PayMethod;
  } | null;
  /** 轮询间隔（ms） */
  pollInterval?: number;
}

const METHOD_LABEL: Record<PayMethod, { name: string; sub: string; icon: 'smartphone' | 'card' }> = {
  alipay: { name: '支付宝', sub: '扫码支付', icon: 'smartphone' },
  wechat: { name: '微信支付', sub: '扫码支付', icon: 'smartphone' },
  card: { name: '兑换充值卡', sub: '输入卡密', icon: 'card' },
};

export function PaymentDialog({
  open, onClose, onSuccess, order, pollInterval = 2000,
}: PaymentDialogProps) {
  const [mounted, setMounted] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [status, setStatus] = useState<OrderStatus>('pending');
  const [paying, setPaying] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => setMounted(true), []);

  // 倒计时
  useEffect(() => {
    if (!open) return;
    setStatus('pending');
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [open]);

  // 状态轮询
  useEffect(() => {
    if (!open || !order) return;
    const tick = async () => {
      try {
        const r = await agentApi.queryOrder(order.orderId);
        if (r.status !== 'pending') {
          setStatus(r.status);
          if (r.status === 'paid') onSuccess?.(r.orderId);
        }
      } catch { /* 忽略，下次重试 */ }
    };
    tick();
    const t = setInterval(tick, pollInterval);
    timerRef.current = t;
    return () => { if (timerRef.current) clearInterval(timerRef.current); };
  }, [open, order, pollInterval, onSuccess]);

  // ESC 关闭
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

  if (!open || !mounted || !order) return null;

  const remainMs = Math.max(0, order.expireAt - now);
  const m = Math.floor(remainMs / 60000);
  const s = Math.floor((remainMs % 60000) / 1000);
  const expired = remainMs <= 0;
  const finalStatus: OrderStatus = expired ? 'expired' : status;
  const method = METHOD_LABEL[order.payMethod];
  const Icon = method.icon === 'smartphone' ? Smartphone : CreditCard;

  // 模拟"我已支付"按钮（仅 mock 阶段方便演示，真后端去掉）
  async function mockPay() {
    setPaying(true);
    try {
      // 真实场景：让用户扫码后由后端回调改状态
      // mock 阶段：轮询 1-2 次后随机变 paid
      await new Promise((r) => setTimeout(r, 800));
    } finally {
      setPaying(false);
    }
  }

  return createPortal(
    <div
      className="fixed inset-0 z-[1100] flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-[420px] rounded-2xl bg-white p-6 shadow-2xl"
      >
        <button
          onClick={onClose}
          aria-label="关闭"
          className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-lg text-fg-muted hover:bg-bg-soft hover:text-fg"
        >
          <X className="h-4 w-4" />
        </button>

        <div className="text-center">
          <div className="inline-flex items-center gap-1.5 text-sm text-fg-muted">
            <Icon className="h-4 w-4" />
            {method.name} {method.sub}
          </div>

          {/* 二维码区 */}
          {finalStatus === 'pending' && order.qrCodeUrl && (
            <div className="mx-auto mt-3 h-52 w-52 overflow-hidden rounded-xl border border-bg-line bg-bg-soft">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={order.qrCodeUrl} alt="支付二维码" className="h-full w-full" />
            </div>
          )}

          {finalStatus === 'pending' && (
            <p className="mt-3 text-sm text-fg-muted">
              请在 <b className="text-fg">{m}</b> 分 <b className="text-fg">{String(s).padStart(2, '0')}</b> 秒内完成支付
            </p>
          )}

          {finalStatus === 'expired' && (
            <p className="mt-4 text-sm text-rose-500">
              支付已超时，请关闭后重新下单
            </p>
          )}

          {finalStatus === 'paid' && (
            <div className="mt-4 flex flex-col items-center gap-2">
              <div className="grid h-12 w-12 place-items-center rounded-full bg-emerald-500 text-white">
                <Check className="h-6 w-6" />
              </div>
              <p className="text-sm font-medium text-fg">支付成功！</p>
              <p className="text-xs text-fg-muted">积分已入账，请稍候…</p>
            </div>
          )}

          {finalStatus === 'cancelled' && (
            <p className="mt-4 text-sm text-fg-muted">订单已取消</p>
          )}

          {finalStatus === 'pending' && (
            <p className="mt-1 text-xs text-fg-subtle">支付结果未确认前请勿重复付款</p>
          )}
        </div>

        {/* 底部按钮 */}
        <div className="mt-5 flex items-center justify-center gap-2">
          {finalStatus === 'pending' && (
            <button
              onClick={mockPay}
              disabled={paying}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-xl border border-bg-line bg-bg-soft px-3 py-1.5 text-xs text-fg-muted transition hover:border-brand/50 hover:text-brand disabled:opacity-50'
              )}
              title="仅 mock 演示用：真后端由扫码成功后回调"
            >
              {paying && <Loader2 className="h-3 w-3 animate-spin" />}
              模拟支付成功
            </button>
          )}
          <button
            onClick={onClose}
            className="rounded-xl bg-fg px-4 py-1.5 text-sm font-medium text-white hover:brightness-110"
          >
            {finalStatus === 'paid' ? '完成' : '关闭'}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
