package com.dingtalk.channel.safety;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 短时 TTL 内存锁，防止同一事件并发处理。
 */
public final class ProcessingLock {
    private final Map<String, Long> locks = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final ScheduledExecutorService scheduler;

    public ProcessingLock(long ttlMs, long sweepIntervalMs) {
        this.ttlMs = ttlMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "processing-lock-sweeper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sweep, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 获取锁，成功返回 true，已被持有返回 false。
     */
    public boolean acquire(String id) {
        long now = System.currentTimeMillis();
        Long exp = locks.get(id);
        if (exp != null && exp > now) {
            return false; // 已被持有
        }
        locks.put(id, now + ttlMs);
        return true;
    }

    /**
     * 释放锁。
     */
    public void release(String id) {
        locks.remove(id);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        locks.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    public void dispose() {
        scheduler.shutdownNow();
        locks.clear();
    }
}
