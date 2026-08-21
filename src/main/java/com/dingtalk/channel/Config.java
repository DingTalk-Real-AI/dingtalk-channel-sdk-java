package com.dingtalk.channel;

import com.dingtalk.channel.safety.ChatQueue;
import com.dingtalk.channel.safety.PolicyConfig;

import java.util.function.Consumer;

/** Channel 配置（SPEC §8/§10），零值取默认。 */
public class Config {
    public static final String DEFAULT_API_BASE = "https://api.dingtalk.com";
    public static final String DEFAULT_OAPI_BASE = "https://oapi.dingtalk.com";
    public static final String DEFAULT_CARD_TEMPLATE_ID = "02fcf2f4-5e02-4a85-b672-46d1f715543e.schema";
    public static final String USER_AGENT = "dingtalk-channel-sdk-java/v0.1.0";
    public static final long DEFAULT_CARD_WATCHDOG_MS = 10 * 60 * 1000L; // 孤儿卡强制收口
    public static final long DEFAULT_ERROR_COOLDOWN_MS = 60 * 1000L; // 错误兜底冷却
    public static final long DEFAULT_STALE_WINDOW_MS = 30 * 60 * 1000L; // 过期消息过滤
    public static final int DEFAULT_TEXT_CHUNK_LIMIT = 3500; // 超长文本分片

    public static final String TOPIC_BOT_MESSAGE = "/v1.0/im/bot/messages/get";
    public static final String TOPIC_CARD_CALLBACK = "/v1.0/card/instances/callback";

    /** 传输模式。对齐钉钉官方两种接收模式：stream 为默认长连接；http 为 HTTP 模式（dispatcher 形态，见 HttpModeVerifier）。 */
    public static final String TRANSPORT_STREAM = "stream";
    public static final String TRANSPORT_HTTP = "http";
    public static final long DEFAULT_HTTP_TIMESTAMP_TOLERANCE_MS = 3600_000L; // 官方默认 1 小时

    final String clientId;
    final String clientSecret;
    final String apiBase;
    final String oapiBase;
    final String cardTemplateId;
    final long streamThrottleMs;
    final long cardWatchdogMs;
    final long errorCooldownMs;
    final long staleMessageWindowMs;
    final int textChunkLimit;
    final double cardQps;
    final boolean autoReconnect;
    final long keepAliveIdleMs;
    final Consumer<String> debug;
    final PolicyConfig policyConfig; // 新增：策略配置
    /** 入站传输模式：TRANSPORT_STREAM（默认）或 TRANSPORT_HTTP（HTTP 模式）。 */
    public final String transport;
    /** HTTP 模式验签时间戳容忍窗口毫秒（默认 1 小时，<=0 关闭窗口检查）。 */
    public final long httpTimestampToleranceMs;
    /** per-chat 串行队列（默认启用）。 */
    public final ChatQueue.Config chatQueue;
    /** 媒体批处理（默认关闭）。 */
    public final ChatQueue.MediaConfig mediaBatch;
    /** 出站配置：重试参数、before_send/after_send 钩子、统一页脚。 */
    public final OutboundConfig outbound;
    /** SSRF 白名单：命中的主机名跳过公网校验（支持通配符 *.example.com）。 */
    public final java.util.List<String> ssrfAllowlist;

