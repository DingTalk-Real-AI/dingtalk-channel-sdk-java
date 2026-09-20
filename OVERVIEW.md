# DingTalk Channel SDK Family · Project Overview

> Origin issue: [DingTalk-Real-AI/dingtalk-workspace-cli#796](https://github.com/DingTalk-Real-AI/dingtalk-workspace-cli/issues/796)—"Will DingTalk offer a Channel-like integration SDK in the future?"
> This project provides the answer: **Four languages, effect parity, ready to use**.

## 1. Deliverables

| Repository | Language | Dependencies | Tests |
|---|---|---|---|
| [DingTalk-Real-AI/dingtalk-channel-sdk-go](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-go) | Go 1.22+ | gorilla/websocket | 19 tests (-race clean) |
| [DingTalk-Real-AI/dingtalk-channel-sdk-nodejs](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-nodejs) | Node 18+ | ws | 12 tests |
| [DingTalk-Real-AI/dingtalk-channel-sdk-python](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-python) | Python 3.10+ | websockets | 12 tests |
| [DingTalk-Real-AI/dingtalk-channel-sdk-java](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-java) | JDK 8+ | Java-WebSocket + Gson | 12 tests |

Each repository contains: complete source code, unit tests, `SPEC.md` (unified contract across four languages), streaming echo examples, **livecheck real integration program**, README, MIT LICENSE.

## 2. Positioning and Boundaries

**A conversation access layer decoupled from Agent runtime**. The SDK handles all the dirty work of the "channel", developers only write "what the user said and what the bot should respond with":

- **Responsible for**: Stream long connection (connect/heartbeat/exponential backoff reconnection/server disconnect recovery), dual-layer event deduplication + expired message filtering, sessionWebhook replies (text/Markdown/image, auto-chunking for oversized content), AI card streaming output (typewriter effect, inter-frame interval race protection, watchdog orphan protection), card API global rate limiting and QpsLimit backoff, media upload/download and media messages (file/video/audio), Markdown normalization, proactive messaging (DM/group + @), 🤔Thinking/🥳Done status badges, explicit abort, error fallback cooldown
- **Not responsible for**: Agent runtime (model/prompt/tool orchestration), conversation context persistence, credential storage, business operations (docs/spreadsheets/calendar—domain of dws CLI and skills)

## 3. Quick Start (Four Languages Isomorphic)

```go
ch := channel.New(channel.Config{ClientID: "ding...", ClientSecret: "..."})
ch.OnMessage(func(ctx context.Context, msg *channel.IncomingMessage, reply channel.Reply) error {
    s, _ := reply.Stream(ctx)              // "Typing" card appears in seconds
    for _, tok := range myLLM(msg.Text) {
        _ = s.Append(tok)                  // Typewriter append (800ms throttle + trailing flush)
    }
    return s.Finish("")                    // Final frame finalization
})
ch.Start(ctx)
```

```js
ch.on('message', async (msg, reply) => { const s = await reply.stream(); ... await s.finish(); });
```
```python
@ch.on_message
async def handle(msg, reply): s = await reply.stream(); await s.append(tok); await s.finish()
```
```java
ch.onMessage((msg, reply) -> { CardStreamer s = reply.stream(); s.append(tok); s.finish(""); });
```

Non-streaming: `reply.Text/Markdown/Image`, attachment download `reply.DownloadURL`, media upload `reply.UploadMedia`;
Proactive messaging (independent of inbound): `ch.SendText/SendMarkdown/SendImage`, group messaging supports `AtUserIds/AtAll`;
Card interaction: `ch.OnCardAction` (registration automatically subscribes to card topic).

## 4. Effect Parity (Acceptance Checklist E1–E10, see SPEC §0)

| | User-Visible Effect | Implementation |
|---|---|---|
| E1 | "Typing" card appears seconds after sending message | `stream()` immediately creates card + delivers INPUTING |
| E2 | Smooth typewriter append | streaming interface + 800ms throttle + **trailing flush** (no drops within window) + long interval 300ms batching |
| E3 | Loading disappears after completion, Markdown finalizes | isFinalize final frame + FINISHED status |
| E4 | Card failure/rate limiting transparent to user | Silent fallback to webhook text; QpsLimit backoff 2s retry |
| E5 | Same experience in group/DM | Same Reply API; delivery target auto-selected; group strips @ prefix |
| E6 | Never duplicate replies | messageId+msgId dual-layer deduplication (TTL 5min) |
| E7 | Card interaction closed loop | OnCardAction + auto-subscribe |
| E8 | Never disconnects | Exponential backoff reconnection + disconnect immediate reconnection + 120s/5s heartbeat + ACK first |
| E9 | Media send/receive | uploadMedia (OAPI multipart) / downloadURL / image reply |
| E10 | Markdown rendering quality | normalizeForCard (code blocks/tables/quotes DingTalk rendering rules) |

Each item has corresponding unit tests in all four languages; E8 has dedicated disconnect reconnection e2e regression.

## 5. Protocol Fidelity (Ground Truth, Not Documentation Speculation)

| Capability | Ground Truth Source |
|---|---|
| Stream wire protocol (open/wss/frames/ACK/heartbeat/topic constants) | Official dingtalk-stream-sdk-go source code line-by-line comparison |
| AI card five-step protocol + rate limiting + Markdown normalization | Official connector (dingtalk-openclaw-connector) card.ts |
| Token (new version/OAPI dual-track) and sessionWebhook payload | Official connector token.ts / messaging.ts + official documentation validation |
| Proactive messaging API | dws (dingtalk-workspace-cli) source code |
| Ticket encoding / localIp / UA headers | Cross-referenced across all four official stream SDKs |

Protocol-level issues fixed during review rounds: msgParam JSON stringification (official documentation requirement), Go disconnect mis-stop, ACK first semantics, ticket URL encoding.

## 6. Key Architectural Decision: Why Build Custom Transport Layer Instead of Using Official stream-sdk

1. **Official connector doesn't trust itself**: DingTalk official connector source code has `autoReconnect:false, keepAlive:false` all disabled and rewritten (issues #571/#536/#573)
2. **Four-language consistency is this project's acceptance standard**, while official four SDKs have inconsistent heartbeat/reconnection/encoding behaviors; referencing them inherits divergence
3. **Dependency weight**: Official Java version pulls Netty multi-modules, Python version includes requests+aiohttp dual HTTP stack; custom implementation only 1–2 small dependencies per language, transport layer ~300 lines/language
4. Leaves evolution seam (`Channel → StreamConn → onFrame` single boundary), official SDK adapter backend can be added later when upstream matures

## 7. Quality Evidence

- **Tests**: Go 14 / Node 12 / Python 12 / Java 12 (BUILD SUCCESS), all include e2e (mock gateway + mock API) and disconnect reconnection regression
- **Real integration**: Each language's `example/livecheck` one-command verification (connection→receive message→text reply→card streaming full cycle→media upload, progressive PASS/FAIL):
  `DD_CLIENT_ID=... DD_CLIENT_SECRET=... go run ./example/livecheck` (Node `npm run live`; Python `python example/livecheck.py`; Java `mvn exec:java`)
- **Code review**: Three rounds (protocol consistency / effect parity / stream layer vs official SDK), fixed 8 issues, all have regression tests

## 8. Known Boundaries and Roadmap

- Real credential live testing to be executed (livecheck ready, one command)
- >20MB file chunked upload (v0.2)
- Card template default value is connector built-in template, recommended to make it configurable for public release
- Platform differences (reaction/comment/forward) will follow DingTalk Open Platform evolution
- Optional: Official stream-sdk adapter backend (seam already left via `WithTransport`)

## 9. Prerequisites

Create **Enterprise Internal Application** in DingTalk Developer Console and enable bot, obtain ClientID/ClientSecret. Stream mode requires no public IP or domain.

---
License: MIT | Contract: `SPEC.md` in each repo | Version: v0.1.0 (Aug 2026)
