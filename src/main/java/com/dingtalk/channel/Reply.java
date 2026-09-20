package com.dingtalk.channel;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/** 回复句柄：text/markdown/image 走 sessionWebhook；stream() 走 AI 卡片（SPEC §4）。 */
public final class Reply {
    private final IncomingMessage msg;
    private final Config cfg;
    private final TokenProvider tokens;
    private final CardClient cards;
    private final OapiClient oapi;

    Reply(IncomingMessage msg, Config cfg, TokenProvider tokens, CardClient cards, OapiClient oapi) {
        this.msg = msg;
        this.cfg = cfg;
        this.tokens = tokens;
        this.cards = cards;
        this.oapi = oapi;
    }

    public void text(String content) {
        webhook("sampleText", Collections3.map("content", content));
    }

    public void markdown(String title, String text) {
        Map<String, Object> param = new HashMap<>();
        param.put("title", title == null || title.isEmpty() ? firstLineTitle(text) : title);
        param.put("text", text);
        webhook("sampleMarkdown", param);
    }

    public void image(String imageUrl) {
        webhook("sampleImageMsg", Collections3.map("photoURL", imageUrl));
    }

    /** 换取消息附件下载地址（E9）。 */
    public String downloadUrl(String downloadCode, String msgId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-acs-dingtalk-access-token", tokens.get());
        String url = cfg.apiBase + "/v1.0/robot/messageFiles/download";
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("downloadCode", downloadCode);
        reqBody.addProperty("robotCode", cfg.clientId);
        JsonObject out = HttpClient.request("POST", url, headers, reqBody).getAsJsonObject();
        return out.has("downloadUrl") ? out.get("downloadUrl").getAsString() : "";
    }

    /** 上传媒体文件，返回 mediaId（E9）。mediaType: image|file|video|voice。 */
    public OapiClient.MediaUploadResult uploadMedia(String mediaType, String filename, String contentType, byte[] data)
            throws java.io.IOException {
        return oapi.uploadMedia(mediaType, filename, contentType, data);
    }

    /** 立即创建并投递 AI 卡片（E1）。失败时返回带降级的 streamer（E4）。 */
    public CardStreamer stream() throws InterruptedException {
        CardClient.CardInstance card;
        try {
            card = cards.createAndDeliver(CardClient.target(
                    IncomingMessage.TYPE_GROUP.equals(msg.conversationType),
                    msg.conversationId,
                    !msg.senderStaffId.isEmpty() ? msg.senderStaffId : msg.senderId,
                    cfg.clientId));
        } catch (RuntimeException e) {
            cfg.debug("card create failed, fallback to webhook text: " + e.getMessage());
            card = null; // 静默降级
        }
        CardStreamer streamer = new CardStreamer(cards, card, this::text, cfg.streamThrottleMs);
        if (card != null) {
            streamer.armWatchdog();
        }
        return streamer;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> ERR_LIM_LAST = new java.util.concurrent.ConcurrentHashMap<>();

    /** 错误兜底文本冷却（connector 同款：同会话 60s 一次，防刷屏）。 */
    private void textWithCooldown(String content) {
        long cooldown = cfg.errorCooldownMs;
        if (cooldown > 0) {
            long now = System.currentTimeMillis();
            Long last = ERR_LIM_LAST.get(msg.conversationId);
            if (last != null && now - last < cooldown) {
                return;
            }
            ERR_LIM_LAST.put(msg.conversationId, now);
        }
        text(content);
    }

    /** 超长文本按 newline 边界切分。 */
    static java.util.List<String> chunkText(String text, int limit) {
        if (limit <= 0 || text.length() <= limit) {
            return java.util.Collections.singletonList(text);
        }
        java.util.List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            if (text.length() - start <= limit) {
                chunks.add(text.substring(start));
                break;
            }
            int cut = -1;
            for (int i = start + limit; i >= start + limit / 2; i--) {
                if (text.charAt(i) == '\n') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut <= 0) {
                cut = start + limit;
            }
            chunks.add(text.substring(start, cut));
            start = cut;
        }
        return chunks;
    }

    private Map<String, Object> applyOutbound(String msgKey, Map<String, Object> msgParam) {
        OutboundConfig out = cfg.outbound;
        if (out == null) {
            return msgParam;
        }
        Map<String, Object> param = out.applyFooter(msgKey, msgParam);
        if (out.beforeSend != null) {
            Map<String, Object> replaced = out.beforeSend.apply("reply", msg.conversationId, param);
            if (replaced != null) {
                param = replaced;
            }
        }
        return param;
    }

    private void afterSend(boolean ok, String error) {
        OutboundConfig out = cfg.outbound;
        if (out != null && out.afterSend != null) {
            out.afterSend.apply("reply", msg.conversationId, ok, error);
        }
    }

    private void webhook(String msgKey, Map<String, Object> msgParam) {
        msgParam = applyOutbound(msgKey, msgParam);
        int limit = cfg.textChunkLimit;
        if (limit > 0 && msgParam != null) {
            Object content = msgParam.get("content");
            Object text = msgParam.get("text");
            if (content instanceof String && ((String) content).length() > limit) {
                for (String c : chunkText((String) content, limit)) {
                    java.util.Map<String, Object> p = new java.util.HashMap<>();
                    p.put("content", c);
                    webhookOnce(msgKey, p);
                }
                return;
            }
            if (text instanceof String && ((String) text).length() > limit) {
                for (String t : chunkText((String) text, limit)) {
                    java.util.Map<String, Object> p = new java.util.HashMap<>();
                    p.put("title", msgParam.get("title"));
                    p.put("text", t);
                    webhookOnce(msgKey, p);
                }
                return;
            }
        }
        webhookOnce(msgKey, msgParam);
    }

    private void webhookOnce(String msgKey, Map<String, Object> msgParam) {
        if (msg.sessionWebhook.isEmpty()) {
            throw new IllegalStateException("reply: sessionWebhook missing");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("x-acs-dingtalk-access-token", tokens.get());
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("msgKey", msgKey);
            // 官方文档要求 msgParam 为字符串化 JSON（对象形式会 400）。
            body.put("msgParam", HttpClient.GSON.toJson(msgParam));
            HttpClient.request("POST", msg.sessionWebhook, headers, body);
            afterSend(true, null);
        } catch (RuntimeException e) {
            afterSend(false, String.valueOf(e.getMessage()));
            throw e;
        }
    }

    static String firstLineTitle(String text) {
        for (String line : (text == null ? "" : text).split("\n")) {
            String t = line.replaceFirst("^[#*->\\s]+", "");
            if (!t.isEmpty()) {
                return t.length() > 20 ? t.substring(0, 20) : t;
            }
        }
        return "Message";
    }

    private static final class Collections3 {
        static Map<String, Object> map(String k, Object v) {
            Map<String, Object> m = new HashMap<>();
            m.put(k, v);
            return m;
        }
    }
}
