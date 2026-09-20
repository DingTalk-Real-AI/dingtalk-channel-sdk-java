package com.dingtalk.channel.safety;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 双层去重：messageId（协议层）+ msgId（业务层），TTL 5min（SPEC §3.2 / E6）。
 * 支持 LRU 驱逐与后台过期清理。
 */
public final class Deduper {
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000;
    private static final int DEFAULT_MAX_ENTRIES = 10000;
    private static final long DEFAULT_SWEEP_INTERVAL_MS = 5 * 60 * 1000;

    private final long ttlMs;
    private final int maxEntries;
    private final Map<String, Long> seen;
    private final ScheduledExecutorService scheduler;

    public Deduper() {
        this(DEFAULT_TTL_MS);
    }

    Deduper(long ttlMs) {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_SWEEP_INTERVAL_MS, ttlMs);
    }

    Deduper(int maxEntries, long sweepIntervalMs, long ttlMs) {
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
        this.seen = new LinkedHashMap<String, Long>(128, 0.75f, true);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deduper-sweeper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sweep, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
    }

    public synchronized boolean checkAndMark(String... keys) {
        long now = System.currentTimeMillis();
        for (String k : keys) {
            if (k != null && !k.isEmpty()) {
                Long ts = seen.get(k);
                if (ts != null && now - ts <= ttlMs) {
                    return true;
                }
            }
        }
        for (String k : keys) {
            if (k != null && !k.isEmpty()) {
                seen.put(k, now);
                while (seen.size() > maxEntries) {
                    Iterator<String> it = seen.keySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }
        }
        return false;
    }

    private synchronized void sweep() {
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(entry -> now - entry.getValue() > ttlMs);
    }

    public synchronized void dispose() {
        scheduler.shutdownNow();
        seen.clear();
    }
}
