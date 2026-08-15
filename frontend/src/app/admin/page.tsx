'use client';

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Check, ChevronDown, Eye, EyeOff, KeyRound, Pencil, Plus, RefreshCw, RotateCcw, Search, ShieldAlert, Trash2, X } from 'lucide-react';
import {
  consoleApi,
  type ConsoleAdminItem,
  type ConsoleAssetItem,
  type ConsoleAuditItem,
  type ConsoleBillingItem,
  type ConsoleFinanceOrderItem,
  type ConsoleJobItem,
  type ConsoleOverview,
  type ConsolePricingRuleItem,
  type ConsoleSettingItem,
  type ConsoleUserDetail,
  type ConsoleUserItem,
} from '@/api/console';
import { ConsoleModal, ModalField } from '@/components/admin/ConsoleModal';
import { ConsoleShell } from '@/components/admin/ConsoleShell';
import { ConsoleTable } from '@/components/admin/ConsoleTable';
import { ConsoleMetricGrid, Panel, StatusPill } from '@/components/admin/ConsoleWidgets';
import { getConsoleUser } from '@/lib/auth-store';
import type { UserInfo } from '@/types/user';

type TabKey = 'overview' | 'users' | 'admins' | 'orders' | 'billings' | 'pricing' | 'jobs' | 'assets' | 'settings' | 'audits';
type FilterState = {
  keyword?: string;
  role?: string;
  disabled?: string;
  status?: string;
  type?: string;
  source?: string;
  assetState?: string;
  action?: string;
};
type ModalState =
  | { type: 'userDetail'; user: ConsoleUserItem }
  | { type: 'userPlan'; user: ConsoleUserItem }
  | { type: 'createAdmin' }
  | { type: 'adminRole'; admin: ConsoleAdminItem }
  | { type: 'resetAdminPassword'; admin: ConsoleAdminItem }
  | { type: 'resetUserPassword'; user: ConsoleUserItem }
  | { type: 'credits'; user: ConsoleUserItem }
  | null;

const defaultFilters: Record<TabKey, FilterState> = {
  overview: {},
  users: { keyword: '', role: '', disabled: '' },
  admins: { keyword: '', role: '', disabled: '' },
  orders: { keyword: '', status: '' },
  billings: { keyword: '', type: '' },
  pricing: {},
  jobs: { keyword: '', status: '', type: '' },
  assets: { keyword: '', type: '', source: '', assetState: 'active' },
  settings: {},
  audits: { action: '' },
};

const CONSOLE_ROLE_NOTES = [
  { role: 'ADMIN', label: '超级管理员', desc: '全部模块都能查看和操作，包含后台账号管理。' },
  { role: 'OPERATOR', label: '运营管理员', desc: '可处理用户、任务、素材等日常运营事务。' },
  { role: 'FINANCE', label: '财务管理员', desc: '可查看订单流水，并进行积分调整相关操作。' },
  { role: 'VIEWER', label: '只读人员', desc: '只能查看数据，新增、编辑、删除等按钮会置灰。' },
];

const PAGE_SIZE = 30;

