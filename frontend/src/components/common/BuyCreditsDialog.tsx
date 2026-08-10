'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { agentApi } from '@/api/agent';
import type { CreateCreditsOrderResponse, CreditPackage } from '@/types/agent';
import { PaymentDialog } from './PaymentDialog';

/**
 * 购买积分弹窗（9 档积分包）
 * 流程：
 *   1) 用户点"购买积分"链接 → 打开本弹窗
 *   2) 用户选一档 → 卡片变蓝边
 *   3) 用户点"立即充值" → 本弹窗关闭 + 自动弹 PaymentDialog（二维码）
 *
 * Props:
 *   - open: 是否显示
 *   - onClose: 关闭
 *   - onPaid: 支付成功回调（业务侧刷新积分）
 */

interface Props {
  open: boolean;
  onClose: () => void;
  onPaid?: () => void;
}

const FALLBACK_CREDIT_PACKAGES: CreditPackage[] = [
  { id: 'pkg-50', price: 50, credits: 50 },
  { id: 'pkg-75', price: 75, credits: 75, highlighted: true },
  { id: 'pkg-150', price: 150, credits: 150 },
  { id: 'pkg-225', price: 225, credits: 225 },
  { id: 'pkg-450', price: 450, credits: 450 },
  { id: 'pkg-882', price: 882, credits: 900 },
  { id: 'pkg-1960', price: 1960, credits: 2000 },
  { id: 'pkg-4900', price: 4900, credits: 5000 },
  { id: 'pkg-9800', price: 9800, credits: 10000 },
];

export function BuyCreditsDialog({ open, onClose, onPaid }: Props) {
  const [mounted, setMounted] = useState(false);
  const [pkgs, setPkgs] = useState<CreditPackage[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  // 创建好的订单 → 触发支付弹窗
  const [order, setOrder] = useState<CreateCreditsOrderResponse | null>(null);
  /** 套餐弹窗"逻辑关闭但组件还在"——保留 order state 等支付弹窗渲染 */
  const [hideBuyModal, setHideBuyModal] = useState(false);

  useEffect(() => setMounted(true), []);

  // 父组件重新打开时重置
  useEffect(() => {
    if (open) {
      setHideBuyModal(false);
      setOrder(null);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    setSelectedId(null);
    setOrder(null);
    agentApi.listCreditPackages()
      .then((arr) => {
        const nextPackages = arr.length > 0 ? arr : FALLBACK_CREDIT_PACKAGES;
        setPkgs(nextPackages);
        // 默认选中第一个 highlighted 的包
        const h = nextPackages.find((p) => p.highlighted) ?? nextPackages[0];
        if (h) setSelectedId(h.id);
      })
      .catch(() => {
        setPkgs(FALLBACK_CREDIT_PACKAGES);
        setSelectedId(FALLBACK_CREDIT_PACKAGES[0].id);
      })
      .finally(() => setLoading(false));
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

  /** 点击"立即充值" → 隐藏本弹窗 + 调接口弹支付（不卸载组件） */
  async function buy() {
    if (!selectedId || creating) return;
    setCreating(true);
    setHideBuyModal(true); // ← 仅隐藏，不卸载
    try {
      const o = await agentApi.createCreditsOrder({ packageId: selectedId });
      setOrder(o);
    } catch (e) {
      console.error('create credits order failed', e);
      setHideBuyModal(false); // 出错时恢复
    } finally {
      setCreating(false);
    }
  }

  return createPortal(
    <>
      {/* 套餐主弹窗 —— 用 open && !hideBuyModal 条件渲染（卸载不会丢失内部状态） */}
      {open && !hideBuyModal && (
      <div
        className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
        onClick={onClose}
        role="dialog"
        aria-modal="true"
      >
        <div
          onClick={(e) => e.stopPropagation()}
          className="relative w-full max-w-[640px] rounded-2xl bg-white p-8 shadow-2xl"
        >
          <button
            onClick={onClose}
            aria-label="关闭"
            className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-lg text-fg-muted hover:bg-bg-soft hover:text-fg"
          >
            <X className="h-4 w-4" />
          </button>

          <div className="text-center">
            <h2 className="text-xl font-semibold text-fg">购买积分</h2>
            <p className="mt-2 text-sm text-fg-muted">
              请选择充值套餐，或直接
              <button className="ml-1 text-brand hover:underline">「兑换充值卡」</button>
            </p>
          </div>

          {loading && (
            <div className="grid place-items-center py-12 text-fg-subtle">
              <Loader2 className="h-5 w-5 animate-spin" />
            </div>
          )}

          {!loading && (
            <div className="mt-6 grid grid-cols-3 gap-3">
              {pkgs.map((p) => {
                const selected = selectedId === p.id;
                return (
                  <button
                    key={p.id}
                    onClick={() => setSelectedId(p.id)}
                    className={cn(
                      'rounded-2xl border-2 p-4 text-left transition',
                      selected
                        ? 'border-cyan-400 bg-cyan-50/50 shadow-glow'
                        : 'border-bg-line bg-white hover:border-cyan-200'
                    )}
                  >
                    <div className="text-lg font-bold text-fg">
                      ¥{p.price.toLocaleString()}
                    </div>
                    <div className="mt-1 text-xs text-fg-muted">
                      {p.credits.toLocaleString()} 积分
                    </div>
                  </button>
                );
              })}
            </div>
          )}

          <button
            onClick={buy}
            disabled={!selectedId || creating}
            className="mt-5 inline-flex h-11 w-full items-center justify-center gap-2 rounded-xl bg-cyan-500 text-base font-medium text-white transition hover:bg-cyan-600 disabled:opacity-60"
          >
            {creating && <Loader2 className="h-4 w-4 animate-spin" />}
            立即充值
          </button>

          <p className="mt-4 text-center text-[11px] text-fg-subtle">
            温馨提示：积分不支持转赠或提现，
            <span className="text-cyan-600">积分有效期为 2 年</span>
            ，不支持退款。
          </p>
        </div>
      </div>
      )}

      {/* 支付弹窗：本弹窗关闭后自动弹 */}
      <PaymentDialog
        open={!!order}
        onClose={() => setOrder(null)}
        onSuccess={() => onPaid?.()}
        order={order ? {
          orderId: order.orderId,
          qrCodeUrl: order.qrCodeUrl,
          qrCodeContent: order.qrCodeContent,
          amount: order.amount,
          expireAt: order.expireAt,
          payMethod: order.payMethod,
        } : null}
      />
    </>,
    document.body
  );
}
