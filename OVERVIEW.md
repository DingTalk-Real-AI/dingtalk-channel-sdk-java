# 钉钉 Channel SDK 家族 · 项目总览

> 起点议题：[DingTalk-Real-AI/dingtalk-workspace-cli#796](https://github.com/DingTalk-Real-AI/dingtalk-workspace-cli/issues/796)——「钉钉未来会出 Channel 这样的集成 SDK 吗」。
> 本项目给出答案：**四个语言、效果对齐、即拿即用**。

## 1. 产出物

| 仓库 | 语言 | 依赖 | 测试 |
|---|---|---|---|
| [typefield/dingtalk-channel-sdk-go](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-go) | Go 1.22+ | gorilla/websocket | 19 tests（-race 干净） |
| [typefield/dingtalk-channel-sdk-nodejs](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-nodejs) | Node 18+ | ws | 12 tests |
| [typefield/dingtalk-channel-sdk-python](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-python) | Python 3.10+ | websockets | 12 tests |
| [typefield/dingtalk-channel-sdk-java](https://github.com/DingTalk-Real-AI/dingtalk-channel-sdk-java) | JDK 8+ | Java-WebSocket + Gson | 12 tests |

每仓含：完整源码、单测、`SPEC.md`（四语言统一契约）、流式 echo 示例、**livecheck 真实联调程序**、README、MIT LICENSE。

## 2. 定位与边界

**与 Agent runtime 解耦的会话接入层**。SDK 负责"通道"的全部脏活，开发者只写「用户说了什么、机器人回什么」：

- **负责**：Stream 长连接（建连/心跳/指数退避重连/服务端 disconnect 自愈）、事件双层去重 + 过期消息过滤、sessionWebhook 回复（文本/Markdown/图片，超长自动分片）、AI 卡片流式输出（打字机、帧间隔防竞态、看门狗孤儿保护）、卡片 API 全局限流与 QpsLimit 退避、媒体上传/下载与媒体消息（file/video/audio）、Markdown 归一化、主动发消息（单聊/群聊 + @）、🤔Thinking/🥳Done 状态章、显式中止 Abort、错误兜底冷却
- **不管**：Agent runtime（模型/prompt/工具编排）、会话上下文持久化、凭据存储、业务操作（文档/表格/日历——dws CLI 与 skills 的领域）

## 3. 快速上手（四语言同构）

```go
ch := channel.New(channel.Config{ClientID: "ding...", ClientSecret: "..."})
ch.OnMessage(func(ctx context.Context, msg *channel.IncomingMessage, reply channel.Reply) error {
    s, _ := reply.Stream(ctx)              // 秒级出"输入中"卡片
    for _, tok := range myLLM(msg.Text) {
        _ = s.Append(tok)                  // 打字机追加（800ms 节流+trailing flush）
    }
    return s.Finish("")                    // 终帧定格
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

非流式：`reply.Text/Markdown/Image`、附件下载 `reply.DownloadURL`、媒体上传 `reply.UploadMedia`；
主动发消息（不依赖入站）：`ch.SendText/SendMarkdown/SendImage`，群发支持 `AtUserIds/AtAll`；
卡片交互：`ch.OnCardAction`（注册即自动订阅 card topic）。

## 4. 效果对齐（验收清单 E1–E10，见 SPEC §0）

| | 用户可见效果 | 实现 |
|---|---|---|
| E1 | 发消息秒级出"输入中"卡片 | `stream()` 立即建卡+投递 INPUTING |
| E2 | 打字机平滑追加 | streaming 接口 + 800ms 节流 + **trailing flush**（窗口内不丢）+ 长间隔 300ms 攒批 |
| E3 | 完成后 loading 消失、Markdown 定格 | isFinalize 终帧 + FINISHED 状态 |
| E4 | 卡片失败/限流用户无感 | 静默降级 webhook 文本；QpsLimit 退避 2s 重试 |
| E5 | 群聊/单聊同一体验 | 同一 Reply API；投递目标自动选；群聊剥 @ 前缀 |
| E6 | 绝不重复回复 | messageId+msgId 双层去重（TTL 5min） |
| E7 | 卡片交互闭环 | OnCardAction + 自动订阅 |
| E8 | 永不掉线 | 指数退避重连 + disconnect 即时重连 + 120s/5s 心跳 + ACK 先行 |
| E9 | 媒体收发 | uploadMedia（OAPI multipart）/ downloadURL / image 回复 |
| E10 | Markdown 渲染质量 | normalizeForCard（代码块/表格/引用钉钉渲染规则） |

每条在四语言均有对应单测；E8 另有专门的断线重连 e2e 回归。

## 5. 协议保真（真源，非文档臆测）

| 能力 | 真源 |
|---|---|
| Stream 线协议（open/wss/帧/ACK/心跳/topic 常量） | 官方 dingtalk-stream-sdk-go 源码逐行对照 |
| AI 卡片五步协议 + 限流 + Markdown 归一化 | 官方 connector（dingtalk-openclaw-connector）card.ts |
| token（新版/OAPI 双轨）与 sessionWebhook 载荷 | 官方 connector token.ts / messaging.ts + 官方文档校验 |
| 主动发消息 API | dws（dingtalk-workspace-cli）源码 |
| ticket 编码 / localIp / UA 头 | 四门官方 stream SDK 交叉对照取齐 |

Review 轮次中修复的协议级问题：msgParam 字符串化 JSON（官方文档要求）、Go 版 disconnect 误停、ACK 先行语义、ticket URL 编码。

## 6. 关键架构决策：为什么自研传输层而非引用官方 stream-sdk

1. **官方 connector 自己都不信任**：钉钉官方 connector 源码 `autoReconnect:false, keepAlive:false` 全关重写（issue #571/#536/#573）
2. **四语言一致是本项目验收标准**，而官方四门 SDK 心跳/重连/编码行为互不一致，引用即继承分歧
3. **依赖重量**：官方 Java 版引 Netty 多模块、Python 版带 requests+aiohttp 双 HTTP 栈；自研每语言仅 1–2 个小依赖，传输层约 300 行/语言
4. 留有演进接缝（`Channel → StreamConn → onFrame` 单一边界），上游 SDK 成熟后可加官方适配后端

## 7. 质量证据

- **测试**：Go 14 / Node 12 / Python 12 / Java 12（BUILD SUCCESS），全部含 e2e（假网关+假 API）与断线重连回归
- **真实联调**：每语言 `example/livecheck` 一键验证（连接→收消息→文本回复→卡片流式全周期→媒体上传，逐步 PASS/FAIL）：
  `DD_CLIENT_ID=... DD_CLIENT_SECRET=... go run ./example/livecheck`（Node `npm run live`；Python `python example/livecheck.py`；Java `mvn exec:java`）
- **Code review**：三轮（协议一致性 / 效果对齐 / stream 层对官方 SDK），修复 8 处问题，全部有回归测试

## 8. 已知边界与路线图

- 真实凭据联跑待执行（livecheck 就绪，一条命令）
- >20MB 文件分块上传（v0.2）
- 卡片模板默认值为 connector 内置模板，对外发布建议配置化强调
- 平台差异项（reaction/评论/转发）随钉钉开放平台演进跟进
- 可选：官方 stream-sdk 适配后端（`WithTransport` 接缝已留）

## 9. 前置条件

钉钉开发者后台创建**企业内部应用**并开启机器人，取得 ClientID/ClientSecret。Stream 模式无需公网 IP 与域名。

---
License：MIT ｜ 契约：各仓 `SPEC.md` ｜ 版本：v0.1.0（2026-08）
