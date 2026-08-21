[English](./SPEC.md) | **简体中文**

# DingTalk Channel SDK — 四语言统一契约（SPEC v0.1）

> 定位：**与 Agent runtime 解耦的会话接入层**。SDK 负责"通道"的脏活，
> 开发者只写"用户说了什么、机器人回什么"。

## 0. 效果对齐验收清单（Effect Parity — 四语言一律以此为验收标准）

以**终端可感知行为**验收，逐条可演示：

| # | 用户可见效果 | 本 SDK 实现 |
|---|---|---|
| E1 | 发消息后**秒级**出现"输入中"卡片（loading） | `reply.stream()` 立即建卡+投递 INPUTING（不等首个 token） |
| E2 | 回复内容**打字机式**平滑追加 | streaming 接口 + 800ms 节流 + 非终帧去尾换行（防闪烁） |
| E3 | 完成后 loading 消失、内容**定格为完整 Markdown** | isFinalize 终帧 + flowStatus=3 + cardUpdateOptions |
| E4 | 卡片失败/QPS 限流**用户无感** | 创建失败→静默降级 webhook 文本；QpsLimit→退避 2s 重试；不弹错误 |
| E5 | **群聊/单聊同一体验** | 同一 Reply API；投递目标自动选 IM_GROUP/IM_ROBOT；群聊自动剥 @ 前缀 |
| E6 | **绝不重复回复**同一条消息 | 双层去重（messageId+msgId，TTL 5min），丢弃仍回 ACK |
| E7 | **卡片交互闭环**：按钮点击到达 Agent，可更新卡片 | OnCardAction（注册即自动订阅 /v1.0/card/instances/callback，四语言均有单测覆盖分发与订阅）+ reply 更新 |
| E8 | 机器人**永不掉线**（断网/服务端切换无感） | 指数退避重连 + SYSTEM/disconnect 即时重连 + 心跳保活 + ACK 防丢 |
| E9 | **媒体收发**：收图片/文件可下载；可上传媒体并内嵌 | `reply.image(url)`（sampleImageMsg）；`reply.uploadMedia()`（OAPI multipart，mediaId 可 `![..](mediaId)` 内嵌卡片）；`reply.downloadURL()` |
| E10 | Markdown **渲染质量**（代码块/表格/列表/引用） | normalizeForCard 归一化（见 §7） |

> 效果演示脚本（每语言 README 附）：echo + 流式模拟（假 LLM 每 100ms 吐 token）必须呈现 E1→E3 全过程。

## 1. 职责边界

**SDK 负责：**
1. Stream 长连接（建连、订阅、心跳、断线重连、服务端 disconnect 处理）
2. 事件解析与去重（协议层 messageId + 业务层 msgId，双层，TTL 5 分钟）
3. 回复发送（sessionWebhook：文本 / Markdown）
4. AI 卡片流式输出（创建 → 投递 → INPUTING → streaming → FINISHED，打字机效果）
5. 卡片 API 全局限流（令牌桶 + QpsLimit 退避重试）
6. Markdown 归一化（适配钉钉 AI 卡片渲染器的换行/表格规则）

**SDK 不管（留给 Agent 侧）：**
- Agent runtime（模型 / prompt / 工具编排）
- 多用户话题隔离与 Session/上下文持久化
- 凭据存储（只接收 clientId/clientSecret）

## 2. 线协议（Stream Mode）

### 2.1 建立连接
```
POST {apiBase}/v1.0/gateway/connections/open
{
  "clientId": "...", "clientSecret": "...",
  "ua": "dingtalk-channel-sdk-{lang}/v0.1.0",
  "localIp": "<first non-loopback IPv4>",
  "subscriptions": [ {"type": "CALLBACK", "topic": "/v1.0/im/bot/messages/get"} ],
  "extras": {}
}
→ {"endpoint": "wss://...", "ticket": "..."}
```
HTTP 头：`Content-Type/Accept: application/json`、`User-Agent: dingtalk-channel-sdk-{lang}/v0.1.0`。
WebSocket 连接：`{endpoint}?ticket=<url-encoded ticket>`（**ticket 需 URL 编码**，官方 Python SDK 同款；topic 在 open 请求里声明，不在 URL）。

