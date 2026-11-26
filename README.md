# autosinin-server

纯服务端化的 QQ 等级任务自动化服务（Kotlin / JVM 17 / Ktor / OkHttp / SQLite），彻底脱离 Android 真机与 Xposed 宿主。

## 模块结构

- `:protocol-core`：从 `autosinin` 迁入的纯 JVM 协议编解码模块（零 `android.*` / `libxposed` 依赖）
  - `PbEdit.kt`、`FarmProto.kt`、`WelfareSignProto.kt`、`PublishReplay.kt`、`FeedRefreshReplay.kt`、`NovelReport.kt`
- `:server`：服务端核心抽象、第二批迁移协议、任务引擎、定时调度与 Ktor HTTP/Web 管理台
  - `server/core/ProtoTransport.kt`：`ProtoTransport` 抽象（含 `HttpGatewayProtoTransport` 与 `StubProtoTransport`）
  - `server/core/TicketProvider.kt`：`TicketProvider` 接口与 `bkn` 计算
  - `server/core/AccountLedger.kt` & `SqliteAccountLedger.kt`：多账号每日账本、票据与运行记录持久化
  - `protocol/`：`MiniGameStatus`、`DramaSso`、`VipSign`、`MiniGameArena`、`VisitReport`、`FeedReadTask`、`DailySignCards`、`WelfareClaim`、`MiniGameProtocolSession`、`FarmGateway`、`TaskPageAction`
  - `server/engine/TaskEngine.kt` & `TaskScheduler.kt`：任务编排与周期调度
  - `server/ServerApp.kt` & `Main.kt`：Ktor Netty 服务与内置 Web 控制台

## 运行与测试

```bash
# 运行全部单元测试（:protocol-core 与 :server）
./gradlew test

# 启动服务（默认监听 0.0.0.0:8080）
./gradlew :server:run
```

### 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AUTOSININ_PORT` | `8080` | HTTP 服务端口 |
| `AUTOSININ_HOST` | `0.0.0.0` | 监听地址 |
| `AUTOSININ_DB_PATH` | `data/autosinin.db` | SQLite 数据库路径 |
| `AUTOSININ_GATEWAY_URL` | 空（使用 `StubProtoTransport`） | 外部无头 QQ MSF / NTQQ / Unidbg HTTP 网关地址（`POST {url}/pb/send`） |
| `AUTOSININ_GATEWAY_TOKEN` | 空 | 网关 Bearer Token |
| `AUTOSININ_SCHEDULE_INTERVAL_MIN` | `360` | 自动调度间隔（分钟） |
| `AUTOSININ_SCHEDULE_ENABLED` | `true` | 是否开启定时调度 |

## 管理接口

- `GET /`：Web 控制台
- `GET /health`：健康检查与当前 Transport 状态
- `GET /api/accounts` / `POST /api/accounts` / `DELETE /api/accounts/{uin}`：账号管理
- `GET /api/accounts/{uin}/tickets` / `POST /api/accounts/{uin}/tickets`：各域 `p_skey` / `skey` 管理
- `GET /api/accounts/{uin}/ledger?day=YYYY-MM-DD`：当日任务账本
- `GET /api/accounts/{uin}/status`：查询 `0x9172` 全量任务状态
- `POST /api/accounts/{uin}/run`：手动触发指定账号任务
- `GET /api/scheduler` / `POST /api/scheduler/run`：调度器状态与全量触发
- `GET /api/runs` / `GET /api/logs`：执行记录与服务日志
