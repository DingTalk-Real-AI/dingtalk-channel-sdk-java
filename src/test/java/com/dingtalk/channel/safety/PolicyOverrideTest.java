package com.dingtalk.channel.safety;

import com.google.gson.JsonObject;
import com.dingtalk.channel.IncomingMessage;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 群级策略覆盖：显式条目放行、黑名单不可豁免、逐群 requireMention/allowFrom/blockFrom。 */
public class PolicyOverrideTest {
    private static IncomingMessage msg(String cid, String sender, boolean inAtList) {
        JsonObject d = new JsonObject();
        d.addProperty("conversationId", cid);
        d.addProperty("conversationType", "2");
        d.addProperty("senderId", sender);
        d.addProperty("senderStaffId", sender);
        d.addProperty("isInAtList", inAtList);
        d.addProperty("msgtype", "text");
        return IncomingMessage.fromJson(d);
    }

    @Test
    public void overrideAdmitsGroupInAllowlistMode() {
        Map<String, PolicyConfig.GroupOverride> ov = new HashMap<>();
        ov.put("cid-other", new PolicyConfig.GroupOverride());
        PolicyGate gate = new PolicyGate(new PolicyConfig()
                .groupAllowlist(Arrays.asList("cid-allowed"))
                .groupOverrides(ov));
        assertTrue(gate.evaluate(msg("cid-other", "staff-1", true)).allowed);
        assertEquals(RejectReason.GROUP_NOT_ALLOWED, gate.evaluate(msg("cid-unknown", "staff-1", true)).reason);
    }

    @Test
    public void blocklistNeverOverridden() {
        Map<String, PolicyConfig.GroupOverride> ov = new HashMap<>();
        ov.put("cid-bad", new PolicyConfig.GroupOverride().enabled(true));
        PolicyGate gate = new PolicyGate(new PolicyConfig()
                .groupBlocklist(Arrays.asList("cid-bad"))
                .groupOverrides(ov));
        assertEquals(RejectReason.GROUP_BLOCKED, gate.evaluate(msg("cid-bad", "staff-1", true)).reason);
    }

    @Test
    public void overrideDisableAndRequireMention() {
        Map<String, PolicyConfig.GroupOverride> ov = new HashMap<>();
        ov.put("cid-1", new PolicyConfig.GroupOverride().enabled(false));
        assertEquals(RejectReason.GROUP_DISABLED,
                new PolicyGate(new PolicyConfig().groupOverrides(ov))
                        .evaluate(msg("cid-1", "staff-1", true)).reason);

        Map<String, PolicyConfig.GroupOverride> ov2 = new HashMap<>();
        ov2.put("cid-1", new PolicyConfig.GroupOverride().requireMention(false));
        ov2.put("cid-2", new PolicyConfig.GroupOverride().requireMention(true));
        PolicyGate gate = new PolicyGate(new PolicyConfig().requireMention(true).groupOverrides(ov2));
        assertTrue(gate.evaluate(msg("cid-1", "staff-1", false)).allowed);
        assertEquals(RejectReason.NO_MENTION, gate.evaluate(msg("cid-2", "staff-1", false)).reason);
        assertEquals(RejectReason.NO_MENTION, gate.evaluate(msg("cid-3", "staff-1", false)).reason);
    }

    @Test
    public void overrideAllowFromAndBlockFrom() {
        Map<String, PolicyConfig.GroupOverride> ov = new HashMap<>();
        ov.put("cid-1", new PolicyConfig.GroupOverride()
                .allowFrom(Arrays.asList("staff-1", "staff-2"))
                .blockFrom(Arrays.asList("staff-2")));
        PolicyGate gate = new PolicyGate(new PolicyConfig().groupOverrides(ov));
        assertTrue(gate.evaluate(msg("cid-1", "staff-1", true)).allowed);
        assertEquals(RejectReason.SENDER_NOT_ALLOWED, gate.evaluate(msg("cid-1", "staff-9", true)).reason);
        assertEquals(RejectReason.SENDER_BLOCKED, gate.evaluate(msg("cid-1", "staff-2", true)).reason);
    }
}