订阅类型：`CALLBACK`（回调）/ `EVENT`（事件）/ `SYSTEM`（系统，SDK 内部占用 `ping`、`disconnect`）。

固定 topic：
- 机器人消息：`/v1.0/im/bot/messages/get`（CALLBACK）
- 卡片回调：`/v1.0/card/instances/callback`（CALLBACK，仅 OnCardAction 注册时订阅）

### 2.2 数据帧
入帧（WebSocket text）：
```json
{"specVersion":"1.0","type":"CALLBACK|EVENT|SYSTEM","time":0,
 "headers":{"topic":"...","messageId":"...","contentType":"application/json","time":"..."},
 "data":"<JSON 字符串>"}
```
ACK 出帧（**必须回**，否则服务端重投；**ACK 先行**——收到即回 `{"success":true}`，业务异步处理，
对齐官方 connector：防止 Agent 长任务期间服务端超时重投；重复投递由双层去重兜底）：

> **服务端视角实证**（lippi-open-proxy 源码，2026-07-23 线上事故复盘交叉验证）：
> ① 服务端推送为 `UnaryRequest`——**同步等待 ACK**，上游超时约 2s；未收到即经 MetaQ **重投（at-least-once）**，
> 且重投会生成新的 messageId（业务层 msgId 去重是必需的，协议层单层不够）——本 SDK 的 ACK 先行 + 双层去重
> 与该语义严格对齐。② 心跳契约是**客户端 ping、服务端自动 pong**（gorilla 默认行为）；服务端读循环阻塞时
> pong 停止、客户端应超时重连——本 SDK 的 120s idle ping + 5s pong 判死即该契约的客户端实现。
> ③ 服务端 ACK 校验仅要求 headers 非空 + 含 messageId；只收/发 Text 帧；ticket 即服务端 connectionId（URL query 传递）。
> ④ **风险应对**：0723 事故证明服务端读循环存在被迟到/重复 ACK 阻塞的模式（修复在 20260723 发布线的
> 分支上，是否已上生产以发布系统为准；本地 master 为过期快照，不代表生产版本）。无论服务端修复是否就位，
> 客户端四项防御都是必要的生产自愈：每帧恰好一次 ACK、ACK 先行（不留迟到 ACK）、pong 超时判死重连
>（服务端 wedged 时唯一出路）、指数退避+抖动重连（防风暴放大）。延迟敏感场景可将 KeepAliveIdleMs
> 调低（默认 120s）以加快 wedged 检测。
```json
{"code":200,"headers":{"contentType":"application/json","messageId":"<同帧>"},
 "message":"ok","data":"{\"success\":true}"}
```
- `SYSTEM/ping`：回 pong，`data` 原样回显。
- `SYSTEM/disconnect`：关闭连接并立即重连（服务端 LB 切换）。

### 2.3 心跳与重连
- 空闲 120s 发 WebSocket 协议层 Ping，5s 未收到 Pong 判死。
- 重连：指数退避 1s→2s→4s…封顶 30s（带抖动）；重连成功清零。
- 读循环异常/断开 → 自动重连（可配 AutoReconnect=false）。

> 与官方 stream-sdk 家族对照：官方心跳 Go=120s idle+5s pong、Java(Netty)=60s idle+pong、
> Python=60s ping（无 pong 检测）、Node=isAlive 标志+terminate；本 SDK 统一取 Go 档（120s+5s pong），
> 强于 Python/Node。重连官方为固定 3s/10s，本 SDK 用指数退避+抖动（与官方 connector 对齐）。

## 3. 事件模型

