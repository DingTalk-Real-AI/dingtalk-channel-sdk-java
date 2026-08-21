**English** | [简体中文](./SPEC.zh-CN.md)

# DingTalk Channel SDK — Four-Language Unified Contract (SPEC v0.1)

> Positioning: **A conversation access layer decoupled from Agent runtime**. The SDK handles the dirty work of the "channel",
> developers only write "what the user said and what the bot should respond with."

## 0. Effect Parity Acceptance Checklist (Effect Parity — All Four Languages Verified Against This Standard)

Verified by **end-user observable behavior**, demonstrable item by item:

| # | User-Visible Effect | This SDK Implementation |
|---|---|---|
| E1 | "Typing" card appears **seconds** after sending message (loading) | `reply.stream()` immediately creates card + delivers INPUTING (doesn't wait for first token) |
| E2 | Response content appends **typewriter-style** smoothly | streaming interface + 800ms throttle + non-final frame trailing newline removal (prevents flicker) |
| E3 | Loading disappears after completion, content **finalizes as complete Markdown** | isFinalize final frame + flowStatus=3 + cardUpdateOptions |
| E4 | Card failure/QPS rate limiting **transparent to user** | Creation failure→silent fallback to webhook text; QpsLimit→backoff 2s retry; no error popup |
| E5 | **Same experience in group/DM** | Same Reply API; delivery target auto-selected IM_GROUP/IM_ROBOT; group auto-strips @ prefix |
| E6 | **Never duplicate reply** to same message | Dual-layer deduplication (messageId+msgId, TTL 5min), discarded messages still return ACK |
| E7 | **Card interaction closed loop**: button clicks reach Agent, can update card | OnCardAction (registration auto-subscribes to /v1.0/card/instances/callback, all four languages have unit test coverage for dispatch and subscription) + reply update |
| E8 | Bot **never disconnects** (network outage/server switch transparent) | Exponential backoff reconnection + SYSTEM/disconnect immediate reconnection + heartbeat keepalive + ACK prevents loss |
| E9 | **Media send/receive**: receive images/files downloadable; can upload media and embed | `reply.image(url)` (sampleImageMsg); `reply.uploadMedia()` (OAPI multipart, mediaId can be embedded in card as `![..](mediaId)`); `reply.downloadURL()` |
| E10 | Markdown **rendering quality** (code blocks/tables/lists/quotes) | normalizeForCard normalization (see §7) |

> Effect demonstration script (attached in each language's README): echo + streaming simulation (fake LLM outputs token every 100ms) must show E1→E3 full process.

## 1. Responsibility Boundaries

**SDK is responsible for:**
1. Stream long connection (connect, subscribe, heartbeat, disconnect reconnection, server disconnect handling)
2. Event parsing and deduplication (protocol layer messageId + business layer msgId, dual-layer, TTL 5 minutes)
3. Reply sending (sessionWebhook: text / Markdown)
4. AI card streaming output (create → deliver → INPUTING → streaming → FINISHED, typewriter effect)
5. Card API global rate limiting (token bucket + QpsLimit backoff retry)
6. Markdown normalization (adapt to DingTalk AI card renderer's newline/table rules)

**SDK is NOT responsible for (left to Agent side):**
- Agent runtime (model / prompt / tool orchestration)
- Multi-user topic isolation and Session/context persistence
- Credential storage (only receives clientId/clientSecret)

## 2. Wire Protocol (Stream Mode)

### 2.1 Establish Connection
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
HTTP headers: `Content-Type/Accept: application/json`, `User-Agent: dingtalk-channel-sdk-{lang}/v0.1.0`.
WebSocket connection: `{endpoint}?ticket=<url-encoded ticket>` (**ticket must be URL-encoded**, same as official Python SDK; topic declared in open request, not in URL).

Subscription types: `CALLBACK` (callback) / `EVENT` (event) / `SYSTEM` (system, SDK internally occupies `ping`, `disconnect`).

Fixed topics:
- Bot messages: `/v1.0/im/bot/messages/get` (CALLBACK)
- Card callback: `/v1.0/card/instances/callback` (CALLBACK, subscribed only when OnCardAction registered)

### 2.2 Data Frames
Inbound frame (WebSocket text):
```json
{"specVersion":"1.0","type":"CALLBACK|EVENT|SYSTEM","time":0,
 "headers":{"topic":"...","messageId":"...","contentType":"application/json","time":"..."},
 "data":"<JSON string>"}
```
ACK outbound frame (**must respond**, otherwise server re-delivers; **ACK first**—respond with `{"success":true}` upon receipt, async business processing,
aligned with official connector: prevents server timeout re-delivery during Agent long tasks; duplicate deliveries handled by dual-layer deduplication):

> **Server-side perspective empirical evidence** (lippi-open-proxy source code, 2026-07-23 production incident postmortem cross-validation):
> ① Server push is `UnaryRequest`—**synchronously waits for ACK**, upstream timeout ~2s; if not received, **re-delivers via MetaQ (at-least-once)**,
> and re-delivery generates new messageId (business layer msgId deduplication is necessary, protocol layer single-layer is insufficient)—this SDK's ACK first + dual-layer deduplication
> strictly aligns with this semantics. ② Heartbeat contract is **client ping, server auto pong** (gorilla default behavior); when server read loop blocks,
> pong stops, client should timeout reconnect—this SDK's 120s idle ping + 5s pong death detection is the client implementation of this contract.
> ③ Server ACK validation only requires headers non-empty + contains messageId; only send/receive Text frames; ticket is server connectionId (passed via URL query).
> ④ **Risk mitigation**: 0723 incident proved server read loop has a mode of being blocked by late/duplicate ACKs (fix is on
> 20260723 release branch, whether deployed to production subject to release system; local master is stale snapshot, doesn't represent production version).
> Regardless of server-side fix status, all four client-side defenses are necessary for production self-healing: exactly-once ACK per frame, ACK first (no late ACKs),
> pong timeout death detection reconnect (only escape when server wedged), exponential backoff+jitter reconnect (prevent storm amplification).
> Latency-sensitive scenarios can lower KeepAliveIdleMs (default 120s) to speed up wedged detection.
```json
{"code":200,"headers":{"contentType":"application/json","messageId":"<same as frame>"},
 "message":"ok","data":"{\"success\":true}"}
```
- `SYSTEM/ping`: Respond pong, `data` echoed back.
- `SYSTEM/disconnect`: Close connection and reconnect immediately (server LB switch).

### 2.3 Heartbeat and Reconnection
- Send WebSocket protocol layer Ping after 120s idle, declare dead if no Pong received within 5s.
- Reconnection: exponential backoff 1s→2s→4s…capped at 30s (with jitter); clear on successful reconnection.
- Read loop exception/disconnect → auto-reconnect (configurable AutoReconnect=false).

> Compared with official stream-sdk family: official heartbeat Go=120s idle+5s pong, Java(Netty)=60s idle+pong,
> Python=60s ping (no pong detection), Node=isAlive flag+terminate; this SDK uniformly adopts Go tier (120s+5s pong),
> stronger than Python/Node. Reconnection official is fixed 3s/10s, this SDK uses exponential backoff+jitter (aligned with official connector).

## 3. Event Model

### 3.1 IncomingMessage (After Normalization)
| Field | Source | Description |
|---|---|---|
| ConversationID | conversationId | Conversation ID |
| ConversationType | conversationType | "1"=DM "2"=group → normalized to `dm`/`group` |
| ConversationTitle | conversationTitle | Group name (group chat) |
| SenderID / SenderStaffID | senderId / senderStaffId | Encrypted ID / Staff ID |
| SenderNick | senderNick | Nickname |
| SenderCorpID | senderCorpId | |
| Text | text.content | **@bot prefix and whitespace removed and trimmed** |
| MsgType / Content | msgtype / content | Rich content (images/files etc. passed through as-is) |
| AtUsers | atUsers[] | [{dingtalkId, staffId}] |
| SessionWebhook | sessionWebhook | Reply webhook (includes expiration SessionWebhookExpiredTime) |
| MsgID / CreateAt | msgId / createAt | Business dedup key / Event timestamp |
| Raw | Original data | |

### 3.2a Stale Message Filtering

Inbound messages with `createAt` older than `StaleMessageWindow` (default 30min, <=0 disables) from now are discarded directly (still ACK)—
old messages flooding in during reconnection storms/re-delivery backlogs no longer trigger replies.
1. Protocol layer: `headers.messageId` (duplicate callbacks for same delivery)
2. Business layer: `data.msgId` (messageId changes on server re-delivery, msgId doesn't)
TTL 5 minutes, LRU cleanup. Hits are discarded (still ACK success).

## 4. Reply API (Four Languages Consistent Semantics)

```
reply.text(content)                     → sessionWebhook, msgKey=sampleText
reply.markdown(title, text)             → sessionWebhook, msgKey=sampleMarkdown
reply.image(url)                        → sessionWebhook, msgKey=sampleImageMsg
reply.downloadURL(downloadCode,msgId)   → GET /v1.0/robot/messageFiles/download
reply.uploadMedia(type,name,data[,ct]) → OAPI upload, returns mediaId (see §9a)
s = reply.stream()                      → immediately creates AI card (E1: "typing" card first)
s.append(delta) / s.append(fullText)    → streaming update (cumulative semantics decided by caller)
s.finish() / s.finish(fullText)         → final frame + FINISHED
s.fail(errText)                         → FINISHED(flowStatus=5) or fallback text
```

**Oversized chunking**: `TextChunkLimit` (default 3500, <=0 disables)—
text/Markdown replies exceeding limit are split by **newline boundary** and sent multiple times (hard cut if no suitable newline), no content loss.

sessionWebhook payload:
```json
{"msgKey":"sampleText","msgParam":"{\"content\":\"...\"}"}
{"msgKey":"sampleMarkdown","msgParam":"{\"title\":\"...\",\"text\":\"...\"}"}
```
**Note**: `msgParam` must be **stringified JSON** (official documentation requirement, object form returns 400).
Header: `x-acs-dingtalk-access-token: <token>`.

## 4a. Proactive Messaging (Proactive Send)

Independent of inbound messages, Agent can initiate at any time:

```
channel.SendText(target, text)
channel.SendMarkdown(target, title, text)      // target can include @: AtUserIds/AtDingtalkIds/AtAll
channel.SendImage(target, imageURL)            // requires publicly accessible URL
```

- DM (target.UserID): `POST /v1.0/robot/oToMessages/batchSend` `{robotCode, userIds:[...], msgKey, msgParam}`
- Group (target.ConversationID): `POST /v1.0/robot/groupMessages/send` `{robotCode, openConversationId, msgKey, msgParam, atUserIds?, atOpendingtalkIds?, isAtAll?}`

## 4b. Policy Gating and Group-Level Overrides

Global policy (PolicyConfig): group allowlist/blocklist, `RequireMention` (default true), DM mode
(open/allowlist/blocklist/disabled) and corresponding lists.

**Group-level overrides (GroupOverrides)**: per-conversationId group override—`Enabled` (explicit disable),
`RequireMention` (@ requirement for this group), `AllowFrom`/`BlockFrom` (sender allowlist/blocklist within group, blocklist takes precedence).
Evaluation order (consistent across four languages):

1. Global blocklist (highest priority, group overrides cannot exempt)
2. Allowlist admission: global allowlist hit, **or explicit group entry exists** (explicit entry can admit this group in allowlist mode)
3. `Enabled=false` → reject (`group_disabled`)
4. @bot check (group override takes precedence over global)
5. `BlockFrom` → `AllowFrom` (sender filtering within group)

## 5. AI Card Protocol (Five Steps)

Template ID default: `02fcf2f4-5e02-4a85-b672-46d1f715543e.schema` (official AI card, configurable).

1. **Create** `POST /v1.0/card/instances`
   `{cardTemplateId, outTrackId: "card_{ts}_{rand}", cardData:{cardParamMap:{config:"{\"autoLayout\":true}"}}, callbackType:"STREAM", imGroupOpenSpaceModel:{supportForward:true}, imRobotOpenSpaceModel:{supportForward:true}}`
2. **Deliver** `POST /v1.0/card/instances/deliver`
   - Group: `{outTrackId, userIdType:1, openSpaceId:"dtv1.card//IM_GROUP.{conversationId}", imGroupOpenDeliverModel:{robotCode}}`
   - DM: `{outTrackId, userIdType:1, openSpaceId:"dtv1.card//IM_ROBOT.{senderStaffId||senderId}", **imRobotOpenDeliverModel**:{spaceType:"IM_ROBOT", robotCode, extension:{dynamicSummary:"true"}}}`
   - robotCode = clientId
   - ⚠️ DM field must be `imRobotOpenDeliverModel` (Deliver not Space; official connector's `imRobotOpenSpaceModel` variant rejected by production: `400 param.spaceDeliverModelEmpty`—2026-08 real device empirical evidence, dws source code is ground truth)
   - ⚠️ **Business-level validation**: deliver returns `{"result":[{"success":false,...}]}` within HTTP 200 (dws production empirical "observed live"), SDK must scan body for `"success":false` and treat as failure (this SDK's create/deliver both do callChecked)
3. **First frame set INPUTING** `PUT /v1.0/card/instances`
   `{outTrackId, cardData:{cardParamMap:{flowStatus:"2", msgContent:<norm>, staticMsgContent:"", sys_full_json_obj:"{\"order\":[\"msgContent\"]}", config:"{\"autoLayout\":true}"}}}`
4. **Streaming update** `PUT /v1.0/card/streaming`
   `{outTrackId, guid:"{ts}_{rand}", key:"msgContent", content:<norm>, isFull:true, isFinalize:<bool>, isError:false}`
   Non-final frames remove trailing consecutive newlines (prevents flicker).
5. **Close with FINISHED** `PUT /v1.0/card/instances`
   First send streaming with isFinalize=true, then set `{outTrackId, cardData:{cardParamMap:{flowStatus:"3", msgContent, ...}}, cardUpdateOptions:{updateCardDataByKey:true}}`

flowStatus: 1=PROCESSING 2=INPUTING 3=FINISHED 4=EXECUTING 5=FAILED.

**Frame rhythm (dws connect_card.go empirical port)**:
- **Frame interval 500ms**: Must leave gap between first content frame and delivery, between final frame and previous frame—back-to-back races with client card pull,
  intermittently renders "content load failed" (this race killed dws #407 card implementation)
- **Single frame content limit 20000** (rune-safe truncation, hermes MAX_MESSAGE_LENGTH counterpart)

**Status badges (aligned with dws/hermes)**: `MarkThinking/MarkDone` puts text emotion on **user message**
(`POST /v1.0/robot/emotion/reply|recall`, emotionType=2, emotionId=2659900):
"🤔Thinking" indicates processing, "🥳Done" indicates completion; only supports user-sent messages (bot messages 500); best-effort doesn't block reply.

**Throttling**:
- Single card streaming update minimum interval 800ms (DingTalk card has same-card concurrency protection, official connector battle-tested value)
- **Updates within window not discarded**: schedules trailing flush (`delay = throttle - elapsed`), content eventually reaches, avoids "output ends at window tail→screen stuck until finish"
- **Long interval batching**: After >2s no updates (tool call/thinking gap), first flush delays 300ms to batch, first screen shows meaningful text instead of 1-2 characters
- flush and finish concurrency safe: pending flush auto-invalidated after closed
**Failure fallback**: create/deliver failure → silently fallback to sessionWebhook text; finish failure → fallback sends accumulated text.

> **Template scope (dws A/B empirical)**: Card template is app-scoped—hermes proprietary template (c629162a-...) renders "content load failed" for other apps;
> default template (02fcf2f4...) is openclaw connector's public template, can be used across apps.
**Truth exposure**: `streamer.CardDelivered()/cardDelivered/card_delivered/cardDelivered()` returns whether card actually delivered successfully (diagnostics/livecheck use, prevents fallback mode misreported as success).

**Three lines of defense**:
- **Watchdog** (`CardWatchdog`, default 10min): timer starts after card created, success frame refreshes; timeout without close→force finish+seal—
  upstream Agent crash/dispatch doesn't return won't leave card spinning forever (connector CARD_WATCHDOG_TIMEOUT counterpart)
- **Explicit abort** `Abort()/abort()`: external interrupt scenario, seals stream+card set FAILED,
  mutually exclusive and idempotent with Finish (normal close)/Fail (error text)
- **Error cooldown** (`ErrorCooldown`, default 60s): same session error fallback text only sent once within 60s, prevents error spam
  (connector deliveredErrorTypes+ERROR_COOLDOWN counterpart)

## 6. Rate Limiting (Global Token Bucket)

- Capacity/rate: default 20 QPS (official limit ~40, conservative value, configurable).
- Recognition: HTTP 403 and response body code string contains `QpsLimit`.
- Strategy: backoff 2s (empty tokens) → re-acquire token retry once; streaming retry with new guid.

## 7. Markdown Normalization (normalizeForCard)

DingTalk AI card renderer conventions (outside code blocks):
- Single `\n` → `<br>`; `\n\n` paragraph preserved
- Code block ``` inside: preserve `\n`
- Markdown block syntax lines (list `- / 1.`, table `|`, heading `#`, horizontal rule) preserve preceding `\n`
- Consecutive quote lines `>`: merge into one line connected by `<br>`, continuation lines strip `>` prefix
- Insert blank line before table separator line if none (otherwise doesn't render)

## 8. Token

`POST /v1.0/oauth2/accessToken` `{appKey, appSecret}` → `{accessToken, expireIn}`;
cached by clientId, refresh 60s before expiration. Header uniformly `x-acs-dingtalk-access-token`.

## 9a. Media Upload (OAPI, compared to official connector media/common.ts)

1. **OAPI token**: `GET {oapiBase}/gettoken?appkey=&appsecret=` → `{errcode:0, access_token, expires_in}` (cached, refresh 60s early; oapiBase default `https://oapi.dingtalk.com`, independent from new version API token)
2. **Upload**: `POST {oapiBase}/media/upload?access_token=&type={image|file|video|voice}`
   multipart/form-data, field name **`media`** (includes filename), Content-Type image uses `image/jpeg`, others `application/octet-stream`
3. **Response**: `{errcode:0, media_id, type, created_at}`; **strip leading `@` from media_id** before use
4. **Media delivery capability matrix (2026-08 real device experimental conclusion)**:
   - OAPI `media/upload` produced mediaId **has no public URL**—`down.dingtalk.com/media/<id>` (with @ / extension variants) **all 404** (fresh upload immediate validation),
     therefore **embedding OAPI-uploaded images in cards not feasible**; card embedding only works for **URLs already publicly accessible** (e.g., already in DingTalk media library)
   - **Reliable delivery = standalone media message** (aligned with official connector sendVideo/sendAudio/sendFileProactive and dws current behavior—dws deprecated old upload command and clarified in migration notes "file message delivery, doesn't render inline image"):
     `SendFile` (sampleFile: mediaId+fileName+fileType), `SendVideo` (sampleVideo: videoMediaId+picMediaId+duration),
     `SendAudio` (sampleAudio: mediaId+duration)—all three require uploadMedia returned **RawMediaID (with @)**
   - `SendImage` (sampleImageMsg) only accepts public photoURL
   - >20MB files use chunked upload (v0.2 roadmap)

**Real integration (livecheck)**: Each language provides `example/livecheck` (Go: `go run ./example/livecheck`;
Node: `npm run live`; Python: `python example/livecheck.py`;
Java: `mvn -q compile exec:java -Dexec.mainClass=...LiveCheck`).
Set `DD_CLIENT_ID/DD_CLIENT_SECRET` (optional `DD_UPLOAD_FILE`) then send a message to the bot,
progressive PASS/FAIL: connection → receive message → text reply (token) → card creation (E1) → streaming full cycle (E2/E3) → media upload.

## 9. Four-Language API Comparison

| | Go | Node.js | Python | Java |
|---|---|---|---|---|
| Create | `channel.New(cfg)` | `new DingTalkChannel(cfg)` | `DingTalkChannel(cfg)` | `DingTalkChannel.create(cfg)` |
| Receive Message | `ch.OnMessage(func(ctx, msg, reply))` | `ch.on('message', async (msg, reply) => {})` | `@ch.on_message` / `ch.on_message(fn)` | `ch.onMessage((msg, reply) -> {})` |
| Card Callback | `ch.OnCardAction(...)` | `ch.on('cardAction', ...)` | `ch.on_card_action(fn)` | `ch.onCardAction(...)` |
| Start | `ch.Start(ctx)` | `await ch.start()` | `await ch.start()` | `ch.start()` / `startAsync()` |
| Streaming Reply | `st, _ := reply.Stream(); st.Append/Finish` | `const s = reply.stream(); await s.append/finish` | `s = await reply.stream(); await s.append/finish` | `CardStreamer s = reply.stream(); s.append/finish` |

## Directory Structure (Four-Layer Package)

```
com.dingtalk.dingtalk.channel
├── (root)             Public API and assembly: DingTalkChannel Config IncomingMessage CardAction
│                      Reply ProactiveSender SendTarget StreamConn TokenProvider
│                      CardClient CardStreamer HttpClient OapiClient Emotion
│                      LifecycleHooks BotIdentity ChannelError TokenBucket
│                      HttpModeVerifier OutboundConfig
├── normalize/         Inbound normalization: MessageNormalizer
├── safety/            Admission and stability: PolicyConfig PolicyGate PolicyDecision
│                      RejectEvent RejectReason Deduper ProcessingLock
│                      ChatQueue BatchConfig BatchedMessage MessageBatcher SsrfGuard
└── outbound/          Outbound processing: RetryUtil MarkdownSplitter MarkdownUtil (card rendering preprocessing)
```
Dependency direction strictly unidirectional: root → sub-packages; safety → root (IncomingMessage); normalize/outbound → root.

## 10. Version and Naming

- Repository: `dingtalk-channel-sdk-{go,nodejs,python,java}` (aligned with dingtalk-stream-sdk-* family)
- UA: `dingtalk-channel-sdk-{lang}/v0.1.0`
- License: MIT
