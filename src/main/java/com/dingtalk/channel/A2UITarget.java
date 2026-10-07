package com.dingtalk.channel;

/** A2UI 个人 openDingTalkId 或群 openConversationId，与机器人 staffId 区分。 */
public final class A2UITarget {
    final String flag;
    final String id;

    private A2UITarget(String flag, String id) {
        this.flag = flag;
        this.id = DwsA2UIClient.identifier(id, flag);
    }

    public static A2UITarget forUser(String openDingTalkId) {
        return new A2UITarget("open-dingtalk-id", openDingTalkId);
    }

    public static A2UITarget forGroup(String conversationId) {
        return new A2UITarget("conversation-id", conversationId);
    }
}
