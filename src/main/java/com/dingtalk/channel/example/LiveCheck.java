package com.dingtalk.channel.example;

import com.dingtalk.channel.CardStreamer;
import com.dingtalk.channel.Config;
import com.dingtalk.channel.DingTalkChannel;
import com.dingtalk.channel.OapiClient;
import com.dingtalk.channel.Reply;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 真实钉钉联调一键验证（live check）。
 *
 * 用法：
 *   DD_CLIENT_ID=ding... DD_CLIENT_SECRET=... [DD_UPLOAD_FILE=/path/img.png] \
 *     mvn -q compile exec:java -Dexec.mainClass=com.dingtalk.channel.example.LiveCheck
 *
 * 流程：① 配置加载 ② Stream 连接 ③ 收消息 ④ 文本回复（token 链路）
 * ⑤ 流式 AI 卡片全生命周期（E1–E3）⑥（可选）媒体上传。每步 PASS/FAIL。
 */
public final class LiveCheck {
    private static int steps = 0;

    public static void main(String[] args) throws Exception {
        String clientId = System.getenv("DD_CLIENT_ID");
        String clientSecret = System.getenv("DD_CLIENT_SECRET");
        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) {
            System.err.println("需要环境变量 DD_CLIENT_ID / DD_CLIENT_SECRET");
            System.exit(2);
        }

        System.out.println("== dingtalk-channel-sdk-java livecheck ==");
        pass("config loaded (clientId=" + clientId.substring(0, Math.min(8, clientId.length())) + "...)");

        DingTalkChannel ch = DingTalkChannel.create(
                Config.builder(clientId, clientSecret).debug(m -> System.out.println("[debug] " + m)).build());

        ch.onMessage((msg, reply) -> live(msg, reply));
        ch.onCardAction((action, reply) -> reply.text("livecheck: card action received"));

        System.out.println("waiting for a message — 在钉钉里给机器人发一句话...");
        Runtime.getRuntime().addShutdownHook(new Thread(ch::close));
        ch.start();
    }

    private static void live(com.dingtalk.channel.IncomingMessage msg, Reply reply) {
        try {
            System.out.println("   收到消息: \"" + msg.text + "\" from " + msg.senderNick
                    + " (" + msg.conversationType + ")");
            pass("stream message received (msgId=" + msg.msgId + ")");

            reply.text("livecheck: text reply ok"); // 隐含新版 token
            pass("text reply via sessionWebhook (token path verified)");

            CardStreamer s = reply.stream();
            if (!s.cardDelivered()) {
                System.err.println("❌ FAIL E1 卡片创建/投递（已降级文本——查 deliver 载荷/权限）");
                System.exit(1);
            }
            pass("AI card created & delivered (E1)");

            String content = "# livecheck 流式验证\n\n"
                    + "- 单聊/群聊: `" + msg.conversationType + "`\n"
                    + "- 来自: " + msg.senderNick + "\n\n";

            String uploadFile = System.getenv("DD_UPLOAD_FILE");
            if (uploadFile != null && !uploadFile.isEmpty()) {
                String mt = uploadFile.toLowerCase().matches(".*\\.(png|jpe?g)$") ? "image" : "file";
                byte[] data = Files.readAllBytes(Paths.get(uploadFile));
                OapiClient.MediaUploadResult media = reply.uploadMedia(
                        mt, Paths.get(uploadFile).getFileName().toString(), "", data);
                pass("media upload, mediaId=" + media.mediaId);
                content += "image".equals(mt)
                        ? "![uploaded](" + media.downloadUrl + ")\n"
                        : "- 上传文件 mediaId: `" + media.mediaId + "`\n";
            }

            for (char c : content.toCharArray()) { // 假流式
                s.append(String.valueOf(c));
                Thread.sleep(15);
            }
            s.finish(content);
            pass("AI card streaming lifecycle (E2/E3)");

            System.out.println("\n🎉 全部 " + (steps + 1) + " 步通过：真实钉钉联调验证成功。");
        } catch (Exception e) {
            System.err.println("❌ FAIL livecheck: " + e);
            System.err.println("完成 " + steps + " 步后失败。凭据/应用配置请核对：https://open-dev.dingtalk.com");
            System.exit(1);
        }
    }

    private static void pass(String name) {
        steps++;
        System.out.println("✅ PASS " + name);
    }

    private LiveCheck() {}
}
