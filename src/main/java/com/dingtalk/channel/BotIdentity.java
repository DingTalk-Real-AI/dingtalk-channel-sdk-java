package com.dingtalk.channel;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 机器人身份信息（带缓存， BotIdentity）。
 */
public final class BotIdentity {
    private final String robotCode;
    private final String robotName;
    private final String avatar;

    public BotIdentity(String robotCode, String robotName, String avatar) {
        this.robotCode = robotCode;
        this.robotName = robotName;
        this.avatar = avatar;
    }

    public String getRobotCode() { return robotCode; }
    public String getRobotName() { return robotName; }
    public String getAvatar() { return avatar; }

    /**
     * 机器人身份提供者（带缓存）。
     */
    static final class Provider {
        private final Config cfg;
        private final TokenProvider tokens;
        private final ReentrantLock lock = new ReentrantLock();
        
        private volatile BotIdentity identity;
        private volatile long fetchedAt;
        private volatile long lastFailureAt;
        
        private static final long DEFAULT_TTL_MS = 30 * 60 * 1000L; // 30 minutes
        private static final long DEFAULT_MIN_REFRESH_MS = 60 * 1000L; // 1 minute

        Provider(Config cfg, TokenProvider tokens) {
            this.cfg = cfg;
            this.tokens = tokens;
        }

        /**
         * 获取机器人身份（带缓存）。
         */
        public BotIdentity get() {
            if (isCacheFresh()) {
                return identity;
            }
            
            lock.lock();
            try {
                // Double-check after acquiring lock
                if (isCacheFresh()) {
                    return identity;
                }
                
                long now = System.currentTimeMillis();
                if (shouldThrottleRefresh(now)) {
                    return identity;
                }
                
                BotIdentity newIdentity = fetch();
                if (newIdentity != null) {
                    this.identity = newIdentity;
                    this.fetchedAt = System.currentTimeMillis();
                    this.lastFailureAt = 0;
                    return newIdentity;
                } else {
                    this.lastFailureAt = now;
                    return identity; // Return stale cache if available
                }
            } finally {
                lock.unlock();
            }
        }

        private boolean isCacheFresh() {
            if (identity == null) {
                return false;
            }
            return System.currentTimeMillis() - fetchedAt < DEFAULT_TTL_MS;
        }

        private boolean shouldThrottleRefresh(long now) {
            if (lastFailureAt == 0) {
                return false;
            }
            return now - lastFailureAt < DEFAULT_MIN_REFRESH_MS;
        }

        private BotIdentity fetch() {
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("x-acs-dingtalk-access-token", tokens.get());
                
                String url = cfg.apiBase + "/v1.0/robot/robotInfo";
                JsonObject resp = HttpClient.request("GET", url, headers, null).getAsJsonObject();
                
                String robotCode = resp.has("robotCode") ? resp.get("robotCode").getAsString() : cfg.clientId;
                String robotName = resp.has("robotName") ? resp.get("robotName").getAsString() : "bot";
                String avatar = resp.has("avatar") ? resp.get("avatar").getAsString() : "";
                
                return new BotIdentity(robotCode, robotName, avatar);
            } catch (Exception e) {
                cfg.debug("failed to fetch bot identity: " + e.getMessage());
                return null;
            }
        }
    }
}
