package io.github.typefield.dingtalk.channel.safety;

/**
 * 策略评估结果。
 */
public final class PolicyDecision {
    public static final PolicyDecision ALLOWED = new PolicyDecision(true, null);
    
    public final boolean allowed;
    public final RejectReason reason;

    public PolicyDecision(boolean allowed, RejectReason reason) {
        this.allowed = allowed;
        this.reason = reason;
    }
}
