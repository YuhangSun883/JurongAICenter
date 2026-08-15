import { Activity, Coins, Database, Image, Users } from 'lucide-react';
import type { ConsoleOverview } from '@/api/console';
import type { ReactNode } from 'react';

const metricIcons = [Users, Activity, Image, Coins];

type MetricTarget = 'users' | 'jobs' | 'assets' | 'billings';

export function ConsoleMetricGrid({ overview, onMetricClick }: { overview: ConsoleOverview | null; onMetricClick?: (target: MetricTarget) => void }) {
  const metrics = [
    { label: '用户总数', value: overview?.totalUsers ?? 0, sub: `正常账号 ${overview?.activeUsers ?? 0}`, target: 'users' as const },
    { label: '今日生成任务', value: overview?.todayJobs ?? 0, sub: `生成中 ${overview?.runningJobs ?? 0}`, target: 'jobs' as const },
    { label: '素材资产总数', value: overview?.totalAssets ?? 0, sub: `今日新增 ${overview?.todayAssets ?? 0}`, target: 'assets' as const },
    { label: '全平台可用积分合计', value: overview?.totalCredits ?? 0, sub: `所有用户当前积分余额总和`, target: 'billings' as const },
  ];

  return (
    <div className="grid grid-cols-1 gap-3 xl:grid-cols-4 md:grid-cols-2">
      {metrics.map((metric, index) => {
        const Icon = metricIcons[index] ?? Database;
        return (
          <button
            key={metric.label}
            type="button"
            onClick={() => onMetricClick?.(metric.target)}
            className="group rounded-lg border border-cyan-300/15 bg-white/[0.045] p-4 text-left transition hover:border-cyan-200/45 hover:bg-cyan-300/[0.075] hover:shadow-[0_0_32px_rgba(34,211,238,0.14)]"
          >
            <div className="mb-4 flex items-center justify-between">
              <span className="text-xs text-cyan-100/58">{metric.label}</span>
              <Icon size={17} className="text-cyan-200/75 transition group-hover:text-cyan-50" />
            </div>
            <div className="text-2xl font-semibold text-white">{metric.value.toLocaleString()}</div>
            <div className="mt-1 text-xs text-cyan-100/52">{metric.sub}</div>
          </button>
        );
      })}
    </div>
  );
}

export function StatusPill({ value }: { value?: string }) {
  const status = value || '-';
  const text = statusLabel(status);
  const color =
    status === 'COMPLETED' ? 'border-emerald-300/30 bg-emerald-300/10 text-emerald-100'
      : status === 'FAILED' ? 'border-rose-300/30 bg-rose-300/10 text-rose-100'
        : status === 'RUNNING' ? 'border-cyan-300/30 bg-cyan-300/10 text-cyan-100'
          : status === 'PENDING' ? 'border-amber-300/30 bg-amber-300/10 text-amber-100'
            : 'border-white/15 bg-white/5 text-white/60';

  return <span className={`rounded-md border px-2 py-1 text-xs ${color}`}>{text}</span>;
}

export function Panel({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-cyan-300/15 bg-black/25">
      <div className="flex min-h-12 items-center justify-between gap-3 border-b border-cyan-300/10 px-4 py-3">
        <h2 className="text-sm font-semibold text-cyan-50">{title}</h2>
        {action}
      </div>
      <div className="p-4">{children}</div>
    </section>
  );
}

function statusLabel(value: string) {
  const labels: Record<string, string> = {
    PENDING: '排队中',
    RUNNING: '生成中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  };
  return labels[value] || value || '-';
}
