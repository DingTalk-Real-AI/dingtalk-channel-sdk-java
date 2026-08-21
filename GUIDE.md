# 钉钉 Channel SDK 接入指南

让你的 Agent 接入钉钉，在**群聊、单聊**中实时对话。SDK 负责事件接入、消息解析与去重、回复发送、
流式输出（打字机）、媒体上传下载、卡片交互——你只需要告诉它「用户说了什么，机器人回什么」。

## 使用效果

以一个客服 Agent 为例，接入后获得三个开箱能力：

- **流式回复**：用户提问后秒级出现"输入中"卡片，回答随 LLM 生成逐字追加，完成后 Markdown 定格
- **卡片交互**：机器人发的卡片按钮被点击时，事件回到你的 Agent，可继续对话或更新卡片
- **主动通知**：不依赖用户先说话，Agent 可随时向个人/群里发消息（支持 @）

## Channel SDK 做了什么

| 不用 SDK（自建） | 用 Channel SDK |
|---|---|
| 研究 Stream 协议、WebSocket 建连、心跳、断线重连 | `channel.New(Config{...})` 一行，连接自愈 |
| 解析原始回调帧、字段映射、防重投 | 统一的 `IncomingMessage` / `CardAction`，双层去重 |
| 对接 AI 卡片创建/投递/流式更新/收口四套接口 | `reply.Stream()` + `Append()` 自动刷新（节流+补刷） |
| 处理限流、失败降级、群聊/单聊差异 | 内置：QpsLimit 退避重试、失败降级文本、目标自动选择 |

## 接入 SDK（五个环节，SDK 包办四个）

1. **传输连接**：Stream 长连接建立、订阅、心跳保活、断线指数退避重连、服务端 disconnect 自愈 —— *SDK 内置*
2. **事件转换**：原始回调帧归一为统一结构（`IncomingMessage`、`CardAction`）—— *SDK 内置*
3. **回复策略**：消息去重（协议层+业务层）、同会话串行、群聊 @ 剥离、卡片失败降级 —— *SDK 内置*
4. **业务分发**：`OnMessage / on('message') / @on_message` 注册你的处理器 —— **唯一需要你写的环节**
5. **出站渲染**：Agent 输出转钉钉消息/AI 卡片，含流式刷新、节流、终帧定格、媒体内嵌 —— *SDK 内置*

## 多语言 SDK

| 语言 | 仓库 | 安装 |
|---|---|---|
| Go | [dingtalk-channel-sdk-go](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-go) | `go get github.com/DingTalk-Real-AI/dingtalk-channel-sdk-go` |
| Node.js | [dingtalk-channel-sdk-nodejs](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-nodejs) | `npm install dingtalk-channel-sdk` |
| Python | [dingtalk-channel-sdk-python](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-python) | `pip install dingtalk-channel-sdk` |
| Java | [dingtalk-channel-sdk-java](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-java) | Maven `io.github.typefield:dingtalk-channel-sdk` |

## 前置条件（一次性）

