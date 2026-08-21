package io.github.typefield.dingtalk.channel.safety;

import io.github.typefield.dingtalk.channel.IncomingMessage;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 消息策略门控。
 */
public final class PolicyGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private PolicyConfig config;

    public PolicyGate(PolicyConfig config) {
        this.config = config != null ? config : new PolicyConfig();
    }

    /**
     * 评估消息是否允许通过。
     */
    public PolicyDecision evaluate(IncomingMessage msg) {
        lock.readLock().lock();
        try {
            if (IncomingMessage.TYPE_GROUP.equals(msg.conversationType)) {
                return evaluateGroup(msg);
            }
            return evaluateDM(msg);
        } finally {
            lock.readLock().unlock();
        }
    }

    private PolicyDecision evaluateGroup(IncomingMessage msg) {
        // 黑名单检查（最高优先级，群覆盖不可豁免）
        if (config.groupBlocklist.contains(msg.conversationId)) {
            return new PolicyDecision(false, RejectReason.GROUP_BLOCKED);
        }

        // 群覆盖（显式条目可在白名单模式下放行该群）
        PolicyConfig.GroupOverride ov = config.groupOverrides.get(msg.conversationId);

        // 白名单检查：全局白名单命中，或存在显式群条目
        if (!config.groupAllowlist.isEmpty()) {
            if (!config.groupAllowlist.contains(msg.conversationId) && ov == null) {
                return new PolicyDecision(false, RejectReason.GROUP_NOT_ALLOWED);
            }
        }

        if (ov != null && Boolean.FALSE.equals(ov.enabled)) {
            return new PolicyDecision(false, RejectReason.GROUP_DISABLED);
        }

        // @机器人检查（群覆盖优先）
        boolean requireMention = config.requireMention != null ? config.requireMention : true;
        if (ov != null && ov.requireMention != null) {
            requireMention = ov.requireMention;
        }
        if (requireMention && !msg.isInAtList) {
            return new PolicyDecision(false, RejectReason.NO_MENTION);
        }

        // 群内发送者黑名单（先于白名单）
        if (ov != null && ov.blockFrom.contains(msg.senderId)) {
            return new PolicyDecision(false, RejectReason.SENDER_BLOCKED);
        }

        // 群内发送者白名单
        if (ov != null && !ov.allowFrom.isEmpty() && !ov.allowFrom.contains(msg.senderId)) {
            return new PolicyDecision(false, RejectReason.SENDER_NOT_ALLOWED);
        }

        // @所有人检查（钉钉无明确 mentionAll 字段）
        // respondToMentionAll 配置暂不生效

        return PolicyDecision.ALLOWED;
    }

    private PolicyDecision evaluateDM(IncomingMessage msg) {
        String mode = config.dmMode != null ? config.dmMode : "open";

        switch (mode) {
            case "disabled":
                return new PolicyDecision(false, RejectReason.DM_DISABLED);
            case "allowlist":
                if (!config.dmAllowlist.contains(msg.senderId)) {
                    return new PolicyDecision(false, RejectReason.DM_NOT_ALLOWED);
                }
                break;
            case "blocklist":
                if (config.dmBlocklist.contains(msg.senderId)) {
                    return new PolicyDecision(false, RejectReason.DM_BLOCKED);
                }
                break;
        }

        return PolicyDecision.ALLOWED;
    }

    /**
     * 更新策略配置。
     */
    public void updateConfig(PolicyConfig config) {
        lock.writeLock().lock();
        try {
            this.config = config != null ? config : new PolicyConfig();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取当前策略配置。
     */
    public PolicyConfig getConfig() {
        lock.readLock().lock();
        try {
            return config;
        } finally {
            lock.readLock().unlock();
        }
    }
}