### 3.1 IncomingMessage（归一化后）
| 字段 | 来源 | 说明 |
|---|---|---|
| ConversationID | conversationId | 会话 ID |
| ConversationType | conversationType | "1"=单聊 "2"=群聊 → 归一为 `dm`/`group` |
| ConversationTitle | conversationTitle | 群名（群聊） |
| SenderID / SenderStaffID | senderId / senderStaffId | 加密 ID / 员工 ID |
| SenderNick | senderNick | 昵称 |
| SenderCorpID | senderCorpId | |
| Text | text.content | **已去除 @机器人 前缀空白并 trim** |
| MsgType / Content | msgtype / content | rich 内容（图片/文件等原样透出） |
| AtUsers | atUsers[] | [{dingtalkId, staffId}] |
| SessionWebhook | sessionWebhook | 回复 webhook（含过期时间 SessionWebhookExpiredTime） |
| MsgID / CreateAt | msgId / createAt | 业务去重键 / 事件时间 |
| Raw | 原始 data | |

### 3.2a 过期消息过滤

入站消息 `createAt` 距今超过 `StaleMessageWindow`（默认 30min，<=0 关闭）直接丢弃（仍回 ACK）——
重连风暴/重投积压期间涌入的旧消息不再触发回复。
1. 协议层：`headers.messageId`（同一次投递的重复回调）
2. 业务层：`data.msgId`（服务端重发时 messageId 变、msgId 不变）
TTL 5 分钟，LRU 清理。命中即丢弃（仍回 ACK success）。

## 4. Reply API（四语言一致语义）

```
reply.text(content)                     → sessionWebhook, msgKey=sampleText
reply.markdown(title, text)             → sessionWebhook, msgKey=sampleMarkdown
reply.image(url)                        → sessionWebhook, msgKey=sampleImageMsg
reply.downloadURL(downloadCode,msgId)   → GET /v1.0/robot/messageFiles/download
reply.uploadMedia(type,name,data[,ct]) → OAPI 上传，返回 mediaId（见 §9a）
s = reply.stream()                      → 立即创建 AI 卡片（E1：先出"输入中"卡）
s.append(delta) / s.append(fullText)    → 流式更新（累积语义由调用方决定）
s.finish() / s.finish(fullText)         → 终帧 + FINISHED
s.fail(errText)                         → FINISHED(flowStatus=5) 或降级文本
```

**超长分片**：`TextChunkLimit`（默认 3500，<=0 关闭）——
文本/Markdown 回复超限时按 **newline 边界**切分多次发送（无合适换行则硬切），内容不丢失。

sessionWebhook 载荷：
```json
{"msgKey":"sampleText","msgParam":"{\"content\":\"...\"}"}
{"msgKey":"sampleMarkdown","msgParam":"{\"title\":\"...\",\"text\":\"...\"}"}
```
**注意**：`msgParam` 必须是**字符串化的 JSON**（官方文档要求，对象形式会 400）。
Header：`x-acs-dingtalk-access-token: <token>`。

## 4a. 主动发消息（Proactive Send）

不依赖入站消息，Agent 可随时发起：

```
channel.SendText(target, text)
channel.SendMarkdown(target, title, text)      // target 可带 @：AtUserIds/AtDingtalkIds/AtAll
channel.SendImage(target, imageURL)            // 需公网可访问 URL
```

- 单聊（target.UserID）：`POST /v1.0/robot/oToMessages/batchSend` `{robotCode, userIds:[...], msgKey, msgParam}`
- 群聊（target.ConversationID）：`POST /v1.0/robot/groupMessages/send` `{robotCode, openConversationId, msgKey, msgParam, atUserIds?, atOpendingtalkIds?, isAtAll?}`

## 4b. 策略门控与群级覆盖

全局策略（PolicyConfig）：群白/黑名单、`RequireMention`（默认 true）、单聊模式
（open/allowlist/blocklist/disabled）与对应名单。

