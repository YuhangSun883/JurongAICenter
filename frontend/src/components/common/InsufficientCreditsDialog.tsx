'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, Sparkles, X, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { agentApi } from '@/api/agent';
import type { CreatePlanOrderResponse, PlanInfo } from '@/types/agent';
import { PaymentDialog } from './PaymentDialog';
import { ContactDialog } from './ContactDialog';
import { BuyCreditsDialog } from './BuyCreditsDialog';
import { RedeemCardDialog } from './RedeemCardDialog';

/**
 * 积分不足弹窗
 * 触发场景：
 *   1) 用户点击"发送" → 业务层先调 agentApi.checkCredits
 *   2) 返回 status='insufficient' → 打开本弹窗
 *   3) 用户点套餐 → 调 agentApi.createPlanOrder → 打开 PaymentDialog
 *   4) 用户扫码 → 后端改状态 → PaymentDialog 显示成功
 *
 * Props:
 *   - open: 是否显示
 *   - onClose: 关闭
 *   - onPaid: 支付成功后的回调（业务侧刷新积分）
 *   - remaining / required: 顶部提示用
 */

interface Props {
  open: boolean;
  onClose: () => void;
  onPaid?: () => void;
  remaining?: number;
  required?: number;
}

export function InsufficientCreditsDialog({
  open, onClose, onPaid, remaining, required,
}: Props) {
  const [mounted, setMounted] = useState(false);
  const [plans, setPlans] = useState<PlanInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [creatingId, setCreatingId] = useState<string | null>(null);
  // 选中的套餐 id（点卡片 → 卡片变蓝边底色）
  const [selectedId, setSelectedId] = useState<string | null>(null);
  // 支付弹窗 —— 由点套餐触发
  const [payingOrder, setPayingOrder] = useState<CreatePlanOrderResponse | null>(null);
  // 客服弹窗（企业套餐的「联系客服」触发）
  const [contactScope, setContactScope] = useState<'enterprise' | 'general' | null>(null);
  // 购买积分弹窗（顶部链接触发，9 档积分包）
  const [buyCredits, setBuyCredits] = useState(false);
  // 兑换充值卡弹窗
  const [redeemCard, setRedeemCard] = useState(false);
  /** 关键：标记套餐弹窗"逻辑上关闭但组件还在"。
   *  用 ref 而非 state —— 关弹窗不会触发组件重渲染，也不会让父组件卸载我们 */
  const hideModalRef = useRef(false);
  const [hideModal, setHideModal] = useState(false);

  useEffect(() => setMounted(true), []);

  // 父组件重新打开时，重置 hideModal
  useEffect(() => {
    if (open) setHideModal(false);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    setSelectedId(null); // 重置选中
    agentApi.listPlans()
      .then((arr) => {
        setPlans(arr);
        // 默认选中：后端 highlighted 的套餐，否则第一张
        const def = arr.find((p) => p.highlighted) ?? arr[0];
        if (def) setSelectedId(def.id);
      })
      .catch(() => setPlans([]))
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

  if (!mounted) return null;

  async function pickPlan(planId: string) {
  // 企业套餐（cta=contact）→ 弹客服弹窗
  const plan = plans.find((p) => p.id === planId);
  if (plan?.cta === 'contact') {
    setSelectedId(planId);
    setContactScope('enterprise');
    return;
  }
  // 第一次点：选中（卡片变蓝边）
  if (selectedId !== planId) {
    setSelectedId(planId);
    return;
  }
  // 第二次点同一个：直接调接口弹支付（套餐弹窗不消失）
  setCreatingId(planId);
  try {
    const order = await agentApi.createPlanOrder({ planId });
    setPayingOrder(order);
  } catch (e) {
    console.error('create order failed', e);
  } finally {
    setCreatingId(null);
  }
}

// （取消买积分直通的逻辑 —— 现在改回弹 BuyCreditsDialog 让用户选档位）

  return createPortal(
    <>
      {/* 套餐主弹窗 —— 用 open && !hideModal 条件渲染（卸载不会丢失内部状态） */}
      {open && !hideModal && (
      <div
        className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
        onClick={onClose}
        role="dialog"
        aria-modal="true"
      >
        <div
          onClick={(e) => e.stopPropagation()}
          className="relative w-full max-w-[1080px] rounded-2xl bg-white p-8 shadow-2xl"
        >
          <button
            onClick={onClose}
            aria-label="关闭"
            className="absolute right-4 top-4 grid h-8 w-8 place-items-center rounded-lg text-fg-muted hover:bg-bg-soft hover:text-fg"
          >
            <X className="h-4 w-4" />
          </button>

          <div className="text-center">
            <h2 className="text-xl font-semibold text-fg">您的积分不足，请选择合适的套餐充值</h2>
            <p className="mt-2 text-sm text-fg-muted">
              选择会员订阅套餐，或直接
              <button
                onClick={() => setBuyCredits(true)}
                className="mx-1 text-brand hover:underline"
              >
                「购买积分」
              </button>
              <span className="text-fg-subtle">|</span>
              <button
                onClick={() => setRedeemCard(true)}
                className="mx-1 text-brand hover:underline"
              >
                「兑换充值卡」
              </button>
            </p>
            {(remaining !== undefined || required !== undefined) && (
              <p className="mt-1 text-xs text-fg-subtle">
                当前剩余 <b className="text-fg">{remaining ?? '-'}</b> 积分
                {required !== undefined && <>，本次需要 <b className="text-rose-500">{required}</b> 积分</>}
              </p>
            )}
          </div>

          <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {loading && (
              <div className="col-span-full grid place-items-center py-10 text-fg-subtle">
                <Loader2 className="h-5 w-5 animate-spin" />
              </div>
            )}
            {!loading && plans.length === 0 && (
              <div className="col-span-full grid place-items-center py-10 text-sm text-fg-subtle">
                套餐加载失败，请稍后再试
              </div>
            )}
            {plans.map((p) => (
              <PlanCard
                key={p.id}
                plan={p}
                selected={selectedId === p.id}
                creating={creatingId === p.id}
                onClick={() => pickPlan(p.id)}
              />
            ))}
          </div>
        </div>
      </div>
      )}

      {/* 支付弹窗 —— 由点套餐触发 */}
      <PaymentDialog
        open={!!payingOrder}
        onClose={() => setPayingOrder(null)}
        onSuccess={() => onPaid?.()}
        order={payingOrder ? {
          orderId: payingOrder.orderId,
          qrCodeUrl: payingOrder.qrCodeUrl,
          qrCodeContent: payingOrder.qrCodeContent,
          amount: payingOrder.amount,
          expireAt: payingOrder.expireAt,
          payMethod: payingOrder.payMethod,
        } : null}
      />

      {/* 客服弹窗 —— 由企业套餐的「联系客服」触发 */}
      <ContactDialog
        open={contactScope !== null}
        onClose={() => setContactScope(null)}
        scope={contactScope ?? 'general'}
      />

      {/* 购买积分弹窗 —— 由「购买积分」链接触发 */}
      <BuyCreditsDialog
        open={buyCredits}
        onClose={() => setBuyCredits(false)}
        onPaid={() => { onPaid?.(); setBuyCredits(false); }}
      />

      {/* 兑换充值卡弹窗 —— 由「兑换充值卡」链接触发 */}
      <RedeemCardDialog
        open={redeemCard}
        onClose={() => setRedeemCard(false)}
        onPaid={() => onPaid?.()}
      />
    </>,
    document.body
  );
}

function PlanCard({
  plan, onClick, selected, creating,
}: {
  plan: PlanInfo;
  onClick: () => void;
  selected: boolean;
  creating: boolean;
}) {
  const isContact = plan.cta === 'contact';
  // 默认：白底黑边；被选中（selected）：淡蓝底 + 青色粗边框
  return (
    <div
      className={cn(
        'flex flex-col rounded-2xl border-2 p-5 transition',
        selected
          ? 'border-cyan-400 bg-gradient-to-b from-cyan-50 to-white shadow-glow'
          : 'border-bg-line bg-white'
      )}
    >
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5 text-sm font-semibold text-fg">
          <Sparkles className="h-3.5 w-3.5 text-brand" />
          {plan.title}
        </div>
        {plan.badge && (
          <span className="rounded border border-cyan-200 bg-cyan-50 px-1.5 py-0.5 text-[10px] text-cyan-600">
            {plan.badge}
          </span>
        )}
      </div>

      {isContact ? (
        <div className="mt-3 text-2xl font-bold text-fg">{plan.description || '联系客服'}</div>
      ) : (
        <div className="mt-3 flex items-baseline gap-2">
          <span className="text-2xl font-bold text-fg">¥{plan.price}</span>
          {plan.originalPrice && (
            <span className="text-xs text-fg-subtle line-through">¥{plan.originalPrice}</span>
          )}
        </div>
      )}

      {plan.description && !isContact && (
        <p className="mt-1 text-xs text-fg-muted">{plan.description}</p>
      )}

      {!isContact && plan.credits > 0 && (
        <div className="mt-3 flex items-center gap-2 text-sm">
          <span className="inline-flex items-center gap-1 text-fg">
            <Sparkles className="h-3.5 w-3.5 text-brand" />
            <b>{plan.credits}</b> 积分
          </span>
          <span className="text-fg-subtle">|</span>
          <span className="inline-flex items-center gap-1 rounded bg-cyan-50 px-1.5 py-0.5 text-xs text-cyan-600">
            <span className="h-2 w-2 rounded-full bg-cyan-400" />
            {plan.validDays} 天有效
          </span>
        </div>
      )}

      <ul className="mt-4 flex-1 space-y-2 border-t border-bg-line/60 pt-4 text-xs text-fg-muted">
        {plan.features.map((f, i) => (
          <li key={i} className="flex items-start gap-1.5">
            <Check className="mt-0.5 h-3 w-3 flex-none text-emerald-500" />
            <span>{f}</span>
          </li>
        ))}
      </ul>

      <button
        onClick={onClick}
        disabled={creating}
        className={cn(
          'mt-5 inline-flex h-10 items-center justify-center gap-2 rounded-xl text-sm font-medium transition disabled:opacity-60',
          // 选中：青色 CTA「立即订阅」；未选中：黑色「选择套餐」
          // 企业套餐 cta=contact → 不变（始终显示「联系客服」）
          plan.cta === 'contact'
            ? 'border border-bg-line bg-white text-fg hover:border-brand/50'
            : selected
              ? 'bg-cyan-500 text-white hover:bg-cyan-600'
              : 'bg-fg text-white hover:brightness-110',
        )}
      >
        {creating && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
        {plan.cta === 'contact'
          ? '联系客服'
          : selected
            ? '立即订阅'
            : '选择套餐'}
      </button>
    </div>
  );
}
