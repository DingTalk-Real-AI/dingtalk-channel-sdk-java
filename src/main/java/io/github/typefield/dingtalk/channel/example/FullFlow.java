package io.github.typefield.dingtalk.channel.example;

import io.github.typefield.dingtalk.channel.CardStreamer;
import io.github.typefield.dingtalk.channel.Config;
import io.github.typefield.dingtalk.channel.DingTalkChannel;
import io.github.typefield.dingtalk.channel.IncomingMessage;
import io.github.typefield.dingtalk.channel.OapiClient;
import io.github.typefield.dingtalk.channel.Reply;
import io.github.typefield.dingtalk.channel.SendTarget;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * 真机全流程测试：被动回复 + 媒体上传 + 主动发消息。
 *
 * 用法：DD_CLIENT_ID=... DD_CLIENT_SECRET=... \
 *   mvn -q compile exec:java -Dexec.mainClass=io.github.typefield.dingtalk.channel.example.FullFlow
 */
public final class FullFlow {
    private static int steps = 0;

    public static void main(String[] args) throws Exception {
        DingTalkChannel ch = DingTalkChannel.create(Config.builder(
                System.getenv("DD_CLIENT_ID"), System.getenv("DD_CLIENT_SECRET")).build());

        setChannel(ch);
        ch.onMessage(FullFlow::run);
        System.out.println("== fullflow(java)：给机器人发一条消息 ==");
        Runtime.getRuntime().addShutdownHook(new Thread(ch::close));
        ch.start();
    }

    private static void run(IncomingMessage msg, Reply reply) {
        try {
            System.out.println("   收到消息: \"" + msg.text + "\" from " + msg.senderNick
                    + " (" + msg.conversationType + ")");
            pass("① stream 消息接收 (msgId=" + msg.msgId + ")");

            reply.text("fullflow: 文本回复 ok");
            pass("② webhook 文本回复");

            CardStreamer s = reply.stream();
            if (!s.cardDelivered()) {
                System.err.println("❌ FAIL ③a 卡片创建/投递（降级）");
                System.exit(1);
            }
            pass("③a 卡片创建+投递 (E1)");

            // 8x8 红色 PNG（最小合法图片）
            byte[] png = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAFklEQVR4nGP8z8Dwn4GBgYGJAQwAHxcCAmXfLkIAAAAASUVORK5CYII=");
            OapiClient.MediaUploadResult media = reply.uploadMedia("image", "fullflow.png", "image/png", png);
            pass("③d 媒体上传 mediaId=" + media.mediaId);

            String content = "# fullflow 全流程\n\n"
                    + "- 会话: `" + msg.conversationType + "`\n"
                    + "- 来自: " + msg.senderNick + "\n\n下面是刚上传的图片：\n\n";
            for (char c : content.toCharArray()) {
                s.append(String.valueOf(c));
                Thread.sleep(10);
            }
            s.finish(content + "![uploaded](" + media.downloadUrl + ")\n");
            pass("③e 流式卡片+图片内嵌收口 (E2/E3/E9)");

            DingTalkChannel ch = channelRef;
            ch.sendText(SendTarget.user(msg.senderStaffId), "fullflow: 主动单聊文本 (SendText/batchSend)");
            pass("④a 主动单聊 SendText (batchSend)");
            ch.sendMarkdown(SendTarget.user(msg.senderStaffId), "主动通知", "**fullflow** 主动单聊 Markdown");
            pass("④b 主动单聊 SendMarkdown");

            if ("group".equals(msg.conversationType)) {
                ch.sendMarkdown(SendTarget.group(msg.conversationId).atUserIds(msg.senderStaffId),
                        "群通知", "@你 fullflow 群发测试 (groupMessages/send)");
                pass("⑤ 群发 SendMarkdown+@");
            } else {
                System.out.println("   （单聊会话：⑤ 群发@用例跳过——把机器人拉进群 @它 再跑一次可测）");
            }

            System.out.println("\n🎉 全流程完成：" + steps + " 步通过（主动消息请看钉钉会话）。");
            Thread.sleep(2000);
            System.exit(0);
        } catch (Exception e) {
            System.err.println("❌ FAIL fullflow: " + e);
            System.exit(1);
        }
    }

    private static DingTalkChannel channelRef;

    static void setChannel(DingTalkChannel ch) {
        channelRef = ch;
    }

    private static void pass(String name) {
        steps++;
        System.out.println("✅ PASS " + name);
    }

    private FullFlow() {}
}