**群级覆盖（GroupOverrides）**：按 conversationId 逐群覆盖——`Enabled`（显式禁用）、
`RequireMention`（该群 @ 要求）、`AllowFrom`/`BlockFrom`（群内发送者白/黑名单，黑名单优先）。
求值顺序（四语言一致）：

1. 全局黑名单（最高优先级，群覆盖不可豁免）
2. 白名单准入：全局白名单命中，**或存在显式群条目**（显式条目可在白名单模式下放行该群）
3. `Enabled=false` → 拒绝（`group_disabled`）
4. @机器人检查（群覆盖优先于全局）
5. `BlockFrom` → `AllowFrom`（群内发送者过滤）

## 5. AI 卡片协议（五步）

模板 ID 默认：`02fcf2f4-5e02-4a85-b672-46d1f715543e.schema`（官方 AI 卡片，可配）。

1. **创建** `POST /v1.0/card/instances`
   `{cardTemplateId, outTrackId: "card_{ts}_{rand}", cardData:{cardParamMap:{config:"{\"autoLayout\":true}"}}, callbackType:"STREAM", imGroupOpenSpaceModel:{supportForward:true}, imRobotOpenSpaceModel:{supportForward:true}}`
2. **投递** `POST /v1.0/card/instances/deliver`
   - 群聊：`{outTrackId, userIdType:1, openSpaceId:"dtv1.card//IM_GROUP.{conversationId}", imGroupOpenDeliverModel:{robotCode}}`
   - 单聊：`{outTrackId, userIdType:1, openSpaceId:"dtv1.card//IM_ROBOT.{senderStaffId||senderId}", **imRobotOpenDeliverModel**:{spaceType:"IM_ROBOT", robotCode, extension:{dynamicSummary:"true"}}}`
   - robotCode = clientId
   - ⚠️ 单聊字段必须是 `imRobotOpenDeliverModel`（Deliver 不是 Space；官方 connector 的 `imRobotOpenSpaceModel` 变体会被生产拒绝：`400 param.spaceDeliverModelEmpty`——2026-08 真机实证，dws 源码为准）
   - ⚠️ **业务级校验**：deliver 会在 HTTP 200 内返回 `{"result":[{"success":false,...}]}`（dws 生产实证"observed live"），SDK 必须扫描 body 中 `"success":false` 并视为失败（本 SDK 的 create/deliver 均做 callChecked）
3. **首帧置 INPUTING** `PUT /v1.0/card/instances`
   `{outTrackId, cardData:{cardParamMap:{flowStatus:"2", msgContent:<norm>, staticMsgContent:"", sys_full_json_obj:"{\"order\":[\"msgContent\"]}", config:"{\"autoLayout\":true}"}}}`
4. **流式更新** `PUT /v1.0/card/streaming`
   `{outTrackId, guid:"{ts}_{rand}", key:"msgContent", content:<norm>, isFull:true, isFinalize:<bool>, isError:false}`
   非终帧去掉末尾连续换行（防闪烁）。
5. **收口 FINISHED** `PUT /v1.0/card/instances`
   先发 isFinalize=true 的 streaming，再置 `{outTrackId, cardData:{cardParamMap:{flowStatus:"3", msgContent, ...}}, cardUpdateOptions:{updateCardDataByKey:true}}`

flowStatus：1=PROCESSING 2=INPUTING 3=FINISHED 4=EXECUTING 5=FAILED。

**帧节奏（dws connect_card.go 实证移植）**：
- **帧间隔 500ms**：首个内容帧与投递之间、终帧与上一帧之间必须留隔——背靠背会与客户端拉卡竞争，
  间歇性渲染"内容加载失败"（该竞态曾干掉 dws #407 卡片实现）
- **单帧内容上限 20000**（rune 安全截断，hermes MAX_MESSAGE_LENGTH 同款）

**状态章（对齐 dws/hermes）**：`MarkThinking/MarkDone` 在**用户消息**上打 text emotion
（`POST /v1.0/robot/emotion/reply|recall`，emotionType=2，emotionId=2659900）：
"🤔Thinking"标识处理中、"🥳Done"标识完成；仅支持人发的消息（机器人消息 500）；best-effort 不阻断回复。

