**English** | [简体中文](./GUIDE.zh-CN.md)

# DingTalk Channel SDK Integration Guide

Connect your Agent to DingTalk for real-time conversations in **group chats and direct messages**. The SDK handles event ingestion, message parsing and deduplication, reply sending, streaming output (typewriter effect), media upload/download, and card interactions—you just need to tell it "what the user said and what the bot should respond with."

## Features in Action

Taking a customer service Agent as an example, you get three out-of-the-box capabilities after integration:

- **Streaming Replies**: A "typing" card appears within seconds of the user's question, with the answer appending character by character as the LLM generates, finalizing as Markdown upon completion
- **Card Interactions**: When a button on a bot-sent card is clicked, the event returns to your Agent for continued conversation or card updates
- **Proactive Notifications**: Independent of user messages, the Agent can send messages to individuals or groups at any time (with @ support)

## What the Channel SDK Does

| Without SDK (Self-built) | With Channel SDK |
|---|---|
| Study Stream protocol, WebSocket connection, heartbeat, reconnection | `channel.New(Config{...})` one line, self-healing connection |
| Parse raw callback frames, field mapping, deduplication | Unified `IncomingMessage` / `CardAction`, dual-layer deduplication |
| Integrate four sets of AI card creation/delivery/streaming/finalization APIs | `reply.Stream()` + `Append()` auto-refresh (throttling + trailing flush) |
| Handle rate limiting, failure fallback, group/DM differences | Built-in: QpsLimit backoff retry, fallback to text, automatic target selection |

## SDK Integration (Five Steps, SDK Handles Four)

1. **Transport Connection**: Stream long connection establishment, subscription, heartbeat keepalive, exponential backoff reconnection, server disconnect recovery — *SDK built-in*
2. **Event Transformation**: Raw callback frames normalized to unified structures (`IncomingMessage`, `CardAction`) — *SDK built-in*
3. **Reply Strategy**: Message deduplication (protocol + business layer), same-session serialization, group @ stripping, card failure fallback — *SDK built-in*
4. **Business Dispatch**: Register your handler via `OnMessage / on('message') / @on_message` — **The only step you write**
5. **Outbound Rendering**: Agent output to DingTalk messages/AI cards, including streaming refresh, throttling, final frame finalization, media embedding — *SDK built-in*

## Multi-Language SDKs

| Language | Repository | Installation |
|---|---|---|
| Go | [dingtalk-channel-sdk-go](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-go) | `go get github.com/DingTalk-Real-AI/dingtalk-channel-sdk-go` |
| Node.js | [dingtalk-channel-sdk-nodejs](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-nodejs) | `npm install dingtalk-channel-sdk` |
| Python | [dingtalk-channel-sdk-python](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-python) | `pip install dingtalk-channel-sdk` |
| Java | [dingtalk-channel-sdk-java](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-java) | Maven `com.dingtalk:dingtalk-channel-sdk` |

## Prerequisites (One-time Setup)

