package com.dingtalk.channel.example;

import com.dingtalk.channel.CardStreamer;
import com.dingtalk.channel.Config;
import com.dingtalk.channel.DingTalkChannel;

/**
 * 演示 E1–E3：流式打字机回复（假 LLM 每 15ms 吐一个字符）。
 *
 * 运行：DD_CLIENT_ID=xxx DD_CLIENT_SECRET=xxx mvn -q compile exec:java \
 *   -Dexec.mainClass=com.dingtalk.channel.example.EchoBot
 */
public final class EchoBot {
    public static void main(String[] args) throws Exception {
        String clientId = System.getenv("DD_CLIENT_ID");
        String clientSecret = System.getenv("DD_CLIENT_SECRET");
        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) {
            throw new IllegalStateException("需要环境变量 DD_CLIENT_ID / DD_CLIENT_SECRET");
        }

        DingTalkChannel ch = DingTalkChannel.create(
                Config.builder(clientId, clientSecret).debug(m -> System.out.println("[debug] " + m)).build());

        ch.onMessage((msg, reply) -> {
            CardStreamer s = reply.stream(); // E1
            String answer = "**收到：" + msg.text + "**\n\n"
                    + "- 单聊/群聊: `" + msg.conversationType + "`\n"
                    + "- 发送者: " + msg.senderNick + "\n"
                    + "```java\nSystem.out.println(\"hello dingtalk channel\");\n```";
            for (int i = 0; i < answer.length(); i++) { // 假流式
                s.append(String.valueOf(answer.charAt(i)));
                Thread.sleep(15);
            }
            s.finish(answer); // E3
        });

        ch.onCardAction((action, reply) -> reply.text("按钮被点击"));

        System.out.println("channel started, waiting for messages...");
        Runtime.getRuntime().addShutdownHook(new Thread(ch::close));
        ch.start();
    }

    private EchoBot() {}
}
