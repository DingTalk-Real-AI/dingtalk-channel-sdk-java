package com.dingtalk.channel.safety;

/**
 * 消息被策略拒绝时触发的事件。
 */
public final class RejectEvent {
    public final String messageId;
    public final String chatId;
    public final String senderId;
    public final RejectReason reason;

    public RejectEvent(String messageId, String chatId, String senderId, RejectReason reason) {
        this.messageId = messageId;
        this.chatId = chatId;
        this.senderId = senderId;
        this.reason = reason;
    }
}
