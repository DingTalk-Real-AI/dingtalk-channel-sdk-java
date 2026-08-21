package com.dingtalk.channel.safety;

/**
 * 拒绝原因。
 */
public enum RejectReason {
    GROUP_NOT_ALLOWED,
    GROUP_BLOCKED,
    GROUP_DISABLED,
    NO_MENTION,
    MENTION_ALL_BLOCKED,
    DM_DISABLED,
    DM_NOT_ALLOWED,
    DM_BLOCKED,
    SENDER_NOT_ALLOWED,
    SENDER_BLOCKED
}
