package com.dingtalk.channel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Stream 长连接：open → wss → 心跳 → 重连 → ACK（SPEC §2 / E8）。 */
final class StreamConn {
    interface OnFrame {
        /** 处理一帧业务数据；返回 ACK data（null/空 用默认）。 */
        String handle(JsonObject frame);
    }

    private static final long RECONNECT_BASE_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 30_000;
    private static final long PONG_WAIT_MS = 5_000;

    private final Config cfg;
    private final OnFrame onFrame;
    private final LifecycleHooks hooks;
    private volatile boolean wantCardTopic;
    private volatile boolean stopped;
    private boolean firstConnect = true;

    StreamConn(Config cfg, OnFrame onFrame, LifecycleHooks hooks) {
        this.cfg = cfg;
        this.onFrame = onFrame;
        this.hooks = hooks;
    }

    void setWantCardTopic(boolean v) {
        this.wantCardTopic = v;
    }

    void stop() {
        stopped = true;
    }

    /** 阻塞运行；断开按配置重连（E8）。 */
    void run() {
        int attempt = 0;
        while (!stopped) {
            Exception err = runOnce();
            if (stopped) {
                return;
            }
            if (!cfg.autoReconnect) {
                if (hooks != null) hooks.fireError(err);
                throw new RuntimeException("stream closed", err);
            }
            // 触发断开和重连钩子
            if (hooks != null) {
                hooks.fireDisconnected();
                hooks.fireReconnecting();
            }
            long delay = backoff(attempt++);
            cfg.debug("stream disconnected (" + err + "), reconnect in " + delay + "ms");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long backoff(int attempt) {
        long d = RECONNECT_BASE_MS;
        for (int i = 0; i < attempt && d < RECONNECT_MAX_MS; i++) {
            d *= 2;
        }
        return Math.min(d, RECONNECT_MAX_MS) + ThreadLocalRandom.current().nextLong(1000);
    }

    private Exception runOnce() {
        final AtomicReference<Exception> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final WebSocketClient[] holder = new WebSocketClient[1];

        try {
            JsonObject open = open();
            URI uri = URI.create(open.get("endpoint").getAsString()
                    + "?ticket=" + open.get("ticket").getAsString());

            WebSocketClient ws = new WebSocketClient(uri) {
                private final Object activityLock = new Object();
                private long lastActivity = System.currentTimeMillis();
                private boolean pongPending;
                private volatile boolean killed;
                private Thread watcher;

                @Override
                public void onOpen(ServerHandshake handshake) {
                    cfg.debug("stream connected");
                    // 触发连接就绪钩子
                    if (hooks != null) {
                        if (firstConnect) {
                            hooks.fireReady();
                            firstConnect = false;
                        } else {
                            hooks.fireReconnected();
                        }
                    }
                    startWatcher();
                }

                @Override
                public void onMessage(String message) {
                    markActivity();
                    handleFrame(this, message);
                }

                @Override
                public void onWebsocketPong(WebSocket conn, org.java_websocket.framing.Framedata f) {
                    synchronized (activityLock) {
                        pongPending = false;
                        lastActivity = System.currentTimeMillis();
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    finish(new RuntimeException("connection closed: " + reason));
                }

                @Override
                public void onError(Exception ex) {
                    if (hooks != null) hooks.fireError(ex);
                    finish(ex);
                }

                private void startWatcher() {
                    watcher = new Thread(() -> {
                        while (!killed && !Thread.currentThread().isInterrupted()) {
                            long idleFor;
                            synchronized (activityLock) {
                                idleFor = System.currentTimeMillis() - lastActivity;
                            }
                            long wait = cfg.keepAliveIdleMs - idleFor;
                            if (wait > 0) {
                                try {
                                    Thread.sleep(Math.min(wait, 500));
                                } catch (InterruptedException e) {
                                    return;
                                }
                                continue;
                            }
                            synchronized (activityLock) {
                                pongPending = true;
                            }
                            try {
                                sendPing();
                            } catch (RuntimeException e) {
                                finish(new RuntimeException("ping failed", e));
                                return;
                            }
                            long deadline = System.currentTimeMillis() + PONG_WAIT_MS;
                            while (System.currentTimeMillis() < deadline) {
                                synchronized (activityLock) {
                                    if (!pongPending) {
                                        lastActivity = System.currentTimeMillis();
                                        break;
                                    }
                                }
                                try {
                                    Thread.sleep(200);
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                            synchronized (activityLock) {
                                if (pongPending) { // pong 超时 → close → onClose → 重连
                                    closeConnection(1006, "pong timeout");
                                    return;
                                }
                            }
                        }
                    }, "dingtalk-channel-keepalive");
                    watcher.setDaemon(true);
                    watcher.start();
                }

                private void markActivity() {
                    synchronized (activityLock) {
                        lastActivity = System.currentTimeMillis();
                    }
                }

                private void finish(Exception e) {
                    killed = true;
                    if (watcher != null) {
                        watcher.interrupt();
                    }
                    result.compareAndSet(null, e);
                    done.countDown();
                }
            };
            holder[0] = ws;
            ws.connectBlocking(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (hooks != null) hooks.fireError(e);
            return e;
        }

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
        WebSocketClient ws = holder[0];
        if (ws != null && !ws.isClosed()) {
            try {
                ws.closeBlocking();
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
        return result.get();
    }

    /** 构建订阅列表（公开以便测试验证 E7 订阅自动添加）。 */
    JsonArray buildSubscriptions() {
        JsonArray subs = new JsonArray();
        subs.add(sub("SYSTEM", "ping"));
        subs.add(sub("SYSTEM", "disconnect"));
        subs.add(sub("CALLBACK", Config.TOPIC_BOT_MESSAGE));
        if (wantCardTopic) {
            subs.add(sub("CALLBACK", Config.TOPIC_CARD_CALLBACK));
        }
        return subs;
    }

    private JsonObject open() {
        JsonArray subs = buildSubscriptions();
        Map<String, Object> body = new HashMap<>();
        body.put("clientId", cfg.clientId);
        body.put("clientSecret", cfg.clientSecret);
        body.put("subscriptions", subs);
        body.put("ua", Config.USER_AGENT);
        body.put("localIp", firstLanIp());
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", Config.USER_AGENT);
        return HttpClient.request("POST", cfg.apiBase + "/v1.0/gateway/connections/open", headers, body)
                .getAsJsonObject();
    }

    /** 第一块非 loopback IPv4（官方 SDK 同款上报字段）。 */
    static String firstLanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (java.net.SocketException ignore) {
            // 回退到 localhost
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return "";
        }
    }

    private static JsonObject sub(String type, String topic) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("topic", topic);
        return o;
    }

    private void handleFrame(WebSocketClient ws, String raw) {
        try {
            JsonObject frame = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject headers = frame.has("headers") ? frame.getAsJsonObject("headers") : new JsonObject();
            String topic = headers.has("topic") ? headers.get("topic").getAsString() : "";
            String messageId = headers.has("messageId") ? headers.get("messageId").getAsString() : "";
            String type = frame.has("type") ? frame.get("type").getAsString() : "";

            if ("SYSTEM".equals(type) && "ping".equals(topic)) {
                JsonObject pong = ack(messageId, frame.has("data") ? frame.get("data").getAsString() : "");
                ws.send(HttpClient.GSON.toJson(pong));
                return;
            }
            if ("SYSTEM".equals(type) && "disconnect".equals(topic)) {
                ws.send(HttpClient.GSON.toJson(ack(messageId, "")));
                ws.close(); // → onClose → 重连
                return;
            }

            // ACK 先行（对齐官方 connector）：立即确认，业务异步处理，防长任务重投（E6 兜底）
            ws.send(HttpClient.GSON.toJson(ack(messageId, "")));
            if (onFrame != null) {
                onFrame.handle(frame);
            }
        } catch (RuntimeException e) {
            cfg.debug("bad frame: " + e);
        }
    }

    private static JsonObject ack(String messageId, String data) {
        JsonObject headers = new JsonObject();
        headers.addProperty("contentType", "application/json");
        headers.addProperty("messageId", messageId);
        JsonObject ack = new JsonObject();
        ack.addProperty("code", 200);
        ack.add("headers", headers);
        ack.addProperty("message", "ok");
        ack.addProperty("data", data == null || data.isEmpty() ? "{\"success\":true}" : data);
        return ack;
    }
}
