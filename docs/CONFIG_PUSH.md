# ConfigPushSvc 推送处理（真机行为 vs 本实现）

## 一、真机 MSF 侧处理链路（已反编译确认）

### 1. 分发入口

`com.tencent.mobileqq.msf.core.t`：

```java
if (fromServiceMsg.getServiceCmd().equals(BaseConstants.CMD_PUSHREQ)) {
    MsfService.getCore().getPushManager().d(fromServiceMsg);
}
```

### 2. PushReq 解析

`com.tencent.mobileqq.msf.core.push.h.d(FromServiceMsg)`：

```java
UniPacket uniPacket = new UniPacket(true);
uniPacket.setEncodeName("utf-8");
uniPacket.decode(fromServiceMsg.getWupBuffer());
f req = (f) uniPacket.getByClass("PushReq", new f());
int type = req.a;

if (type == 1) {
    msfNetService.q().a(req);                       // 网络/服务器配置
} else if (type == 2) {
    MsfSdkUtils.addFromMsgProcessName("*", fromServiceMsg);
    fromServiceMsg.setMsfCommand(MsfCommand.onRecvConfigPush);
    MsfService.getCore().addRespToQuque(null, fromServiceMsg);   // 转发主进程
} else if (type == 3) {
    LogReport.getInstance().onLogPush(fromServiceMsg, req);       // 日志上报配置
}
a(fromServiceMsg, req);                              // 无论哪个分支都回包
```

### 3. PushResp 构造

`push.h.a(FromServiceMsg, f)`：

```java
ToServiceMsg msg = new ToServiceMsg(uin, "ConfigPushSvc.PushResp");
UniPacket uni = new UniPacket(true);
uni.setServantName("QQService.ConfigPushSvc.MainServant");
uni.setFuncName("PushResp");
g resp = new g();
resp.a = req.a;                                  // 类型原样带回
resp.b = req.c;                                  // long 原样带回
if (req.a == 3) resp.c = req.b;                  // 只有 type==3 带数据
uni.put("PushResp", resp);
msg.putWupBuffer(uni.encode());
msg.setNeedCallback(false);
msg.setTimeout(30000L);
msg.setRequestSsoSeq(fromServiceMsg.getRequestSsoSeq());   // 复用推送的 seq
MsfService.getCore().sendSsoMsg(msg);
```

### 4. 两个 JCE 结构

```java
// serverconfig.f —— PushReq
public int a;        // tag 1
public byte[] b;     // tag 2
public long c;       // tag 3

// serverconfig.g —— PushResp
public int a;        // tag 1 = req.a
public long b;       // tag 2 = req.c
public byte[] c;     // tag 3 = req.b（仅 type==3）
```

## 二、实测报文结构（2026-11-28 抓取）

### 1. 外层 WUP/JCE

Command 为 `ConfigPushSvc.PushReq`，payload 直接以 JCE 结构开头，**没有 4 字节长度前缀**（长度由 SSO 帧表达）：

```
10 02                 tag1 BYTE, 版本=2
2c 3c 4c              tag2/3/4 ZERO（packetType / messageType / requestId 均为 0）
56 23 "QQService.ConfigPushSvc.MainServant"    tag5 STRING1, len=0x23=35
66 07 "PushReq"                                 tag6 STRING1
7d 00 02 <len> <sBuffer>                        tag7 SIMPLE_LIST
8c 98 0c a8 0c                                   tag8 ZERO, tag9/10 空 map
```

### 2. sBuffer 是三层结构（已完整解码验证）

```
08 00 01                                 map, size=1
06 07 "PushReq"                           key
18 00 01                                 value = map, size=1
06 12 "ConfigPush.PushReq"                key
1d 00 01 10 a2 0a                        value = bytes(0x10a2 = 4258)
```

内层字节 = JCE struct `f`，完整解析结果：

```
0a                     STRUCT_BEGIN
10 01                  tag1 BYTE  a = 1          ← 推送类型
2d 00 01 10 9e         tag2 SIMPLE_LIST  b = 4240 字节  ← 服务器列表
33 00 00 00 10 f4 ...  tag3 LONG      c = 284396523    ← 回包要带回
0b                     STRUCT_END
```

`b` 字段就是服务器列表，节选：

```
0a 16 0d 31 32 33 2e 31 32 35 2e 30 2e 31 39 35     "123.125.0.195"
21 01 bb 3c 4c 5c 60 08 70 01 86 02 74 6a 96 03 ...
c6 47 69 64 3d 30 26 6c 69 73 74 3d 32 47 33 47 ...  "id=0&list=2G3G&ipv4=1&echo=1&policy=v2&..."
```

可以识别出这是 **服务器 IP 列表**（`ConfigPush.PushDomain` 同源内容），每条记录含 IP、端口、协议参数。

### 3. 结论

SDK 的 `getByClass("PushReq", f)` 对嵌套 map 做了隐式处理：取内层 `ConfigPush.PushReq` 的字节再按 `f` 解码。本实现按同样路径解析，已验证可用。

## 三、本实现现状

### 已实现

| 文件 | 内容 |
|---|---|
| `qqclient/JceCodec.kt` | JCE 编解码（byte/short/int/long/string/map/list/simple-list/struct） |
| `qqclient/ConfigPushCodec.kt` | PushReq 解码 + PushResp 编码 |
| `SsoClient.sendPacketNoWait` | 复用推送 seq、不等回包地发送 |
| `SsoClient.handleServerPush` | 收到 `ConfigPushSvc.PushReq` 时尝试解码并回 `PushResp` |
| `ConfigPushCodecTest` | 4 个用例（编解码往返、PushResp 结构） |

### 编码侧已按真机格式修正

- 不带 4 字节长度头（否则 `encodeSsoFrame` 会再包一层，服务端解析失败）
- servant = `QQService.ConfigPushSvc.MainServant`，func = `PushResp`
- JCE 的 map/list/simple-list 长度是**带标签的 int**（0 → `0x0C`，非 0 → `0x02` + 4 字节）

### 回包已跑通（2026-11-28 22:25）

```
uin=3937903488 已回复 ConfigPushSvc.PushResp type=1 seq=284575705 ssoSeq=-1594472488
uin=2475318409 已回复 ConfigPushSvc.PushResp type=2 seq=541606003 ssoSeq=-1337442190
uin=2475318409 已回复 ConfigPushSvc.PushResp type=1 seq=305652947 ssoSeq=-1573395246
```

`seq` 为 `f.c` 的回显，`ssoSeq` 复用原推送的序列号。

## 四、剩余问题

1. `f.b` 里 4240 字节服务器列表的内部格式（JCE 列表，元素含 IP、端口、`id=0&list=2G3G&...` 参数）
2. `f.c` 的语义（每次推送不同，疑似序号或时间戳）
3. type=2 的业务负载是否需要额外处理（当前只回包不解析）

## 五、参考：完整 WUP 字段顺序

| tag | 字段 | 类型 |
|---|---|---|
| 1 | iVersion | byte（值 2） |
| 2 | cPacketType | byte |
| 3 | iMessageType | int |
| 4 | iRequestId | int |
| 5 | sServantName | string |
| 6 | sFuncName | string |
| 7 | sBuffer | byte[] |
| 8 | iTimeout | int |
| 9 | context | map |
| 10 | status | map |
