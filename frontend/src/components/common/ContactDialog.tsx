'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Loader2, Smartphone, CreditCard, Phone, Mail } from 'lucide-react';
import type { ContactChannel, ContactInfoResponse, PayMethod } from '@/types/agent';
import { agentApi } from '@/api/agent';

/**
 * 通用客服/企业咨询弹窗
 * 触发场景：
 *   1) 企业套餐的「联系客服」按钮
 *   2) 任何场景想调 agentApi.getContactInfo() 拿联系方式
 *
 * Props:
 *   - open: 是否显示
 *   - onClose: 关闭
 *   - scope: 'enterprise' | 'general'，传给接口的 scope 参数
 */

interface Props {
  open: boolean;
  onClose: () => void;
  scope?: 'enterprise' | 'general';
}

const CHANNEL_ICON: Record<PayMethod | 'phone' | 'email', typeof Smartphone> = {
  wechat: Smartphone,
  alipay: Smartphone,
  card: CreditCard,
  phone: Phone,
  email: Mail,
};

const CHANNEL_NAME: Record<PayMethod | 'phone' | 'email', string> = {
  wechat: '微信',
  alipay: '支付宝',
  card: '充值卡',
  phone: '电话',
  email: '邮箱',
};

export function ContactDialog({ open, onClose, scope }: Props) {
  const [mounted, setMounted] = useState(false);
  const [info, setInfo] = useState<ContactInfoResponse | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    agentApi.getContactInfo(scope)
      .then(setInfo)
      .catch(() => setInfo(null))
      .finally(() => setLoading(false));
  }, [open, scope]);

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

  return createPortal(
    <div
      className="fixed inset-0 z-[1200] flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-[560px] rounded-2xl bg-white p-8 shadow-2xl"
      >
        <button
          onClick={onClose}
          aria-label="关闭"
          className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-lg text-fg-muted hover:bg-bg-soft hover:text-fg"
        >
          <X className="h-4 w-4" />
        </button>

        {loading && (
          <div className="grid place-items-center py-12 text-fg-subtle">
            <Loader2 className="h-5 w-5 animate-spin" />
          </div>
        )}

        {!loading && !info && (
          <div className="grid place-items-center py-12 text-sm text-fg-subtle">
            客服信息加载失败，请稍后再试
          </div>
        )}

        {!loading && info && (
          <>
            <div className="text-center">
              <h2 className="text-xl font-semibold text-fg">{info.title}</h2>
              {info.description && (
                <p className="mt-2 text-sm text-fg-muted">{info.description}</p>
              )}
            </div>

            <div className="mt-6 flex flex-wrap items-center justify-center gap-4">
              {info.channels.map((c, i) => (
                <ChannelCard key={i} channel={c} />
              ))}
            </div>

            {info.footerHint && (
              <p className="mt-5 text-center text-xs text-fg-subtle">{info.footerHint}</p>
            )}
          </>
        )}
      </div>
    </div>,
    document.body
  );
}

function ChannelCard({ channel }: { channel: ContactChannel }) {
  const Icon = CHANNEL_ICON[channel.method];
  const name = CHANNEL_NAME[channel.method];

  return (
    <div className="rounded-2xl border border-bg-line bg-bg-soft/30 p-4 text-center">
      {/* 二维码 / 文本联系方式 */}
      {channel.qrCodeUrl && (
        <div className="mx-auto h-44 w-44 overflow-hidden rounded-xl border border-bg-line bg-white">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={channel.qrCodeUrl} alt={channel.description || name} className="h-full w-full" />
        </div>
      )}
      {!channel.qrCodeUrl && channel.value && (
        <div className="mx-auto flex h-44 w-44 items-center justify-center rounded-xl border border-bg-line bg-white">
          <div className="text-center">
            <Icon className="mx-auto h-8 w-8 text-fg-muted" />
            <div className="mt-2 text-sm font-medium text-fg">{channel.value}</div>
          </div>
        </div>
      )}

      {/* 渠道名 + 描述 */}
      <div className="mt-3 flex items-center justify-center gap-1.5 text-sm font-medium text-fg">
        <Icon className="h-3.5 w-3.5 text-brand" />
        {name}
      </div>
      {channel.description && (
        <p className="mt-1 text-xs text-fg-muted">{channel.description}</p>
      )}
    </div>
  );
}