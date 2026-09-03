# dingtalk-channel-sdk-java

**English** | [简体中文](./README.zh-CN.md)

DingTalk Channel SDK (Java) — a conversation access layer decoupled from any agent runtime: Stream long connection, inbound event normalization, a unified safety pipeline, and streaming AI-card replies, all behind one high-level Channel.

Requires JDK 8+. Dependencies: Java-WebSocket and Gson only.

## Install

```xml
<dependency>
  <groupId>com.dingtalk</groupId>
  <artifactId>dingtalk-channel-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

Available after release; locally build with `mvn install`.

## Minimal Example

```java
DingTalkChannel ch = DingTalkChannel.create(
        Config.builder(System.getenv("DD_CLIENT_ID"), System.getenv("DD_CLIENT_SECRET")).build());

ch.onMessage((msg, reply) -> reply.text("received: " + msg.text));

ch.start();   // blocks, auto-reconnects
```

`start()` establishes the Stream long connection and blocks (auto-reconnect); replies go through the sessionWebhook, no public ingress required.

## Highlights

- **Streaming AI-card replies**: `reply.stream()` delivers a "typing" card immediately, `s.append(token)` streams tokens (800ms throttle), `s.finish("")` freezes the card; orphan watchdog, text fallback on card failure, token-bucket rate limiting with QpsLimit backoff
- **Unified safety pipeline**: two-layer dedup (messageId + msgId), policy gate (per-group overrides / @-mention), per-chat serialization and batching, consecutive-media window merging
- **Outbound reliability**: structured error classification (`isRetryable()` / `isReplyTargetGone()`) for retry policies
- **Dual transport**: Stream (default) and HTTP mode (official signature verification built in)
- **livecheck**: one-command verification against the real environment

Streaming in three lines:

```java
CardStreamer s = reply.stream();
for (String token : myLLM(msg.text)) s.append(token);
s.finish("");
```

## Documentation

| Topic | Content |
|-------|---------|
| [SPEC.md](./SPEC.md) | Shared four-language contract and the E1–E10 acceptance checklist |
| [GUIDE.md](./GUIDE.md) | Integration guide: bring your agent into DMs and group chats |
| [OVERVIEW.md](./OVERVIEW.md) | Architecture layers and module overview |
| Advanced config | Policy / hooks / batching / outbound / HTTP mode (see below) |

## Examples

| Example | Description |
|---------|-------------|
| `example/EchoBot` | Minimal echo bot |
| `example/FullFlow` | Full feature: proactive send + media upload & embedding |
| `example/LiveCheck` | One-command live verification |

## Package Boundaries

Application code typically imports only the root package:

```java
import com.dingtalk.channel.DingTalkChannel;
```

Sub-packages (`normalize` / `safety` / `outbound`) are internal layering and carry no compatibility promise.

## Advanced Config

| Option | Default | Description |
|--------|---------|-------------|
| `policyConfig` | allow all | Admission policy: @-mention requirement, group/sender allow-block lists, per-group overrides |
| `chatQueue` | enabled | Strict per-conversation serialization |
| `mediaBatch` | disabled | Merge consecutive pictures/files/audio/video within a window |
| `outbound` | — | Unified footer, before/after-send hooks |
| `ssrfAllowlist` | — | Exempt internal CDN download URLs |
| `transport` | `stream` | `http` = HTTP mode (`ch.handleHttpCallback(body, timestamp, sign)`) |
| `ch.onReject` | — | Reject-event callback (with reason) |

Proactive send: `ch.sendText(SendTarget.user("staff-1"), "hello")` (groups use `SendTarget.group("cid...")`, @ mentions supported).

## Development

```bash
mvn test        # 34 tests
```

Live check:

```bash
DD_CLIENT_ID=... DD_CLIENT_SECRET=... \
  mvn -q compile exec:java -Dexec.mainClass=com.dingtalk.channel.example.LiveCheck
```

## License

MIT
