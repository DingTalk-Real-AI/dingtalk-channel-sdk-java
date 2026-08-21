package io.github.typefield.dingtalk.channel.safety;

import io.github.typefield.dingtalk.channel.IncomingMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * per-chat 串行队列：同会话消息强制串行处理，批处理刷新同样按会话串行。
 */
public final class ChatQueue {

    /** per-chat 串行队列配置（默认启用）。 */
    public static final class Config {
        public final boolean enabled;

        public Config(boolean enabled) {
            this.enabled = enabled;
        }

        public Config() {
            this(true);
        }
    }

    /** 媒体消息批处理（默认关闭）：启用后同会话连续媒体在 delayMs 窗口内合并投递。 */
    public static final class MediaConfig {
        public final boolean enabled;
        public final long delayMs;
        public final int maxItems;

        public MediaConfig(boolean enabled, long delayMs, int maxItems) {
            this.enabled = enabled;
            this.delayMs = delayMs;
            this.maxItems = maxItems;
        }

        public MediaConfig() {
            this(false, 800, 9);
        }
    }

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dingtalk-chatqueue-timer");
        t.setDaemon(true);
        return t;
    });

    private final String scope;
    private final BatchConfig cfg;
    private final MediaConfig media;
    private final ExecutorService serial;
    private final Object mu = new Object();
    private List<IncomingMessage> buffer = new ArrayList<>();
    private int bufferChars = 0;
    private Consumer<List<IncomingMessage>> pending;
    private ScheduledFuture<?> timer;

    ChatQueue(String scope, BatchConfig cfg, MediaConfig media) {
        this.scope = scope;
        this.cfg = cfg;
        this.media = media;
        this.serial = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dingtalk-chatqueue-" + scope);
            t.setDaemon(true);
            return t;
        });
    }

    /** 串行执行任务（同会话一次一个）。 */
    public void run(Runnable task) {
        synchronized (mu) {
            flushLocked();
        }
        serial.submit(task);
    }

    /** 缓冲 + debounce；flush 在串行执行器上执行。 */
    public void push(IncomingMessage msg, Consumer<List<IncomingMessage>> handler) {
        synchronized (mu) {
            buffer.add(msg);
            bufferChars += msg.text == null ? 0 : msg.text.length();
            if (pending == null) {
                pending = handler;
            }

            int maxMessages = cfg.maxMessages;
            int maxItems = media != null ? media.maxItems : 8;
            int cap = Math.min(maxMessages, maxItems);
            if (buffer.size() >= cap || bufferChars >= cfg.maxChars) {
                flushLocked();
                return;
            }
            if (cfg.delayMs <= 0) {
                flushLocked();
                return;
            }
            if (timer != null) {
                timer.cancel(false);
            }
            long delay = cfg.delayMs;
            if (bufferChars >= cfg.longThresholdChars) {
                delay = cfg.longDelayMs;
            }
            if (media != null && media.enabled && msg.resources != null && !msg.resources.isEmpty()) {
                delay = media.delayMs;
            }
            final Consumer<List<IncomingMessage>> h = pending;
            timer = SCHEDULER.schedule(() -> {
                synchronized (mu) {
                    timer = null;
                    flushLocked();
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void flushLocked() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        if (buffer.isEmpty()) {
            return;
        }
        List<IncomingMessage> batch = buffer;
        Consumer<List<IncomingMessage>> handler = pending;
        buffer = new ArrayList<>();
        bufferChars = 0;
        pending = null;
        if (handler == null) {
            return;
        }
        serial.submit(() -> handler.accept(batch));
    }

    /** 立即刷新并等待串行队列排空。 */
    public void flushNow() {
        synchronized (mu) {
            flushLocked();
        }
        try {
            serial.submit(() -> {
            }).get();
        } catch (Exception ignored) {
            // 排空等待被中断时直接返回
        }
    }

    void dispose() {
        synchronized (mu) {
            flushLocked();
        }
        serial.shutdownNow();
    }

    /** 合并一批消息：文本拼接 + 资源/提及去重合并（媒体批处理）。 */
    public static IncomingMessage mergeMessages(List<IncomingMessage> msgs) {
        if (msgs.size() == 1) {
            return msgs.get(0);
        }
        IncomingMessage last = msgs.get(msgs.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (IncomingMessage m : msgs) {
            if (m.text != null && !m.text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(m.text);
            }
        }
        last.text = sb.toString();

        List<IncomingMessage.Resource> resources = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (IncomingMessage m : msgs) {
            if (m.resources == null) {
                continue;
            }
            for (IncomingMessage.Resource r : m.resources) {
                String key = r.downloadCode == null || r.downloadCode.isEmpty()
                        ? r.type + ":" + r.fileName : r.downloadCode;
                if (key != null && seen.add(key)) {
                    resources.add(r);
                }
            }
        }
        if (!resources.isEmpty()) {
            last.resources = resources;
        }
        return last;
    }

    /** scope → ChatQueue 的惰性注册表。 */
    public static final class Manager {
        private final BatchConfig batchCfg;
        private final Config queueCfg;
        private final MediaConfig media;
        private final Map<String, ChatQueue> queues = new ConcurrentHashMap<>();

        public Manager(BatchConfig batchCfg, Config queueCfg, MediaConfig media) {
            this.batchCfg = batchCfg != null ? batchCfg : new BatchConfig();
            this.queueCfg = queueCfg != null ? queueCfg : new Config();
            this.media = media;
        }

        public boolean enabled() {
            return queueCfg.enabled;
        }

        private ChatQueue get(String scope) {
            return queues.computeIfAbsent(scope, k -> new ChatQueue(k, batchCfg, media));
        }

        public void run(String scope, Runnable task) {
            get(scope).run(task);
        }

        public void push(String scope, IncomingMessage msg, Consumer<List<IncomingMessage>> handler) {
            get(scope).push(msg, handler);
        }

        public void flushAll() {
            queues.values().forEach(ChatQueue::flushNow);
        }

        public void dispose() {
            queues.values().forEach(ChatQueue::dispose);
            queues.clear();
        }
    }
}
