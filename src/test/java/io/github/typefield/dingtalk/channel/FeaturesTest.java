package io.github.typefield.dingtalk.channel;

import com.google.gson.JsonObject;
import io.github.typefield.dingtalk.channel.safety.ChatQueue;
import io.github.typefield.dingtalk.channel.safety.SsrfGuard;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** ChatQueue 串行 / 媒体批处理 / SSRF 白名单 / 出站钩子（四特性）。 */
public class FeaturesTest {

    private static JsonObject botFrame(String messageId, String msgId, String text) {
        JsonObject d = new JsonObject();
        d.addProperty("conversationId", "cid-1");
        d.addProperty("conversationType", "2");
        d.addProperty("msgId", msgId);
        d.addProperty("senderStaffId", "staff-1");
        d.addProperty("isInAtList", true);
        d.addProperty("msgtype", "text");
        JsonObject t = new JsonObject();
        t.addProperty("content", text);
        d.add("text", t);
        JsonObject f = new JsonObject();
        f.addProperty("topic", Config.TOPIC_BOT_MESSAGE);
        f.addProperty("messageId", messageId);
        JsonObject frame = new JsonObject();
        frame.add("headers", f);
        frame.addProperty("data", d.toString());
        return frame;
    }

    @Test
    public void chatQueueSerializesPerConversation() throws Exception {
        DingTalkChannel ch = DingTalkChannel.create(Config.builder("id", "sec").build());
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger concurrent = new AtomicInteger();
        ch.onMessage((msg, reply) -> {
            if (concurrent.incrementAndGet() > 1) {
                throw new IllegalStateException("concurrent processing detected");
            }
            Thread.sleep(20);
            order.add(msg.msgId);
            concurrent.decrementAndGet();
        });
        for (int i = 0; i < 3; i++) {
            ch.dispatchForTestSync(botFrame("m-q" + i, "q-" + i, "hi"));
        }
        ch.chatQueueFlushForTest();
        assertEquals(Arrays.asList("q-0", "q-1", "q-2"), order);
        ch.close();
    }

    @Test
    public void chatQueueDisabledKeepsOldSyncPath() throws Exception {
        DingTalkChannel ch = DingTalkChannel.create(
                Config.builder("id", "sec").chatQueue(new ChatQueue.Config(false)).build());
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        ch.onMessage((msg, reply) -> calls.add(msg.msgId));
        ch.dispatchForTestSync(botFrame("m-d1", "d-1", "hi"));
        ch.awaitHandlerForTest(2, TimeUnit.SECONDS);
        assertEquals(Collections.singletonList("d-1"), calls);
        ch.close();
    }

    @Test
    public void mergeMessagesMergesTextAndResources() {
        IncomingMessage a = new IncomingMessage();
        a.text = "t1";
        a.resources = new ArrayList<>();
        IncomingMessage.Resource r1 = new IncomingMessage.Resource("image", "x");
        a.resources.add(r1);

        IncomingMessage b = new IncomingMessage();
        b.text = "t2";
        b.resources = new ArrayList<>();
        b.resources.add(r1);
        IncomingMessage.Resource r2 = new IncomingMessage.Resource("image", "y");
        b.resources.add(r2);

        IncomingMessage merged = ChatQueue.mergeMessages(Arrays.asList(a, b));
        assertEquals("t1\n\nt2", merged.text);
        assertEquals(2, merged.resources.size());
    }

    @Test
    public void ssrfAllowlistBypassesOnlyListedHosts() throws Exception {
        SsrfGuard.assertPublicUrl("http://cdn.internal.corp/x", Arrays.asList("*.internal.corp"));
        SsrfGuard.assertPublicUrl("http://internal.corp/x", Arrays.asList("internal.corp"));
        boolean blocked = false;
        try {
            SsrfGuard.assertPublicUrl("http://other.corp/x", Arrays.asList("*.internal.corp"));
        } catch (ChannelError e) {
            blocked = true;
        }
        assertTrue("non-listed host must stay blocked", blocked);
    }

    @Test
    public void outboundConfigFooterAndHooks() {
        OutboundConfig out = new OutboundConfig();
        java.util.Map<String, Object> param = new java.util.HashMap<>();
        param.put("content", "hello");
        java.util.Map<String, Object> applied = out.applyFooter("sampleText", param);
        assertEquals("hello", applied.get("content")); // footer 为空不改写

        OutboundConfig withFooter = new OutboundConfig(null, null, null, "AI 生成");
        java.util.Map<String, Object> applied2 = withFooter.applyFooter("sampleText", param);
        assertEquals("hello\n\nAI 生成", applied2.get("content"));

        java.util.Map<String, Object> md = new java.util.HashMap<>();
        md.put("text", "body");
        java.util.Map<String, Object> applied3 = withFooter.applyFooter("sampleMarkdown", md);
        assertEquals("body\n\n---\nAI 生成", applied3.get("text"));
    }
}