    private Config(Builder b) {
        this.clientId = b.clientId;
        this.clientSecret = b.clientSecret;
        this.apiBase = b.apiBase == null || b.apiBase.isEmpty() ? DEFAULT_API_BASE : b.apiBase;
        this.oapiBase = b.oapiBase == null || b.oapiBase.isEmpty() ? DEFAULT_OAPI_BASE : b.oapiBase;
        this.cardTemplateId = b.cardTemplateId == null || b.cardTemplateId.isEmpty()
                ? DEFAULT_CARD_TEMPLATE_ID : b.cardTemplateId;
        this.streamThrottleMs = b.streamThrottleMs > 0 ? b.streamThrottleMs : 800;
        this.cardWatchdogMs = b.cardWatchdogMs >= 0 ? b.cardWatchdogMs : DEFAULT_CARD_WATCHDOG_MS;
        this.errorCooldownMs = b.errorCooldownMs >= 0 ? b.errorCooldownMs : DEFAULT_ERROR_COOLDOWN_MS;
        this.staleMessageWindowMs = b.staleMessageWindowMs >= 0 ? b.staleMessageWindowMs : DEFAULT_STALE_WINDOW_MS;
        this.textChunkLimit = b.textChunkLimit > 0 ? b.textChunkLimit : DEFAULT_TEXT_CHUNK_LIMIT;
        this.cardQps = b.cardQps > 0 ? b.cardQps : 20;
        this.autoReconnect = b.autoReconnect;
        this.keepAliveIdleMs = b.keepAliveIdleMs > 0 ? b.keepAliveIdleMs : 120_000;
        this.debug = b.debug == null ? s -> {} : b.debug;
        this.policyConfig = b.policyConfig != null ? b.policyConfig : new PolicyConfig();
        this.transport = b.transport == null || b.transport.isEmpty() ? TRANSPORT_STREAM : b.transport;
        if (!TRANSPORT_STREAM.equals(this.transport) && !TRANSPORT_HTTP.equals(this.transport)) {
            throw new IllegalArgumentException(
                    "Config.transport: unknown transport '" + this.transport + "' (supported: stream, http)");
        }
        this.httpTimestampToleranceMs = b.httpTimestampToleranceMs != 0
                ? b.httpTimestampToleranceMs : DEFAULT_HTTP_TIMESTAMP_TOLERANCE_MS;
        this.chatQueue = b.chatQueue != null ? b.chatQueue : new ChatQueue.Config();
        this.mediaBatch = b.mediaBatch;
        this.outbound = b.outbound;
        this.ssrfAllowlist = b.ssrfAllowlist != null ? b.ssrfAllowlist : java.util.Collections.emptyList();
    }

    public void debug(String msg) {
        debug.accept(msg);
    }

    public static Builder builder(String clientId, String clientSecret) {
        return new Builder(clientId, clientSecret);
    }

    public static class Builder {
        final String clientId;
        final String clientSecret;
        String apiBase;
        String oapiBase;
        String cardTemplateId;
        long streamThrottleMs;
        long cardWatchdogMs = -1;
        long errorCooldownMs = -1;
        long staleMessageWindowMs = -1;
        int textChunkLimit = 0;
        double cardQps;
        boolean autoReconnect = true;
        long keepAliveIdleMs;
        Consumer<String> debug;
        PolicyConfig policyConfig; // 新增
        String transport;
        long httpTimestampToleranceMs;
        ChatQueue.Config chatQueue;
        ChatQueue.MediaConfig mediaBatch;
        OutboundConfig outbound;
        java.util.List<String> ssrfAllowlist;

        Builder(String clientId, String clientSecret) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public Builder apiBase(String v) { this.apiBase = v; return this; }
        public Builder oapiBase(String v) { this.oapiBase = v; return this; }
        public Builder cardTemplateId(String v) { this.cardTemplateId = v; return this; }
        public Builder streamThrottleMs(long v) { this.streamThrottleMs = v; return this; }
        public Builder cardWatchdogMs(long v) { this.cardWatchdogMs = v; return this; }
        public Builder errorCooldownMs(long v) { this.errorCooldownMs = v; return this; }
        public Builder staleMessageWindowMs(long v) { this.staleMessageWindowMs = v; return this; }
        public Builder textChunkLimit(int v) { this.textChunkLimit = v; return this; }
        public Builder cardQps(double v) { this.cardQps = v; return this; }
        public Builder autoReconnect(boolean v) { this.autoReconnect = v; return this; }
        public Builder keepAliveIdleMs(long v) { this.keepAliveIdleMs = v; return this; }
        public Builder debug(Consumer<String> v) { this.debug = v; return this; }
        public Builder policyConfig(PolicyConfig v) { this.policyConfig = v; return this; } // 新增
        public Builder transport(String v) { this.transport = v; return this; }
        public Builder httpTimestampToleranceMs(long v) { this.httpTimestampToleranceMs = v; return this; }
        public Builder chatQueue(ChatQueue.Config v) { this.chatQueue = v; return this; }
        public Builder mediaBatch(ChatQueue.MediaConfig v) { this.mediaBatch = v; return this; }
        public Builder outbound(OutboundConfig v) { this.outbound = v; return this; }
        public Builder ssrfAllowlist(java.util.List<String> v) { this.ssrfAllowlist = v; return this; }

        public Config build() {
            if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) {
                throw new IllegalArgumentException("clientId/clientSecret required");
            }
            return new Config(this);
        }
    }
}
