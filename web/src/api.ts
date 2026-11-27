export interface HealthDto {
  ok: boolean;
  day: string;
  accounts: number;
  transportType: string;
}

export interface SignHealthDto {
  configured: boolean;
  url: string;
  healthy: boolean;
  detail: string;
}

export interface AccountDto {
  uin: string;
  nickname: string;
  enabled: boolean;
  qqVersion: string;
  updatedAt: number;
  online: boolean;
  doneCount: number;
  pendingCount: number;
  failedCount: number;
  totalCount: number;
  riskRestricted: boolean;
}

export interface TaskMetaDto {
  key: string;
  title: string;
  category: string;
  remoteTaskId?: number | null;
}

export interface LedgerEntryDto {
  uin: string;
  day: string;
  taskKey: string;
  state: 'DONE' | 'PENDING' | 'FAILED' | 'SKIPPED' | string;
  detail: string;
  attempts: number;
  updatedAt: number;
}

export interface StatusTaskDto {
  id: number;
  title: string;
  status: number;
  done: boolean;
  vipTask: boolean;
}

export interface TaskRunDto {
  id: number;
  uin: string;
  day: string;
  taskKey: string;
  ok: boolean;
  pending: boolean;
  detail: string;
  durationMs: number;
  createdAt: number;
}

export interface TaskExecutionResult {
  uin: string;
  taskKey: string;
  ok: boolean;
  skipped: boolean;
  pending: boolean;
  detail: string;
  durationMs: number;
}

export interface QrLoginStateDto {
  qrId: string;
  status: string;
  url: string;
  pngBase64: string;
  uin: string;
  error: string;
  updatedAt: number;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init);
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!res.ok) {
    const msg =
      typeof data === 'object' && data && 'error' in data
        ? String((data as { error: unknown }).error)
        : `HTTP ${res.status}`;
    throw new Error(msg);
  }
  return data as T;
}

export const api = {
  health: () => request<HealthDto>('/health'),
  signHealth: () => request<SignHealthDto>('/api/sign/health'),
  tasks: () => request<TaskMetaDto[]>('/api/tasks'),
  accounts: () => request<AccountDto[]>('/api/accounts'),
  deleteAccount: (uin: string) =>
    request<{ deleted: boolean; uin: string }>(`/api/accounts/${encodeURIComponent(uin)}`, {
      method: 'DELETE',
    }),
  ledger: (uin: string) => request<LedgerEntryDto[]>(`/api/accounts/${encodeURIComponent(uin)}/ledger`),
  remoteStatus: (uin: string) => request<StatusTaskDto[]>(`/api/accounts/${encodeURIComponent(uin)}/status`),
  runs: (uin?: string, limit = 40) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (uin) params.set('uin', uin);
    return request<TaskRunDto[]>(`/api/runs?${params.toString()}`);
  },
  logs: (limit = 120) => request<string[]>(`/api/logs?limit=${limit}`),
  runTasks: (uin: string, tasks: string[] = [], force = false) =>
    request<TaskExecutionResult[]>(`/api/accounts/${encodeURIComponent(uin)}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tasks, force }),
    }),
  reconcile: (uin: string) =>
    request<TaskExecutionResult[]>(`/api/accounts/${encodeURIComponent(uin)}/reconcile`, {
      method: 'POST',
    }),
  startQrLogin: () => request<QrLoginStateDto>('/api/accounts/qr/start', { method: 'POST' }),
  startAndroidLogin: (uin: string, password: string) =>
    request<QrLoginStateDto>('/api/accounts/android/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ uin, password }),
    }),
  submitAndroidVerify: (loginId: string, code: string) =>
    request<{ ok: boolean }>('/api/accounts/android/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ loginId, code }),
    }),
  pollLoginState: (loginId: string) =>
    request<QrLoginStateDto>(`/api/accounts/qr/${encodeURIComponent(loginId)}`),
};

export function qqAvatarUrl(uin: string): string {
  return `https://q1.qlogo.cn/g?b=qq&nk=${encodeURIComponent(uin)}&s=100`;
}
