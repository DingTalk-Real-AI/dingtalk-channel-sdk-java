package io.github.typefield.dingtalk.channel.safety;

import io.github.typefield.dingtalk.channel.IncomingMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 消息批处理器（按会话分组， MessageBatcher）。
 */
public class MessageBatcher {

    public interface Handler {
        void handle(BatchedMessage batched) throws Exception;
    }

    private final BatchConfig cfg;
    private final Handler handler;
    private final ConcurrentHashMap<String, ChatPipeline> pipelines = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public MessageBatcher(BatchConfig cfg, Handler handler) {
        this.cfg = cfg;
        this.handler = handler;
        this.scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "dingtalk-batcher-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** 添加消息到批处理队列。 */
    public void push(IncomingMessage msg) {
        String scope = msg.conversationId != null && !msg.conversationId.isEmpty()
                ? msg.conversationId : msg.msgId;
        pipelines.computeIfAbsent(scope, k -> new ChatPipeline(cfg, scope, handler, scheduler)).push(msg);
    }

    /** 立即刷新所有批处理。 */
    public void flushAll() {
        for (ChatPipeline p : pipelines.values()) {
            p.flushNow();
        }
    }

    /** 清理所有批处理。 */
    public void dispose() {
        flushAll();
        for (ChatPipeline p : pipelines.values()) {
            p.dispose();
        }
        pipelines.clear();
        scheduler.shutdownNow();
    }

    // ── 单个会话的批处理管道 ──

    private static class ChatPipeline {
        private final BatchConfig cfg;
        private final String scope;
        private final Handler handler;
        private final ScheduledExecutorService scheduler;

        private final Object lock = new Object();
        private List<IncomingMessage> buffer = new ArrayList<>();
        private int bufferChars;
        private ScheduledFuture<?> timer;

        ChatPipeline(BatchConfig cfg, String scope, Handler handler, ScheduledExecutorService scheduler) {
            this.cfg = cfg;
            this.scope = scope;
            this.handler = handler;
            this.scheduler = scheduler;
        }

        void push(IncomingMessage msg) {
            synchronized (lock) {
                buffer.add(msg);
                bufferChars += msg.text != null ? msg.text.length() : 0;

                if (buffer.size() >= cfg.maxMessages || bufferChars >= cfg.maxChars) {
                    clearTimer();
                    flushLocked();
                    return;
                }

                clearTimer();
                long delay = cfg.delayMs;
                if (bufferChars >= cfg.longThresholdChars) {
                    delay = cfg.longDelayMs;
                }
                timer = scheduler.schedule(this::delayedFlush, delay, TimeUnit.MILLISECONDS);
            }
        }

        private void delayedFlush() {
            synchronized (lock) {
                timer = null;
                if (!buffer.isEmpty()) {
                    flushLocked();
                }
            }
        }

        void flushNow() {
            synchronized (lock) {
                if (!buffer.isEmpty()) {
                    clearTimer();
                    flushLocked();
                }
            }
        }

        private void flushLocked() {
            if (buffer.isEmpty()) {
                return;
            }

            List<IncomingMessage> batch = buffer;
            buffer = new ArrayList<>();
            bufferChars = 0;

            IncomingMessage merged = mergeMessages(batch);
            List<String> sourceIds = new ArrayList<>(batch.size());
            for (IncomingMessage m : batch) {
                sourceIds.add(m.msgId);
            }

            BatchedMessage batched = new BatchedMessage(merged, sourceIds);

            if (handler != null) {
                try {
                    handler.handle(batched);
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        private void clearTimer() {
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
        }

        void dispose() {
            synchronized (lock) {
                clearTimer();
                buffer.clear();
            }
        }
    }

    /** 合并多条消息为一条。 */
    static IncomingMessage mergeMessages(List<IncomingMessage> msgs) {
        if (msgs.isEmpty()) {
            return IncomingMessage.fromJson(new com.google.gson.JsonObject());
        }
        if (msgs.size() == 1) {
            return msgs.get(0);
        }

        IncomingMessage last = msgs.get(msgs.size() - 1);

        StringBuilder sb = new StringBuilder();
        for (IncomingMessage m : msgs) {
            String t = m.text;
            if (t != null && !t.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(t);
            }
        }

        // 用最后一条的 raw JsonObject 作为基础，覆盖 text 字段
        com.google.gson.JsonObject mergedData = last.raw.deepCopy();
        com.google.gson.JsonObject textObj = new com.google.gson.JsonObject();
        textObj.addProperty("content", sb.toString());
        mergedData.add("text", textObj);

        return IncomingMessage.fromJson(mergedData);
    }
}
