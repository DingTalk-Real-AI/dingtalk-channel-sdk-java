package com.dingtalk.channel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** E7 卡片回调 + E9 媒体上传 单测。 */
public class E7MediaTest {

    @Test
    public void cardActionDispatch() throws Exception { // E7：分发 + 归一化
        DingTalkChannel ch = DingTalkChannel.create(Config.builder("a", "b").cardQps(100).build());
        List<CardAction> got = new CopyOnWriteArrayList<>();
        ch.onCardAction((action, reply) -> got.add(action));

        JsonObject data = new JsonObject();
        data.addProperty("outTrackId", "card_123");
        data.addProperty("userId", "u-1");
        JsonObject dc = new JsonObject();
        dc.addProperty("action", "confirm");
        data.add("dataContent", dc);

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "CALLBACK");
        JsonObject headers = new JsonObject();
        headers.addProperty("topic", Config.TOPIC_CARD_CALLBACK);
        headers.addProperty("messageId", "m-c1");
        frame.add("headers", headers);
        frame.addProperty("data", data.toString());
        ch.dispatchFrame(frame);

        long deadline = System.currentTimeMillis() + 3000;
        while (got.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, got.size());
        assertEquals("card_123", got.get(0).outTrackId);
        assertEquals("u-1", got.get(0).userId);
        assertEquals("confirm", got.get(0).dataContent.getAsJsonObject().get("action").getAsString());
    }

    @Test
    public void cardTopicSubscription() { // E7：注册即自动订阅
        DingTalkChannel ch = DingTalkChannel.create(Config.builder("a", "b").cardQps(100).build());
        // 未注册时不含卡片 topic
        assertEquals(3, ch.buildSubscriptionsForTest().size());
        ch.onCardAction((action, reply) -> {});
        List<String> topics = new java.util.ArrayList<>();
        ch.buildSubscriptionsForTest().forEach(e -> topics.add(e.getAsJsonObject().get("topic").getAsString()));
        assertTrue(topics.contains(Config.TOPIC_CARD_CALLBACK));
        assertTrue(topics.contains(Config.TOPIC_BOT_MESSAGE));
        assertTrue(topics.contains("ping"));
    }

    @Test
    public void uploadMedia() throws Exception { // E9：gettoken + multipart /media/upload
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.add(ex.getRequestURI().toString());
            if (ex.getRequestURI().getPath().equals("/gettoken")) {
                respond(ex, "{\"errcode\":0,\"access_token\":\"oapi-tok\",\"expires_in\":7200}");
            } else if (ex.getRequestURI().getPath().equals("/v1.0/oauth2/accessToken")) {
                respond(ex, "{\"accessToken\":\"new-tok\",\"expireIn\":7200}");
            } else if (ex.getRequestURI().getPath().equals("/v1.0/robot/robotInfo")) {
                respond(ex, "{\"robotCode\":\"ding-test\",\"robotName\":\"TestBot\"}");
            } else if (ex.getRequestURI().getPath().equals("/media/upload")) {
                String ct = ex.getRequestHeaders().getFirst("Content-Type");
                assertTrue("not multipart: " + ct, ct.startsWith("multipart/form-data"));
                byte[] raw = read(ex.getRequestBody());
                String body = new String(raw, StandardCharsets.UTF_8);
                assertTrue("field 'media' missing", body.contains("name=\"media\""));
                assertTrue("payload missing", body.contains("fake-jpeg-bytes"));
                respond(ex, "{\"errcode\":0,\"media_id\":\"@MEDIA_5\",\"type\":\"image\"}");
            } else {
                respond(ex, 404, "{}");
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        final DingTalkChannel ch = DingTalkChannel.create(Config.builder("ding-test", "s")
                .apiBase(base).oapiBase(base).streamThrottleMs(10).cardQps(100).build());
        final OapiClient.MediaUploadResult[] result = new OapiClient.MediaUploadResult[1];
        ch.onMessage((msg, reply) -> result[0] = reply.uploadMedia("image", "a.jpg", "", "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8)));
        JsonObject data = new JsonObject();
        data.addProperty("msgId", "b-1");
        data.addProperty("isInAtList", true);
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "CALLBACK");
        JsonObject headers = new JsonObject();
        headers.addProperty("topic", Config.TOPIC_BOT_MESSAGE);
        headers.addProperty("messageId", "m-1");
        frame.add("headers", headers);
        frame.addProperty("data", data.toString());
        ch.dispatchFrame(frame);

        long deadline = System.currentTimeMillis() + 3000;
        while (result[0] == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertNotNull("upload not completed, calls=" + calls, result[0]);
        assertEquals("MEDIA_5", result[0].mediaId); // 去前导 @
        // Find the gettoken and upload calls (may not be first due to bot identity calls)
        String gettokenCall = null;
        String uploadCall = null;
        for (String call : calls) {
            if (call.contains("appkey=ding-test")) {
                gettokenCall = call;
            }
            if (call.contains("access_token=oapi-tok")) {
                uploadCall = call;
            }
        }
        assertNotNull("gettoken call not found in: " + calls, gettokenCall);
        assertNotNull("upload call not found in: " + calls, uploadCall);
        assertTrue("upload should have type=image, got: " + uploadCall, uploadCall.contains("type=image"));
        server.stop(0);
    }

    @Test
    public void proactiveSend() throws Exception { // dm batchSend + group send 含 @
        List<String[]> calls = new CopyOnWriteArrayList<>(); // {path, body}
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] raw = read(ex.getRequestBody());
            calls.add(new String[]{ex.getRequestURI().getPath(), new String(raw, StandardCharsets.UTF_8)});
            respond(ex, ex.getRequestURI().getPath().endsWith("/accessToken")
                    ? "{\"accessToken\":\"tok\",\"expireIn\":7200}" : "{}");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        DingTalkChannel ch = DingTalkChannel.create(Config.builder("ding-test", "s")
                .apiBase(base).streamThrottleMs(10).cardQps(100).build());
        ch.sendText(SendTarget.user("staff-1"), "hello");
        ch.sendMarkdown(SendTarget.group("cid-g").atUserIds("u1", "u2"), "", "# hello");
        try {
            ch.sendText(SendTarget.user(""), "x");
            org.junit.Assert.fail("empty target should fail");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        List<String[]> sends = new java.util.ArrayList<>();
        for (String[] c : calls) {
            if (c[0].startsWith("/v1.0/robot/")) {
                sends.add(c);
            }
        }
        assertEquals("/v1.0/robot/oToMessages/batchSend", sends.get(0)[0]);
        org.junit.Assert.assertTrue(sends.get(0)[1].contains("\"userIds\":[\"staff-1\"]"));
        org.junit.Assert.assertTrue(sends.get(0)[1].contains("\"msgParam\":\""));
        assertEquals("/v1.0/robot/groupMessages/send", sends.get(1)[0]);
        org.junit.Assert.assertTrue(sends.get(1)[1].contains("\"atUserIds\":[\"u1\",\"u2\"]"));
        org.junit.Assert.assertTrue(sends.get(1)[1].contains("\"openConversationId\":\"cid-g\""));
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
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, raw.length);
        ex.getResponseBody().write(raw);
        ex.close();
    }

    private static void respond(HttpExchange ex, String body) throws java.io.IOException {
        respond(ex, 200, body);
    }
}
