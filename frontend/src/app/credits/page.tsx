'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { FileText, RefreshCw, Ticket, WalletCards } from 'lucide-react';
import { Sidebar } from '@/components/home/Sidebar';
import { BuyCreditsDialog } from '@/components/common/BuyCreditsDialog';
import { RedeemCardDialog } from '@/components/common/RedeemCardDialog';
import { agentApi } from '@/api/agent';
import { cn } from '@/lib/utils';
import type { AgentCreditInfo, CreditLedgerItem } from '@/types/agent';

const TYPE_FILTERS = [
  { key: '', label: '全部' },
  { key: 'CONSUME', label: '消耗' },
  { key: 'REFUND', label: '退款' },
  { key: 'RECHARGE', label: '积分充值' },
  { key: 'EXPIRE', label: '过期' },
  { key: 'GRANT', label: '赠送' },
];

const TOOL_FILTERS = [
  { key: '', label: '全部工具' },
  { key: 'video', label: '视频生成' },
  { key: 'image', label: '图片生成' },
  { key: 'subtitle', label: '视频拆条' },
  { key: 'enhance', label: '画质增强' },
  { key: 'prompt', label: '提示词生成' },
  { key: 'agent', label: 'Agent对话' },
  { key: 'recharge', label: '充值/兑换' },
];

const TYPE_LABEL: Record<string, string> = {
  CONSUME: '消耗',
  RECHARGE: '积分充值',
  REFUND: '退款',
  GRANT: '赠送',
  EXPIRE: '过期',
};