1. [钉钉开发者后台](https://open-dev.dingtalk.com) 创建**企业内部应用**
2. 应用内添加**机器人**能力，记录 **ClientID（AppKey）/ ClientSecret（AppSecret）**
3. 无需公网 IP、无需域名、无需 webhook（Stream 长连接模式）

## 快速开始（同一示例 · 四语言完整对照）

同一段逻辑：收消息 → 流式卡片回复（打字机）→ 卡片按钮处理 → 阻塞运行。
每段都是完整可复制的示例；四门语言的步骤一一对应。

### Go

```go
ch := channel.New(channel.Config{
    ClientID:     os.Getenv("DD_CLIENT_ID"),
    ClientSecret: os.Getenv("DD_CLIENT_SECRET"),
})

ch.OnMessage(func(ctx context.Context, msg *channel.IncomingMessage, reply channel.Reply) error {
    if msg.Text == "" {
        return nil // 非 text 消息在 msg.Content / msg.MsgType
    }
    s, _ := reply.Stream(ctx)                  // ① 立即出"输入中"卡片
    answer := myAgent(ctx, msg.Text)           // ② 你的 Agent
    for _, tok := range streamTokens(answer) { // ③ 流式追加（节流+补刷内置）
        _ = s.Append(tok)
    }
    return s.Finish(answer)                    // ④ 终帧定格 Markdown
})

ch.OnCardAction(func(ctx context.Context, a *channel.CardAction, reply channel.Reply) error {
    return reply.Text(ctx, "收到按钮点击: "+string(a.DataContent))
})

// 其他回复方式
_ = reply.Markdown(ctx, "标题", "# 正文")
_ = reply.Image(ctx, "https://.../a.png")
media, _ := reply.UploadMedia(ctx, "image", "a.png", "", imgBytes) // → ![..](media.MediaID) 内嵌卡片
url, _ := reply.DownloadURL(ctx, code, msg.MsgID)                  // 附件下载地址

// 主动发消息（不依赖入站）
_ = ch.SendText(ctx, channel.SendTarget{UserID: "staff-1"}, "主动单聊通知")
_ = ch.SendMarkdown(ctx, channel.SendTarget{ConversationID: "cid...", AtUserIds: []string{"u1"}}, "日报", "@u1 构建完成")

log.Fatal(ch.Start(ctx)) // 阻塞运行，断线自动重连
```

### Node.js

```js
const ch = new DingTalkChannel({
  clientId: process.env.DD_CLIENT_ID,
  clientSecret: process.env.DD_CLIENT_SECRET,
});

ch.on('message', async (msg, reply) => {
  if (!msg.text) return; // 非 text 消息在 msg.content / msg.msgType
  const s = await reply.stream();              // ①
  const answer = await myAgent(msg.text);      // ②
  for await (const tok of streamTokens(answer)) {
    await s.append(tok);                       // ③
  }
  await s.finish(answer);                      // ④
});

ch.on('cardAction', async (action, reply) => {
  await reply.text('收到按钮点击: ' + JSON.stringify(action.dataContent));
});

// 其他回复方式
await reply.markdown('标题', '# 正文');
await reply.image('https://.../a.png');
const media = await reply.uploadMedia('image', 'a.png', imgBytes); // → ![..](media.mediaId)
const url = await reply.downloadURL(code, msg.msgId);

// 主动发消息
await ch.sendText({ userId: 'staff-1' }, '主动单聊通知');
await ch.sendMarkdown({ conversationId: 'cid...', atUserIds: ['u1'] }, '日报', '@u1 构建完成');

const controller = new AbortController();
process.on('SIGINT', () => controller.abort());
await ch.start(controller.signal); // 阻塞运行，断线自动重连
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
        return  # 非 text 消息在 msg.content / msg.msg_type
    s = await reply.stream()               # ①
    answer = await my_agent(msg.text)      # ②
    for tok in stream_tokens(answer):
        await s.append(tok)                # ③
    await s.finish(answer)                 # ④

@ch.on_card_action
async def on_card(action, reply):
    await reply.text(f"收到按钮点击: {action.data_content}")

# 其他回复方式
await reply.markdown("标题", "# 正文")
await reply.image("https://.../a.png")
media = await reply.upload_media("image", "a.png", img_bytes)  # → ![..](media["mediaId"])
url = await reply.download_url(code, msg.msg_id)

# 主动发消息
await ch.send_text(SendTarget(user_id="staff-1"), "主动单聊通知")
await ch.send_markdown(SendTarget(conversation_id="cid...", at_user_ids=["u1"]), "日报", "@u1 构建完成")

asyncio.run(ch.start())  # 阻塞运行，断线自动重连
```

### Java

```java
DingTalkChannel ch = DingTalkChannel.create(Config.builder(
        System.getenv("DD_CLIENT_ID"), System.getenv("DD_CLIENT_SECRET")).build());

ch.onMessage((msg, reply) -> {
    if (msg.text.isEmpty()) return;          // 非 text 消息在 msg.content / msg.msgType
    CardStreamer s = reply.stream();         // ①
    String answer = myAgent(msg.text);       // ②
    for (String tok : streamTokens(answer)) {
        s.append(tok);                       // ③
    }
    s.finish(answer);                        // ④
});

ch.onCardAction((action, reply) ->
        reply.text("收到按钮点击: " + action.dataContent));

// 其他回复方式（在 handler 内）
reply.markdown("标题", "# 正文");
reply.image("https://.../a.png");
OapiClient.MediaUploadResult media = reply.uploadMedia("image", "a.png", "", imgBytes); // → ![..](media.mediaId)
String url = reply.downloadUrl(code, msg.msgId);

// 主动发消息
ch.sendText(SendTarget.user("staff-1"), "主动单聊通知");
ch.sendMarkdown(SendTarget.group("cid...").atUserIds("u1"), "日报", "@u1 构建完成");

Runtime.getRuntime().addShutdownHook(new Thread(ch::close));
ch.start(); // 阻塞运行，断线自动重连
```

### API 速查对照

| 能力 | Go | Node.js | Python | Java |
|---|---|---|---|---|
| 创建 | `channel.New(cfg)` | `new DingTalkChannel(cfg)` | `DingTalkChannel(...)` | `DingTalkChannel.create(cfg)` |
| 收消息 | `ch.OnMessage(fn)` | `ch.on('message', fn)` | `@ch.on_message` | `ch.onMessage(fn)` |
| 卡片回调 | `ch.OnCardAction(fn)` | `ch.on('cardAction', fn)` | `@ch.on_card_action` | `ch.onCardAction(fn)` |
| 启动 | `ch.Start(ctx)` | `await ch.start(signal)` | `await ch.start()` | `ch.start()` |
| 流式回复 | `reply.Stream(ctx)` → `s.Append/Finish` | `await reply.stream()` → `await s.append/finish` | `await reply.stream()` → `await s.append/finish` | `reply.stream()` → `s.append/finish` |
| 文本/MD/图片 | `reply.Text/Markdown/Image` | `reply.text/markdown/image` | `reply.text/markdown/image` | `reply.text/markdown/image` |
| 媒体上传 | `reply.UploadMedia` | `reply.uploadMedia` | `await reply.upload_media` | `reply.uploadMedia` |
| 附件下载 | `reply.DownloadURL` | `reply.downloadURL` | `await reply.download_url` | `reply.downloadUrl` |
| 主动发消息 | `ch.SendText/SendMarkdown(SendTarget{...})` | `ch.sendText/sendMarkdown({userId|conversationId, atUserIds})` | `ch.send_text/send_markdown(SendTarget(...))` | `ch.sendText/sendMarkdown(SendTarget.user/group)` |

## 验证：livecheck 一键联调

```bash
DD_CLIENT_ID=ding... DD_CLIENT_SECRET=... go run ./example/livecheck
# 然后在钉钉里给机器人发一句话；逐步 PASS/FAIL：连接→收消息→文本回复→卡片流式全周期→（可选 DD_UPLOAD_FILE）媒体上传
```

Node：`npm run live`；Python：`python example/livecheck.py`；Java：`mvn -q compile exec:java -Dexec.mainClass=...LiveCheck`。

## 能力边界（SDK 不做什么）

以下留给你的 Agent 侧：

- **Agent runtime**：模型调用、prompt、工具编排（SDK 只管通道）
- **多用户话题隔离**：多会话路由与隔离策略
- **Session/上下文持久化**：对话历史存储
- **凭据存储**：SDK 只接收 clientId/clientSecret，不负责保管

## 更多

- 完整契约与效果验收清单（E1–E10）：各仓 [`SPEC.md`](./SPEC.md)
- 项目总览：各仓 [`OVERVIEW.md`](./OVERVIEW.md)
- 每语言完整 API 与示例：各仓 README 与 `example/`

---
License：MIT ｜ v0.1.0
