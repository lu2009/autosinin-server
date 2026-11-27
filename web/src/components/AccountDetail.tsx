import { useState } from 'react';
import {
  Activity,
  CheckCircle2,
  Clock,
  Loader2,
  Menu,
  Play,
  RefreshCw,
  ShieldAlert,
  Sparkles,
  Terminal,
  Trash2,
  XCircle,
} from 'lucide-react';
import {
  AccountDto,
  LedgerEntryDto,
  SignHealthDto,
  StatusTaskDto,
  TaskMetaDto,
  TaskRunDto,
  qqAvatarUrl,
} from '../api';
import { ActivityRing } from './ActivityRing';

interface AccountDetailProps {
  account: AccountDto;
  tasks: TaskMetaDto[];
  ledger: LedgerEntryDto[];
  remoteStatus: StatusTaskDto[] | undefined;
  remoteError: Error | null;
  runs: TaskRunDto[];
  logs: string[];
  signHealth?: SignHealthDto;
  runningKey: string | null;
  reconciling: boolean;
  onRunAll: (force: boolean) => void;
  onRunSingle: (taskKey: string) => void;
  onReconcile: () => void;
  onRelogin: (uin: string) => void;
  onDelete: (uin: string) => void;
  onRefreshRemote: () => void;
  onOpenMobileSidebar: () => void;
}

type TabKey = 'overview' | 'remote' | 'activity';

function stateBadge(state?: string) {
  switch (state) {
    case 'DONE':
      return { label: '已完成', bg: 'rgba(52, 199, 89, 0.14)', color: 'var(--apple-green)', Icon: CheckCircle2 };
    case 'PENDING':
      return { label: '待对账', bg: 'rgba(255, 149, 0, 0.14)', color: 'var(--apple-orange)', Icon: Clock };
    case 'FAILED':
      return { label: '失败', bg: 'rgba(255, 59, 48, 0.14)', color: 'var(--apple-red)', Icon: XCircle };
    default:
      return { label: '未执行', bg: 'var(--apple-surface-secondary)', color: 'var(--apple-text-tertiary)', Icon: Sparkles };
  }
}

