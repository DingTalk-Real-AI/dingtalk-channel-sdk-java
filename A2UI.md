# 发送 A2UI 卡片

本 SDK 参考 [dingtalk-aicard](https://github.com/DingTalk-Real-AI/dingtalk-aicard/tree/d2c2e7f05592a346e72486f0e41b744c1189f6fa) 的公开 DWS 接入方式，增加可选发送通道。A2UI 消息使用 `version: "v1.0"`；V0.8 是钉钉规范版本，不能替换消息版本。

## 发送身份与准备

`DwsA2UIClient`（Go 为 `DWSA2UIClient`）调用本机 DWS，用其登录账号和 Profile 发送。显式配置后才启用，发送身份与 Channel 的机器人应用 Token 独立。建议指定固定的 `corpId:userId` Profile，创建和更新始终使用同一个 Profile；适配器拒绝同时选择多个 Profile。

先单独安装并登录 [DingTalk Workspace CLI](https://github.com/DingTalk-Real-AI/dingtalk-workspace-cli)，确认所用版本有以下两个命令：

```bash
dws chat message send-a2ui-card --help
dws chat message update-a2ui-card --help
```

默认执行 `dws`，可配置命令路径、固定前缀参数和执行超时（默认 30 秒）。SDK 使用参数数组执行，始终不调用 shell。DWS 接收一个 `--content` 参数，本适配器将其限制为 UTF-8 64 KiB，回执限制为 8 MiB。

新增发送通道可以独立使用，也可以注入已有 Channel 的配置。自定义 A2UI 通道需实现同样的发送、更新接口；这为其他已验证的投递集成保留扩展点。

## 消息与生命周期

消息是非空数组，元素可为 JSON 对象或已经序列化的 JSON 字符串。适配器将其转换为 DWS 要求的字符串数组，保留消息顺序和业务数据。SDK 检查 JSON、`version`、单一操作和非空 `surfaceId`；组件属性、引用、初始数据与资源需按 dingtalk-aicard 校验。

仓库中的 `example/a2ui-card.json` 是完整创建示例，`example/a2ui-update.json` 是同一 Surface 的完成增量。可在 dingtalk-aicard 仓库内设置 Python 环境并验证创建示例：

```bash
python3 skills/dingtalk-aicard/scripts/setup_env.py
.aicard-venv/bin/python skills/dingtalk-aicard/scripts/aicard_lint.py /path/to/sdk/example/a2ui-card.json --preflight new-card --format json
```

组件与事件定义以 [钉钉规范](https://github.com/DingTalk-Real-AI/dingtalk-aicard/tree/d2c2e7f05592a346e72486f0e41b744c1189f6fa/spec) 为准。

单聊必须提供 `openDingTalkId`，群聊提供 `openConversationId`，选择一个目标。不要把机器人 `staffId`、普通 `userId` 或 `senderId` 当作个人开放标识；适配器不会自动转换这些标识。

创建使用 `send-a2ui-card`，状态为 `PROCESSING`。发送结果保留完整 DWS 回执，并提取服务端 `bizId`。缺少它时返回更新警告；不能把请求侧 `bizCardId` 或 `openTaskId` 当作更新标识，也不要自动再创建一张卡片。

更新使用 `update-a2ui-card`，必须传原卡片 `bizId`、非空增量和 `flowStatus`。保持原 `surfaceId`、组件 ID 和用户表单数据，发送完整 A2UI 消息，不发送 JSON token 片段。静态卡片通过状态 `FINISH` 和非空、保留原状态的增量结束。

状态支持 `PROCESSING`、`INPUTTING`、`FINISH`、`EXECUTING`、`ERROR`、`ABORTED`、`TIMEOUT`、`CONFIRMING`、`CONFIRMED`，兼容字符串 `1` 至 `9`。

## 验证与回调

只有回执明确确认接受请求才返回成功；执行错误、非 JSON 输出、无确认标志、dry-run 及失败回执会返回错误。写入超时或进程失败后，服务端可能已经接收请求；SDK 明确报告结果可能未知，并且不会自动重试或降级为 Markdown 消息。请保留回执并核实实际状态。

DWS 命令成功和本地测试不能证明钉钉客户端渲染或用户点击回调。本次适配器负责发送与更新；DWS 身份下的业务回调须通过相应的 `user_card_action_triggered` 事件通道另外接入，不会自动进入 Channel 的机器人 `cardAction` 回调。

参考仓库没有给出可复用的机器人应用 Token A2UI OpenAPI 实现。本适配器实现它已经公开的 DWS 路径；需要机器人原生发送时，应提供已验证的接口契约并实现可注入发送通道。

## SDK 调用

```java
import com.dingtalk.channel.*;
import java.util.Collections;

DwsA2UIClient a2ui = new DwsA2UIClient(
    Collections.singletonList("dws"), System.getenv("DWS_PROFILE"), 30000);
DingTalkChannel ch = DingTalkChannel.create(Config.builder(
    System.getenv("DD_CLIENT_ID"), System.getenv("DD_CLIENT_SECRET"))
    .a2uiClient(a2ui).build());
// messages、delta 分别是创建和完成增量的 List<JsonElement> 或 List<String>。
A2UICardResult result = ch.sendA2UICard(
    A2UITarget.forUser(System.getenv("DWS_OPEN_DINGTALK_ID")), messages);
if (result.bizId == null) throw new IllegalStateException(result.updateWarning);
ch.updateA2UICard(result.bizId, delta, "FINISH");
```

群聊使用 `A2UITarget.forGroup("<openConversationId>")`。独立使用时直接调用 `DwsA2UIClient.sendCard` 和 `updateCard`，不需要机器人凭据。完整可运行示例为 `com.dingtalk.channel.example.A2UI`。
