package io.github.luna.autosinin.server

import io.github.luna.autosinin.server.acidify.AcidifySessionManager
import io.github.luna.autosinin.server.acidify.SignHealthDto
import io.github.luna.autosinin.server.core.AccountInfo
import io.github.luna.autosinin.server.core.AccountLedger
import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import io.github.luna.autosinin.server.core.TicketRecord
import io.github.luna.autosinin.server.engine.TaskEngine
import io.github.luna.autosinin.server.engine.TaskExecutionResult
import io.github.luna.autosinin.server.engine.TaskScheduler
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AccountUpsertRequest(
    val uin: String,
    val nickname: String = "",
    val enabled: Boolean = true,
    val qqVersion: String = "9.3.85",
)

@Serializable
data class AccountDto(
    val uin: String,
    val nickname: String,
    val enabled: Boolean,
    val qqVersion: String,
    val updatedAt: Long,
)

@Serializable
data class TicketUpsertRequest(
    val domain: String,
    val pskey: String,
    val skey: String? = null,
)

@Serializable
data class TicketDto(
    val uin: String,
    val domain: String,
    val pskeyPreview: String,
    val hasSkey: Boolean,
    val updatedAt: Long,
)

@Serializable
data class RunTasksRequest(
    val tasks: List<String> = emptyList(),
    val force: Boolean = false,
)

@Serializable
data class LedgerEntryDto(
    val uin: String,
    val day: String,
    val taskKey: String,
    val state: String,
    val detail: String,
    val attempts: Int,
    val updatedAt: Long,
)

@Serializable
data class TaskRunDto(
    val id: Long,
    val uin: String,
    val day: String,
    val taskKey: String,
    val ok: Boolean,
    val pending: Boolean,
    val detail: String,
    val durationMs: Long,
    val createdAt: Long,
)

@Serializable
data class HealthDto(
    val ok: Boolean,
    val day: String,
    val accounts: Int,
    val transportType: String,
)

@Serializable
data class StatusTaskDto(
    val id: Int,
    val title: String,
    val status: Int,
    val done: Boolean,
    val vipTask: Boolean,
)

@Serializable
data class SimpleOkResponse(
    val ok: Boolean = true,
    val uin: String = "",
    val domain: String = "",
)