export default function CreditsPage() {
  const [credits, setCredits] = useState<AgentCreditInfo | null>(null);
  const [items, setItems] = useState<CreditLedgerItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [type, setType] = useState('');
  const [tool, setTool] = useState('');
  const [buyOpen, setBuyOpen] = useState(false);
  const [redeemOpen, setRedeemOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [creditInfo, ledger] = await Promise.all([
        agentApi.getCredits(),
        agentApi.listCreditLedger({ type, tool, page: 1, pageSize: 50 }),
      ]);
      setCredits(creditInfo);
      setItems(ledger.items);
    } catch (err) {
      console.warn('[credits] load failed:', err);
      setCredits(null);
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [type, tool]);

  useEffect(() => {
    load();
  }, [load]);

  const summary = useMemo(() => {
    const available = Math.max(0, credits?.remaining ?? credits?.used ?? credits?.total ?? 0);
    const subscription = Math.max(0, Math.min(available, credits?.monthlyQuota ?? 0));
    const recharge = Math.max(0, available - subscription);
    return { available, subscription, recharge };
  }, [credits]);

  return (
    <div className="min-h-screen bg-[#f7f7f8] pl-[72px]">
      <Sidebar />
      <main className="mx-auto w-full max-w-[1480px] px-6 pb-16 pt-8">
        <div className="mb-7 flex items-center justify-between gap-4">
          <h1 className="text-2xl font-semibold text-[#080b14]">我的积分</h1>
          <button
            type="button"
            onClick={load}
            className="inline-flex h-9 items-center gap-2 rounded-lg border border-[#dde1e7] bg-white px-3 text-sm text-[#3f4654] transition hover:border-[#c7cdd7] hover:bg-[#f9fafb]"
          >
            <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} />
            刷新
          </button>
        </div>

        <section className="mb-8 rounded-lg border border-[#dfe3ea] bg-white px-6 py-6">
          <div className="flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <div className="text-sm text-[#4f5969]">可用积分</div>
              <div className="mt-2 flex flex-wrap items-end gap-x-3 gap-y-1">
                <span className="text-4xl font-semibold leading-none text-[#050811]">{formatCredits(summary.available)}</span>
                <span className="pb-1 text-sm text-[#8a93a3]">
                  = 订阅积分 {formatCredits(summary.subscription)} + 充值积分 {formatCredits(summary.recharge)}
                </span>
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-6">
              <MiniMetric label="订阅积分" value={summary.subscription} />
              <MiniMetric label="充值积分" value={summary.recharge} />
              <button
                type="button"
                onClick={() => setRedeemOpen(true)}
                className="inline-flex h-9 items-center gap-2 rounded-lg border border-[#e6e8ee] bg-white px-4 text-sm font-semibold text-[#111827] transition hover:border-[#c9ced8] hover:bg-[#f8fafc]"
              >
                <Ticket className="h-4 w-4" />
                兑换充值卡
              </button>
              <button
                type="button"
                onClick={() => setBuyOpen(true)}
                className="inline-flex h-9 items-center gap-2 rounded-lg bg-[#25272d] px-4 text-sm font-semibold text-white transition hover:bg-[#111318]"
              >
                <WalletCards className="h-4 w-4" />
                订阅 / 充值
              </button>
            </div>
          </div>
        </section>

        <section>
          <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
            <h2 className="text-base font-semibold text-[#050811]">积分明细</h2>
            <button
              type="button"
              className="inline-flex h-9 items-center gap-2 rounded-lg bg-[#25272d] px-4 text-sm font-semibold text-white transition hover:bg-[#111318]"
            >
              <FileText className="h-4 w-4" />
              发票管理
            </button>
          </div>

          <FilterRow label="类型">
            {TYPE_FILTERS.map((item) => (
              <FilterChip key={item.key || 'all'} active={type === item.key} onClick={() => setType(item.key)}>
                {item.label}
              </FilterChip>
            ))}
          </FilterRow>

          <FilterRow label="工具">
            {TOOL_FILTERS.map((item) => (
              <FilterChip key={item.key || 'all-tools'} active={tool === item.key} onClick={() => setTool(item.key)}>
                {item.label}
              </FilterChip>
            ))}
          </FilterRow>

          <div className="mt-10 overflow-hidden rounded-lg border border-dashed border-[#e2e6ee] bg-white/50">
            {loading ? (
              <div className="flex h-[86px] items-center justify-center text-sm text-[#667085]">正在加载明细...</div>
            ) : items.length === 0 ? (
              <div className="flex h-[86px] items-center justify-center text-sm text-[#667085]">没有匹配的明细</div>
            ) : (
              <div className="divide-y divide-[#edf0f5] bg-white">
                {items.map((item) => (
                  <div key={item.id} className="grid gap-3 px-5 py-4 text-sm md:grid-cols-[1.2fr_1fr_120px_120px] md:items-center">
                    <div className="min-w-0">
                      <div className="font-medium text-[#111827]">{item.description || formatType(item.type)}</div>
                      <div className="mt-1 text-xs text-[#8a93a3]">{formatDate(item.createdAt)}</div>
                    </div>
                    <div className="text-[#667085]">{formatTool(item.tool)}</div>
                    <div className={cn('font-semibold', item.creditsDelta >= 0 ? 'text-[#0f9f6e]' : 'text-[#dc2626]')}>
                      {item.creditsDelta >= 0 ? '+' : ''}{formatCredits(item.creditsDelta)}
                    </div>
                    <div className="text-[#667085]">余额 {formatCredits(item.balanceAfter)}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>
      </main>

      <RedeemCardDialog open={redeemOpen} onClose={() => setRedeemOpen(false)} onPaid={load} />
      <BuyCreditsDialog open={buyOpen} onClose={() => setBuyOpen(false)} onPaid={load} />
    </div>
  );
}

function MiniMetric({ label, value }: { label: string; value: number }) {
  return (
    <div className="min-w-[70px]">
      <div className="text-xs text-[#4f5969]">{label}</div>
      <div className="mt-1 text-lg font-semibold leading-none text-[#050811]">{formatCredits(value)}</div>
      <div className="mt-2 text-xs text-[#9aa3af]">-</div>
    </div>
  );
}

function FilterRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
      <span className="w-8 shrink-0 text-[#4f5969]">{label}</span>
      <div className="flex flex-wrap items-center gap-3">{children}</div>
    </div>
  );
}

function FilterChip({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'h-8 rounded-full px-3 text-xs font-medium text-[#303846] transition hover:bg-[#ebeef3]',
        active ? 'bg-[#dfe1e5] text-[#050811]' : 'bg-transparent'
      )}
    >
      {children}
    </button>
  );
}

function formatCredits(value?: number) {
  return Number(value ?? 0).toFixed(2);
}

function formatType(type?: string) {
  return TYPE_LABEL[type || ''] || type || '积分变动';
}

function formatTool(tool?: string) {
  return TOOL_FILTERS.find((item) => item.key === tool)?.label || '其他';
}

function formatDate(value?: string) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