**节流**：
- 单卡片流式更新最小间隔 800ms（钉钉卡片有同卡并发保护，官方 connector 实战值）
- **窗口内更新不丢弃**：安排 trailing flush（`delay = throttle - elapsed`），内容最终必达，避免"输出在窗口尾部结束→画面卡住直到 finish"
- **长间隔攒批**：>2s 无更新后（工具调用/思考间隙），首个 flush 延迟 300ms 攒批，首屏是有意义的文本而非 1-2 个字符
- flush 与 finish 并发安全：closed 后 pending flush 自动作废
**失败降级**：创建/投递失败 → 静默降级为 sessionWebhook 文本；finish 失败 → 降级发累积文本。

> **模板作用域（dws A/B 实证）**：卡片模板是 app-scoped——hermes 自有模板（c629162a-...）对其他应用
> 渲染"内容加载失败"；默认模板（02fcf2f4...）是 openclaw connector 的公开模板，可跨应用使用。
**真值暴露**：`streamer.CardDelivered()/cardDelivered/card_delivered/cardDelivered()` 返回卡片是否真实投递成功（诊断/livecheck 用，防降级模式被误报为成功）。

**三条防线**：
- **看门狗**（`CardWatchdog`，默认 10min）：卡片建立后计时，成功帧刷新；超时未收口→强制 finish+密封——
  上游 Agent 挂死/dispatch 不返回时卡片不会永久转圈（connector CARD_WATCHDOG_TIMEOUT 同款）
- **显式中止** `Abort()/abort()`：外部打断场景，密封流+卡片置 FAILED，
  与 Finish（正常收口）/Fail（错误文案）互斥且幂等
- **错误兜底冷却**（`ErrorCooldown`，默认 60s）：同会话错误降级文本 60s 内只发一次，防止错误刷屏
  （connector deliveredErrorTypes+ERROR_COOLDOWN 同款）

## 6. 限流（全局令牌桶）

- 容量/速率：默认 20 QPS（官方上限约 40，保守值，可配）。
- 识别：HTTP 403 且响应体 code 字符串含 `QpsLimit`。
- 策略：退避 2s（清空令牌）→ 重取令牌重试一次；streaming 重试换新 guid。

## 7. Markdown 归一化（normalizeForCard）

钉钉 AI 卡片渲染器约定（代码块外）：
- 单 `\n` → `<br>`；`\n\n` 段落保留
- 代码块 ``` 内：保留 `\n`
- Markdown 块语法行（列表 `- / 1.`、表格 `|`、标题 `#`、分隔线）前保留 `\n`
- 连续引用行 `>`：合并为一行 `<br>` 连接，续行去 `>` 前缀
- 表格分隔行前若无空行，插入空行（否则不渲染）

## 8. Token

`POST /v1.0/oauth2/accessToken` `{appKey, appSecret}` → `{accessToken, expireIn}`；
按 clientId 缓存，过期前 60s 刷新。Header 统一 `x-acs-dingtalk-access-token`。

## 9a. 媒体上传（OAPI，对比官方 connector media/common.ts）

1. **OAPI token**：`GET {oapiBase}/gettoken?appkey=&appsecret=` → `{errcode:0, access_token, expires_in}`（缓存，提前 60s 刷新；oapiBase 默认 `https://oapi.dingtalk.com`，与新版 API token 相互独立）
2. **上传**：`POST {oapiBase}/media/upload?access_token=&type={image|file|video|voice}`
   multipart/form-data，字段名 **`media`**（含 filename），Content-Type image 用 `image/jpeg`，其余 `application/octet-stream`
