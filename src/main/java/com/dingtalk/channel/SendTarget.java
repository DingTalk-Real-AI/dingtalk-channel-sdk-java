package com.dingtalk.channel;

import java.util.ArrayList;
import java.util.List;

/** 主动发消息目标。userId（单聊）与 conversationId（群聊）二选一。 */
public final class SendTarget {
    final String userId;
    final String conversationId;
    final List<String> atUserIds = new ArrayList<>();
    final List<String> atDingtalkIds = new ArrayList<>();
    boolean atAll;

    private SendTarget(String userId, String conversationId) {
        this.userId = userId;
        this.conversationId = conversationId;
    }

    /** 单聊目标。 */
    public static SendTarget user(String userId) {
        return new SendTarget(userId, null);
    }

    /** 群聊目标（openConversationId）。 */
    public static SendTarget group(String conversationId) {
        return new SendTarget(null, conversationId);
    }

    public SendTarget atUserIds(String... ids) {
        for (String id : ids) {
            atUserIds.add(id);
        }
        return this;
    }

    public SendTarget atDingtalkIds(String... ids) {
        for (String id : ids) {
            atDingtalkIds.add(id);
        }
        return this;
    }

    public SendTarget atAll() {
        this.atAll = true;
        return this;
    }
}
