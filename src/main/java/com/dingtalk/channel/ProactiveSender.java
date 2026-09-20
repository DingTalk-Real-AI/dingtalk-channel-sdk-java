package com.dingtalk.channel;

import java.util.HashMap;
import java.util.Map;

/** 主动发消息（不依赖入站消息）。 */
final class ProactiveSender {
    private final Config cfg;
    private final CardClient cards;

    ProactiveSender(Config cfg, CardClient cards) {
        this.cfg = cfg;
        this.cards = cards;
    }

    void sendText(SendTarget target, String content) throws InterruptedException {
        Map<String, Object> param = new HashMap<>();
        param.put("content", content);
        sendWithHooks(target, "sampleText", param);
    }

    void sendMarkdown(SendTarget target, String title, String text) throws InterruptedException {
        Map<String, Object> param = new HashMap<>();
        param.put("title", title == null || title.isEmpty() ? Reply.firstLineTitle(text) : title);
        param.put("text", text);
        sendWithHooks(target, "sampleMarkdown", param);
    }

    /** 视频消息（sampleVideo，对齐官方 connector sendVideoProactive）。mediaId 均为带 @ 的 RawMediaID。 */
    void sendVideo(SendTarget target, String rawVideoMediaId, String rawPicMediaId, long durationMs) throws InterruptedException {
        Map<String, Object> param = new HashMap<>();
        param.put("duration", String.valueOf(durationMs > 0 ? durationMs : 60000));
        param.put("videoMediaId", rawVideoMediaId);
        param.put("videoType", "mp4");
        param.put("picMediaId", rawPicMediaId == null ? "" : rawPicMediaId);
        send(target, "sampleVideo", param);
    }

    /** 音频消息（sampleAudio，对齐官方 connector sendAudioProactive）。 */
    void sendAudio(SendTarget target, String rawMediaId, long durationMs) throws InterruptedException {
        Map<String, Object> param = new HashMap<>();
        param.put("mediaId", rawMediaId);
        param.put("duration", String.valueOf(durationMs > 0 ? durationMs : 60000));
        send(target, "sampleAudio", param);
    }

    void sendImage(SendTarget target, String imageUrl) throws InterruptedException {
        Map<String, Object> param = new HashMap<>();
        param.put("photoURL", imageUrl);
        send(target, "sampleImageMsg", param);
    }

    void sendWithHooks(SendTarget target, String msgKey, Map<String, Object> msgParam) throws InterruptedException {
        OutboundConfig out = cfg.outbound;
        if (out != null) {
            msgParam = out.applyFooter(msgKey, msgParam);
            if (out.beforeSend != null) {
                Map<String, Object> replaced = out.beforeSend.apply("send",
                        target.userId != null && !target.userId.isEmpty() ? target.userId : target.conversationId, msgParam);
                if (replaced != null) {
                    msgParam = replaced;
                }
            }
        }
        String targetId = target.userId != null && !target.userId.isEmpty() ? target.userId : target.conversationId;
        try {
            send(target, msgKey, msgParam);
            if (out != null && out.afterSend != null) {
                out.afterSend.apply("send", targetId, true, null);
            }
        } catch (RuntimeException | InterruptedException e) {
            if (out != null && out.afterSend != null) {
                out.afterSend.apply("send", targetId, false, String.valueOf(e.getMessage()));
            }
            throw e;
        }
    }

    private void send(SendTarget target, String msgKey, Map<String, Object> msgParam) throws InterruptedException {
        // msgParam 必须字符串化 JSON（官方文档）
        String param = HttpClient.GSON.toJson(msgParam);
        boolean hasUser = target.userId != null && !target.userId.isEmpty();
        boolean hasGroup = target.conversationId != null && !target.conversationId.isEmpty();
        if (hasUser == hasGroup) {
            throw new IllegalArgumentException("SendTarget: 恰好设置 userId（单聊）或 conversationId（群聊）之一");
        }
        if (hasUser) {
            Map<String, Object> body = new HashMap<>();
            body.put("robotCode", cfg.clientId);
            body.put("userIds", java.util.Collections.singletonList(target.userId));
            body.put("msgKey", msgKey);
            body.put("msgParam", param);
            cards.call("POST", "/v1.0/robot/oToMessages/batchSend", body);
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("robotCode", cfg.clientId);
        body.put("openConversationId", target.conversationId);
        body.put("msgKey", msgKey);
        body.put("msgParam", param);
        if (!target.atUserIds.isEmpty()) {
            body.put("atUserIds", target.atUserIds);
        }
        if (!target.atDingtalkIds.isEmpty()) {
            body.put("atOpendingtalkIds", target.atDingtalkIds);
        }
        if (target.atAll) {
            body.put("isAtAll", true);
        }
        cards.call("POST", "/v1.0/robot/groupMessages/send", body);
    }
}