3. **响应**：`{errcode:0, media_id, type, created_at}`；**media_id 去前导 `@`** 后使用
4. **媒体送达能力矩阵（2026-08 真机实验定案）**：
   - OAPI `media/upload` 产出的 mediaId **无公网 URL**——`down.dingtalk.com/media/<id>`（含 @/加扩展名变体）**全部 404**（新鲜上传即时验证），
     因此**卡片内嵌 OAPI 上传的图片不可行**；卡片内嵌仅对**本就公网可达的 URL** 生效（如已在钉钉媒体库的图）
   - **可靠送达 = 独立媒体消息**（对齐官方 connector sendVideo/sendAudio/sendFileProactive 与 dws 现行行为——dws 已下线旧上传命令并在迁移说明中明确"file 消息送达、不渲染内联 image"）：
     `SendFile`（sampleFile：mediaId+fileName+fileType）、`SendVideo`（sampleVideo：videoMediaId+picMediaId+duration）、
     `SendAudio`（sampleAudio：mediaId+duration）——三者均要求 uploadMedia 返回的 **RawMediaID（带 @）**
   - `SendImage`（sampleImageMsg）仅接受公网 photoURL
   - >20MB 文件走分块上传（v0.2 路线）

**真实联调（livecheck）**：每语言提供 `example/livecheck`（Go: `go run ./example/livecheck`；
Node: `npm run live`；Python: `python example/livecheck.py`；
Java: `mvn -q compile exec:java -Dexec.mainClass=...LiveCheck`）。
设置 `DD_CLIENT_ID/DD_CLIENT_SECRET`（可选 `DD_UPLOAD_FILE`）后给机器人发一句话，
逐步 PASS/FAIL：连接 → 收消息 → 文本回复（token）→ 卡片创建（E1）→ 流式全周期（E2/E3）→ 媒体上传。

## 9. 四语言 API 对照

| | Go | Node.js | Python | Java |
|---|---|---|---|---|
| 创建 | `channel.New(cfg)` | `new DingTalkChannel(cfg)` | `DingTalkChannel(cfg)` | `DingTalkChannel.create(cfg)` |
| 收消息 | `ch.OnMessage(func(ctx, msg, reply))` | `ch.on('message', async (msg, reply) => {})` | `@ch.on_message` / `ch.on_message(fn)` | `ch.onMessage((msg, reply) -> {})` |
| 卡片回调 | `ch.OnCardAction(...)` | `ch.on('cardAction', ...)` | `ch.on_card_action(fn)` | `ch.onCardAction(...)` |
| 启动 | `ch.Start(ctx)` | `await ch.start()` | `await ch.start()` | `ch.start()` / `startAsync()` |
| 流式回复 | `st, _ := reply.Stream(); st.Append/Finish` | `const s = reply.stream(); await s.append/finish` | `s = await reply.stream(); await s.append/finish` | `CardStreamer s = reply.stream(); s.append/finish` |

## 目录结构（四层分包）

```
io.github.typefield.dingtalk.channel
├── （根）             公开 API 与组装：DingTalkChannel Config IncomingMessage CardAction
│                      Reply ProactiveSender SendTarget StreamConn TokenProvider
│                      CardClient CardStreamer HttpClient OapiClient Emotion
│                      LifecycleHooks BotIdentity ChannelError TokenBucket
│                      HttpModeVerifier OutboundConfig
├── normalize/         入站归一化：MessageNormalizer
├── safety/            准入与稳定性：PolicyConfig PolicyGate PolicyDecision
│                      RejectEvent RejectReason Deduper ProcessingLock
│                      ChatQueue BatchConfig BatchedMessage MessageBatcher SsrfGuard
└── outbound/          出站处理：RetryUtil MarkdownSplitter MarkdownUtil（卡片渲染预处理）
```
依赖方向严格单向：根 → 子包；safety → 根（IncomingMessage）；normalize/outbound → 根。

## 10. 版本与命名

- 仓库：`dingtalk-channel-sdk-{go,nodejs,python,java}`（对齐 dingtalk-stream-sdk-* 家族）
- UA：`dingtalk-channel-sdk-{lang}/v0.1.0`
- License：MIT
