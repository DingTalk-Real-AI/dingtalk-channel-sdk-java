# dingtalk-channel-sdk-java

[English](./README.md) | **简体中文**

钉钉 Channel SDK（Java 版）——与 Agent runtime 解耦的会话接入层：Stream 长连接、入站事件归一化、统一安全管线、AI 卡片流式回复，一个高阶 Channel 全部覆盖。

要求 JDK 8+。依赖仅 Java-WebSocket 与 Gson。

## 安装

```xml
<dependency>
  <groupId>com.dingtalk</groupId>
  <artifactId>dingtalk-channel-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

发布后可用；本地先 `mvn install`。

## 最小示例

```java
DingTalkChannel ch = DingTalkChannel.create(
        Config.builder(System.getenv("DD_CLIENT_ID"), System.getenv("DD_CLIENT_SECRET")).build());

ch.onMessage((msg, reply) -> reply.text("received: " + msg.text));

ch.start();   // 阻塞运行，自动重连
```

`start()` 建立 Stream 长连接并阻塞运行（自动重连）；回复走 sessionWebhook，不依赖公网入口。

## 核心特性

- **AI 卡片流式回复**：`reply.stream()` 立即出"输入中"卡片，`s.append(token)` 逐字追加（800ms 节流），`s.finish("")` 定格；孤儿 watchdog、卡片失败降级文本、令牌桶限流 + QpsLimit 退避
- **统一安全管线**：双层去重（messageId + msgId）、策略门控（逐群覆盖/@要求）、per-chat 串行与批处理、连续媒体窗口合并
- **出站可靠性**：结构化错误分类（`isRetryable()` / `isReplyTargetGone()`）支撑重试策略
- **双传输模式**：Stream（默认）与 HTTP 模式（官方验签内置）
- **livecheck 一键真机验收**

流式回复只要三行：

```java
CardStreamer s = reply.stream();
for (String token : myLLM(msg.text)) s.append(token);
s.finish("");
```

## 文档

| 主题 | 内容 |
|------|------|
| [SPEC.md](./SPEC.md) | 四语言统一契约与 E1–E10 效果验收清单 |
| [GUIDE.md](./GUIDE.md) | 接入指南：让 Agent 接入群聊/单聊 |
| [OVERVIEW.md](./OVERVIEW.md) | 架构分层与模块总览 |
| 高级配置 | 策略 / 钩子 / 批处理 / 出站 / HTTP 模式（见下方「高级配置」） |

## 示例

| 示例 | 说明 |
|------|------|
| `example/EchoBot` | 最小回声机器人 |
| `example/FullFlow` | 全功能：主动发送 + 媒体上传内嵌 |
| `example/LiveCheck` | 真机一键验收 |

## 包边界

业务代码通常只需导入根包：

```java
import com.dingtalk.channel.DingTalkChannel;
```

子包（`normalize` / `safety` / `outbound`）属内部分层，不在兼容性承诺范围内。

## 高级配置

| 配置 | 默认 | 说明 |
|------|------|------|
| `policyConfig` | 全开放 | 准入策略：@要求、群/发送者黑白名单、逐群覆盖 |
| `chatQueue` | 启用 | 同会话消息强制串行 |
| `mediaBatch` | 关闭 | 连续图片/文件/音视频窗口内合并投递 |
| `outbound` | — | 统一页脚、before/after-send 钩子 |
| `ssrfAllowlist` | — | 内网 CDN 等下载 URL 豁免 |
| `transport` | `stream` | `http` = HTTP 模式（`ch.handleHttpCallback(body, timestamp, sign)`） |
| `ch.onReject` | — | 拒绝事件回调（含原因） |

主动发送：`ch.sendText(SendTarget.user("staff-1"), "你好")`（群聊用 `SendTarget.group("cid...")`，支持 @）。

## 本地开发

```bash
mvn test        # 34 个测试
```

真实联调：

```bash
DD_CLIENT_ID=... DD_CLIENT_SECRET=... \
  mvn -q compile exec:java -Dexec.mainClass=com.dingtalk.channel.example.LiveCheck
```

## License

MIT
