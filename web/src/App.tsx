import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Smartphone } from 'lucide-react';
import { api } from './api';
import { Sidebar } from './components/Sidebar';
import { AccountDetail } from './components/AccountDetail';
import { LoginSheet } from './components/LoginSheet';

export default function App() {
  const queryClient = useQueryClient();
  const [selectedUin, setSelectedUin] = useState<string | null>(null);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginInitialUin, setLoginInitialUin] = useState('');
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [runningKey, setRunningKey] = useState<string | null>(null);
  const [reconciling, setReconciling] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast((curr) => (curr === msg ? null : curr)), 3200);
  };

  const healthQuery = useQuery({ queryKey: ['health'], queryFn: api.health, refetchInterval: 15000 });
  const signHealthQuery = useQuery({ queryKey: ['signHealth'], queryFn: api.signHealth, refetchInterval: 15000 });
  const tasksQuery = useQuery({ queryKey: ['tasks'], queryFn: api.tasks });
  const accountsQuery = useQuery({ queryKey: ['accounts'], queryFn: api.accounts, refetchInterval: 8000 });
  const schedulerQuery = useQuery({
    queryKey: ['scheduler'],
    queryFn: api.scheduler,
    refetchInterval: 30000,
  });

  const accounts = accountsQuery.data ?? [];
  useEffect(() => {
    if (!selectedUin && accounts.length > 0) {
      setSelectedUin(accounts[0].uin);
    } else if (selectedUin && accounts.length > 0 && !accounts.some((a) => a.uin === selectedUin)) {
      setSelectedUin(accounts[0].uin);
    }
  }, [accounts, selectedUin]);

  const activeAccount = accounts.find((a) => a.uin === selectedUin) ?? null;

  const ledgerQuery = useQuery({
    queryKey: ['ledger', selectedUin],
    queryFn: () => api.ledger(selectedUin!),
    enabled: Boolean(selectedUin),
    refetchInterval: 10000,
  });

  const remoteStatusQuery = useQuery({
    queryKey: ['remoteStatus', selectedUin],
    queryFn: () => api.remoteStatus(selectedUin!),
    enabled: Boolean(selectedUin && activeAccount?.online),
    retry: false,
  });

  const runsQuery = useQuery({
    queryKey: ['runs', selectedUin],
    queryFn: () => api.runs(selectedUin!, 40),
    enabled: Boolean(selectedUin),
    refetchInterval: 10000,
  });

  const logsQuery = useQuery({
    queryKey: ['logs'],
    queryFn: () => api.logs(120),
    refetchInterval: 8000,
  });

  const refreshAccountData = async (uin: string) => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['accounts'] }),
      queryClient.invalidateQueries({ queryKey: ['ledger', uin] }),
      queryClient.invalidateQueries({ queryKey: ['runs', uin] }),
      queryClient.invalidateQueries({ queryKey: ['logs'] }),
    ]);
  };

  const handleRunAll = async (force: boolean) => {
    if (!selectedUin) return;
    setRunningKey('__all__');
    try {
      const results = await api.runTasks(selectedUin, [], force);
      const okCount = results.filter((r) => r.ok).length;
      showToast(`已执行 ${results.length} 项任务，成功 ${okCount} 项`);
      await refreshAccountData(selectedUin);
    } catch (err) {
      showToast(err instanceof Error ? err.message : '执行失败');
    } finally {
      setRunningKey(null);
    }
  };

  const handleRunSingle = async (taskKey: string) => {
    if (!selectedUin) return;
    setRunningKey(taskKey);
    try {
      const results = await api.runTasks(selectedUin, [taskKey], true);
      const first = results[0];
      showToast(first ? `${taskKey}: ${first.detail}` : `${taskKey} 已执行`);
      await refreshAccountData(selectedUin);
    } catch (err) {
      showToast(err instanceof Error ? err.message : '执行失败');
    } finally {
      setRunningKey(null);
    }
  };

  const handleReconcile = async () => {
    if (!selectedUin) return;
    setReconciling(true);
    try {
      const promoted = await api.reconcile(selectedUin);
      showToast(`0x9172 对账完成，转正 ${promoted.length} 项`);
      await refreshAccountData(selectedUin);
    } catch (err) {
      showToast(err instanceof Error ? err.message : '对账失败');
    } finally {
      setReconciling(false);
    }
  };

  const handleDelete = async (uin: string) => {
    if (!window.confirm(`确定要从控制台移除账号 ${uin} 吗？`)) return;
    try {
      await api.deleteAccount(uin);
      showToast(`已移除账号 ${uin}`);
      await queryClient.invalidateQueries({ queryKey: ['accounts'] });
    } catch (err) {
      showToast(err instanceof Error ? err.message : '删除失败');
    }
  };

  return (
    <div className="flex h-screen w-screen overflow-hidden">
      <Sidebar
        accounts={accounts}
        scheduler={schedulerQuery.data}
        onSaveSchedule={async (start, end) => {
          await api.setSchedule(start, end);
          await schedulerQuery.refetch();
          showToast(`每日调度已设为 ${start} – ${end}`);
        }}
        selectedUin={selectedUin}
        onSelect={setSelectedUin}
        onAddAccount={() => {
          setLoginInitialUin('');
          setLoginOpen(true);
        }}
        health={healthQuery.data}
        signHealth={signHealthQuery.data}
        mobileOpen={mobileSidebarOpen}
        onCloseMobile={() => setMobileSidebarOpen(false)}
      />

      {activeAccount ? (
        <AccountDetail
          account={activeAccount}
          tasks={tasksQuery.data ?? []}
          ledger={ledgerQuery.data ?? []}
          remoteStatus={remoteStatusQuery.data}
          remoteError={remoteStatusQuery.error instanceof Error ? remoteStatusQuery.error : null}
          runs={runsQuery.data ?? []}
          logs={logsQuery.data ?? []}
          signHealth={signHealthQuery.data}
          runningKey={runningKey}
          reconciling={reconciling}
          onRunAll={handleRunAll}
          onRunSingle={handleRunSingle}
          onReconcile={handleReconcile}
          onRelogin={(uin) => {
            setLoginInitialUin(uin);
            setLoginOpen(true);
          }}
          onDelete={handleDelete}
          onRefreshRemote={() => remoteStatusQuery.refetch()}
          onOpenMobileSidebar={() => setMobileSidebarOpen(true)}
        />
      ) : (
        <div className="flex flex-1 flex-col items-center justify-center p-8 text-center">
          <div
            className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl"
            style={{ backgroundColor: 'var(--apple-surface)' }}
          >
            <Smartphone className="h-8 w-8" style={{ color: 'var(--apple-blue)' }} />
          </div>
          <h2 className="text-[20px] font-semibold">欢迎使用 AutoSinin</h2>
          <p className="mt-1 max-w-sm text-[13px]" style={{ color: 'var(--apple-text-secondary)' }}>
            添加并登录一个 QQ 账号后，即可在这里查看每日任务进度、0x9172 远端状态与执行日志。
          </p>
          <button
            onClick={() => {
              setLoginInitialUin('');
              setLoginOpen(true);
            }}
            className="mt-5 flex items-center gap-1.5 rounded-full px-5 py-2.5 text-[14px] font-semibold text-white"
            style={{ backgroundColor: 'var(--apple-blue)' }}
          >
            <Plus className="h-4 w-4" />
            添加 / 登录账号
          </button>
        </div>
      )}

      <LoginSheet
        open={loginOpen}
        initialUin={loginInitialUin}
        onClose={() => setLoginOpen(false)}
        onSuccess={(uin) => {
          setSelectedUin(uin);
          queryClient.invalidateQueries({ queryKey: ['accounts'] });
          showToast(`账号 ${uin} 已上线`);
        }}
      />

      {toast && (
        <div
          className="apple-vibrancy animate-sheet-in fixed bottom-5 right-5 z-50 rounded-full px-4 py-2 text-[13px] font-medium shadow-lg"
          style={{
            backgroundColor: 'var(--apple-surface)',
            border: '0.5px solid var(--apple-border)',
          }}
        >
          {toast}
        </div>
      )}
    </div>
  );
}
