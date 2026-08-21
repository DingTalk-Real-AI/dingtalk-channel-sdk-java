package io.github.typefield.dingtalk.channel.safety;

/**
 * 配置消息批处理行为。
 */
public class BatchConfig {
    /** 批处理延迟（毫秒），默认 600ms */
    public final long delayMs;
    /** 长消息阈值（字符数） */
    public final int longThresholdChars;
    /** 长消息延迟（毫秒），默认 2s */
    public final long longDelayMs;
    /** 最大批处理消息数，默认 8 */
    public final int maxMessages;
    /** 最大批处理字符数，默认 4000 */
    public final int maxChars;

    private BatchConfig(Builder b) {
        this.delayMs = b.delayMs > 0 ? b.delayMs : 600;
        this.longThresholdChars = b.longThresholdChars > 0 ? b.longThresholdChars : 1000;
        this.longDelayMs = b.longDelayMs > 0 ? b.longDelayMs : 2000;
        this.maxMessages = b.maxMessages > 0 ? b.maxMessages : 8;
        this.maxChars = b.maxChars > 0 ? b.maxChars : 4000;
    }

    public BatchConfig() {
        this(new Builder());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        long delayMs;
        int longThresholdChars;
        long longDelayMs;
        int maxMessages;
        int maxChars;

        public Builder delayMs(long v) { this.delayMs = v; return this; }
        public Builder longThresholdChars(int v) { this.longThresholdChars = v; return this; }
        public Builder longDelayMs(long v) { this.longDelayMs = v; return this; }
        public Builder maxMessages(int v) { this.maxMessages = v; return this; }
        public Builder maxChars(int v) { this.maxChars = v; return this; }

        public BatchConfig build() {
            return new BatchConfig(this);
        }
    }
}
