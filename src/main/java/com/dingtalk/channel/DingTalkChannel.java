package com.dingtalk.channel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.dingtalk.channel.safety.BatchConfig;
import com.dingtalk.channel.safety.BatchedMessage;
import com.dingtalk.channel.safety.ChatQueue;
import com.dingtalk.channel.safety.Deduper;
import com.dingtalk.channel.safety.MessageBatcher;
import com.dingtalk.channel.safety.PolicyConfig;
import com.dingtalk.channel.safety.PolicyDecision;
import com.dingtalk.channel.safety.PolicyGate;
import com.dingtalk.channel.safety.ProcessingLock;
import com.dingtalk.channel.safety.RejectEvent;
import com.dingtalk.channel.safety.SsrfGuard;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 钉钉会话接入层。
 *
 * <pre>
 * DingTalkChannel ch = DingTalkChannel.create(Config.builder("ding...", "...").build());
 * ch.onMessage((msg, reply) -> {
 *     CardStreamer s = reply.stream();          // E1：立即出"输入中"卡片
 *     for (String token : myLLM(msg.text)) {
 *         s.append(token);                      // E2：打字机式追加
 *     }
 *     s.finish("");                             // E3：终帧定格
 * });
 * ch.start();                                   // 阻塞运行，自动重连
 * </pre>
 */
public final class DingTalkChannel {

    /** 消息处理器（单聊/群聊统一入口，E5）。 */
    public interface MessageHandler {
        void handle(IncomingMessage msg, Reply reply) throws Exception;
    }

    /** 卡片交互处理器（E7）。 */
    public interface CardActionHandler {
        void handle(CardAction action, Reply reply) throws Exception;
    }

    /** 消息被策略拒绝时的回调。 */
    public interface RejectHandler {
        void handle(RejectEvent event);
    }

    /** 批处理消息处理器。 */
    public interface BatchMessageHandler {
        void handle(BatchedMessage batched, Reply reply) throws Exception;
    }

    private final Config cfg;
    private final TokenProvider tokens;
    private final CardClient cards;
    private final OapiClient oapi;
    private final ProactiveSender sender;
    private final Emotion emotion;
    private final Deduper dedup = new Deduper();
    private final StreamConn conn;

    // 新增功能
    private final BotIdentity.Provider botIdentityProvider;
    private final LifecycleHooks hooks = new LifecycleHooks();
    private final PolicyGate policyGate;
    private final ProcessingLock processingLock;

    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dingtalk-channel-worker");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, ReentrantLock> convLocks = new ConcurrentHashMap<>();

    private volatile MessageHandler messageHandler;
    private volatile CardActionHandler cardActionHandler;
    private volatile RejectHandler rejectHandler;
    private volatile BatchMessageHandler batchMessageHandler;
    private volatile MessageBatcher batcher;
    private final ChatQueue.Manager chatQueue;

    private DingTalkChannel(Config cfg) {
        this.cfg = cfg;
        this.tokens = new TokenProvider(cfg);
        this.cards = new CardClient(cfg, tokens);
        this.oapi = new OapiClient(cfg);
        this.sender = new ProactiveSender(cfg, cards);
        this.emotion = new Emotion(cfg, cards);
        this.botIdentityProvider = new BotIdentity.Provider(cfg, tokens);
        this.policyGate = new PolicyGate(cfg.policyConfig);
        this.processingLock = new ProcessingLock(5 * 60 * 1000L, 60 * 1000L); // 5min TTL, 1min sweep
        this.chatQueue = new ChatQueue.Manager(null, cfg.chatQueue, cfg.mediaBatch);
        this.conn = new StreamConn(cfg, this::dispatchFrame, hooks);
    }

    public static DingTalkChannel create(Config cfg) {
        return new DingTalkChannel(cfg);
    }

    // ── Handler 注册 ──

    public void onMessage(MessageHandler handler) {
        this.messageHandler = handler;
    }

    public void onCardAction(CardActionHandler handler) {
        this.cardActionHandler = handler;
        conn.setWantCardTopic(true);
    }

    /** 注册消息被策略拒绝时的回调。 */
    public void onReject(RejectHandler handler) {
        this.rejectHandler = handler;
    }

    /** 注册批处理消息处理器。 */
    public void onBatchMessage(BatchMessageHandler handler, BatchConfig... configs) {
        this.batchMessageHandler = handler;
        BatchConfig cfg = configs.length > 0 ? configs[0] : new BatchConfig();
        this.batcher = new MessageBatcher(cfg, batched -> {
            JsonObject fakeData = new JsonObject();
            fakeData.addProperty("conversationId", batched.message.conversationId);
            IncomingMessage fake = new IncomingMessage(fakeData);
            Reply reply = new Reply(fake, this.cfg, tokens, cards, oapi);
            batchMessageHandler.handle(batched, reply);
        });
    }

