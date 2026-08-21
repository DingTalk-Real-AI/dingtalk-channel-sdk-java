package io.github.typefield.dingtalk.channel.outbound;

import io.github.typefield.dingtalk.channel.ChannelError;

import java.util.concurrent.Callable;

/**
 * 重试工具：指数退避 + 可重试判断。
 */
public final class RetryUtil {

    public static class RetryOptions {
        public int maxAttempts = 3;
        public long baseDelayMs = 500;

        public RetryOptions() {}

        public RetryOptions(int maxAttempts, long baseDelayMs) {
            this.maxAttempts = maxAttempts;
            this.baseDelayMs = baseDelayMs;
        }
    }

    private RetryUtil() {}

    public static <T> T retry(Callable<T> op, RetryOptions opts) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= opts.maxAttempts; attempt++) {
            try {
                return op.call();
            } catch (Exception e) {
                lastError = e;
                if (!ChannelError.isRetryable(e) || attempt >= opts.maxAttempts) {
                    throw e;
                }
                long delay = opts.baseDelayMs * (long) Math.pow(3, attempt - 1);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw lastError != null ? lastError
                : new IllegalStateException("retry loop exhausted without exception");
    }
}
