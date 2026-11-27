import { Plus, ShieldAlert, Server, X } from 'lucide-react';
import { AccountDto, HealthDto, SignHealthDto, qqAvatarUrl } from '../api';
import { ActivityRing } from './ActivityRing';

interface SidebarProps {
  accounts: AccountDto[];
  selectedUin: string | null;
  onSelect: (uin: string) => void;
  onAddAccount: () => void;
  health?: HealthDto;
  signHealth?: SignHealthDto;
  mobileOpen?: boolean;
  onCloseMobile?: () => void;
}

export function Sidebar({
  accounts,
  selectedUin,
  onSelect,
  onAddAccount,
  health,
  signHealth,
  mobileOpen = false,
  onCloseMobile,
}: SidebarProps) {
  const signOk = Boolean(signHealth?.configured && signHealth?.healthy);

  const content = (
    <aside
      className="apple-vibrancy flex h-full w-[276px] flex-col border-r"
      style={{
        backgroundColor: 'var(--apple-sidebar)',
        borderColor: 'var(--apple-separator)',
      }}
    >
      {/* Top App Title & Status Pill */}
      <div className="flex items-center justify-between px-4 pt-5 pb-3">
        <div>
          <div className="text-[17px] font-semibold tracking-tight">AutoSinin</div>
          <div className="text-[11px] font-medium" style={{ color: 'var(--apple-text-tertiary)' }}>
            {health?.day ?? '加载中'} · {accounts.length} 个账号
          </div>
        </div>
        {onCloseMobile && (
          <button
            onClick={onCloseMobile}
            className="rounded-full p-1.5 lg:hidden"
            style={{ backgroundColor: 'var(--apple-surface-secondary)' }}
            aria-label="关闭侧边栏"
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </div>

      {/* Sign Server Status Pill */}
      <div className="px-4 pb-3">
        <div
          className="flex items-center gap-2 rounded-xl px-3 py-2 text-[12px]"
          style={{
            backgroundColor: 'var(--apple-surface-secondary)',
            border: '0.5px solid var(--apple-border)',
          }}
        >
          <span
            className="h-2 w-2 shrink-0 rounded-full"
            style={{ backgroundColor: signOk ? 'var(--apple-green)' : 'var(--apple-red)' }}
          />
          <Server className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--apple-text-secondary)' }} />
          <span className="truncate font-medium">
            {signHealth ? (signOk ? '签名服务正常' : '签名服务离线') : '检测签名服务...'}
          </span>
        </div>
      </div>

      {/* Account List */}
      <div className="px-4 pb-1.5 text-[11px] font-semibold tracking-wide uppercase" style={{ color: 'var(--apple-text-tertiary)' }}>
        托管账号
      </div>
      <div className="flex-1 space-y-1 overflow-y-auto px-2.5 pb-3">
        {accounts.length === 0 ? (
          <div
            className="mx-1.5 rounded-xl px-3 py-6 text-center text-[13px]"
            style={{ color: 'var(--apple-text-tertiary)' }}
          >
            暂无账号，点击下方添加
          </div>
        ) : (
          accounts.map((acc) => {
            const active = acc.uin === selectedUin;
            return (
              <button
                key={acc.uin}
                onClick={() => {
                  onSelect(acc.uin);
                  onCloseMobile?.();
                }}
                className="flex w-full items-center gap-3 rounded-xl px-2.5 py-2 text-left transition-colors"
                style={{
                  backgroundColor: active ? 'var(--apple-blue)' : 'transparent',
                  color: active ? '#ffffff' : 'var(--apple-text)',
                }}
              >
                <div className="relative shrink-0">
                  <img
                    src={qqAvatarUrl(acc.uin)}
                    alt={acc.uin}
                    className="h-9 w-9 rounded-full object-cover"
                    style={{ backgroundColor: 'var(--apple-surface-secondary)' }}
                  />
                  <span
                    className="absolute -right-0.5 -bottom-0.5 h-2.5 w-2.5 rounded-full ring-2"
                    style={{
                      backgroundColor: acc.online ? 'var(--apple-green)' : 'var(--apple-text-tertiary)',
                      // @ts-expect-error Tailwind ring color var
                      '--tw-ring-color': active ? 'var(--apple-blue)' : 'var(--apple-surface)',
                    }}
                  />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <span className="truncate text-[14px] font-medium">
                      {acc.nickname && acc.nickname !== acc.uin ? acc.nickname : acc.uin}
                    </span>
                    {acc.riskRestricted && (
                      <ShieldAlert
                        className="h-3.5 w-3.5 shrink-0"
                        style={{ color: active ? '#ffffff' : 'var(--apple-red)' }}
                      />
                    )}
                  </div>
                  <div
                    className="tabular-nums truncate text-[11px]"
                    style={{ color: active ? 'rgba(255,255,255,0.82)' : 'var(--apple-text-tertiary)' }}
                  >
                    {acc.uin} · {acc.online ? '在线' : '离线'}
                  </div>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  <span
                    className="tabular-nums text-[11px] font-medium"
                    style={{ color: active ? 'rgba(255,255,255,0.9)' : 'var(--apple-text-secondary)' }}
                  >
                    {acc.doneCount}/{acc.totalCount}
                  </span>
                  <ActivityRing
                    done={acc.doneCount}
                    total={acc.totalCount}
                    size={20}
                    strokeWidth={2.5}
                    color={active ? '#ffffff' : 'var(--apple-green)'}
                  />
                </div>
              </button>
            );
          })
        )}
      </div>

      {/* Bottom Action */}
      <div className="border-t p-3" style={{ borderColor: 'var(--apple-separator)' }}>
        <button
          onClick={onAddAccount}
          className="flex w-full items-center justify-center gap-1.5 rounded-xl py-2.5 text-[14px] font-semibold text-white transition-opacity hover:opacity-90 active:scale-[0.99]"
          style={{ backgroundColor: 'var(--apple-blue)' }}
        >
          <Plus className="h-4 w-4" />
          添加 / 登录账号
        </button>
      </div>
    </aside>
  );

  return (
    <>
      <div className="hidden h-full lg:block">{content}</div>
      {mobileOpen && (
        <div className="fixed inset-0 z-40 flex lg:hidden">
          <div className="fixed inset-0 bg-black/35 backdrop-blur-xs" onClick={onCloseMobile} />
          <div className="relative z-10 h-full">{content}</div>
        </div>
      )}
    </>
  );
}