    // ── Lifecycle hooks ──

    public void onReady(Runnable handler) { hooks.onReady(handler); }
    public void onError(Consumer<Throwable> handler) { hooks.onError(handler); }
    public void onReconnecting(Runnable handler) { hooks.onReconnecting(handler); }
    public void onReconnected(Runnable handler) { hooks.onReconnected(handler); }
    public void onDisconnected(Runnable handler) { hooks.onDisconnected(handler); }

    // ── Policy ──

    /** 动态更新策略配置。 */
    public void updatePolicy(PolicyConfig config) {
        policyGate.updateConfig(config);
    }

    /** 获取当前策略配置。 */
    public PolicyConfig getPolicy() {
        return policyGate.getConfig();
    }

    // ── Bot Identity ──

    /** 获取机器人身份信息（带缓存）。 */
    public BotIdentity getBotIdentity() {
        return botIdentityProvider.get();
    }

    // ── Lifecycle ──

    /** 阻塞运行（内部自动重连，E8）。 */
    public void start() {
        if (messageHandler == null && batchMessageHandler == null) {
            throw new IllegalStateException("onMessage or onBatchMessage handler must be registered before start()");
        }
        if (Config.TRANSPORT_HTTP.equals(cfg.transport)) {
            throw new IllegalStateException(
                    "http mode has no long-running connection; "
                    + "call ch.handleHttpCallback(body, timestamp, sign) per HTTP request instead of start()");
        }
        conn.run();
    }

    public void close() {
        conn.stop();
        worker.shutdownNow();
        processingLock.dispose();
        dedup.dispose();
        if (batcher != null) {
            batcher.dispose();
        }
        if (chatQueue != null) {
            chatQueue.dispose();
        }
    }

    // ── DownloadFile ──

