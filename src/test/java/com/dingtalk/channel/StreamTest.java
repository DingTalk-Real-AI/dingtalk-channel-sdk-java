package com.dingtalk.channel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** E8 + SPEC §2：假网关全链路（open → wss → 帧分发 → ACK / SYSTEM pong）。 */
public class StreamTest {

    @Test
    public void streamEndToEnd() throws Exception {
        List<JsonObject> acks = new CopyOnWriteArrayList<>();
        List<String> got = new CopyOnWriteArrayList<>();
        CountDownLatch messageSeen = new CountDownLatch(1);

        // WebSocket 侧（自选空闲端口，等待 onStart 后再暴露）
        java.net.ServerSocket probe = new java.net.ServerSocket(0);
        int wsPort = probe.getLocalPort();
        probe.close();
        CountDownLatch wsStarted = new CountDownLatch(1);
        WebSocketServer wsServer = new WebSocketServer(new InetSocketAddress("127.0.0.1", wsPort)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                JsonObject data = new JsonObject();
                data.addProperty("conversationId", "cid");
                data.addProperty("conversationType", "1");
                data.addProperty("msgId", "b-9");
                JsonObject text = new JsonObject();
                text.addProperty("content", "ping");
                data.add("text", text);

                JsonObject msgFrame = new JsonObject();
                msgFrame.addProperty("type", "CALLBACK");
                JsonObject h1 = new JsonObject();
                h1.addProperty("topic", Config.TOPIC_BOT_MESSAGE);
                h1.addProperty("messageId", "m-9");
                msgFrame.add("headers", h1);
                msgFrame.addProperty("data", data.toString());
                conn.send(msgFrame.toString());

                JsonObject pingFrame = new JsonObject();
                pingFrame.addProperty("type", "SYSTEM");
                JsonObject h2 = new JsonObject();
                h2.addProperty("topic", "ping");
                h2.addProperty("messageId", "m-ping");
                pingFrame.add("headers", h2);
                pingFrame.addProperty("data", "keepalive");
                conn.send(pingFrame.toString());
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                acks.add(JsonParser.parseString(message).getAsJsonObject());
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {}

            @Override
            public void onError(WebSocket conn, Exception ex) {}

            @Override
            public void onStart() {
                wsStarted.countDown();
            }
        };
        wsServer.start();
        assertTrue("ws server not started", wsStarted.await(5, TimeUnit.SECONDS));

        // HTTP 侧：gateway open
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1.0/gateway/connections/open", ex -> {
            String body = "{\"endpoint\":\"ws://127.0.0.1:" + wsPort + "\",\"ticket\":\"t-1\"}";
            respond(ex, body);
        });
        httpServer.start();
        int httpPort = httpServer.getAddress().getPort();

        DingTalkChannel ch = DingTalkChannel.create(Config.builder("ding-test", "s")
                .apiBase("http://127.0.0.1:" + httpPort)
                .keepAliveIdleMs(30_000)
                .streamThrottleMs(10)
                .cardQps(100)
                .build());
        ch.onMessage((msg, reply) -> {
            got.add(msg.text);
            messageSeen.countDown();
        });

        Thread runner = new Thread(ch::start, "channel-runner");
        runner.setDaemon(true);
        runner.start();

        assertTrue("message not delivered", messageSeen.await(5, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 5000;
        while (acks.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        ch.close();
        assertEquals(2, acks.size());
        for (JsonObject a : acks) {
            assertEquals(200, a.get("code").getAsInt());
            assertTrue(a.getAsJsonObject("headers").has("messageId"));
        }
        boolean ponged = false;
        for (JsonObject a : acks) {
            if ("keepalive".equals(a.get("data").getAsString())) {
                ponged = true;
            }
        }
        assertTrue("SYSTEM ping not ponged with echoed data", ponged);
        assertEquals(1, got.size());
        assertEquals("ping", got.get(0));

        wsServer.stop(0);
        httpServer.stop(0);
    }

    private static void respond(HttpExchange ex, String body) throws java.io.IOException {
        byte[] raw = body.getBytes("UTF-8");
        ex.sendResponseHeaders(200, raw.length);
        ex.getResponseBody().write(raw);
        ex.close();
    }
}