export default function AdminConsolePage() {
  const [active, setActive] = useState<TabKey>('overview');
  const [loading, setLoading] = useState(false);
  const [filters, setFilters] = useState<Record<TabKey, FilterState>>(defaultFilters);
  const [pageMap, setPageMap] = useState<Record<string, number>>({});
  const [totalMap, setTotalMap] = useState<Record<string, number>>({});
  const [overview, setOverview] = useState<ConsoleOverview | null>(null);
  const [admins, setAdmins] = useState<ConsoleAdminItem[]>([]);
  const [users, setUsers] = useState<ConsoleUserItem[]>([]);
  const [jobs, setJobs] = useState<ConsoleJobItem[]>([]);
  const [assets, setAssets] = useState<ConsoleAssetItem[]>([]);
  const [audits, setAudits] = useState<ConsoleAuditItem[]>([]);
  const [orders, setOrders] = useState<ConsoleFinanceOrderItem[]>([]);
  const [billings, setBillings] = useState<ConsoleBillingItem[]>([]);
  const [pricing, setPricing] = useState<ConsolePricingRuleItem[]>([]);
  const [settings, setSettings] = useState<ConsoleSettingItem[]>([]);
  const [modal, setModal] = useState<ModalState>(null);
  const [userDetail, setUserDetail] = useState<ConsoleUserDetail | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [createAdminForm, setCreateAdminForm] = useState({ email: '', displayName: '', role: 'VIEWER', password: '' });
  const [userPlanForm, setUserPlanForm] = useState({ displayName: '', plan: 'FREE', monthlyQuota: '50' });
  const [roleForm, setRoleForm] = useState('VIEWER');
  const [passwordForm, setPasswordForm] = useState('');
  const [creditForm, setCreditForm] = useState({ delta: '100', reason: '后台手动调整积分' });
  const currentRole = (getConsoleUser<UserInfo>()?.role || 'VIEWER').toUpperCase();

  async function load(tab: TabKey = active, override?: FilterState, pageOverride?: number) {
    setLoading(true);
    const current = override ?? filters[tab] ?? {};
    const currentPage = pageOverride ?? pageMap[tab] ?? 1;
    try {
      if (tab === 'overview') {
        const data = await consoleApi.overview();
        setOverview(data);
        setJobs(data.recentJobs ?? []);
      }
      if (tab === 'users') {
        const data = await consoleApi.users(userQuery(current, currentPage));
        setUsers(data.items);
        setPageMeta(tab, data.page, data.total);
      }
      if (tab === 'admins') {
        const data = await consoleApi.admins(adminQuery(current, currentPage));
        setAdmins(data.items);
        setPageMeta(tab, data.page, data.total);
      }
      if (tab === 'orders') {
        const data = await consoleApi.orders(orderQuery(current, currentPage));
        setOrders(data.items);
        setPageMeta(tab, data.page, data.total);
      }
      if (tab === 'billings') {
        const data = await consoleApi.billings(billingQuery(current, currentPage));
        setBillings(data.items);
        setPageMeta(tab, data.page, data.total);
      }
      if (tab === 'pricing') setPricing(await consoleApi.pricing());
      if (tab === 'jobs') {
        const data = await consoleApi.jobs(jobQuery(current, currentPage));
        setJobs(data.items);
        setPageMeta(tab, data.page, data.total);
      }
      if (tab === 'assets') {
        const data = await consoleApi.assets(assetQuery(current, currentPage));
        setAssets(data.items);
        setPageMeta(tab, data.page, data.total);
      }
      if (tab === 'settings') setSettings(await consoleApi.settings());
      if (tab === 'audits') {
        const data = await consoleApi.audits(auditQuery(current, currentPage));
        setAudits(data.items);
        setPageMeta(tab, data.page, data.total);
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load(active);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  const title = useMemo(() => ({
    overview: '经营总览',
    users: '用户与权限',
    admins: '后台账号',
    orders: '财务订单',
    billings: '积分流水',
    pricing: '计费规则',
    jobs: '生成任务',
    assets: '素材资产',
    settings: '系统配置',
    audits: '操作审计',
  }[active]), [active]);

  const subtitle = useMemo(() => ({
    overview: '查看用户、任务、素材和积分的整体运行情况',
    users: '管理前台用户状态、角色、积分和登录密码',
    admins: '后台独立账号管理，和前台用户账号分开',
    orders: '查看充值、套餐和后台入账记录',
    billings: '追踪每一笔积分增加、扣减、赠送和消费',
    pricing: '运营和老板能直接看懂的功能扣费规则',
    jobs: '查看生成任务状态，必要时取消排队或生成中的任务',
    assets: '查看用户素材资产，支持误删恢复',
    settings: '查看当前接入的 AI、存储和工作流服务',
    audits: '追踪后台管理员的关键操作记录',
  }[active]), [active]);

  return (
    <ConsoleShell active={active} onActiveChange={(key) => setActive(key as TabKey)}>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="text-xs text-cyan-100/45">全域智像后台</div>
          <h2 className="mt-1 text-2xl font-semibold text-white">{title}</h2>
          <p className="mt-1 text-xs text-cyan-100/45">{subtitle}</p>
        </div>
        <button
          type="button"
          onClick={() => load(active)}
          className="inline-flex h-9 items-center gap-2 rounded-lg border border-cyan-300/20 bg-white/5 px-3 text-sm text-cyan-100/72 hover:text-white"
        >
          <RefreshCw size={16} className={loading ? 'animate-spin' : ''} />
          刷新
        </button>
      </div>

      {renderFilters()}

      {active === 'overview' && (
        <div className="space-y-4">
          <ConsoleMetricGrid overview={overview} onMetricClick={openOverviewMetric} />
          <Panel title="最近生成任务">
            <JobTable rows={jobs} onChange={() => load('overview')} compact onUserClick={jumpToUser} onJobClick={jumpToJob} />
          </Panel>
        </div>
      )}

      {active === 'users' && (
        <Panel title="前台用户列表">
          <div className="space-y-3">
            <ConsoleTable
              rows={users}
              columns={[
                { key: 'id', title: '用户ID', render: (row) => linkButton(`#${row.id}`, () => openUserDetail(row)) },
                { key: 'email', title: '登录邮箱', render: (row) => linkButton(row.email, () => openUserDetail(row)) },
                { key: 'name', title: '名称', render: (row) => row.displayName || '-' },
                { key: 'role', title: '用户角色', render: (row) => formatRole(row.role) },
                { key: 'credits', title: '当前积分余额', render: (row) => row.credits ?? 0 },
                { key: 'plan', title: '套餐', render: (row) => formatPlan(row.plan) },
                { key: 'disabled', title: '账号状态', render: (row) => row.disabled ? '已禁用' : '正常' },
                { key: 'createdAt', title: '创建时间', render: (row) => formatDate(row.createdAt) },
                {
                  key: 'actions',
                  title: '操作',
                  className: 'min-w-[340px]',
                  render: (row) => (
                    <div className="flex flex-wrap gap-2">
                      <ActionButton onClick={() => openUserDetail(row)}>详情</ActionButton>
                      <IconAction title="编辑套餐" onClick={() => openUserPlan(row)} disabled={!canManageUser()}>
                        <Pencil size={14} />
                      </IconAction>
                      <ActionButton onClick={() => toggleUser(row)} disabled={!canManageUser()}>{row.disabled ? '启用' : '禁用'}</ActionButton>
                      <ActionButton tone="amber" onClick={() => openCredits(row)} disabled={!canAdjustCredits()}>调积分</ActionButton>
                      <IconAction title="修改密码" onClick={() => openResetUserPassword(row)} disabled={!canManageUser()}><KeyRound size={14} /></IconAction>
                    </div>
                  ),
                },
              ]}
            />
            {renderPagination('users')}
          </div>
        </Panel>
      )}

      {active === 'admins' && (
        <Panel
          title="后台账号列表"
          action={(
            <button
              type="button"
              onClick={openCreateAdmin}
              disabled={!canManageAdmin()}
              className="inline-flex h-8 items-center gap-2 rounded-md border border-cyan-300/25 bg-cyan-300/10 px-3 text-xs text-cyan-100 hover:bg-cyan-300/20 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Plus size={14} />
              新增后台账号
            </button>
          )}
        >
          <div className="space-y-3">
            <div className="rounded-lg border border-cyan-300/15 bg-cyan-300/[0.06] p-3">
              <div className="text-xs font-medium text-cyan-100">操作权限说明</div>
              <div className="mt-2 grid gap-2 text-xs text-slate-300 md:grid-cols-2">
                {CONSOLE_ROLE_NOTES.map((item) => (
                  <div key={item.role} className="rounded-md border border-white/10 bg-slate-950/45 px-3 py-2">
                    <span className="text-cyan-100">{item.label}</span>
                    <span className="ml-2 text-slate-400">{item.desc}</span>
                  </div>
                ))}
              </div>
              <div className="mt-2 text-[11px] text-slate-500">说明：后台接口也会校验角色权限，按钮置灰只是页面提示，不能绕过权限操作。</div>
            </div>
            <ConsoleTable
              rows={admins}
              columns={[
              { key: 'id', title: '后台ID', render: (row) => row.id },
              { key: 'email', title: '登录邮箱', render: (row) => row.email },
              { key: 'name', title: '姓名', render: (row) => row.displayName || '-' },
              { key: 'role', title: '后台角色', render: (row) => formatConsoleRole(row.role) },
              { key: 'disabled', title: '账号状态', render: (row) => row.disabled ? '已禁用' : '正常' },
              { key: 'lastLogin', title: '最近登录', render: (row) => formatDate(row.lastLoginAt) },
              { key: 'createdAt', title: '创建时间', render: (row) => formatDate(row.createdAt) },
              {
                key: 'actions',
                title: '操作',
                className: 'min-w-[300px]',
                render: (row) => (
                  <div className="flex flex-wrap gap-2">
                    <ActionButton onClick={() => openAdminRole(row)} disabled={!canManageAdmin()}>改角色</ActionButton>
                    <ActionButton tone="amber" onClick={() => toggleAdmin(row)} disabled={!canManageAdmin()}>{row.disabled ? '启用' : '禁用'}</ActionButton>
                    <IconAction title="重置后台密码" onClick={() => openResetAdminPassword(row)} disabled={!canManageAdmin()}><KeyRound size={14} /></IconAction>
                    <IconAction title="删除后台账号" tone="rose" onClick={() => deleteAdmin(row)} disabled={!canManageAdmin()}><Trash2 size={14} /></IconAction>
                  </div>
                ),
              },
            ]}
            />
            {renderPagination('admins')}
          </div>
        </Panel>
      )}

      {active === 'jobs' && (
        <Panel title="任务列表">
          <div className="space-y-3">
            <JobTable rows={jobs} onChange={() => load('jobs')} onUserClick={jumpToUser} onJobClick={jumpToJob} canCancel={canOperate()} />
            {renderPagination('jobs')}
          </div>
        </Panel>
      )}

      {active === 'orders' && (
        <Panel title="财务订单">
          <div className="space-y-3">
            <ConsoleTable
              rows={orders}
              columns={[
              { key: 'orderNo', title: '订单号', render: (row) => row.orderNo },
              { key: 'user', title: '用户', render: (row) => linkButton(row.userEmail || `#${row.userId}`, () => jumpToUser(row.userEmail || row.userId)) },
              { key: 'source', title: '来源', render: (row) => row.source || '-' },
              { key: 'status', title: '支付状态', render: (row) => formatOrderStatus(row.status) },
              { key: 'amount', title: '支付金额', render: (row) => row.amount ? `¥${row.amount}` : '待接真实支付' },
              { key: 'credits', title: '入账积分', render: (row) => row.credits ?? 0 },
              { key: 'paidAt', title: '入账时间', render: (row) => formatDate(row.paidAt) },
              ]}
            />
            {renderPagination('orders')}
          </div>
        </Panel>
      )}

      {active === 'billings' && (
        <Panel title="积分流水">
          <div className="space-y-3">
            <ConsoleTable
              rows={billings}
              columns={[
              { key: 'id', title: '流水ID', render: (row) => row.id },
              { key: 'user', title: '用户', render: (row) => linkButton(row.userEmail || `#${row.userId}`, () => jumpToUser(row.userEmail || row.userId)) },
              { key: 'job', title: '关联任务', render: (row) => row.jobId ? linkButton(`#${row.jobId}`, () => jumpToJob(row.jobId!)) : '-' },
              { key: 'type', title: '流水类型', render: (row) => formatBillingType(row.type) },
              { key: 'delta', title: '积分变化', render: (row) => formatCreditsDelta(row.creditsDelta) },
              { key: 'balance', title: '变动后余额', render: (row) => row.balanceAfter },
              { key: 'desc', title: '说明', render: (row) => row.description || '-' },
              { key: 'time', title: '创建时间', render: (row) => formatDate(row.createdAt) },
              ]}
            />
            {renderPagination('billings')}
          </div>
        </Panel>
      )}

      {active === 'pricing' && (
        <Panel title="计费规则">
          <ConsoleTable
            rows={pricing}
            columns={[
              { key: 'scene', title: '功能场景', render: (row) => row.scene },
              { key: 'base', title: '基础积分', render: (row) => row.baseCredits },
              { key: 'logic', title: '计费方式', render: (row) => row.billingLogic },
              { key: 'enabled', title: '接入状态', render: (row) => row.enabled },
              { key: 'note', title: '备注', render: (row) => row.note },
            ]}
          />
        </Panel>
      )}

      {active === 'assets' && (
        <Panel title="素材列表">
          <div className="space-y-3">
            <ConsoleTable
              rows={assets}
              columns={[
              { key: 'id', title: '素材ID', render: (row) => row.id },
              { key: 'name', title: '名称', render: (row) => row.name || '-' },
              { key: 'type', title: '类型', render: (row) => formatAssetType(row.type) },
              { key: 'source', title: '来源', render: (row) => formatAssetSource(row.source) },
              { key: 'owner', title: '所属用户', render: (row) => linkButton(row.userEmail || `#${row.userId}`, () => jumpToUser(row.userEmail || row.userId)) },
              { key: 'task', title: '来源任务', render: (row) => row.sourceTaskId ? linkButton(row.sourceTaskId, () => jumpToJob(row.sourceTaskId!)) : '-' },
              { key: 'size', title: '大小', render: (row) => formatBytes(row.sizeBytes) },
              { key: 'createdAt', title: '创建时间', render: (row) => formatDate(row.createdAt) },
              {
                key: 'actions',
                title: '操作',
                className: 'min-w-[120px]',
                render: (row) => row.deleted ? (
                  <IconAction title="恢复素材" tone="emerald" onClick={() => restoreAsset(row)} disabled={!canOperate()}>
                    <RotateCcw size={14} />
                  </IconAction>
                ) : (
                  <IconAction title="删除素材" tone="rose" onClick={() => removeAsset(row)} disabled={!canOperate()}>
                    <Trash2 size={14} />
                  </IconAction>
                ),
              },
            ]}
            />
            {renderPagination('assets')}
          </div>
        </Panel>
      )}

      {active === 'settings' && (
        <Panel title="运行配置">
          <ConsoleTable
            rows={settings}
            columns={[
              { key: 'group', title: '模块', render: (row) => formatSettingGroup(row.group) },
              { key: 'key', title: '配置项', render: (row) => formatSettingKey(row.key) },
              { key: 'value', title: '当前状态', render: (row) => <span className="font-mono text-xs">{formatSettingValue(row.value)}</span> },
              { key: 'note', title: '说明', render: (row) => formatSettingNote(row.note) },
            ]}
          />
        </Panel>
      )}

      {active === 'audits' && (
        <Panel title="审计记录">
          <div className="space-y-3">
            <ConsoleTable
              rows={audits}
              columns={[
              { key: 'id', title: '审计ID', render: (row) => row.id },
              { key: 'admin', title: '管理员', render: (row) => row.adminEmail || row.adminId },
              { key: 'action', title: '动作', render: (row) => formatAuditAction(row.action) },
              { key: 'target', title: '目标', render: (row) => renderAuditTarget(row) },
              { key: 'detail', title: '详情', render: (row) => formatAuditDetail(row.detail) },
              { key: 'time', title: '创建时间', render: (row) => formatDate(row.createdAt) },
              ]}
            />
            {renderPagination('audits')}
          </div>
        </Panel>
      )}

      {renderModal()}
    </ConsoleShell>
  );

  function renderFilters() {
    if (['overview', 'pricing', 'settings'].includes(active)) return null;
    const f = filters[active] ?? {};
    return (
      <div className="mb-4 rounded-lg border border-cyan-300/15 bg-black/24 p-3">
        <div className="flex flex-wrap items-end gap-3">
          {active !== 'audits' && (
            <FilterInput
              label={filterKeywordLabel(active)}
              value={f.keyword || ''}
              placeholder="输入关键词"
              onChange={(value) => patchFilter({ keyword: value })}
              onEnter={() => load(active, undefined, 1)}
            />
          )}
          {active === 'users' && (
            <>
              <FilterSelect label="用户角色" value={f.role || ''} onChange={(value) => patchFilter({ role: value })} options={[['', '全部'], ['USER', '普通用户'], ['ADMIN', '前台管理员']]} />
              <FilterSelect label="账号状态" value={f.disabled || ''} onChange={(value) => patchFilter({ disabled: value })} options={[['', '全部'], ['false', '正常'], ['true', '已禁用']]} />
            </>
          )}
          {active === 'admins' && (
            <>
              <FilterSelect label="后台角色" value={f.role || ''} onChange={(value) => patchFilter({ role: value })} options={[['', '全部'], ['ADMIN', '超级管理员'], ['FINANCE', '财务'], ['OPERATOR', '运营'], ['VIEWER', '只读']]} />
              <FilterSelect label="账号状态" value={f.disabled || ''} onChange={(value) => patchFilter({ disabled: value })} options={[['', '全部'], ['false', '正常'], ['true', '已禁用']]} />
            </>
          )}
          {active === 'jobs' && (
            <>
              <FilterSelect label="任务状态" value={f.status || ''} onChange={(value) => patchFilter({ status: value })} options={[['', '全部'], ['PENDING', '排队中'], ['RUNNING', '生成中'], ['COMPLETED', '已完成'], ['FAILED', '失败'], ['CANCELLED', '已取消']]} />
              <FilterSelect label="任务类型" value={f.type || ''} onChange={(value) => patchFilter({ type: value })} options={[['', '全部'], ['text-to-video', '文生视频'], ['image-to-video', '图生视频'], ['text-to-image', '文生图'], ['image-to-image', '图生图']]} />
            </>
          )}
          {active === 'assets' && (
            <>
              <FilterSelect label="素材类型" value={f.type || ''} onChange={(value) => patchFilter({ type: value })} options={[['', '全部'], ['image', '图片'], ['video', '视频'], ['audio', '音频']]} />
              <FilterSelect label="素材来源" value={f.source || ''} onChange={(value) => patchFilter({ source: value })} options={[['', '全部'], ['uploaded', '用户上传'], ['ai-generated', 'AI 生成'], ['generated', 'AI 生成']]} />
              <FilterSelect label="资产状态" value={f.assetState || 'active'} onChange={(value) => patchFilter({ assetState: value })} options={[['active', '正常素材'], ['deleted', '已删除素材']]} />
            </>
          )}
          {active === 'orders' && (
            <FilterSelect label="支付状态" value={f.status || ''} onChange={(value) => patchFilter({ status: value })} options={[['', '全部'], ['PAID', '已支付']]} />
          )}
          {active === 'billings' && (
            <FilterSelect label="流水类型" value={f.type || ''} onChange={(value) => patchFilter({ type: value })} options={[['', '全部'], ['RECHARGE', '充值入账'], ['CONSUME', '生成扣费'], ['REFUND', '退款返还'], ['GRANT', '后台赠送'], ['EXPIRE', '积分过期']]} />
          )}
          {active === 'audits' && (
            <FilterSelect label="操作类型" value={f.action || ''} onChange={(value) => patchFilter({ action: value })} options={[['', '全部'], ['CONSOLE_USER_UPDATE', '修改用户'], ['CONSOLE_USER_PASSWORD_RESET', '重置用户密码'], ['CONSOLE_CREDITS_ADJUST', '调整积分'], ['CONSOLE_ADMIN_CREATE', '新增后台账号'], ['CONSOLE_ADMIN_UPDATE', '修改后台账号'], ['CONSOLE_ADMIN_PASSWORD_RESET', '重置后台密码'], ['CONSOLE_ASSET_DELETE', '删除素材'], ['CONSOLE_ASSET_RESTORE', '恢复素材']]} />
          )}
          <button type="button" onClick={() => load(active, undefined, 1)} className="inline-flex h-9 items-center gap-2 rounded-lg bg-cyan-300 px-4 text-sm font-semibold text-[#041019] hover:bg-cyan-200">
            <Search size={15} />
            查询
          </button>
          <button type="button" onClick={resetFilter} className="h-9 rounded-lg border border-cyan-300/20 bg-white/5 px-4 text-sm text-cyan-100/72 hover:text-white">
            重置
          </button>
        </div>
      </div>
    );
  }

  function renderModal() {
    if (!modal) return null;
    if (modal.type === 'userDetail') {
      return (
        <ConsoleModal title={`用户详情：${modal.user.email}`} onClose={closeModal} footer={<button type="button" onClick={closeModal} className="h-9 rounded-lg bg-cyan-300 px-4 text-sm font-semibold text-[#041019] hover:bg-cyan-200">关闭</button>}>
          {!userDetail ? (
            <div className="py-6 text-center text-sm text-cyan-100/55">正在加载用户详情...</div>
          ) : (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3 text-sm">
                <DetailCell label="用户ID" value={`#${userDetail.user.id}`} />
                <DetailCell label="账号状态" value={userDetail.user.disabled ? '已禁用' : '正常'} />
                <DetailCell label="登录邮箱" value={userDetail.user.email} />
                <DetailCell label="名称" value={userDetail.user.displayName || '-'} />
                <DetailCell label="套餐" value={formatPlan(userDetail.user.plan)} />
                <DetailCell label="月额度" value={`${userDetail.user.monthlyQuota ?? 0}`} />
                <DetailCell label="当前积分" value={`${userDetail.user.credits ?? 0}`} />
                <DetailCell label="创建时间" value={formatDate(userDetail.user.createdAt)} />
              </div>
              <DetailList title="最近积分记录" rows={userDetail.recentBillings.map((item) => `${formatDate(item.createdAt)}  ${formatBillingType(item.type)}  ${formatCreditsDelta(item.creditsDelta)}  余额 ${item.balanceAfter}`)} />
              <DetailList title="最近生成任务" rows={userDetail.recentJobs.map((item) => `${formatDate(item.createdAt)}  #${item.id}  ${formatJobType(item.templateId)}  ${item.status}`)} />
              <DetailList title="最近素材资产" rows={userDetail.recentAssets.map((item) => `${formatDate(item.createdAt)}  #${item.id}  ${item.name || '-'}  ${formatAssetType(item.type)}`)} />
            </div>
          )}
        </ConsoleModal>
      );
    }
    if (modal.type === 'userPlan') {
      return (
        <ConsoleModal title={`编辑套餐：${modal.user.email}`} onClose={closeModal} footer={modalFooter(submitUserPlan)}>
          <ModalField label="用户名称">
            <TextInput value={userPlanForm.displayName} onChange={(value) => setUserPlanForm((old) => ({ ...old, displayName: value }))} placeholder="请输入用户名称" />
          </ModalField>
          <ModalField label="套餐类型">
            <SelectInput value={userPlanForm.plan} onChange={(value) => setUserPlanForm((old) => ({ ...old, plan: value }))} options={[['FREE', '免费版'], ['BASIC', '基础版'], ['STANDARD', '标准版'], ['PREMIUM', '高级版'], ['ENTERPRISE', '企业版']]} />
          </ModalField>
          <ModalField label="每月额度">
            <TextInput value={userPlanForm.monthlyQuota} onChange={(value) => setUserPlanForm((old) => ({ ...old, monthlyQuota: value }))} placeholder="例如：50" />
          </ModalField>
        </ConsoleModal>
      );
    }
    if (modal.type === 'createAdmin') {
      return (
        <ConsoleModal title="新增后台账号" onClose={closeModal} footer={modalFooter(submitCreateAdmin)}>
          <ModalField label="登录邮箱">
            <TextInput value={createAdminForm.email} onChange={(value) => setCreateAdminForm((old) => ({ ...old, email: value }))} placeholder="admin@company.com" />
          </ModalField>
          <ModalField label="姓名">
            <TextInput value={createAdminForm.displayName} onChange={(value) => setCreateAdminForm((old) => ({ ...old, displayName: value }))} placeholder="例如：运营主管" />
          </ModalField>
          <ModalField label="后台角色">
            <SelectInput value={createAdminForm.role} onChange={(value) => setCreateAdminForm((old) => ({ ...old, role: value }))} options={[['ADMIN', '超级管理员'], ['FINANCE', '财务'], ['OPERATOR', '运营'], ['VIEWER', '只读']]} />
          </ModalField>
          <ModalField label="初始登录密码">
            <PasswordInput
              name="console-admin-create-password"
              value={createAdminForm.password}
              onChange={(value) => setCreateAdminForm((old) => ({ ...old, password: value }))}
            />
          </ModalField>
        </ConsoleModal>
      );
    }
    if (modal.type === 'adminRole') {
      return (
        <ConsoleModal title={`修改后台角色：${modal.admin.email}`} onClose={closeModal} footer={modalFooter(submitAdminRole)}>
          <ModalField label="后台角色">
            <SelectInput value={roleForm} onChange={setRoleForm} options={[['ADMIN', '超级管理员'], ['FINANCE', '财务'], ['OPERATOR', '运营'], ['VIEWER', '只读']]} />
          </ModalField>
        </ConsoleModal>
      );
    }
    if (modal.type === 'resetAdminPassword') {
      return (
        <ConsoleModal title={`重置后台密码：${modal.admin.email}`} onClose={closeModal} footer={modalFooter(submitResetAdminPassword)}>
          <ModalField label="新后台登录密码">
            <PasswordInput name="console-admin-reset-password" value={passwordForm} onChange={setPasswordForm} />
          </ModalField>
        </ConsoleModal>
      );
    }
    if (modal.type === 'resetUserPassword') {
      return (
        <ConsoleModal title={`修改用户密码：${modal.user.email}`} onClose={closeModal} footer={modalFooter(submitResetUserPassword)}>
          <ModalField label="新前台登录密码">
            <PasswordInput name="console-front-user-reset-password" value={passwordForm} onChange={setPasswordForm} />
          </ModalField>
        </ConsoleModal>
      );
    }
    return (
      <ConsoleModal title={`调整用户积分：${modal.user.email}`} onClose={closeModal} footer={modalFooter(submitCredits)}>
        <ModalField label="积分变化">
          <TextInput value={creditForm.delta} onChange={(value) => setCreditForm((old) => ({ ...old, delta: value }))} placeholder="正数增加，负数扣减" />
        </ModalField>
        <ModalField label="调整原因">
          <TextInput value={creditForm.reason} onChange={(value) => setCreditForm((old) => ({ ...old, reason: value }))} placeholder="例如：客户补偿" />
        </ModalField>
      </ConsoleModal>
    );
  }

  function renderAuditTarget(row: ConsoleAuditItem) {
    if (row.targetType === 'USER' && row.targetId) return linkButton(`用户 #${row.targetId}`, () => jumpToUser(row.targetId!));
    if (row.targetType === 'JOB' && row.targetId) return linkButton(`任务 #${row.targetId}`, () => jumpToJob(row.targetId!));
    return formatAuditTarget(row.targetType, row.targetId);
  }

  function setPageMeta(tab: TabKey, page: number, total: number) {
    setPageMap((old) => ({ ...old, [tab]: page }));
    setTotalMap((old) => ({ ...old, [tab]: total }));
  }

  function renderPagination(tab: TabKey) {
    const page = pageMap[tab] ?? 1;
    const total = totalMap[tab] ?? 0;
    const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
    return (
      <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-cyan-100/55">
        <span>共 {total.toLocaleString()} 条，第 {page} / {totalPages} 页</span>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={page <= 1 || loading}
            onClick={() => load(tab, undefined, page - 1)}
            className="h-8 rounded-md border border-cyan-300/20 bg-white/5 px-3 text-cyan-100/70 hover:text-white disabled:cursor-not-allowed disabled:opacity-35"
          >
            上一页
          </button>
          <button
            type="button"
            disabled={page >= totalPages || loading}
            onClick={() => load(tab, undefined, page + 1)}
            className="h-8 rounded-md border border-cyan-300/20 bg-white/5 px-3 text-cyan-100/70 hover:text-white disabled:cursor-not-allowed disabled:opacity-35"
          >
            下一页
          </button>
        </div>
      </div>
    );
  }

  function patchFilter(patch: FilterState) {
    setFilters((old) => ({ ...old, [active]: { ...(old[active] ?? {}), ...patch } }));
  }

  function resetFilter() {
    const next = defaultFilters[active];
    setFilters((old) => ({ ...old, [active]: next }));
    load(active, next, 1);
  }

  function jumpToUser(value: string | number) {
    const next = { ...defaultFilters.users, keyword: String(value) };
    setFilters((old) => ({ ...old, users: next }));
    setActive('users');
    load('users', next, 1);
  }

  function jumpToJob(value: string | number) {
    const next = { ...defaultFilters.jobs, keyword: String(value) };
    setFilters((old) => ({ ...old, jobs: next }));
    setActive('jobs');
    load('jobs', next, 1);
  }

  function openOverviewMetric(target: 'users' | 'jobs' | 'assets' | 'billings') {
    const next = defaultFilters[target];
    setFilters((old) => ({ ...old, [target]: next }));
    setActive(target);
    load(target, next, 1);
  }

  async function openUserDetail(row: ConsoleUserItem) {
    setUserDetail(null);
    setModal({ type: 'userDetail', user: row });
    setUserDetail(await consoleApi.userDetail(row.id));
  }

  function openUserPlan(row: ConsoleUserItem) {
    setUserPlanForm({
      displayName: row.displayName || '',
      plan: (row.plan || 'FREE').toUpperCase(),
      monthlyQuota: String(row.monthlyQuota ?? 50),
    });
    setModal({ type: 'userPlan', user: row });
  }

  async function toggleUser(row: ConsoleUserItem) {
    await consoleApi.patchUser(row.id, { disabled: !row.disabled });
    await load('users');
  }

  function openCredits(row: ConsoleUserItem) {
    setCreditForm({ delta: '100', reason: '后台手动调整积分' });
    setModal({ type: 'credits', user: row });
  }

  function openResetUserPassword(row: ConsoleUserItem) {
    setPasswordForm('');
    setShowPassword(false);
    setModal({ type: 'resetUserPassword', user: row });
  }

  function openCreateAdmin() {
    setCreateAdminForm({ email: '', displayName: '', role: 'VIEWER', password: '' });
    setShowPassword(false);
    setModal({ type: 'createAdmin' });
  }

  function openAdminRole(row: ConsoleAdminItem) {
    setRoleForm(row.role || 'VIEWER');
    setModal({ type: 'adminRole', admin: row });
  }

  function openResetAdminPassword(row: ConsoleAdminItem) {
    setPasswordForm('');
    setShowPassword(false);
    setModal({ type: 'resetAdminPassword', admin: row });
  }

  function closeModal() {
    setModal(null);
    setPasswordForm('');
  }

  async function submitCreateAdmin() {
    if (!createAdminForm.email || !createAdminForm.password) return window.alert('请填写登录邮箱和初始密码');
    if (createAdminForm.password.length < 6) return window.alert('密码至少 6 位');
    await consoleApi.createAdmin(createAdminForm);
    closeModal();
    await load('admins');
  }

  async function submitUserPlan() {
    if (!modal || modal.type !== 'userPlan') return;
    const monthlyQuota = Number(userPlanForm.monthlyQuota);
    if (!Number.isInteger(monthlyQuota) || monthlyQuota < 0) return window.alert('每月额度必须是非负整数');
    await consoleApi.patchUserPlan(modal.user.id, {
      displayName: userPlanForm.displayName,
      plan: userPlanForm.plan,
      monthlyQuota,
    });
    closeModal();
    await load('users');
  }

  async function submitAdminRole() {
    if (!modal || modal.type !== 'adminRole') return;
    await consoleApi.patchAdmin(modal.admin.id, { role: roleForm });
    closeModal();
    await load('admins');
  }

  async function submitResetAdminPassword() {
    if (!modal || modal.type !== 'resetAdminPassword') return;
    if (passwordForm.length < 6) return window.alert('密码至少 6 位');
    await consoleApi.resetAdminPassword(modal.admin.id, { password: passwordForm });
    closeModal();
    await load('admins');
  }

  async function submitResetUserPassword() {
    if (!modal || modal.type !== 'resetUserPassword') return;
    if (passwordForm.length < 6) return window.alert('密码至少 6 位');
    await consoleApi.resetUserPassword(modal.user.id, { password: passwordForm });
    closeModal();
    await load('users');
  }

  async function submitCredits() {
    if (!modal || modal.type !== 'credits') return;
    const delta = Number(creditForm.delta);
    if (!Number.isFinite(delta) || delta === 0) return window.alert('积分变化必须是非 0 数字');
    await consoleApi.adjustCredits(modal.user.id, { delta, reason: creditForm.reason || '后台手动调整积分' });
    closeModal();
    await load('users');
  }

  async function toggleAdmin(row: ConsoleAdminItem) {
    await consoleApi.patchAdmin(row.id, { disabled: !row.disabled });
    await load('admins');
  }

  async function deleteAdmin(row: ConsoleAdminItem) {
    if (!window.confirm(`确认删除后台账号「${row.email}」？删除后该账号无法登录后台。`)) return;
    await consoleApi.deleteAdmin(row.id);
    await load('admins');
  }

  async function removeAsset(row: ConsoleAssetItem) {
    if (!window.confirm(`确认删除素材「${row.name || row.id}」？后台回收站里可以恢复。`)) return;
    await consoleApi.deleteAsset(row.id);
    await load('assets');
  }

  async function restoreAsset(row: ConsoleAssetItem) {
    await consoleApi.restoreAsset(row.id);
    await load('assets');
  }

  function canManageAdmin() {
    return currentRole === 'ADMIN';
  }

  function canManageUser() {
    return currentRole === 'ADMIN' || currentRole === 'OPERATOR';
  }

  function canAdjustCredits() {
    return currentRole === 'ADMIN' || currentRole === 'FINANCE';
  }

  function canOperate() {
    return currentRole === 'ADMIN' || currentRole === 'OPERATOR';
  }

  function modalFooter(onSubmit: () => void | Promise<void>, submitText = '保存') {
    return (
      <>
        <button type="button" onClick={closeModal} className="h-9 rounded-lg border border-cyan-300/20 bg-white/5 px-4 text-sm text-cyan-100/72 hover:text-white">取消</button>
        <button type="button" onClick={onSubmit} className="h-9 rounded-lg bg-cyan-300 px-4 text-sm font-semibold text-[#041019] hover:bg-cyan-200">{submitText}</button>
      </>
    );
  }

  function PasswordInput({ name, value, onChange }: { name: string; value: string; onChange: (value: string) => void }) {
    return (
      <div className="flex h-10 items-center rounded-lg border border-cyan-300/20 bg-white/[0.055] focus-within:border-cyan-200">
        <input
          name={name}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          type={showPassword ? 'text' : 'password'}
          autoComplete="new-password"
          data-lpignore="true"
          data-1p-ignore="true"
          className="min-w-0 flex-1 bg-transparent px-3 text-sm outline-none placeholder:text-cyan-100/25"
          placeholder="请输入密码"
        />
        {value && (
          <button
            type="button"
            onClick={() => onChange('')}
            title="清空"
            className="grid h-10 w-8 place-items-center text-cyan-100/45 hover:text-cyan-50"
          >
            <X size={15} />
          </button>
        )}
        <button
          type="button"
          onClick={() => setShowPassword((old) => !old)}
          title={showPassword ? '隐藏密码' : '显示密码'}
          className="grid h-10 w-10 place-items-center text-cyan-100/55 hover:text-cyan-50"
        >
          {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
        </button>
      </div>
    );
  }
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

function JobTable({ rows, onChange, compact = false, onUserClick, onJobClick, canCancel = true }: {
  rows: ConsoleJobItem[];
  onChange: () => void;
  compact?: boolean;
  onUserClick: (value: string | number) => void;
  onJobClick: (value: string | number) => void;
  canCancel?: boolean;
}) {
  async function cancelJob(row: ConsoleJobItem) {
    await consoleApi.patchJob(row.id, { status: 'CANCELLED', reason: '后台取消任务' });
    onChange();
  }

  return (
    <ConsoleTable
      rows={rows}
      columns={[
        { key: 'id', title: '任务ID', render: (row) => linkButton(`#${row.id}`, () => onJobClick(row.id)) },
        { key: 'template', title: '任务类型', render: (row) => formatJobType(row.templateId) },
        { key: 'taskId', title: '中转任务号', render: (row) => row.taskId || '-' },
        { key: 'user', title: '提交用户', render: (row) => linkButton(row.userEmail || `#${row.userId}`, () => onUserClick(row.userEmail || row.userId)) },
        { key: 'status', title: '任务状态', render: (row) => <StatusPill value={row.status} /> },
        { key: 'credits', title: '消耗积分', render: (row) => row.creditsCost ?? 0 },
        { key: 'time', title: '创建时间', render: (row) => formatDate(row.createdAt) },
        { key: 'completedAt', title: '结束时间', render: (row) => formatDate(row.completedAt) },
        {
          key: 'actions',
          title: '操作',
          render: (row) => compact ? '-' : (
            <button
              onClick={() => cancelJob(row)}
              disabled={!canCancel || !['PENDING', 'RUNNING'].includes(row.status)}
              className="inline-flex items-center gap-1 text-amber-200 hover:text-white disabled:text-white/25"
            >
              <ShieldAlert size={14} />
              取消任务
            </button>
          ),
        },
      ]}
    />
  );
}

function DetailCell({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="rounded-lg border border-cyan-300/12 bg-white/[0.035] px-3 py-2">
      <div className="text-xs text-cyan-100/45">{label}</div>
      <div className="mt-1 truncate text-sm text-cyan-50">{value}</div>
    </div>
  );
}

function DetailList({ title, rows }: { title: string; rows: string[] }) {
  return (
    <div className="rounded-lg border border-cyan-300/12 bg-white/[0.025] p-3">
      <div className="mb-2 text-xs font-semibold text-cyan-100/70">{title}</div>
      {rows.length === 0 ? (
        <div className="text-xs text-cyan-100/38">暂无记录</div>
      ) : (
        <div className="space-y-1">
          {rows.map((row, index) => (
            <div key={`${title}-${index}`} className="truncate rounded-md bg-black/20 px-2 py-1.5 text-xs text-cyan-50/78">
              {row}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function FilterInput({ label, value, placeholder, onChange, onEnter }: { label: string; value: string; placeholder: string; onChange: (value: string) => void; onEnter: () => void }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs text-cyan-100/55">{label}</span>
      <div className="relative w-[260px]">
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => event.key === 'Enter' && onEnter()}
          className="h-9 w-full rounded-lg border border-cyan-300/15 bg-white/[0.045] px-3 pr-9 text-sm text-cyan-50 outline-none placeholder:text-cyan-100/30 focus:border-cyan-200"
          placeholder={placeholder}
        />
        {value && <ClearInputButton onClick={() => onChange('')} />}
      </div>
    </label>
  );
}

function FilterSelect({ label, value, options, onChange }: { label: string; value: string; options: Array<[string, string]>; onChange: (value: string) => void }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs text-cyan-100/55">{label}</span>
      <DropdownInput value={value} options={options} onChange={onChange} className="min-w-[128px]" />
    </label>
  );
}

function TextInput({ value, onChange, placeholder }: { value: string; onChange: (value: string) => void; placeholder?: string }) {
  return (
    <div className="relative">
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="h-10 w-full rounded-lg border border-cyan-300/20 bg-white/[0.055] px-3 pr-9 text-sm text-cyan-50 outline-none placeholder:text-cyan-100/25 focus:border-cyan-200"
        placeholder={placeholder}
      />
      {value && <ClearInputButton onClick={() => onChange('')} />}
    </div>
  );
}

function SelectInput({ value, onChange, options }: { value: string; options: Array<[string, string]>; onChange: (value: string) => void }) {
  return <DropdownInput value={value} options={options} onChange={onChange} className="w-full" size="large" />;
}

function DropdownInput({ value, options, onChange, className = '', size = 'normal' }: {
  value: string;
  options: Array<[string, string]>;
  onChange: (value: string) => void;
  className?: string;
  size?: 'normal' | 'large';
}) {
  const [open, setOpen] = useState(false);
  const current = options.find(([key]) => key === value)?.[1] ?? '请选择';
  const height = size === 'large' ? 'h-10' : 'h-9';

  return (
    <div
      className={`relative ${className}`}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
    >
      <button
        type="button"
        onClick={() => setOpen((old) => !old)}
        className={`${height} flex w-full items-center justify-between gap-2 rounded-lg border border-cyan-300/20 bg-[#0b1524]/95 px-3 text-left text-sm text-cyan-50 outline-none transition hover:border-cyan-200/55 hover:bg-[#102137] focus:border-cyan-200`}
      >
        <span className="truncate">{current}</span>
        <ChevronDown size={15} className={`shrink-0 text-cyan-100/65 transition ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <div className="absolute left-0 top-[calc(100%+6px)] z-40 max-h-64 min-w-full overflow-hidden rounded-lg border border-cyan-300/25 bg-[#07101d] py-1 shadow-[0_18px_45px_rgba(0,0,0,0.45),0_0_28px_rgba(34,211,238,0.14)]">
          {options.map(([key, labelText]) => {
            const selected = key === value;
            return (
              <button
                key={key}
                type="button"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => {
                  onChange(key);
                  setOpen(false);
                }}
                className={[
                  'flex h-9 w-full items-center justify-between gap-3 px-3 text-left text-sm transition',
                  selected ? 'bg-cyan-300/14 text-cyan-50' : 'text-cyan-100/72 hover:bg-white/[0.07] hover:text-white',
                ].join(' ')}
              >
                <span className="whitespace-nowrap">{labelText}</span>
                {selected && <Check size={14} className="text-cyan-200" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function ActionButton({ children, onClick, tone = 'cyan', disabled = false }: { children: string; onClick: () => void; tone?: 'cyan' | 'amber'; disabled?: boolean }) {
  const color = tone === 'amber' ? 'text-amber-200 hover:text-white' : 'text-cyan-200 hover:text-white';
  return <button type="button" onClick={onClick} disabled={disabled} className={`${color} disabled:cursor-not-allowed disabled:text-white/25`}>{children}</button>;
}

function IconAction({ children, title, onClick, tone = 'cyan', disabled = false }: { children: ReactNode; title: string; onClick: () => void; tone?: 'cyan' | 'rose' | 'emerald'; disabled?: boolean }) {
  const color =
    tone === 'rose' ? 'border-rose-300/25 bg-rose-300/10 text-rose-100 hover:bg-rose-300/20'
      : tone === 'emerald' ? 'border-emerald-300/25 bg-emerald-300/10 text-emerald-100 hover:bg-emerald-300/20'
        : 'border-cyan-300/25 bg-cyan-300/10 text-cyan-100 hover:bg-cyan-300/20';
  return (
    <button type="button" title={title} onClick={onClick} disabled={disabled} className={`inline-grid h-8 w-8 place-items-center rounded-md border ${color} disabled:cursor-not-allowed disabled:opacity-35`}>
      {children}
    </button>
  );
}

function linkButton(label: ReactNode, onClick: () => void) {
  return <button type="button" onClick={onClick} className="text-cyan-200 underline-offset-4 hover:text-white hover:underline">{label}</button>;
}

function filterKeywordLabel(tab: TabKey) {
  const labels: Partial<Record<TabKey, string>> = {
    users: '邮箱 / 姓名 / 用户ID',
    admins: '邮箱 / 姓名',
    jobs: '任务类型 / 中转任务号',
    assets: '素材名称 / 来源任务',
    orders: '订单号 / 支付号 / 说明',
    billings: '支付号 / 说明',
  };
  return labels[tab] || '关键词';
}

function compactQuery(filter: Record<string, string | number | boolean | undefined>) {
  return Object.fromEntries(Object.entries(filter).filter(([, value]) => value !== undefined && value !== '')) as Record<string, string | number | boolean | undefined>;
}

function userQuery(f: FilterState, page: number) {
  return compactQuery({ page, pageSize: PAGE_SIZE, keyword: f.keyword, role: f.role, disabled: f.disabled ? f.disabled === 'true' : undefined });
}

function adminQuery(f: FilterState, page: number) {
  return compactQuery({ page, pageSize: PAGE_SIZE, keyword: f.keyword, role: f.role, disabled: f.disabled ? f.disabled === 'true' : undefined });
}

function orderQuery(f: FilterState, page: number) {
  return compactQuery({ page, pageSize: PAGE_SIZE, keyword: f.keyword, status: f.status });
}

function billingQuery(f: FilterState, page: number) {
  return compactQuery({ page, pageSize: PAGE_SIZE, keyword: f.keyword, type: f.type });
}

function jobQuery(f: FilterState, page: number) {
  return compactQuery({ page, pageSize: PAGE_SIZE, keyword: f.keyword, status: f.status, templateId: f.type });
}

function assetQuery(f: FilterState, page: number) {
  return compactQuery({ page, pageSize: PAGE_SIZE, keyword: f.keyword, type: f.type, source: f.source, deleted: f.assetState === 'deleted' });
}

function auditQuery(f: FilterState, page: number) {
  return compactQuery({ page, pageSize: PAGE_SIZE, action: f.action });
}

function formatRole(value?: string) {
  const labels: Record<string, string> = { ADMIN: '前台管理员', USER: '普通用户' };
  return labels[value || ''] || value || '-';
}

function formatConsoleRole(value?: string) {
  const labels: Record<string, string> = { ADMIN: '超级管理员', FINANCE: '财务管理员', OPERATOR: '运营管理员', VIEWER: '只读人员' };
  return labels[value || ''] || value || '-';
}

function formatPlan(value?: string) {
  const labels: Record<string, string> = { FREE: '免费版', BASIC: '基础版', STANDARD: '标准版', PREMIUM: '高级版', ENTERPRISE: '企业版' };
  return labels[(value || '').toUpperCase()] || value || '-';
}

function formatJobType(value?: string) {
  const labels: Record<string, string> = {
    'text-to-video': '文生视频',
    'image-to-video': '图生视频',
    'text-to-image': '文生图',
    'image-to-image': '图生图',
    video: '视频生成',
    image: '图片生成',
  };
  return labels[value || ''] || value || '-';
}

function formatAssetType(value?: string) {
  const labels: Record<string, string> = { image: '图片', video: '视频', audio: '音频' };
  return labels[(value || '').toLowerCase()] || value || '-';
}

function formatAssetSource(value?: string) {
  const labels: Record<string, string> = {
    uploaded: '用户上传',
    upload: '用户上传',
    generated: 'AI 生成',
    'ai-generated': 'AI 生成',
    favorite: '收藏素材',
  };
  return labels[(value || '').toLowerCase()] || value || '-';
}

function formatAuditAction(value?: string) {
  const labels: Record<string, string> = {
    CONSOLE_USER_UPDATE: '修改用户状态或权限',
    CONSOLE_USER_PASSWORD_RESET: '重置用户密码',
    CONSOLE_CREDITS_ADJUST: '调整用户积分',
    CONSOLE_JOB_STATUS: '修改任务状态',
    CONSOLE_ASSET_DELETE: '删除素材资产',
    CONSOLE_ASSET_RESTORE: '恢复素材资产',
    CONSOLE_ADMIN_CREATE: '新增后台账号',
    CONSOLE_ADMIN_UPDATE: '修改后台账号',
    CONSOLE_ADMIN_PASSWORD_RESET: '重置后台密码',
    CONSOLE_ADMIN_DELETE: '删除后台账号',
  };
  return labels[value || ''] || value || '-';
}

function formatOrderStatus(value?: string) {
  const labels: Record<string, string> = { PAID: '已支付', paid: '已支付', PENDING: '待支付', pending: '待支付', EXPIRED: '已过期', expired: '已过期', CANCELLED: '已取消', cancelled: '已取消' };
  return labels[value || ''] || value || '-';
}

function formatBillingType(value?: string) {
  const labels: Record<string, string> = { RECHARGE: '充值入账', CONSUME: '生成扣费', REFUND: '退款返还', GRANT: '后台赠送', EXPIRE: '积分过期' };
  return labels[value || ''] || value || '-';
}

function formatCreditsDelta(value?: number) {
  if (value === undefined || value === null) return '-';
  return value > 0 ? `+${value}` : String(value);
}

function formatAuditTarget(type?: string, id?: number) {
  const labels: Record<string, string> = { USER: '用户', JOB: '任务', ASSET: '素材', CONSOLE_ADMIN: '后台账号' };
  return `${labels[type || ''] || type || '对象'} #${id || '-'}`;
}

function formatAuditDetail(value?: string) {
  if (!value) return '-';
  let text = value;
  try {
    const parsed = JSON.parse(value) as { message?: string };
    text = parsed.message || value;
  } catch {
    text = value;
  }
  return text
    .replaceAll('role:', '权限:')
    .replaceAll('disabled:', '账号状态:')
    .replaceAll('monthlyQuota:', '月额度:')
    .replaceAll('plan:', '套餐:')
    .replaceAll('name:', '名称:')
    .replaceAll('delta:', '积分变化:')
    .replaceAll('balance:', '余额:')
    .replaceAll('->', ' 改为 ');
}

function formatSettingGroup(value?: string) {
  const labels: Record<string, string> = { AI: 'AI 服务', Storage: '素材存储', Workflow: '工作流引擎', Security: '安全配置' };
  return labels[value || ''] || value || '-';
}

function formatSettingKey(value?: string) {
  const labels: Record<string, string> = {
    'newapi.base-url': '图文模型接口',
    'newapi.video-base-url': '视频模型接口',
    'aicoming.proxy.base-url': '素材代理接口',
    'minio.endpoint': '对象存储地址',
    'comfyui.base-url': 'ComfyUI 工作流地址',
    tokens: '密钥状态',
  };
  return labels[value || ''] || value || '-';
}

function formatSettingNote(value?: string) {
  const labels: Record<string, string> = {
    'NewAPI chat and image endpoint': '用于对接聊天、图片等 AI 能力',
    'Video generation endpoint': '用于对接视频生成能力',
    'Asset proxy endpoint': '用于素材代理和上游资产访问',
    'Object storage endpoint': '用于保存用户上传和生成素材',
    'ComfyUI workflow endpoint': '用于执行工作流类生成任务',
    'Secret values are intentionally hidden': '密钥已隐藏，避免后台泄露',
  };
  return labels[value || ''] || value || '-';
}

function formatSettingValue(value?: string) {
  return value === '******' ? '已隐藏' : value || '-';
}

function formatBytes(value?: number) {
  if (!value) return '-';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(value?: string) {
  if (!value) return '-';
  return value.replace('T', ' ').slice(0, 19);
}