    /** 下载媒体文件。 */
    public byte[] downloadFile(String downloadCode, String msgId, String mediaType) {
        String downloadUrl = resolveDownloadUrl(downloadCode, msgId);
        SsrfGuard.assertPublicUrl(downloadUrl, cfg.ssrfAllowlist);

        // 下载文件内容
        try {
            java.net.URL u = new java.net.URL(downloadUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new RuntimeException("download failed: http " + status);
            }
            try (java.io.InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("download failed: " + e.getMessage(), e);
        }
    }

    /**
     * 流式下载媒体文件到本地路径，不整块占用内存（对齐 lark channel-sdk 的 downloadResourceToFile）。
     * 父目录必须已存在；先写同目录临时文件再原子重命名，失败不落半截文件。
     *
     * @return 写入的字节数
     */
    public long downloadFileToFile(String downloadCode, String msgId, String mediaType, java.nio.file.Path destPath) {
        if (destPath == null || destPath.toString().isEmpty()) {
            throw new IllegalArgumentException("destPath cannot be empty");
        }
        String downloadUrl = resolveDownloadUrl(downloadCode, msgId);
        SsrfGuard.assertPublicUrl(downloadUrl, cfg.ssrfAllowlist);

        java.nio.file.Path dest = destPath.toAbsolutePath().normalize();
        java.nio.file.Path tmp = null;
        try {
            java.net.URL u = new java.net.URL(downloadUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new RuntimeException("download failed: http " + status);
            }
            tmp = java.nio.file.Files.createTempFile(dest.getParent(), "." + dest.getFileName(), ".tmp");
            long n;
            try (java.io.InputStream in = conn.getInputStream()) {
                n = java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                java.nio.file.Files.move(tmp, dest,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp = null;
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp = null;
            }
            return n;
        } catch (java.io.IOException e) {
            throw new RuntimeException("download to file failed: " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tmp);
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }

    /** 换取媒体下载 URL（downloadCode → downloadUrl）。 */
    private String resolveDownloadUrl(String downloadCode, String msgId) {
        if (downloadCode == null || downloadCode.isEmpty()) {
            throw new IllegalArgumentException("downloadCode cannot be empty");
        }
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("x-acs-dingtalk-access-token", tokens.get());
        String url = cfg.apiBase + "/v1.0/robot/messageFiles/download"
                + "?downloadCode=" + downloadCode + "&messageId=" + msgId
                + "&robotCode=" + cfg.clientId;
        JsonObject resp = HttpClient.request("GET", url, headers, null).getAsJsonObject();
        String downloadUrl = resp.has("downloadUrl") ? resp.get("downloadUrl").getAsString() : "";
        if (downloadUrl.isEmpty()) {
            throw new RuntimeException("empty download URL");
        }
        return downloadUrl;
    }

    // ── 主动发送（已有） ──

    /** 主动发文本（不依赖入站消息）。 */
    public void sendText(SendTarget target, String content) throws InterruptedException {
        sender.sendText(target, content);
    }

    public void sendMarkdown(SendTarget target, String title, String text) throws InterruptedException {
        sender.sendMarkdown(target, title, text);
    }

    public void sendImage(SendTarget target, String imageUrl) throws InterruptedException {
        sender.sendImage(target, imageUrl);
    }

    public void sendVideo(SendTarget target, String rawVideoMediaId, String rawPicMediaId, long durationMs) throws InterruptedException {
        sender.sendVideo(target, rawVideoMediaId, rawPicMediaId, durationMs);
    }

    public void sendAudio(SendTarget target, String rawMediaId, long durationMs) throws InterruptedException {
        sender.sendAudio(target, rawMediaId, durationMs);
    }

    /** 在用户消息上打"🤔Thinking"状态章（仅人发的消息）。 */
    public void markThinking(String conversationId, String msgId) throws InterruptedException {
        emotion.markThinking(conversationId, msgId);
    }

    /** 把"🤔Thinking"换成"🥳Done"（best-effort）。 */
    public void markDone(String conversationId, String msgId) throws InterruptedException {
        emotion.markDone(conversationId, msgId);
    }

    // ── 内部调度 ──

    String dispatchFrame(JsonObject frame) {
        JsonObject headers = frame.has("headers") ? frame.getAsJsonObject("headers") : new JsonObject();
        String topic = headers.has("topic") ? headers.get("topic").getAsString() : "";
        String messageId = headers.has("messageId") ? headers.get("messageId").getAsString() : "";
        String data = frame.has("data") ? frame.get("data").getAsString() : "";

        if (Config.TOPIC_BOT_MESSAGE.equals(topic)) {
            handleBotMessage(messageId, data);
        } else if (Config.TOPIC_CARD_CALLBACK.equals(topic)) {
            handleCardAction(data);
        } else {
            cfg.debug("unsubscribed topic " + topic + " ignored");
        }
        return "";
    }

    private void handleBotMessage(String messageId, String data) {
        final IncomingMessage msg;
        try {
            msg = new IncomingMessage(JsonParser.parseString(data).getAsJsonObject());
        } catch (RuntimeException e) {
            cfg.debug("bad bot message payload: " + e);
            return;
        }
        processIncoming(messageId, msg);
    }

    /**
     * 处理一帧 HTTP 模式回调（企业内部机器人，body 与 Stream 模式 data 载荷同构）。
     * 验签失败/载荷非法抛出异常（调用方回 401/400）；业务处理与 Stream 模式一致。
     */
    public void handleHttpCallback(String body, String timestamp, String sign) {
        if (messageHandler == null && batchMessageHandler == null) {
            throw new IllegalStateException("onMessage or onBatchMessage handler must be registered first");
        }
        HttpModeVerifier.verifySign(cfg.clientSecret, timestamp, sign, cfg.httpTimestampToleranceMs);
        final IncomingMessage msg;
        try {
            msg = new IncomingMessage(JsonParser.parseString(body).getAsJsonObject());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("http mode: bad bot message payload: " + e.getMessage(), e);
        }
        // HTTP 模式回调无协议层投递 ID，两层去重均落 msgId；重试重推由 Deduper 幂等吸收。
        processIncoming(msg.msgId, msg);
    }

    /** 传输无关的处理管线：去重 → 过期过滤 → 处理锁 → 策略 → 自回复 → 批处理/处理器。 */
    private void processIncoming(String messageId, IncomingMessage msg) {
        // 双层去重（E6）：协议层投递 ID + 业务层 msgId。
        if (dedup.checkAndMark(messageId, msg.msgId)) {
            cfg.debug("duplicate message dropped: msgId=" + msg.msgId);
            return;
        }
        // 过期消息过滤
        if (cfg.staleMessageWindowMs > 0 && msg.createAt > 0
                && System.currentTimeMillis() - msg.createAt > cfg.staleMessageWindowMs) {
            cfg.debug("stale message dropped: msgId=" + msg.msgId);
            return;
        }

        // Processing lock：防止同一消息并发处理
        if (!processingLock.acquire(msg.msgId)) {
            cfg.debug("message already being processed: msgId=" + msg.msgId);
            return;
        }

        // 策略门控
        PolicyDecision decision = policyGate.evaluate(msg);
        if (!decision.allowed) {
            cfg.debug("message rejected by policy: msgId=" + msg.msgId + " reason=" + decision.reason);
            processingLock.release(msg.msgId);
            if (rejectHandler != null) {
                rejectHandler.handle(new RejectEvent(msg.msgId, msg.conversationId, msg.senderId, decision.reason));
            }
            return;
        }

        // 自回复过滤
        BotIdentity botId = getBotIdentity();
        if (botId != null && msg.senderId.equals(botId.getRobotCode())) {
            cfg.debug("self-reply dropped: msgId=" + msg.msgId);
            processingLock.release(msg.msgId);
            return;
        }

        // ChatQueue 启用：批处理刷新与消息处理共享 per-chat 串行
        if (chatQueue.enabled()) {
            if (batcher != null) {
                chatQueue.push(msg.conversationId, msg, batch -> {
                    IncomingMessage merged = ChatQueue.mergeMessages(batch);
                    java.util.List<String> ids = batch.stream().map(m -> m.msgId).collect(java.util.stream.Collectors.toList());
                    IncomingMessage fake = merged;
                    Reply reply = new Reply(fake, cfg, tokens, cards, oapi);
                    try {
                        batchMessageHandler.handle(new BatchedMessage(merged, ids), reply);
                    } catch (Exception e) {
                        cfg.debug("batch handler error: " + e);
                    }
                });
                processingLock.release(msg.msgId);
                return;
            }
            chatQueue.run(msg.conversationId, () -> {
                try {
                    Reply reply = new Reply(msg, cfg, tokens, cards, oapi);
                    messageHandler.handle(msg, reply);
                } catch (Exception e) {
                    cfg.debug("message handler error: " + e);
                } finally {
                    processingLock.release(msg.msgId);
                }
            });
            return;
        }

        // ChatQueue 关闭：回退旧行为
        if (batcher != null) {
            batcher.push(msg);
            processingLock.release(msg.msgId);
            return;
        }

        ReentrantLock lock = convLocks.computeIfAbsent(msg.conversationId, k -> new ReentrantLock());
        worker.execute(() -> {
            lock.lock();
            try {
                Reply reply = new Reply(msg, cfg, tokens, cards, oapi);
                messageHandler.handle(msg, reply);
            } catch (Exception e) {
                cfg.debug("message handler error: " + e);
            } finally {
                lock.unlock();
                processingLock.release(msg.msgId);
            }
        });
    }

    private void handleCardAction(String data) {
        if (cardActionHandler == null) {
            return;
        }
        try {
            JsonObject d = JsonParser.parseString(data).getAsJsonObject();
            CardAction action = new CardAction(d);
            IncomingMessage fake = new IncomingMessage(new JsonObject());
            Reply reply = new Reply(fake, cfg, tokens, cards, oapi);
            worker.execute(() -> {
                Runnable invoke = () -> {
            try {
                    cardActionHandler.handle(action, reply);
                } catch (Exception e) {
                    cfg.debug("card action handler error: " + e);
                }
        };
        // 卡片回调与同会话消息共享串行队列
        if (chatQueue.enabled()) {
            com.google.gson.JsonObject d0 = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
            chatQueue.run(d0.has("outTrackId") ? d0.get("outTrackId").getAsString() : "", invoke);
        } else {
            invoke.run();
        }
            });
        } catch (RuntimeException e) {
            cfg.debug("bad card action payload: " + e);
        }
    }

    /** 测试入口：当前订阅列表（E7 验证）。 */
    com.google.gson.JsonArray buildSubscriptionsForTest() {
        return conn.buildSubscriptions();
    }

    // 测试入口：直接注入一帧业务数据（同步执行 handler，但经过策略门控）。
    void chatQueueFlushForTest() {
        chatQueue.flushAll();
    }

    void awaitHandlerForTest(long timeout, TimeUnit unit) throws InterruptedException {
        worker.shutdown();
        worker.awaitTermination(timeout, unit);
    }

    void dispatchForTestSync(JsonObject frame) throws Exception {
        JsonObject headers = frame.has("headers") ? frame.getAsJsonObject("headers") : new JsonObject();
        String messageId = headers.has("messageId") ? headers.get("messageId").getAsString() : "";
        String data = frame.has("data") ? frame.get("data").getAsString() : "";
        IncomingMessage msg = new IncomingMessage(JsonParser.parseString(data).getAsJsonObject());
        if (dedup.checkAndMark(messageId, msg.msgId)) {
            return;
        }
        // 策略门控
        PolicyDecision decision = policyGate.evaluate(msg);
        if (!decision.allowed) {
            cfg.debug("message rejected by policy: msgId=" + msg.msgId + " reason=" + decision.reason);
            return;
        }
        Reply reply = new Reply(msg, cfg, tokens, cards, oapi);
        messageHandler.handle(msg, reply);
    }
}
