package io.github.typefield.dingtalk.channel;

import java.util.Map;

/**
 * 出站配置：重试参数、before_send/after_send 钩子、统一页脚。
 */
public final class OutboundConfig {

    /** 发送前钩子：可返回替换后的 payload（返回 null 表示沿用原 payload）。 */
    public interface BeforeSend {
        Map<String, Object> apply(String kind, String target, Map<String, Object> payload);
    }

    /** 发送后钩子（含失败；error 为错误描述，成功时为 null）。 */
    public interface AfterSend {
        void apply(String kind, String target, boolean ok, String error);
    }

    /** 出站重试参数（指数退避）。 */
    public static final class Retry {
        public final int maxAttempts;
        public final long baseDelayMs;

        public Retry(int maxAttempts, long baseDelayMs) {
            this.maxAttempts = maxAttempts;
            this.baseDelayMs = baseDelayMs;
        }

        public Retry() {
            this(3, 500);
        }
    }

    public final Retry retry;
    public final BeforeSend beforeSend;
    public final AfterSend afterSend;
    /** 统一页脚：追加到每条文本/Markdown 消息末尾（如免责声明）。 */
    public final String footer;

    public OutboundConfig(Retry retry, BeforeSend beforeSend, AfterSend afterSend, String footer) {
        this.retry = retry != null ? retry : new Retry();
        this.beforeSend = beforeSend;
        this.afterSend = afterSend;
        this.footer = footer != null ? footer : "";
    }

    public OutboundConfig() {
        this(null, null, null, null);
    }

    /** 应用页脚：sampleText/sampleMarkdown 追加 footer。 */
    public Map<String, Object> applyFooter(String msgKey, Map<String, Object> param) {
        if (footer.isEmpty()) {
            return param;
        }
        Map<String, Object> p = new MapCopy(param);
        if ("sampleText".equals(msgKey) && p.get("content") instanceof String) {
            p.put("content", p.get("content") + "\n\n" + footer);
        } else if ("sampleMarkdown".equals(msgKey) && p.get("text") instanceof String) {
            p.put("text", p.get("text") + "\n\n---\n" + footer);
        }
        return p;
    }

    private static final class MapCopy extends java.util.HashMap<String, Object> {
        MapCopy(Map<String, Object> src) {
            super(src);
        }
    }
}
