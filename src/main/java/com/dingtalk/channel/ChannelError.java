package com.dingtalk.channel;

/**
 * 结构化错误，携带错误码。
 */
public final class ChannelError extends RuntimeException {
    private final ErrorCode code;

    public enum ErrorCode {
        TARGET_REVOKED,
        PERMISSION_DENIED,
        FORMAT_ERROR,
        RATE_LIMITED,
        QPS_LIMITED,
        SEND_TIMEOUT,
        UNKNOWN,
        SSRF_BLOCKED
    }

    public ChannelError(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ChannelError(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    /**
     * 判断错误是否可重试。
     */
    public static boolean isRetryable(Throwable err) {
        if (err instanceof ChannelError) {
            ChannelError ce = (ChannelError) err;
            return ce.code == ErrorCode.RATE_LIMITED
                    || ce.code == ErrorCode.QPS_LIMITED
                    || ce.code == ErrorCode.UNKNOWN
                    || ce.code == ErrorCode.SEND_TIMEOUT;
        }
        return true;
    }

    /**
     * 判断是否为回复目标已撤回。
     */
    public static boolean isReplyTargetGone(Throwable err) {
        return err instanceof ChannelError && ((ChannelError) err).code == ErrorCode.TARGET_REVOKED;
    }

    /**
     * 判断是否为格式错误。
     */
    public static boolean isFormatError(Throwable err) {
        return err instanceof ChannelError && ((ChannelError) err).code == ErrorCode.FORMAT_ERROR;
    }

    /**
     * 将原始错误分类为结构化 ChannelError。
     */
    public static ChannelError classify(Throwable err) {
        if (err == null) {
            return null;
        }
        if (err instanceof ChannelError) {
            return (ChannelError) err;
        }
        ErrorCode code = classifyCode(err);
        return new ChannelError(code, err.getMessage(), err);
    }

    private static ErrorCode classifyCode(Throwable err) {
        String msg = err.getMessage() != null ? err.getMessage().toLowerCase() : "";

        if (msg.contains("status 429") || msg.contains("too many requests")) {
            return ErrorCode.RATE_LIMITED;
        }
        if (msg.contains("status 401") || msg.contains("status 403")) {
            return ErrorCode.PERMISSION_DENIED;
        }
        if (msg.contains("status 400")) {
            return ErrorCode.FORMAT_ERROR;
        }
        if (msg.contains("status 404")) {
            return ErrorCode.TARGET_REVOKED;
        }
        if (msg.contains("qpslimit")) {
            return ErrorCode.QPS_LIMITED;
        }
        if (msg.contains("timeout") || msg.contains("context deadline exceeded")) {
            return ErrorCode.SEND_TIMEOUT;
        }

        return ErrorCode.UNKNOWN;
    }
}
