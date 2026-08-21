package io.github.typefield.dingtalk.channel;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** E8 回归：服务端下发 SYSTEM/disconnect 后必须重连（而非整体退出）。 */
public class ReconnectTest {

    @Test
    public void reconnectsAfterServerDisconnect() throws Exception {
        final AtomicInteger connections = new AtomicInteger();
        final CountDownLatch messageSeen = new CountDownLatch(1);

        java.net.ServerSocket probe = new java.net.ServerSocket(0);
        int wsPort = probe.getLocalPort();
        probe.close();
        CountDownLatch wsStarted = new CountDownLatch(1);

        WebSocketServer wsServer = new WebSocketServer(new InetSocketAddress("127.0.0.1", wsPort)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                int n = connections.incrementAndGet();
                JsonObject frame = new JsonObject();
                JsonObject headers = new JsonObject();
                if (n == 1) {
                    frame.addProperty("type", "SYSTEM");
                    headers.addProperty("topic", "disconnect");
                    headers.addProperty("messageId", "m-d");
                } else {
                    JsonObject data = new JsonObject();
                    data.addProperty("conversationId", "cid");
                    data.addProperty("conversationType", "1");
                    data.addProperty("msgId", "b-r");
                    JsonObject text = new JsonObject();
                    text.addProperty("content", "after-reconnect");
                    data.add("text", text);
                    frame.addProperty("type", "CALLBACK");
                    headers.addProperty("topic", Config.TOPIC_BOT_MESSAGE);
                    headers.addProperty("messageId", "m-r");
                    frame.addProperty("data", data.toString());
                }
                frame.add("headers", headers);
                conn.send(frame.toString());
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                // 收到 ACK：第一条连接关闭以推进重连
                if (connections.get() == 1) {
                    conn.close();
                }
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
        assertTrue(wsStarted.await(5, TimeUnit.SECONDS));

        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1.0/gateway/connections/open", ex ->
                respond(ex, "{\"endpoint\":\"ws://127.0.0.1:" + wsPort + "\",\"ticket\":\"t-1\"}"));
        httpServer.start();

        DingTalkChannel ch = DingTalkChannel.create(Config.builder("ding-test", "s")
                .apiBase("http://127.0.0.1:" + httpServer.getAddress().getPort())
                .keepAliveIdleMs(30_000)
                .streamThrottleMs(10)
                .cardQps(100)
                .build());
        java.util.List<String> got = new CopyOnWriteArrayList<>();
        ch.onMessage((msg, reply) -> {
            got.add(msg.text);
            messageSeen.countDown();
        });

        Thread runner = new Thread(ch::start, "channel-runner");
        runner.setDaemon(true);
        runner.start();

        assertTrue("message not delivered after reconnect", messageSeen.await(10, TimeUnit.SECONDS));
        ch.close();

        assertEquals(1, got.size());
        assertEquals("after-reconnect", got.get(0));
        assertTrue("expected >=2 connections, got " + connections.get(), connections.get() >= 2);

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