@Serializable
data class DeleteAccountResponse(
    val deleted: Boolean,
    val uin: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class AndroidLoginRequest(
    val uin: String,
    val password: String,
    val signUrl: String? = null,
)

@Serializable
data class AndroidVerifyRequest(
    val loginId: String,
    val code: String,
)

fun Application.configureAutoSininModule(
    ledger: AccountLedger,
    transport: ProtoTransport,
    engine: TaskEngine,
    scheduler: TaskScheduler,
    sessionManager: AcidifySessionManager? = null,
) {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }

    routing {
        get("/") {
            call.respondText(DASHBOARD_HTML, ContentType.Text.Html)
        }

        get("/health") {
            call.respond(
                HealthDto(
                    ok = true,
                    day = AccountLedger.todayKey(),
                    accounts = ledger.listAccounts().size,
                    transportType = transport::class.simpleName ?: "Unknown",
                ),
            )
        }

        get("/api/accounts") {
            val list = ledger.listAccounts().map {
                AccountDto(it.uin, it.nickname, it.enabled, it.qqVersion, it.updatedAt)
            }
            call.respond(list)
        }

        post("/api/accounts") {
            val req = call.receive<AccountUpsertRequest>()
            if (req.uin.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("uin is required"))
                return@post
            }
            val info = AccountInfo(
                uin = req.uin.trim(),
                nickname = req.nickname.trim(),
                enabled = req.enabled,
                qqVersion = req.qqVersion.ifBlank { "9.3.85" },
            )
            ledger.upsertAccount(info)
            call.respond(
                HttpStatusCode.OK,
                AccountDto(info.uin, info.nickname, info.enabled, info.qqVersion, info.updatedAt),
            )
        }

        delete("/api/accounts/{uin}") {
            val uin = call.parameters["uin"].orEmpty()
            val deleted = ledger.deleteAccount(uin)
            if (deleted) {
                call.respond(HttpStatusCode.OK, DeleteAccountResponse(deleted = true, uin = uin))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("account not found"))
            }
        }

        get("/api/accounts/{uin}/tickets") {
            val uin = call.parameters["uin"].orEmpty()
            val list = ledger.listTickets(uin).map {
                val preview = if (it.pskey.length <= 6) "***" else it.pskey.take(3) + "***" + it.pskey.takeLast(3)
                TicketDto(it.uin, it.domain, preview, !it.skey.isNullOrBlank(), it.updatedAt)
            }
            call.respond(list)
        }

        post("/api/accounts/{uin}/tickets") {
            val uin = call.parameters["uin"].orEmpty()
            if (ledger.getAccount(uin) == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("account not found"))
                return@post
            }
            val req = call.receive<TicketUpsertRequest>()
            if (req.domain.isBlank() || req.pskey.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("domain and pskey are required"))
                return@post
            }
            val rec = TicketRecord(uin = uin, domain = req.domain.trim(), pskey = req.pskey.trim(), skey = req.skey?.trim())
            ledger.upsertTicket(rec)
            call.respond(HttpStatusCode.OK, SimpleOkResponse(ok = true, uin = uin, domain = rec.domain))
        }

        get("/api/accounts/{uin}/ledger") {
            val uin = call.parameters["uin"].orEmpty()
            val day = call.request.queryParameters["day"]?.takeIf { it.isNotBlank() } ?: AccountLedger.todayKey()
            val entries = ledger.listDayEntries(uin, day).map {
                LedgerEntryDto(it.uin, it.day, it.taskKey, it.state.name, it.detail, it.attempts, it.updatedAt)
            }
            call.respond(entries)
        }

        get("/api/accounts/{uin}/status") {
            val uin = call.parameters["uin"].orEmpty()
            val tasks = engine.miniGameStatus.fetchAllTasks(uin)
            if (tasks == null) {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse("0x9172 status fetch failed or gateway unconfigured"))
                return@get
            }
            call.respond(tasks.map { StatusTaskDto(it.id, it.title, it.status, it.done, it.isVipTask) })
        }

        post("/api/accounts/{uin}/run") {
            val uin = call.parameters["uin"].orEmpty()
            val req = runCatching { call.receive<RunTasksRequest>() }.getOrDefault(RunTasksRequest())
            val filter = req.tasks.map { it.trim() }.filter { it.isNotBlank() }.toSet().ifEmpty { null }
            val results: List<TaskExecutionResult> = engine.runAccount(uin, filter, req.force)
            call.respond(results)
        }

        post("/api/accounts/{uin}/reconcile") {
            val uin = call.parameters["uin"].orEmpty()
            val promoted = engine.reconcilePending(uin)
            call.respond(promoted)
        }

        get("/api/sign/health") {
            val status = sessionManager?.checkSignServerHealth()
                ?: SignHealthDto(configured = false, url = "", healthy = false, detail = "AcidifySessionManager not enabled")
            call.respond(status)
        }

        post("/api/accounts/qr/start") {
            val mgr = sessionManager
            if (mgr == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AcidifySessionManager not enabled"))
                return@post
            }
            val state = mgr.startQrLogin()
            call.respond(state)
        }

        post("/api/accounts/android/login") {
            val mgr = sessionManager
            if (mgr == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AcidifySessionManager not enabled"))
                return@post
            }
            val req = call.receive<AndroidLoginRequest>()
            val uinLong = req.uin.toLongOrNull()
            if (uinLong == null || uinLong <= 0L || req.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid uin or password"))
                return@post
            }
            call.respond(mgr.startAndroidLogin(uinLong, req.password, req.signUrl))
        }

        post("/api/accounts/android/verify") {
            val mgr = sessionManager
            if (mgr == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AcidifySessionManager not enabled"))
                return@post
            }
            val req = call.receive<AndroidVerifyRequest>()
            if (req.loginId.isBlank() || req.code.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("loginId and code are required"))
                return@post
            }
            val ok = mgr.submitVerification(req.loginId, req.code)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no pending verification for loginId"))
            } else {
                call.respond(mapOf("ok" to true))
            }
        }

        get("/api/accounts/qr/{qrId}") {
            val qrId = call.parameters["qrId"].orEmpty()
            val state = sessionManager?.getQrState(qrId)
            if (state == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("qr session not found"))
            } else {
                call.respond(state)
            }
        }

        get("/api/scheduler") {
            call.respond(scheduler.status())
        }

        post("/api/scheduler/run") {
            val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: false
            val results = scheduler.triggerNow(force = force)
            call.respond(results)
        }

        get("/api/runs") {
            val uin = call.request.queryParameters["uin"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val runs = ledger.listRecentRuns(uin, limit).map {
                TaskRunDto(it.id, it.uin, it.day, it.taskKey, it.ok, it.pending, it.detail, it.durationMs, it.createdAt)
            }
            call.respond(runs)
        }

        get("/api/logs") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 300) ?: 100
            call.respond(ServerLog.recent(limit))
        }
    }
}

