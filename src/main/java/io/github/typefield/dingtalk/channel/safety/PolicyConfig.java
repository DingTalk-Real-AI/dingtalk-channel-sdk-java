package io.github.typefield.dingtalk.channel.safety;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息策略配置。
 */
public final class PolicyConfig {
    /** 群聊白名单（空 = 允许所有群） */
    public List<String> groupAllowlist = new ArrayList<>();
    
    /** 群聊黑名单 */
    public List<String> groupBlocklist = new ArrayList<>();
    
    /** 群聊是否需要 @机器人（默认 true） */
    public Boolean requireMention = true;
    
    /** 是否响应 @所有人（默认 false） */
    public Boolean respondToMentionAll = false;
    
    /** 单聊模式："open" | "disabled" | "allowlist" | "blocklist" */
    public String dmMode = "open";
    
    /** 单聊白名单（dmMode="allowlist" 时生效） */
    public List<String> dmAllowlist = new ArrayList<>();
    
    /** 单聊黑名单（dmMode="blocklist" 时生效） */
    public List<String> dmBlocklist = new ArrayList<>();

    /** 按群（conversationId）覆盖策略。零值字段沿用全局；显式条目可在白名单模式下放行该群，黑名单永不例外。 */
    public Map<String, GroupOverride> groupOverrides = new HashMap<>();

    public PolicyConfig() {}

    public PolicyConfig groupAllowlist(List<String> v) { this.groupAllowlist = v; return this; }
    public PolicyConfig groupBlocklist(List<String> v) { this.groupBlocklist = v; return this; }
    public PolicyConfig requireMention(Boolean v) { this.requireMention = v; return this; }
    public PolicyConfig respondToMentionAll(Boolean v) { this.respondToMentionAll = v; return this; }
    public PolicyConfig dmMode(String v) { this.dmMode = v; return this; }
    public PolicyConfig dmAllowlist(List<String> v) { this.dmAllowlist = v; return this; }
    public PolicyConfig dmBlocklist(List<String> v) { this.dmBlocklist = v; return this; }
    public PolicyConfig groupOverrides(Map<String, GroupOverride> v) { this.groupOverrides = v; return this; }

    /** 单群策略覆盖。零值字段沿用全局配置。 */
    public static final class GroupOverride {
        /** 显式禁用该群（false = 拒绝该群所有消息）；null 沿用全局。 */
        public Boolean enabled;
        /** 覆盖该群的 @机器人 要求；null 沿用全局。 */
        public Boolean requireMention;
        /** 该群内发送者白名单（设置后仅名单内 sender 可通过）。 */
        public List<String> allowFrom = new ArrayList<>();

        /** 该群内发送者黑名单（先于 allowFrom 检查，命中即拒绝）。 */
        public List<String> blockFrom = new ArrayList<>();

        public GroupOverride() {}

        public GroupOverride enabled(Boolean v) { this.enabled = v; return this; }
        public GroupOverride requireMention(Boolean v) { this.requireMention = v; return this; }
        public GroupOverride allowFrom(List<String> v) { this.allowFrom = v; return this; }
        public GroupOverride blockFrom(List<String> v) { this.blockFrom = v; return this; }
    }
}
