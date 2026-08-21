package io.github.typefield.dingtalk.channel;

import io.github.typefield.dingtalk.channel.outbound.MarkdownUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 流式卡片句柄：append 节流、finish 收口、fail 置错（E1–E4）。 */
public final class CardStreamer {
    private final CardClient client;
    private final CardClient.CardInstance card;
    private final Fallback fallback; // 卡片失败时的降级通道
    private final long throttleMs;

    // flush-controller 效果参数
    private static final long LONG_GAP_THRESHOLD_MS = 2000;
    private static final long LONG_GAP_BATCH_MS = 300;
    // dws 实证：帧间隔防"内容加载失败"竞态；单帧内容上限
    private static final long FRAME_GAP_MS = 500;
    private static final int MAX_CONTENT = 20000;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dingtalk-channel-flush");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private String accumulated = "";
    private long lastUpdate;
    private boolean closed;
    private boolean hasPending;
    private int frameCount;
    private long lastFrameAt;
    private java.util.concurrent.ScheduledFuture<?> watchdogFuture;
    private boolean aborted;

    interface Fallback {
        void send(String text) throws Exception;
    }

    CardStreamer(CardClient client, CardClient.CardInstance card, Fallback fallback, long throttleMs) {
        this.client = client;
        this.card = card;
        this.fallback = fallback;
        this.throttleMs = throttleMs;
    }

    /** 卡片是否真实创建并投递成功（诊断用；false 表示处于降级模式）。 */
    public boolean cardDelivered() {
        synchronized (lock) {
            return card != null;
        }
    }

    /** 追加增量内容；窗口内不丢弃——安排 trailing flush。 */
    public void append(String delta) throws InterruptedException {
        boolean flushNow = false;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("streamer already closed");
            }
            accumulated += delta;
            if (card == null) {
                return; // 卡片不可用（E4 降级模式）：仅累积，finish 时走降级
            }
            long now = System.currentTimeMillis();
            long elapsed = now - lastUpdate;
            if (elapsed >= throttleMs && elapsed > LONG_GAP_THRESHOLD_MS) {
                schedulePendingLocked(LONG_GAP_BATCH_MS); // 长间隔后攒批
            } else if (elapsed >= throttleMs) {
                lastUpdate = now;
                flushNow = true;
            } else if (!hasPending) {
                schedulePendingLocked(throttleMs - elapsed); // trailing flush
            }
        }
        if (flushNow) {
            update(accumulatedSnapshot(), false);
        }
    }

    private void schedulePendingLocked(long delayMs) {
        if (hasPending) {
            return;
        }
        hasPending = true;
        TIMER.schedule(() -> {
            String content;
            synchronized (lock) {
                hasPending = false;
                if (closed || card == null) {
                    return;
                }
                lastUpdate = System.currentTimeMillis();
                content = accumulated;
            }
            try {
                update(content, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Math.max(1, delayMs), TimeUnit.MILLISECONDS);
    }

    private String accumulatedSnapshot() {
        synchronized (lock) {
            return accumulated;
        }
    }

    private void update(String content, boolean finalize) throws InterruptedException {
        if (card == null) {
            throw new IllegalStateException("card unavailable");
        }
        // 首帧与投递间、终帧与上帧间留出间隔（防"内容加载失败"竞态，dws 实证）
        if (frameCount == 0 || finalize) {
            long elapsed = System.currentTimeMillis() - lastFrameAt;
            if (elapsed < FRAME_GAP_MS) {
                Thread.sleep(FRAME_GAP_MS - elapsed);
            }
        }
        if (content.length() > MAX_CONTENT) {
            content = content.substring(0, MAX_CONTENT); // UTF-16 截断，与 rune 语义在 BMP 内等价
        }
        if (!card.inputingStarted) {
            client.setStatus(card, CardClient.FLOW_INPUTING, MarkdownUtil.normalizeForCard(content));
            card.inputingStarted = true;
        }
        frameCount += 1;
        lastFrameAt = System.currentTimeMillis();
        resetWatchdog();
        client.stream(card, content, finalize);
    }

    // ---- 孤儿卡看门狗（connector 同款防线） ----
    void armWatchdog() {
        resetWatchdog();
    }

    private void resetWatchdog() {
        long timeout = client.cfg.cardWatchdogMs;
        if (timeout <= 0 || card == null) {
            return;
        }
        if (watchdogFuture != null) {
            watchdogFuture.cancel(false);
        }
        watchdogFuture = TIMER.schedule(() -> {
            String content;
            CardClient.CardInstance theCard;
            synchronized (lock) {
                if (closed || card == null) {
                    return;
                }
                closed = true; // 密封
                content = accumulated;
                theCard = card;
            }
            try {
                client.stream(theCard, content, true);
                client.setStatus(theCard, CardClient.FLOW_FINISHED, MarkdownUtil.normalizeForCard(content));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, timeout, TimeUnit.MILLISECONDS);
    }

    private void clearWatchdog() {
        if (watchdogFuture != null) {
            watchdogFuture.cancel(false);
            watchdogFuture = null;
        }
    }

    /** 收口：终帧 + FINISHED。text 非空时覆盖累积内容（E3）。 */
    /** 显式中止：密封流，卡片置 FAILED；幂等。 */
    public void abort() {
        String content;
        CardClient.CardInstance theCard;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            aborted = true;
            clearWatchdog();
            content = accumulated;
            theCard = card;
        }
        if (theCard == null) {
            return;
        }
        try {
            client.stream(theCard, content, true);
            client.setStatus(theCard, CardClient.FLOW_FAILED, MarkdownUtil.normalizeForCard(content));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void finish(String text) throws InterruptedException {
        String content;
        synchronized (lock) {
            if (closed) {
                return; // 幂等
            }
            closed = true;
            clearWatchdog();
            hasPending = false;
            if (text != null && !text.isEmpty()) {
                accumulated = text;
            }
            content = accumulated;
        }
        if (card == null) {
            tryFallback(content);
            return;
        }
        try {
            update(content, true);
            client.setStatus(card, CardClient.FLOW_FINISHED, MarkdownUtil.normalizeForCard(content));
        } catch (RuntimeException e) {
            tryFallback(content); // E4：降级保证用户拿到回复
            throw e;
        }
    }

    /** 置卡片为 FAILED 并降级发错误文本。 */
    public void fail(String errText) throws InterruptedException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            clearWatchdog();
            hasPending = false;
        }
        if (card != null) {
            try {
                if (!card.inputingStarted) {
                    client.setStatus(card, CardClient.FLOW_INPUTING, "");
                    card.inputingStarted = true;
                }
                client.stream(card, errText, true);
                client.setStatus(card, CardClient.FLOW_FAILED, MarkdownUtil.normalizeForCard(errText));
            } catch (RuntimeException | InterruptedException ignore) {
                // 失败路径尽力而为
            }
        }
        tryFallback(errText);
    }

    private void tryFallback(String text) {
        if (fallback == null || text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            fallback.send(text);
        } catch (Exception ignore) {
            // 降级失败只能放弃
        }
    }
}
