package com.dingtalk.channel;

import java.util.HashMap;
import java.util.Map;

/** 消息表情回应（"🤔Thinking"状态章）——移植自 dws connect_card.go（hermes 同款）。 */
public final class Emotion {
    public static final String THINKING = "\uD83E\uDD14Thinking";
    public static final String DONE = "\uD83E\uDD73Done";

    private final Config cfg;
    private final CardClient cards;

    Emotion(Config cfg, CardClient cards) {
        this.cfg = cfg;
        this.cards = cards;
    }

    private void send(String conversationId, String msgId, String name, boolean recall) throws InterruptedException {
        if (conversationId == null || conversationId.isEmpty() || msgId == null || msgId.isEmpty()) {
            throw new IllegalArgumentException("emotion needs openConversationId and openMsgId");
        }
        Map<String, Object> textEmotion = new HashMap<>();
        textEmotion.put("emotionId", "2659900");
        textEmotion.put("emotionName", name);
        textEmotion.put("text", name);
        textEmotion.put("backgroundId", "im_bg_1");
        Map<String, Object> body = new HashMap<>();
        body.put("robotCode", cfg.clientId);
        body.put("openConversationId", conversationId);
        body.put("openMsgId", msgId);
        body.put("emotionType", 2);
        body.put("emotionName", name);
        body.put("textEmotion", textEmotion);
        cards.call("POST", recall ? "/v1.0/robot/emotion/recall" : "/v1.0/robot/emotion/reply", body);
    }

    /** 在用户消息上打"🤔Thinking"状态章（仅人发的消息）。 */
    public void markThinking(String conversationId, String msgId) throws InterruptedException {
        send(conversationId, msgId, THINKING, false);
    }

    /** 把"🤔Thinking"换成"🥳Done"（best-effort）。 */
    public void markDone(String conversationId, String msgId) throws InterruptedException {
        try {
            send(conversationId, msgId, THINKING, true);
        } catch (RuntimeException ignore) {
            // best-effort
        }
        send(conversationId, msgId, DONE, false);
    }
}
