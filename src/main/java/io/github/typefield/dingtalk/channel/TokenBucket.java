package io.github.typefield.dingtalk.channel;

/** 全局令牌桶 + QpsLimit 退避（SPEC §6）。 */
final class TokenBucket {
    private static final long QPS_BACKOFF_MS = 2_000;

    private final double rate;
    private double tokens;
    private long lastRefill = System.currentTimeMillis();
    private long backoffUntil;

    TokenBucket(double rate) {
        this.rate = rate;
        this.tokens = rate;
    }

    private void refill(long now) {
        double elapsed = (now - lastRefill) / 1000.0;
        if (elapsed > 0) {
            tokens = Math.min(rate, tokens + elapsed * rate);
            lastRefill = now;
        }
    }

    /** 取一个令牌，无令牌时阻塞等待。 */
    synchronized void waitFor() throws InterruptedException {
        while (true) {
            long now = System.currentTimeMillis();
            if (now < backoffUntil) {
                wait(Math.max(1, backoffUntil - now));
                continue;
            }
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return;
            }
            long needMs = (long) Math.ceil((1 - tokens) / rate * 1000);
            wait(Math.max(1, needMs));
        }
    }

    synchronized void triggerBackoff() {
        backoffUntil = System.currentTimeMillis() + QPS_BACKOFF_MS;
        tokens = 0;
        lastRefill = backoffUntil;
        notifyAll();
    }
}