export function AccountDetail({
  account,
  tasks,
  ledger,
  remoteStatus,
  remoteError,
  runs,
  logs,
  signHealth,
  runningKey,
  reconciling,
  onRunAll,
  onRunSingle,
  onReconcile,
  onRelogin,
  onDelete,
  onRefreshRemote,
  onOpenMobileSidebar,
}: AccountDetailProps) {
  const [tab, setTab] = useState<TabKey>('overview');
  const [remoteFilter, setRemoteFilter] = useState<'all' | 'undone' | 'vip'>('all');
  const [logFilter, setLogFilter] = useState('');

  const ledgerByKey = new Map(ledger.map((e) => [e.taskKey, e]));
  const categories = Array.from(new Set(tasks.map((t) => t.category)));
  const lastRun = runs[0];

  const filteredRemote = (remoteStatus ?? []).filter((t) => {
    if (remoteFilter === 'undone') return !t.done;
    if (remoteFilter === 'vip') return t.vipTask;
    return true;
  });

  const filteredLogs = logs.filter((line) =>
    logFilter.trim() ? line.toLowerCase().includes(logFilter.trim().toLowerCase()) : true,
  );

  return (
    <div className="flex h-full flex-1 flex-col overflow-hidden">
      {/* Sticky Vibrancy Header */}
      <header
        className="apple-vibrancy sticky top-0 z-20 border-b px-5 py-4 lg:px-8"
        style={{
          backgroundColor: 'var(--apple-header)',
          borderColor: 'var(--apple-separator)',
        }}
      >
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3.5">
            <button
              onClick={onOpenMobileSidebar}
              className="rounded-xl p-2 lg:hidden"
              style={{ backgroundColor: 'var(--apple-surface-secondary)' }}
              aria-label="打开账号列表"
            >
              <Menu className="h-4 w-4" />
            </button>
            <img
              src={qqAvatarUrl(account.uin)}
              alt={account.uin}
              className="h-11 w-11 rounded-full object-cover shadow-xs"
            />
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-[20px] font-semibold tracking-tight">
                  {account.nickname && account.nickname !== account.uin ? account.nickname : account.uin}
                </h1>
                <span
                  className="rounded-full px-2.5 py-0.5 text-[11px] font-medium"
                  style={{
                    backgroundColor: account.online ? 'rgba(52, 199, 89, 0.14)' : 'var(--apple-surface-secondary)',
                    color: account.online ? 'var(--apple-green)' : 'var(--apple-text-secondary)',
                  }}
                >
                  {account.online ? 'Android Pad · 在线' : '离线'}
                </span>
              </div>
              <div className="tabular-nums text-[12px]" style={{ color: 'var(--apple-text-secondary)' }}>
                QQ {account.uin} · 设备 24091RPADC (HyperOS 4)
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={() => onRunAll(false)}
              disabled={runningKey !== null}
              className="flex items-center gap-1.5 rounded-full px-4 py-2 text-[13px] font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
              style={{ backgroundColor: 'var(--apple-blue)' }}
            >
              {runningKey === '__all__' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />}
              运行全部
            </button>
            <button
              onClick={onReconcile}
              disabled={reconciling}
              className="flex items-center gap-1.5 rounded-full px-3.5 py-2 text-[13px] font-medium transition-opacity hover:opacity-80 disabled:opacity-50"
              style={{
                backgroundColor: 'var(--apple-surface)',
                border: '0.5px solid var(--apple-border)',
              }}
            >
              <RefreshCw className={`h-3.5 w-3.5 ${reconciling ? 'animate-spin' : ''}`} />
              0x9172 对账
            </button>
            <button
              onClick={() => onRelogin(account.uin)}
              className="rounded-full px-3.5 py-2 text-[13px] font-medium transition-opacity hover:opacity-80"
              style={{
                backgroundColor: 'var(--apple-surface)',
                border: '0.5px solid var(--apple-border)',
              }}
            >
              重新登录
            </button>
            <button
              onClick={() => onDelete(account.uin)}
              className="rounded-full p-2 transition-opacity hover:opacity-80"
              style={{
                backgroundColor: 'var(--apple-surface)',
                border: '0.5px solid var(--apple-border)',
                color: 'var(--apple-red)',
              }}
              title="删除账号"
              aria-label="删除账号"
            >
              <Trash2 className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* Apple Segmented Control */}
        <div className="mt-4 inline-grid grid-cols-3 rounded-xl p-1" style={{ backgroundColor: 'var(--apple-surface-secondary)' }}>
          {(
            [
              ['overview', '今日任务'],
              ['remote', '0x9172 远端'],
              ['activity', '活动与日志'],
            ] as const
          ).map(([key, label]) => (
            <button
              key={key}
              onClick={() => setTab(key)}
              className="rounded-lg px-4 py-1.5 text-[13px] font-medium transition-all"
              style={{
                backgroundColor: tab === key ? 'var(--apple-surface)' : 'transparent',
                boxShadow: tab === key ? '0 1px 3px rgba(0,0,0,0.08)' : 'none',
              }}
            >
              {label}
            </button>
          ))}
        </div>
      </header>

      {/* Scrollable Body */}
      <main className="flex-1 overflow-y-auto px-5 py-6 lg:px-8">
        {tab === 'overview' && (
          <div className="mx-auto max-w-5xl space-y-6">
            {/* Bento Overview Cards */}
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <div className="apple-card flex items-center justify-between p-4">
                <div>
                  <div className="text-[12px] font-medium" style={{ color: 'var(--apple-text-secondary)' }}>
                    今日任务进度
                  </div>
                  <div className="tabular-nums mt-1 text-[24px] font-semibold">
                    {account.doneCount}
                    <span className="text-[14px] font-normal" style={{ color: 'var(--apple-text-tertiary)' }}>
                      {' '}/ {account.totalCount}
                    </span>
                  </div>
                </div>
                <ActivityRing done={account.doneCount} total={account.totalCount} size={46} strokeWidth={5} />
              </div>

              <div className="apple-card p-4">
                <div className="text-[12px] font-medium" style={{ color: 'var(--apple-text-secondary)' }}>
                  签名网关
                </div>
                <div className="mt-1 flex items-center gap-2 text-[17px] font-semibold">
                  <span
                    className="h-2.5 w-2.5 rounded-full"
                    style={{ backgroundColor: signHealth?.healthy ? 'var(--apple-green)' : 'var(--apple-red)' }}
                  />
                  {signHealth?.healthy ? 'Unidbg 正常' : '异常'}
                </div>
                <div className="truncate pt-0.5 text-[11px]" style={{ color: 'var(--apple-text-tertiary)' }}>
                  {signHealth?.url || '未配置'}
                </div>
              </div>

              <div className="apple-card p-4">
                <div className="text-[12px] font-medium" style={{ color: 'var(--apple-text-secondary)' }}>
                  风控健康度
                </div>
                <div
                  className="mt-1 flex items-center gap-1.5 text-[17px] font-semibold"
                  style={{ color: account.riskRestricted ? 'var(--apple-red)' : 'var(--apple-green)' }}
                >
                  {account.riskRestricted ? (
                    <>
                      <ShieldAlert className="h-4 w-4" />
                      写操作受限 (-10201)
                    </>
                  ) : (
                    <>
                      <CheckCircle2 className="h-4 w-4" />
                      状态正常
                    </>
                  )}
                </div>
                <div className="pt-0.5 text-[11px]" style={{ color: 'var(--apple-text-tertiary)' }}>
                  {account.riskRestricted ? '建议暂缓写操作，等待冷却' : '未检测到写封禁错误'}
                </div>
              </div>

              <div className="apple-card p-4">
                <div className="text-[12px] font-medium" style={{ color: 'var(--apple-text-secondary)' }}>
                  最近执行
                </div>
                <div className="tabular-nums mt-1 text-[17px] font-semibold">
                  {lastRun ? `${lastRun.durationMs} ms` : '暂无记录'}
                </div>
                <div className="truncate pt-0.5 text-[11px]" style={{ color: 'var(--apple-text-tertiary)' }}>
                  {lastRun ? `${lastRun.taskKey} · ${new Date(lastRun.createdAt).toLocaleTimeString()}` : '点击右上角运行'}
                </div>
              </div>
            </div>

            {/* Inset Grouped Task Lists */}
            {categories.map((cat) => {
              const items = tasks.filter((t) => t.category === cat);
              return (
                <section key={cat}>
                  <h2
                    className="mb-2 px-1 text-[13px] font-semibold"
                    style={{ color: 'var(--apple-text-secondary)' }}
                  >
                    {cat}
                  </h2>
                  <div className="apple-card divide-y overflow-hidden" style={{ borderColor: 'var(--apple-separator)' }}>
                    {items.map((task) => {
                      const entry = ledgerByKey.get(task.key);
                      const badge = stateBadge(entry?.state);
                      const BadgeIcon = badge.Icon;
                      const isRunning = runningKey === task.key || runningKey === '__all__';
                      return (
                        <div
                          key={task.key}
                          className="flex flex-wrap items-center justify-between gap-3 px-4 py-3.5"
                          style={{ borderColor: 'var(--apple-separator)' }}
                        >
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2.5">
                              <span className="text-[14px] font-medium">{task.title}</span>
                              <span
                                className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium"
                                style={{ backgroundColor: badge.bg, color: badge.color }}
                              >
                                <BadgeIcon className="h-3 w-3" />
                                {badge.label}
                              </span>
                              <span
                                className="tabular-nums text-[11px]"
                                style={{ color: 'var(--apple-text-tertiary)' }}
                              >
                                {task.key}
                              </span>
                            </div>
                            <div
                              className="mt-1 truncate text-[12px]"
                              style={{ color: 'var(--apple-text-secondary)' }}
                            >
                              {entry?.detail || '今日尚未执行'}
                            </div>
                          </div>

                          <button
                            onClick={() => onRunSingle(task.key)}
                            disabled={runningKey !== null}
                            className="flex shrink-0 items-center gap-1 rounded-full px-3 py-1.5 text-[12px] font-medium transition-opacity hover:opacity-80 disabled:opacity-50"
                            style={{
                              backgroundColor: 'var(--apple-surface-secondary)',
                              color: 'var(--apple-blue)',
                            }}
                          >
                            {isRunning ? <Loader2 className="h-3 w-3 animate-spin" /> : <Play className="h-3 w-3" />}
                            执行
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </section>
              );
            })}
          </div>
        )}

        {tab === 'remote' && (
          <div className="mx-auto max-w-5xl space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="inline-grid grid-cols-3 rounded-xl p-1" style={{ backgroundColor: 'var(--apple-surface-secondary)' }}>
                {(
                  [
                    ['all', '全部'],
                    ['undone', '未完成'],
                    ['vip', 'VIP 任务'],
                  ] as const
                ).map(([k, label]) => (
                  <button
                    key={k}
                    onClick={() => setRemoteFilter(k)}
                    className="rounded-lg px-3.5 py-1 text-[12px] font-medium"
                    style={{
                      backgroundColor: remoteFilter === k ? 'var(--apple-surface)' : 'transparent',
                      boxShadow: remoteFilter === k ? '0 1px 2px rgba(0,0,0,0.06)' : 'none',
                    }}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <button
                onClick={onRefreshRemote}
                className="flex items-center gap-1.5 rounded-full px-3.5 py-1.5 text-[12px] font-medium"
                style={{
                  backgroundColor: 'var(--apple-surface)',
                  border: '0.5px solid var(--apple-border)',
                }}
              >
                <RefreshCw className="h-3.5 w-3.5" />
                刷新 0x9172
              </button>
            </div>

            {remoteError ? (
              <div className="apple-card p-5 text-[13px]" style={{ color: 'var(--apple-red)' }}>
                无法获取 0x9172 远端状态：{remoteError.message}
              </div>
            ) : (
              <div className="apple-card divide-y overflow-hidden" style={{ borderColor: 'var(--apple-separator)' }}>
                {filteredRemote.length === 0 ? (
                  <div className="p-6 text-center text-[13px]" style={{ color: 'var(--apple-text-tertiary)' }}>
                    暂无匹配任务
                  </div>
                ) : (
                  filteredRemote.map((item) => (
                    <div
                      key={item.id}
                      className="flex items-center justify-between px-4 py-3"
                      style={{ borderColor: 'var(--apple-separator)' }}
                    >
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="text-[14px] font-medium">{item.title || `任务 #${item.id}`}</span>
                          {item.vipTask && (
                            <span
                              className="rounded-full px-2 py-0.5 text-[10px] font-semibold"
                              style={{ backgroundColor: 'rgba(255, 149, 0, 0.14)', color: 'var(--apple-orange)' }}
                            >
                              VIP
                            </span>
                          )}
                        </div>
                        <div className="tabular-nums text-[11px]" style={{ color: 'var(--apple-text-tertiary)' }}>
                          ID: {item.id} · status={item.status}
                        </div>
                      </div>
                      <span
                        className="rounded-full px-2.5 py-0.5 text-[11px] font-medium"
                        style={{
                          backgroundColor: item.done ? 'rgba(52, 199, 89, 0.14)' : 'var(--apple-surface-secondary)',
                          color: item.done ? 'var(--apple-green)' : 'var(--apple-text-secondary)',
                        }}
                      >
                        {item.done ? '已完成' : '未完成'}
                      </span>
                    </div>
                  ))
                )}
              </div>
            )}
          </div>
        )}

        {tab === 'activity' && (
          <div className="mx-auto max-w-5xl space-y-6">
            <section>
              <div className="mb-2 flex items-center gap-2 px-1 text-[13px] font-semibold" style={{ color: 'var(--apple-text-secondary)' }}>
                <Activity className="h-4 w-4" />
                近期执行记录
              </div>
              <div className="apple-card divide-y overflow-hidden" style={{ borderColor: 'var(--apple-separator)' }}>
                {runs.length === 0 ? (
                  <div className="p-6 text-center text-[13px]" style={{ color: 'var(--apple-text-tertiary)' }}>
                    暂无执行记录
                  </div>
                ) : (
                  runs.map((r) => (
                    <div
                      key={r.id}
                      className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-[13px]"
                      style={{ borderColor: 'var(--apple-separator)' }}
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{r.taskKey}</span>
                          <span
                            className="rounded-full px-2 py-0.5 text-[10px] font-semibold"
                            style={{
                              backgroundColor: r.ok
                                ? 'rgba(52, 199, 89, 0.14)'
                                : r.pending
                                  ? 'rgba(255, 149, 0, 0.14)'
                                  : 'rgba(255, 59, 48, 0.14)',
                              color: r.ok
                                ? 'var(--apple-green)'
                                : r.pending
                                  ? 'var(--apple-orange)'
                                  : 'var(--apple-red)',
                            }}
                          >
                            {r.ok ? 'OK' : r.pending ? 'PENDING' : 'FAILED'}
                          </span>
                        </div>
                        <div className="truncate text-[12px]" style={{ color: 'var(--apple-text-secondary)' }}>
                          {r.detail}
                        </div>
                      </div>
                      <div className="tabular-nums text-right text-[11px]" style={{ color: 'var(--apple-text-tertiary)' }}>
                        <div>{r.durationMs} ms</div>
                        <div>{new Date(r.createdAt).toLocaleTimeString()}</div>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </section>

            <section>
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2 px-1">
                <div className="flex items-center gap-2 text-[13px] font-semibold" style={{ color: 'var(--apple-text-secondary)' }}>
                  <Terminal className="h-4 w-4" />
                  服务实时日志
                </div>
                <input
                  type="text"
                  value={logFilter}
                  onChange={(e) => setLogFilter(e.target.value)}
                  placeholder="过滤日志关键词..."
                  className="rounded-full px-3 py-1 text-[12px] outline-none"
                  style={{
                    backgroundColor: 'var(--apple-surface)',
                    border: '0.5px solid var(--apple-border)',
                  }}
                />
              </div>
              <pre
                className="apple-card max-h-[340px] overflow-auto p-4 font-mono text-[11.5px] leading-relaxed"
                style={{ color: 'var(--apple-text-secondary)' }}
              >
                {filteredLogs.length > 0 ? filteredLogs.join('\n') : '暂无匹配日志'}
              </pre>
            </section>
          </div>
        )}
      </main>
    </div>
  );
}