1. Create an **Enterprise Internal Application** in [DingTalk Developer Console](https://open-dev.dingtalk.com)
2. Add **Robot** capability to the application, note down **ClientID (AppKey) / ClientSecret (AppSecret)**
3. No public IP, no domain, no webhook required (Stream long connection mode)

## Quick Start (Same Example · Four Languages Complete Comparison)

Same logic flow: receive message → streaming card reply (typewriter) → card button handling → blocking run.
Each snippet is a complete, copy-paste ready example; steps correspond one-to-one across four languages.

### Go

```go
ch := channel.New(channel.Config{
    ClientID:     os.Getenv("DD_CLIENT_ID"),
    ClientSecret: os.Getenv("DD_CLIENT_SECRET"),
})

ch.OnMessage(func(ctx context.Context, msg *channel.IncomingMessage, reply channel.Reply) error {
    if msg.Text == "" {
        return nil // Non-text messages in msg.Content / msg.MsgType
    }
    s, _ := reply.Stream(ctx)                  // ① Immediately show "typing" card
    answer := myAgent(ctx, msg.Text)           // ② Your Agent
    for _, tok := range streamTokens(answer) { // ③ Stream append (throttling + trailing flush built-in)
        _ = s.Append(tok)
    }
    return s.Finish(answer)                    // ④ Final frame finalize Markdown
})

ch.OnCardAction(func(ctx context.Context, a *channel.CardAction, reply channel.Reply) error {
    return reply.Text(ctx, "Button click received: "+string(a.DataContent))
})

// Other reply methods
_ = reply.Markdown(ctx, "Title", "# Content")
_ = reply.Image(ctx, "https://.../a.png")
media, _ := reply.UploadMedia(ctx, "image", "a.png", "", imgBytes) // → ![..](media.MediaID) embed in card
url, _ := reply.DownloadURL(ctx, code, msg.MsgID)                  // Attachment download URL

// Proactive messaging (independent of inbound)
_ = ch.SendText(ctx, channel.SendTarget{UserID: "staff-1"}, "Proactive DM notification")
_ = ch.SendMarkdown(ctx, channel.SendTarget{ConversationID: "cid...", AtUserIds: []string{"u1"}}, "Daily Report", "@u1 Build complete")

log.Fatal(ch.Start(ctx)) // Blocking run, auto-reconnect on disconnect
```

### Node.js

```js
const ch = new DingTalkChannel({
  clientId: process.env.DD_CLIENT_ID,
  clientSecret: process.env.DD_CLIENT_SECRET,
});

ch.on('message', async (msg, reply) => {
  if (!msg.text) return; // Non-text messages in msg.content / msg.msgType
  const s = await reply.stream();              // ①
  const answer = await myAgent(msg.text);      // ②
  for await (const tok of streamTokens(answer)) {
    await s.append(tok);                       // ③
  }
  await s.finish(answer);                      // ④
});

ch.on('cardAction', async (action, reply) => {
  await reply.text('Button click received: ' + JSON.stringify(action.dataContent));
});

// Other reply methods
await reply.markdown('Title', '# Content');
await reply.image('https://.../a.png');
const media = await reply.uploadMedia('image', 'a.png', imgBytes); // → ![..](media.mediaId)
const url = await reply.downloadURL(code, msg.msgId);

// Proactive messaging
await ch.sendText({ userId: 'staff-1' }, 'Proactive DM notification');
await ch.sendMarkdown({ conversationId: 'cid...', atUserIds: ['u1'] }, 'Daily Report', '@u1 Build complete');

const controller = new AbortController();
process.on('SIGINT', () => controller.abort());
await ch.start(controller.signal); // Blocking run, auto-reconnect on disconnect
```

### Python

```python
ch = DingTalkChannel(
    client_id=os.environ["DD_CLIENT_ID"],
    client_secret=os.environ["DD_CLIENT_SECRET"],
)

@ch.on_message
async def handle(msg, reply):
    if not msg.text:
        return  # Non-text messages in msg.content / msg.msg_type
    s = await reply.stream()               # ①
    answer = await my_agent(msg.text)      # ②
    for tok in stream_tokens(answer):
        await s.append(tok)                # ③
    await s.finish(answer)                 # ④

@ch.on_card_action
async def on_card(action, reply):
    await reply.text(f"Button click received: {action.data_content}")

# Other reply methods
await reply.markdown("Title", "# Content")
await reply.image("https://.../a.png")
media = await reply.upload_media("image", "a.png", img_bytes)  # → ![..](media["mediaId"])
url = await reply.download_url(code, msg.msg_id)

# Proactive messaging
await ch.send_text(SendTarget(user_id="staff-1"), "Proactive DM notification")
await ch.send_markdown(SendTarget(conversation_id="cid...", at_user_ids=["u1"]), "Daily Report", "@u1 Build complete")

asyncio.run(ch.start())  # Blocking run, auto-reconnect on disconnect
```

### Java

```java
DingTalkChannel ch = DingTalkChannel.create(Config.builder(
        System.getenv("DD_CLIENT_ID"), System.getenv("DD_CLIENT_SECRET")).build());

ch.onMessage((msg, reply) -> {
    if (msg.text.isEmpty()) return;          // Non-text messages in msg.content / msg.msgType
    CardStreamer s = reply.stream();         // ①
    String answer = myAgent(msg.text);       // ②
    for (String tok : streamTokens(answer)) {
        s.append(tok);                       // ③
    }
    s.finish(answer);                        // ④
});

ch.onCardAction((action, reply) ->
        reply.text("Button click received: " + action.dataContent));

// Other reply methods (within handler)
reply.markdown("Title", "# Content");
reply.image("https://.../a.png");
OapiClient.MediaUploadResult media = reply.uploadMedia("image", "a.png", "", imgBytes); // → ![..](media.mediaId)
String url = reply.downloadUrl(code, msg.msgId);

// Proactive messaging
ch.sendText(SendTarget.user("staff-1"), "Proactive DM notification");
ch.sendMarkdown(SendTarget.group("cid...").atUserIds("u1"), "Daily Report", "@u1 Build complete");

Runtime.getRuntime().addShutdownHook(new Thread(ch::close));
ch.start(); // Blocking run, auto-reconnect on disconnect
```

### API Quick Reference

| Capability | Go | Node.js | Python | Java |
|---|---|---|---|---|
| Create | `channel.New(cfg)` | `new DingTalkChannel(cfg)` | `DingTalkChannel(...)` | `DingTalkChannel.create(cfg)` |
| Receive Message | `ch.OnMessage(fn)` | `ch.on('message', fn)` | `@ch.on_message` | `ch.onMessage(fn)` |
| Card Callback | `ch.OnCardAction(fn)` | `ch.on('cardAction', fn)` | `@ch.on_card_action` | `ch.onCardAction(fn)` |
| Start | `ch.Start(ctx)` | `await ch.start(signal)` | `await ch.start()` | `ch.start()` |
| Streaming Reply | `reply.Stream(ctx)` → `s.Append/Finish` | `await reply.stream()` → `await s.append/finish` | `await reply.stream()` → `await s.append/finish` | `reply.stream()` → `s.append/finish` |
| Text/MD/Image | `reply.Text/Markdown/Image` | `reply.text/markdown/image` | `reply.text/markdown/image` | `reply.text/markdown/image` |
| Media Upload | `reply.UploadMedia` | `reply.uploadMedia` | `await reply.upload_media` | `reply.uploadMedia` |
| Attachment Download | `reply.DownloadURL` | `reply.downloadURL` | `await reply.download_url` | `reply.downloadUrl` |
| Proactive Messaging | `ch.SendText/SendMarkdown(SendTarget{...})` | `ch.sendText/sendMarkdown({userId\|conversationId, atUserIds})` | `ch.send_text/send_markdown(SendTarget(...))` | `ch.sendText/sendMarkdown(SendTarget.user/group)` |

## Verification: One-Command livecheck

```bash
DD_CLIENT_ID=ding... DD_CLIENT_SECRET=... go run ./example/livecheck
# Then send a message to the bot in DingTalk; progressive PASS/FAIL: connection→receive message→text reply→card streaming full cycle→(optional DD_UPLOAD_FILE) media upload
```

Node: `npm run live`; Python: `python example/livecheck.py`; Java: `mvn -q compile exec:java -Dexec.mainClass=...LiveCheck`.

## Capability Boundaries (What the SDK Doesn't Do)

The following are left to your Agent:

- **Agent runtime**: Model invocation, prompts, tool orchestration (SDK only handles the channel)
- **Multi-user topic isolation**: Multi-session routing and isolation strategy
- **Session/context persistence**: Conversation history storage
- **Credential storage**: SDK only receives clientId/clientSecret, not responsible for safekeeping

## More

- Complete contract and effect acceptance checklist (E1–E10): [`SPEC.md`](./SPEC.md) in each repo
- Project overview: [`OVERVIEW.md`](./OVERVIEW.md) in each repo
- Complete API and examples for each language: README and `example/` in each repo

---
License: MIT | v0.1.0
