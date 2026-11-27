import { useEffect, useRef, useState } from 'react';
import { CheckCircle2, Loader2, QrCode, ShieldCheck, Smartphone, X } from 'lucide-react';
import { api, QrLoginStateDto } from '../api';

declare global {
  interface Window {
    TencentCaptcha?: new (
      appIdOrEl: string,
      callback: (res: { ret: number; ticket?: string }) => void,
      options?: Record<string, unknown>,
    ) => { show: () => void };
  }
}

interface LoginSheetProps {
  open: boolean;
  initialUin?: string;
  onClose: () => void;
  onSuccess: (uin: string) => void;
}

export function LoginSheet({ open, initialUin = '', onClose, onSuccess }: LoginSheetProps) {
  const [mode, setMode] = useState<'android' | 'qr'>('android');
  const [uin, setUin] = useState(initialUin);
  const [password, setPassword] = useState('');
  const [loginState, setLoginState] = useState<QrLoginStateDto | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [otpDigits, setOtpDigits] = useState<string[]>(['', '', '', '', '', '']);
  const otpRefs = useRef<Array<HTMLInputElement | null>>([]);
  const captchaTriggeredRef = useRef<string>('');

  useEffect(() => {
    if (open) {
      setUin(initialUin);
      setPassword('');
      setLoginState(null);
      setError('');
      setOtpDigits(['', '', '', '', '', '']);
      captchaTriggeredRef.current = '';
    }
  }, [open, initialUin]);

  // Poll active login / QR state
  useEffect(() => {
    if (!open || !loginState?.qrId) return;
    if (loginState.status === 'LOGGED_IN') {
      const timer = setTimeout(() => {
        onSuccess(loginState.uin || uin);
        onClose();
      }, 650);
      return () => clearTimeout(timer);
    }
    if (loginState.status === 'ERROR') return;

    const interval = setInterval(async () => {
      try {
        const next = await api.pollLoginState(loginState.qrId);
        setLoginState(next);
      } catch {
        // ignore transient poll error
      }
    }, 1500);
    return () => clearInterval(interval);
  }, [open, loginState?.qrId, loginState?.status, loginState?.uin, uin, onSuccess, onClose]);

  // Auto-open TencentCaptcha when backend requests NEED_CAPTCHA
  useEffect(() => {
    if (!loginState || loginState.status !== 'NEED_CAPTCHA' || !loginState.url) return;
    if (captchaTriggeredRef.current === loginState.qrId) return;
    captchaTriggeredRef.current = loginState.qrId;

    const match = loginState.url.match(/[?&]aid=(\d+)/);
    const aid = match?.[1] || '16';
    // sid/uin 把滑块票据绑定到本次登录挑战，缺了腾讯会判无效（WtLogin 237）
    let sid = '';
    let uinParam = '0';
    try {
      const u = new URL(loginState.url);
      sid = u.searchParams.get('sid') || '';
      uinParam = u.searchParams.get('uin') || '0';
    } catch {
      // URL 解析失败时使用默认值
    }
    if (window.TencentCaptcha) {
      try {
        const cap = new window.TencentCaptcha(aid, async (res) => {
          if (res.ret === 0 && res.ticket) {
            try {
              await api.submitAndroidVerify(loginState.qrId, res.ticket);
              const next = await api.pollLoginState(loginState.qrId);
              setLoginState(next);
            } catch (e) {
              setError(e instanceof Error ? e.message : '提交滑块票据失败');
            }
          }
        }, { sid, uin: uinParam });
        cap.show();
      } catch {
        // user can still click link manually
      }
    }
  }, [loginState]);

  if (!open) return null;

  const handleAndroidLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!uin.trim() || !password) return;
    setSubmitting(true);
    setError('');
    captchaTriggeredRef.current = '';
    try {
      const res = await api.startAndroidLogin(uin.trim(), password);
      setLoginState(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : '发起登录失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleStartQr = async () => {
    setSubmitting(true);
    setError('');
    try {
      const res = await api.startQrLogin();
      setLoginState(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : '获取二维码失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleOtpChange = (index: number, val: string) => {
    const clean = val.replace(/\D/g, '');
    if (clean.length > 1) {
      const next = [...otpDigits];
      for (let i = 0; i < 6; i++) {
        next[i] = clean[i] ?? '';
      }
      setOtpDigits(next);
      const focusIdx = Math.min(5, clean.length);
      otpRefs.current[focusIdx]?.focus();
      return;
    }
    const next = [...otpDigits];
    next[index] = clean;
    setOtpDigits(next);
    if (clean && index < 5) {
      otpRefs.current[index + 1]?.focus();
    }
  };

  const handleOtpSubmit = async () => {
    if (!loginState?.qrId) return;
    const code = otpDigits.join('');
    if (code.length < 4) return;
    setSubmitting(true);
    setError('');
    try {
      await api.submitAndroidVerify(loginState.qrId, code);
      const next = await api.pollLoginState(loginState.qrId);
      setLoginState(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : '短信验证提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="fixed inset-0 bg-black/40 backdrop-blur-sm" onClick={onClose} />
      <div
        className="apple-card animate-sheet-in relative z-10 w-full max-w-[420px] overflow-hidden p-6"
        style={{ backgroundColor: 'var(--apple-surface)' }}
      >
        {/* Header */}
        <div className="mb-5 flex items-center justify-between">
          <div>
            <h2 className="text-[18px] font-semibold tracking-tight">添加或登录 QQ 账号</h2>
            <p className="text-[12px]" style={{ color: 'var(--apple-text-secondary)' }}>
              Android Pad 协议支持不挤掉手机端 QQ
            </p>
          </div>
          <button
            onClick={onClose}
            className="rounded-full p-1.5 transition-opacity hover:opacity-80"
            style={{ backgroundColor: 'var(--apple-surface-secondary)' }}
            aria-label="关闭"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Segmented Control */}
        <div
          className="mb-5 grid grid-cols-2 rounded-xl p-1"
          style={{ backgroundColor: 'var(--apple-surface-secondary)' }}
        >
          <button
            type="button"
            onClick={() => {
              setMode('android');
              setLoginState(null);
              setError('');
            }}
            className="flex items-center justify-center gap-1.5 rounded-lg py-1.5 text-[13px] font-medium transition-all"
            style={{
              backgroundColor: mode === 'android' ? 'var(--apple-surface)' : 'transparent',
              boxShadow: mode === 'android' ? '0 1px 3px rgba(0,0,0,0.08)' : 'none',
            }}
          >
            <Smartphone className="h-3.5 w-3.5" />
            Android Pad 登录
          </button>
          <button
            type="button"
            onClick={() => {
              setMode('qr');
              setLoginState(null);
              setError('');
            }}
            className="flex items-center justify-center gap-1.5 rounded-lg py-1.5 text-[13px] font-medium transition-all"
            style={{
              backgroundColor: mode === 'qr' ? 'var(--apple-surface)' : 'transparent',
              boxShadow: mode === 'qr' ? '0 1px 3px rgba(0,0,0,0.08)' : 'none',
            }}
          >
            <QrCode className="h-3.5 w-3.5" />
            扫码登录
          </button>
        </div>

        {/* Android Login Form */}
        {mode === 'android' && (
          <form onSubmit={handleAndroidLogin} className="space-y-3.5">
            <div>
              <label className="mb-1 block text-[12px] font-medium" style={{ color: 'var(--apple-text-secondary)' }}>
                QQ 号码
              </label>
              <input
                type="text"
                inputMode="numeric"
                value={uin}
                onChange={(e) => setUin(e.target.value)}
                placeholder="例如 3885997084"
                className="tabular-nums w-full rounded-xl px-3.5 py-2.5 text-[14px] outline-none"
                style={{
                  backgroundColor: 'var(--apple-surface-secondary)',
                  border: '0.5px solid var(--apple-border)',
                }}
              />
            </div>
            <div>
              <label className="mb-1 block text-[12px] font-medium" style={{ color: 'var(--apple-text-secondary)' }}>
                密码
              </label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="输入 QQ 密码"
                className="w-full rounded-xl px-3.5 py-2.5 text-[14px] outline-none"
                style={{
                  backgroundColor: 'var(--apple-surface-secondary)',
                  border: '0.5px solid var(--apple-border)',
                }}
              />
            </div>

            <button
              type="submit"
              disabled={submitting || !uin.trim() || !password}
              className="flex w-full items-center justify-center gap-2 rounded-xl py-2.5 text-[14px] font-semibold text-white transition-opacity disabled:opacity-50"
              style={{ backgroundColor: 'var(--apple-blue)' }}
            >
              {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
              发起登录
            </button>
          </form>
        )}

        {/* QR Login */}
        {mode === 'qr' && (
          <div className="flex flex-col items-center py-2 text-center">
            {!loginState?.pngBase64 ? (
              <button
                type="button"
                onClick={handleStartQr}
                disabled={submitting}
                className="flex items-center gap-2 rounded-xl px-5 py-2.5 text-[14px] font-semibold text-white"
                style={{ backgroundColor: 'var(--apple-blue)' }}
              >
                {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
                生成登录二维码
              </button>
            ) : (
              <div className="space-y-3">
                <img
                  src={`data:image/png;base64,${loginState.pngBase64}`}
                  alt="QQ 登录二维码"
                  className="mx-auto h-44 w-44 rounded-2xl border p-2 bg-white"
                  style={{ borderColor: 'var(--apple-border)' }}
                />
                <div className="text-[13px]" style={{ color: 'var(--apple-text-secondary)' }}>
                  请使用手机 QQ 扫描二维码确认
                </div>
              </div>
            )}
          </div>
        )}

        {/* Verification States */}
        {loginState && loginState.status === 'LOGGING_IN' && (
          <div className="mt-4 flex items-center gap-2 rounded-xl px-3.5 py-2.5 text-[13px]" style={{ backgroundColor: 'var(--apple-surface-secondary)' }}>
            <Loader2 className="h-4 w-4 animate-spin" style={{ color: 'var(--apple-blue)' }} />
            正在连接腾讯验证服务器...
          </div>
        )}

        {loginState && loginState.status === 'NEED_CAPTCHA' && (
          <div className="mt-4 space-y-2 rounded-xl px-3.5 py-3 text-[13px]" style={{ backgroundColor: 'var(--apple-surface-secondary)' }}>
            <div className="flex items-center gap-2 font-medium">
              <ShieldCheck className="h-4 w-4" style={{ color: 'var(--apple-orange)' }} />
              需要完成安全滑块验证
            </div>
            <p className="text-[12px]" style={{ color: 'var(--apple-text-secondary)' }}>
              已尝试自动唤起验证弹窗；如未弹出，请点击下方链接或手动提交 ticket。
            </p>
            {loginState.url && (
              <a
                href={loginState.url}
                target="_blank"
                rel="noreferrer"
                className="inline-block text-[12px] font-medium underline"
                style={{ color: 'var(--apple-blue)' }}
              >
                打开安全验证页面
              </a>
            )}
          </div>
        )}

        {loginState && loginState.status === 'NEED_SMS' && (
          <div className="mt-4 space-y-3 rounded-xl px-3.5 py-3" style={{ backgroundColor: 'var(--apple-surface-secondary)' }}>
            <div className="text-[13px] font-medium">
              请输入短信验证码
              {loginState.url && (
                <span className="ml-1.5 text-[12px] font-normal" style={{ color: 'var(--apple-text-secondary)' }}>
                  ({loginState.url})
                </span>
              )}
            </div>
            <div className="grid grid-cols-6 gap-2">
              {otpDigits.map((digit, idx) => (
                <input
                  key={idx}
                  ref={(el) => {
                    otpRefs.current[idx] = el;
                  }}
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  value={digit}
                  onChange={(e) => handleOtpChange(idx, e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Backspace' && !digit && idx > 0) {
                      otpRefs.current[idx - 1]?.focus();
                    }
                  }}
                  className="tabular-nums h-11 rounded-xl text-center text-[17px] font-semibold outline-none"
                  style={{
                    backgroundColor: 'var(--apple-surface)',
                    border: '0.5px solid var(--apple-border)',
                  }}
                />
              ))}
            </div>
            <button
              type="button"
              onClick={handleOtpSubmit}
              disabled={submitting || otpDigits.join('').length < 4}
              className="w-full rounded-xl py-2 text-[13px] font-semibold text-white disabled:opacity-50"
              style={{ backgroundColor: 'var(--apple-blue)' }}
            >
              提交短信验证码
            </button>
          </div>
        )}

        {loginState && loginState.status === 'LOGGED_IN' && (
          <div className="mt-4 flex items-center gap-2 rounded-xl px-3.5 py-2.5 text-[13px] font-medium" style={{ backgroundColor: 'rgba(52, 199, 89, 0.14)', color: 'var(--apple-green)' }}>
            <CheckCircle2 className="h-4 w-4" />
            登录成功，正在刷新控制台...
          </div>
        )}

        {(error || loginState?.status === 'ERROR') && (
          <div className="mt-4 rounded-xl px-3.5 py-2.5 text-[12px]" style={{ backgroundColor: 'rgba(255, 59, 48, 0.12)', color: 'var(--apple-red)' }}>
            {error || loginState?.error}
          </div>
        )}
      </div>
    </div>
  );
}