private val DASHBOARD_HTML = """
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>autosinin-server 控制台</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 24px; background: #0f172a; color: #e2e8f0; }
    h1 { font-size: 20px; margin-bottom: 16px; }
    .card { background: #1e293b; border: 1px solid #334155; border-radius: 10px; padding: 16px; margin-bottom: 16px; }
    input, button { padding: 8px 12px; border-radius: 6px; border: 1px solid #475569; background: #0f172a; color: #e2e8f0; }
    button { background: #2563eb; border-color: #2563eb; cursor: pointer; font-weight: 600; }
    button.secondary { background: #334155; border-color: #475569; }
    table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
    th, td { text-align: left; padding: 8px; border-bottom: 1px solid #334155; }
    pre { background: #020617; padding: 12px; border-radius: 8px; overflow: auto; max-height: 280px; font-size: 12px; }
  </style>
</head>
<body>
  <h1>autosinin-server 控制台</h1>
  <div class="card">
    <div id="health">加载中...</div>
    <div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap;">
      <input id="uin" placeholder="QQ号" />
      <input id="nick" placeholder="备注(可选)" />
      <button onclick="addAccount()">添加/更新账号</button>
      <button class="secondary" onclick="runAll()">全部执行</button>
      <button class="secondary" onclick="refreshAll()">刷新</button>
    </div>
  </div>
  <div class="card">
    <strong>Android 协议登录</strong> <span id="signHealth" style="font-size:12px;color:#94a3b8;margin-left:8px;">检查签名服务...</span>
    <div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap;">
      <input id="loginUin" placeholder="QQ号" />
      <input id="loginPwd" type="password" placeholder="QQ密码" />
      <button onclick="startAndroidLogin()">登录</button>
    </div>
    <div id="loginStatus" style="margin-top:10px;font-size:13px;color:#cbd5e1;"></div>
    <div id="verifyBox" style="display:none;margin-top:10px;padding:10px;background:#0f172a;border:1px solid #475569;border-radius:8px;">
      <div id="verifyPrompt" style="font-size:13px;margin-bottom:8px;"></div>
      <div style="display:flex;gap:8px;flex-wrap:wrap;">
        <input id="verifyCode" placeholder="输入滑块 ticket 或短信验证码" style="min-width:260px;" />
        <button onclick="submitVerify()">提交验证</button>
      </div>
    </div>
  </div>
  <div class="card">
    <strong>账号列表</strong>
    <table><thead><tr><th>UIN</th><th>备注</th><th>状态</th><th>操作</th></tr></thead><tbody id="accounts"></tbody></table>
  </div>
  <div class="card">
    <strong>最近执行记录</strong>
    <table><thead><tr><th>时间</th><th>UIN</th><th>任务</th><th>结果</th><th>详情</th></tr></thead><tbody id="runs"></tbody></table>
  </div>
  <div class="card">
    <strong>服务日志</strong>
    <pre id="logs"></pre>
  </div>
<script src="https://captcha.gtimg.com/TCaptcha.js" onerror="window.__tcaptchaFailed=true"></script>
<script>
async function api(url, opts) {
  const r = await fetch(url, opts);
  const text = await r.text();
  try { return JSON.parse(text); } catch { return { error: 'HTTP ' + r.status + ': ' + (text || r.statusText) }; }
}
async function addAccount() {
  const uin = document.getElementById('uin').value.trim();
  const nickname = document.getElementById('nick').value.trim();
  if (!uin) return;
  await api('/api/accounts', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({uin, nickname, enabled:true}) });
  refreshAll();
}
async function runOne(uin) {
  await api('/api/accounts/' + encodeURIComponent(uin) + '/run', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({force:false}) });
  refreshAll();
}
async function runAll() {
  await api('/api/scheduler/run', { method:'POST' });
  refreshAll();
}
let activeLoginId = null;
let loginPollTimer = null;
async function startAndroidLogin() {
  const uin = document.getElementById('loginUin').value.trim();
  const password = document.getElementById('loginPwd').value;
  if (!uin || !password) {
    document.getElementById('loginStatus').textContent = '请输入 QQ号 和 密码';
    return;
  }
  document.getElementById('verifyBox').style.display = 'none';
  document.getElementById('loginStatus').textContent = '正在发起登录...';
  const res = await api('/api/accounts/android/login', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({uin, password})
  });
  if (res.error) {
    document.getElementById('loginStatus').textContent = '登录失败: ' + res.error;
    return;
  }
  activeLoginId = res.qrId;
  pollLoginState();
}
async function pollLoginState() {
  if (!activeLoginId) return;
  if (loginPollTimer) clearTimeout(loginPollTimer);
  const st = await api('/api/accounts/qr/' + encodeURIComponent(activeLoginId));
  if (st.status === 'LOGGING_IN') {
    document.getElementById('loginStatus').textContent = '正在登录 ' + (st.uin||'') + ' ...';
    document.getElementById('verifyBox').style.display = 'none';
    loginPollTimer = setTimeout(pollLoginState, 1200);
  } else if (st.status === 'NEED_CAPTCHA') {
    document.getElementById('loginStatus').textContent = '需要滑块验证（完成滑块后将自动提交）';
    document.getElementById('verifyPrompt').innerHTML = '<button onclick="openAutoCaptcha()" style="margin-right:8px;">打开滑块验证</button> <a href="' + st.url + '" target="_blank" style="color:#60a5fa;font-size:12px;">备用原链接</a>';
    window.__lastCaptchaUrl = st.url;
    document.getElementById('verifyBox').style.display = 'block';
    if (!window.__captchaShownFor || window.__captchaShownFor !== activeLoginId) {
      window.__captchaShownFor = activeLoginId;
      openAutoCaptcha();
    }
    loginPollTimer = setTimeout(pollLoginState, 2000);
  } else if (st.status === 'NEED_SMS') {
    document.getElementById('loginStatus').textContent = '需要短信验证码';
    document.getElementById('verifyPrompt').textContent = '短信验证 (' + (st.url||'') + ')，请输入收到的验证码：';
    document.getElementById('verifyBox').style.display = 'block';
    loginPollTimer = setTimeout(pollLoginState, 2000);
  } else if (st.status === 'LOGGED_IN') {
    document.getElementById('verifyBox').style.display = 'none';
    document.getElementById('loginStatus').textContent = '登录成功：' + st.uin;
    activeLoginId = null;
    refreshAll();
  } else {
    document.getElementById('verifyBox').style.display = 'none';
    document.getElementById('loginStatus').textContent = '登录失败：' + (st.error || st.status);
    activeLoginId = null;
  }
}
async function openAutoCaptcha() {
  if (!window.TencentCaptcha || !window.__lastCaptchaUrl) return;
  try {
    const u = new URL(window.__lastCaptchaUrl);
    const aid = u.searchParams.get('aid') || '16';
    const sid = u.searchParams.get('sid') || '';
    const uin = u.searchParams.get('uin') || '0';
    const cap = new window.TencentCaptcha(aid, async (res) => {
      if (res && res.ret === 0 && res.ticket) {
        document.getElementById('loginStatus').textContent = '滑块已完成，正在自动提交验证...';
        await api('/api/accounts/android/verify', {
          method: 'POST',
          headers: {'Content-Type':'application/json'},
          body: JSON.stringify({loginId: activeLoginId, code: res.ticket})
        });
        document.getElementById('verifyBox').style.display = 'none';
        pollLoginState();
      }
    }, { sid, uin });
    cap.show();
  } catch (e) {
    console.warn('TencentCaptcha init failed', e);
  }
}
async function submitVerify() {
  if (!activeLoginId) return;
  const code = document.getElementById('verifyCode').value.trim();
  if (!code) return;
  await api('/api/accounts/android/verify', {
    method: 'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({loginId: activeLoginId, code})
  });
  document.getElementById('verifyCode').value = '';
  document.getElementById('verifyBox').style.display = 'none';
  pollLoginState();
}
async function refreshAll() {
  const [h, sh, accs, runs, logs] = await Promise.all([api('/health'), api('/api/sign/health'), api('/api/accounts'), api('/api/runs?limit=20'), api('/api/logs?limit=50')]);
  document.getElementById('health').textContent = '日期: ' + h.day + ' | 账号数: ' + h.accounts + ' | Transport: ' + h.transportType;
  document.getElementById('signHealth').textContent = sh.configured ? (sh.healthy ? '签名服务正常 (' + sh.url + ')' : '签名服务异常: ' + sh.detail) : '未配置签名服务';
  document.getElementById('accounts').innerHTML = accs.map(a =>
    '<tr><td>' + a.uin + '</td><td>' + (a.nickname||'-') + '</td><td>' + (a.enabled?'启用':'停用') + '</td><td><button onclick="runOne(\'' + a.uin + '\')">执行</button></td></tr>'
  ).join('');
  document.getElementById('runs').innerHTML = runs.map(r =>
    '<tr><td>' + new Date(r.createdAt).toLocaleTimeString() + '</td><td>' + r.uin + '</td><td>' + r.taskKey + '</td><td>' + (r.ok?'OK':(r.pending?'PENDING':'FAIL')) + '</td><td>' + r.detail + '</td></tr>'
  ).join('');
  document.getElementById('logs').textContent = logs.join('\n');
}
refreshAll();
</script>
</body>
</html>
""".trimIndent()
