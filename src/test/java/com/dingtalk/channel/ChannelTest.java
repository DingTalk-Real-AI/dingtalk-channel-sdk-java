package com.dingtalk.channel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/** 效果验收：E1–E6/E9（不依赖真实钉钉）。 */
public class ChannelTest {
    private HttpServer server;
    private String base;
    final AtomicInteger createCount = new AtomicInteger();
    final AtomicInteger deliverCount = new AtomicInteger();
    final Map<String, String> instances = new ConcurrentHashMap<>();
    final List<JsonObject> streams = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<JsonObject> webhooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean failCreate;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(read(ex.getRequestBody()), "UTF-8");
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/oauth2/accessToken")) {
                respond(ex, "{\"accessToken\":\"tok-1\",\"expireIn\":7200}");
            } else if (path.equals("/v1.0/card/instances") && "POST".equals(ex.getRequestMethod())) {
                createCount.incrementAndGet();
                if (failCreate) {
                    respond(ex, 500, "{\"code\":\"InternalError\"}");
                } else {
                    respond(ex, "{}");
                }
            } else if (path.equals("/v1.0/card/instances/deliver")) {
                deliverCount.incrementAndGet();
                respond(ex, "{}");
            } else if (path.equals("/v1.0/card/instances")) {
                JsonObject body_ = JsonParser.parseString(body).getAsJsonObject();
                instances.put(body_.get("outTrackId").getAsString(),
                        body_.getAsJsonObject("cardData").getAsJsonObject("cardParamMap").get("msgContent").getAsString());
                respond(ex, "{}");
            } else if (path.equals("/v1.0/card/streaming")) {
                streams.add(JsonParser.parseString(body).getAsJsonObject());
                respond(ex, "{}");
            } else if (path.equals("/webhook")) {
                webhooks.add(JsonParser.parseString(body).getAsJsonObject());
                respond(ex, "{\"errcode\":0}");
            } else {
                respond(ex, 404, "{}");
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
        }
        return buf.toByteArray();
    }

    private static void respond(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] raw = body.getBytes("UTF-8");
        ex.sendResponseHeaders(status, raw.length);
        ex.getResponseBody().write(raw);
        ex.close();
    }

    private static void respond(HttpExchange ex, String body) throws java.io.IOException {
        respond(ex, 200, body);
    }

    @Test
    public void trailingFlushDeliversInWindowContent() throws Exception {
        // 节流窗口内的 append 不丢弃，安排 trailing flush。
        DingTalkChannel ch = DingTalkChannel.create(Config.builder("ding-test", "s")
                .apiBase(base).streamThrottleMs(200).cardQps(100).build());
        ch.onMessage((msg, reply) -> {
            CardStreamer s = reply.stream();
            s.append("first");  // 立即刷
            s.append("chunk2"); // 窗口内 → trailing flush
        });
        ch.dispatchForTestSync(botFrame("m-1", "b-1", "hi"));

        long deadline = System.currentTimeMillis() + 3000;
        boolean hit = false;
        while (System.currentTimeMillis() < deadline) {
            hit = streams.stream().anyMatch(s -> "firstchunk2".equals(s.get("content").getAsString()));
            if (hit) break;
            Thread.sleep(20);
        }
        assertTrue("trailing flush did not deliver in-window content", hit);
    }

    private DingTalkChannel newChannel() {
        return DingTalkChannel.create(Config.builder("ding-test", "s")
                .apiBase(base).streamThrottleMs(10).cardQps(100).build());
    }

    private JsonObject botFrame(String messageId, String msgId, String text) {
        JsonObject data = new JsonObject();
        data.addProperty("conversationId", "cid-1");
        data.addProperty("conversationType", "2");
        data.addProperty("msgId", msgId);
        data.addProperty("senderStaffId", "staff-1");
        data.addProperty("senderNick", "John");
        data.addProperty("sessionWebhook", base + "/webhook");
        JsonObject textObj = new JsonObject();
        textObj.addProperty("content", text);
        data.add("text", textObj);
        data.addProperty("msgtype", "text");
        data.addProperty("isInAtList", true);

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "CALLBACK");
        JsonObject headers = new JsonObject();
        headers.addProperty("topic", Config.TOPIC_BOT_MESSAGE);
        headers.addProperty("messageId", messageId);
        frame.add("headers", headers);
        frame.addProperty("data", data.toString());
        return frame;
    }

    @Test
    public void dedupBothLayers() throws Exception { // E6
        DingTalkChannel ch = newChannel();
        List<String> calls = new ArrayList<>();
        ch.onMessage((msg, reply) -> calls.add(msg.text));
        ch.dispatchForTestSync(botFrame("m-1", "b-1", "hi"));
        ch.dispatchForTestSync(botFrame("m-1", "b-1", "hi")); // 协议层重复
        ch.dispatchForTestSync(botFrame("m-2", "b-1", "hi")); // 业务层重复
        ch.dispatchForTestSync(botFrame("m-3", "b-2", "hi"));
        assertEquals(2, calls.size());
    }

    @Test
    public void streamLifecycleAndAtStrip() throws Exception { // E1–E3 + E5
        DingTalkChannel ch = newChannel();
        List<String> got = new ArrayList<>();
        ch.onMessage((msg, reply) -> {
            got.add(msg.text);
            CardStreamer s = reply.stream();
            s.append("Hello ");
            s.append("World"); // 节流合并
            Thread.sleep(30);
            s.append("!");
            s.finish("");
        });
        ch.dispatchForTestSync(botFrame("m-1", "b-1", "@bot 你好"));

        assertEquals(1, got.size());
        assertEquals("你好", got.get(0)); // E5：@ 剥离
        assertEquals(1, createCount.get());
        assertEquals(1, deliverCount.get());
        boolean finalized = false;
        for (JsonObject s : streams) {
            if (s.get("isFinalize").getAsBoolean() && s.get("content").getAsString().contains("World")) {
                finalized = true; // E3
            }
        }
        assertTrue("no finalize frame", finalized);
        assertTrue("no FINISHED status", instances.values().stream().anyMatch(c -> c.contains("Hello")));
    }

    @Test
    public void streamFallbackOnCardFailure() throws Exception { // E4
        failCreate = true;
        DingTalkChannel ch = newChannel();
        ch.onMessage((msg, reply) -> {
            CardStreamer s = reply.stream();
            s.append("final answer");
            try {
                s.finish("");
            } catch (RuntimeException ignore) {
                // finish 在卡片不可用时走降级，可忽略
            }
        });
        ch.dispatchForTestSync(botFrame("m-1", "b-1", "hi"));
        assertTrue("fallback webhook not called", !webhooks.isEmpty());
        JsonObject last = webhooks.get(webhooks.size() - 1);
        assertEquals("sampleText", last.get("msgKey").getAsString());
        assertEquals("final answer", JsonParser.parseString(last.get("msgParam").getAsString())
                .getAsJsonObject().get("content").getAsString());
    }

    @Test
    public void webhookReplyMsgKeys() throws Exception { // E9
        DingTalkChannel ch = newChannel();
        ch.onMessage((msg, reply) -> {
            reply.text("plain");
            reply.markdown("T", "# md");
            reply.image("https://x/y.png");
        });
        ch.dispatchForTestSync(botFrame("m-1", "b-1", "hi"));
        assertEquals(3, webhooks.size());
        assertEquals("sampleText", webhooks.get(0).get("msgKey").getAsString());
        assertEquals("sampleMarkdown", webhooks.get(1).get("msgKey").getAsString());
        assertEquals("sampleImageMsg", webhooks.get(2).get("msgKey").getAsString());
    }

    @Test
    public void testNormalizeRichText() {
        JsonObject data = new JsonObject();
        data.addProperty("conversationId", "cid-1");
        data.addProperty("conversationType", "2");
        data.addProperty("msgId", "rt-1");
        data.addProperty("senderStaffId", "staff-1");
        data.addProperty("senderNick", "John");
        data.addProperty("sessionWebhook", "");
        data.addProperty("isInAtList", true);
        data.addProperty("msgtype", "richText");
        JsonObject content = new JsonObject();
        com.google.gson.JsonArray richText = new com.google.gson.JsonArray();
        JsonObject t1 = new JsonObject();
        t1.addProperty("type", "text");
        t1.addProperty("text", "hello ");
        richText.add(t1);
        JsonObject t2 = new JsonObject();
        t2.addProperty("type", "text");
        t2.addProperty("text", "world");
        richText.add(t2);
        JsonObject at = new JsonObject();
        at.addProperty("type", "at");
        com.google.gson.JsonArray atUserIds = new com.google.gson.JsonArray();
        atUserIds.add("staff-2");
        at.add("atUserIds", atUserIds);
        richText.add(at);
        content.add("richText", richText);
        data.add("content", content);

        IncomingMessage msg = new IncomingMessage(data);
        assertEquals("hello world", msg.text);
        assertFalse(msg.mentions.isEmpty());
        assertEquals("staff-2", msg.mentions.get(0).userId);
    }

    @Test
    public void testNormalizePicture() {
        JsonObject data = new JsonObject();
        data.addProperty("conversationId", "cid-1");
        data.addProperty("conversationType", "2");
        data.addProperty("msgId", "pic-1");
        data.addProperty("senderStaffId", "staff-1");
        data.addProperty("senderNick", "John");
        data.addProperty("sessionWebhook", "");
        data.addProperty("isInAtList", true);
        data.addProperty("msgtype", "picture");
        JsonObject content = new JsonObject();
        content.addProperty("downloadCode", "dc-abc123");
        data.add("content", content);

        IncomingMessage msg = new IncomingMessage(data);
        assertEquals(1, msg.resources.size());
        assertEquals("image", msg.resources.get(0).type);
        assertEquals("dc-abc123", msg.resources.get(0).downloadCode);
    }

    @Test
    public void testNormalizeUnknownType() {
        JsonObject data = new JsonObject();
        data.addProperty("conversationId", "cid-1");
        data.addProperty("conversationType", "2");
        data.addProperty("msgId", "unk-1");
        data.addProperty("senderStaffId", "staff-1");
        data.addProperty("senderNick", "John");
        data.addProperty("sessionWebhook", "");
        data.addProperty("isInAtList", true);
        data.addProperty("msgtype", "someNewType");
        JsonObject content = new JsonObject();
        content.addProperty("content", "fallback text");
        data.add("content", content);

        IncomingMessage msg = new IncomingMessage(data);
        assertEquals("fallback text", msg.text);
    }

    @Test
    public void testNormalizeFile() {
        JsonObject data = new JsonObject();
        data.addProperty("conversationId", "cid-1");
        data.addProperty("conversationType", "2");
        data.addProperty("msgId", "file-1");
        data.addProperty("senderStaffId", "staff-1");
        data.addProperty("senderNick", "John");
        data.addProperty("sessionWebhook", "");
        data.addProperty("isInAtList", true);
        data.addProperty("msgtype", "file");
        JsonObject content = new JsonObject();
        content.addProperty("downloadCode", "dc-f1");
        content.addProperty("fileName", "report.pdf");
        data.add("content", content);

        IncomingMessage msg = new IncomingMessage(data);
        assertEquals("[文件: report.pdf]", msg.text);
        assertEquals(1, msg.resources.size());
        assertEquals("file", msg.resources.get(0).type);
        assertEquals("dc-f1", msg.resources.get(0).downloadCode);
        assertEquals("report.pdf", msg.resources.get(0).fileName);
    }

    @Test
    public void testNormalizeAudio() {
        JsonObject data = new JsonObject();
        data.addProperty("conversationId", "cid-1");
        data.addProperty("conversationType", "2");
        data.addProperty("msgId", "audio-1");
        data.addProperty("senderStaffId", "staff-1");
        data.addProperty("senderNick", "John");
        data.addProperty("sessionWebhook", "");
        data.addProperty("isInAtList", true);
        data.addProperty("msgtype", "audio");
        JsonObject content = new JsonObject();
        content.addProperty("downloadCode", "dc-a1");
        content.addProperty("recognition", "你好");
        data.add("content", content);

        IncomingMessage msg = new IncomingMessage(data);
        assertEquals("你好", msg.text);
        assertEquals(1, msg.resources.size());
        assertEquals("audio", msg.resources.get(0).type);
        assertEquals("dc-a1", msg.resources.get(0).downloadCode);
        assertEquals("你好", msg.resources.get(0).recognition);
    }

    @Test
    public void testNormalizeVideo() {
        JsonObject data = new JsonObject();
        data.addProperty("conversationId", "cid-1");
        data.addProperty("conversationType", "2");
        data.addProperty("msgId", "video-1");
        data.addProperty("senderStaffId", "staff-1");
        data.addProperty("senderNick", "John");
        data.addProperty("sessionWebhook", "");
        data.addProperty("isInAtList", true);
        data.addProperty("msgtype", "video");
        JsonObject content = new JsonObject();
        content.addProperty("downloadCode", "dc-v1");
        content.addProperty("fileName", "clip.mp4");
        data.add("content", content);

        IncomingMessage msg = new IncomingMessage(data);
        assertEquals("[视频]", msg.text);
        assertEquals(1, msg.resources.size());
        assertEquals("video", msg.resources.get(0).type);
        assertEquals("dc-v1", msg.resources.get(0).downloadCode);
        assertEquals("clip.mp4", msg.resources.get(0).fileName);
    }

    @Test
    public void testNormalizeActionCard() {
        JsonObject data = new JsonObject();
        data.addProperty("conversationId", "cid-1");
        data.addProperty("conversationType", "2");
        data.addProperty("msgId", "card-1");
        data.addProperty("senderStaffId", "staff-1");
        data.addProperty("senderNick", "John");
        data.addProperty("sessionWebhook", "");
        data.addProperty("isInAtList", true);
        data.addProperty("msgtype", "actionCard");
        JsonObject content = new JsonObject();
        content.addProperty("title", "请假审批");
        content.addProperty("text", "张三申请年假");
        data.add("content", content);

        IncomingMessage msg = new IncomingMessage(data);
        assertTrue(msg.text.contains("请假审批"));
        assertTrue(msg.text.contains("张三"));
    }
}
