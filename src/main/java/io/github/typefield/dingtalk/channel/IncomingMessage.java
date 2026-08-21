package io.github.typefield.dingtalk.channel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.typefield.dingtalk.channel.normalize.MessageNormalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IncomingMessage {
    public static final String TYPE_DM = "dm";
    public static final String TYPE_GROUP = "group";

    public final String conversationId;
    public final String conversationType;
    public final String conversationTitle;
    public final String senderId;
    public final String senderStaffId;
    public final String senderNick;
    public final String senderCorpId;
    public String text;
    public final String msgType;
    public final JsonElement content;
    public final String sessionWebhook;
    public final long webhookExpiredAt;
    public final String msgId;
    public final long createAt;
    public final boolean isAdmin;
    public final boolean isInAtList;
    public final JsonObject raw;
    public List<Resource> resources;
    public List<Mention> mentions;
    public boolean mentionAll;

    IncomingMessage() {
        this.conversationId = "";
        this.conversationType = TYPE_GROUP;
        this.conversationTitle = "";
        this.senderId = "";
        this.senderStaffId = "";
        this.senderNick = "";
        this.senderCorpId = "";
        this.msgType = "text";
        this.content = null;
        this.sessionWebhook = "";
        this.webhookExpiredAt = 0;
        this.msgId = "";
        this.createAt = 0;
        this.isAdmin = false;
        this.isInAtList = true;
        this.raw = null;
    }

    IncomingMessage(JsonObject d) {
        this.conversationId = str(d, "conversationId");
        this.conversationType = "1".equals(str(d, "conversationType")) ? TYPE_DM : TYPE_GROUP;
        this.conversationTitle = str(d, "conversationTitle");
        this.senderId = str(d, "senderId");
        this.senderStaffId = str(d, "senderStaffId");
        this.senderNick = str(d, "senderNick");
        this.senderCorpId = str(d, "senderCorpId");

        String t = "";
        if (d.has("text") && d.get("text").isJsonObject()) {
            t = str(d.getAsJsonObject("text"), "content").trim();
        }
        if (TYPE_GROUP.equals(this.conversationType) && t.startsWith("@")) {
            int i = t.indexOf(' ');
            if (i >= 0) {
                t = t.substring(i + 1).trim();
            }
        }
        this.text = t;

        this.msgType = str(d, "msgtype");
        this.content = d.has("content") ? d.get("content") : null;
        this.sessionWebhook = str(d, "sessionWebhook");
        this.webhookExpiredAt = d.has("sessionWebhookExpiredTime") ? d.get("sessionWebhookExpiredTime").getAsLong() : 0;
        this.msgId = str(d, "msgId");
        this.createAt = d.has("createAt") ? d.get("createAt").getAsLong() : 0;
        this.isAdmin = d.has("isAdmin") && d.get("isAdmin").getAsBoolean();
        this.isInAtList = d.has("isInAtList") && d.get("isInAtList").getAsBoolean();
        this.raw = d;

        this.resources = new ArrayList<>();
        this.mentions = new ArrayList<>();
        this.mentionAll = false;

        JsonObject contentObj = null;
        if (d.has("content") && d.get("content").isJsonObject()) {
            contentObj = d.getAsJsonObject("content");
        }

        List<AtUser> atUsers = new ArrayList<>();
        if (d.has("atUsers") && d.get("atUsers").isJsonArray()) {
            for (JsonElement e : d.getAsJsonArray("atUsers")) {
                if (e.isJsonObject()) {
                    JsonObject ao = e.getAsJsonObject();
                    atUsers.add(new AtUser(str(ao, "dingtalkId"), str(ao, "staffId")));
                }
            }
        }

        MessageNormalizer.ParseResult parsed = MessageNormalizer.parseContent(this.msgType, contentObj, atUsers);

        if (!"text".equals(this.msgType) && parsed.text != null && !parsed.text.isEmpty()) {
            this.text = parsed.text;
        }

        if (parsed.resources != null) {
            this.resources.addAll(parsed.resources);
        }
        if (parsed.mentions != null) {
            this.mentions.addAll(parsed.mentions);
        }

        for (AtUser au : atUsers) {
            if ("all".equals(au.staffId) || "all".equals(au.dingtalkId)) {
                this.mentionAll = true;
            }
            boolean already = false;
            for (Mention m : this.mentions) {
                if (m.userId != null && m.userId.equals(au.staffId)) {
                    already = true;
                    break;
                }
            }
            if (!already && au.staffId != null && !au.staffId.isEmpty()) {
                this.mentions.add(new Mention(au.staffId, au.dingtalkId, false));
            }
        }
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /**
     * 跨包构造入口：safety 层（批处理合并）等外部包从这里构建实例；
     * 根包内代码仍可直接使用包内构造器。
     */
    public static IncomingMessage fromJson(JsonObject d) {
        return new IncomingMessage(d);
    }

    @Override
    public String toString() {
        return "IncomingMessage{msgId=" + msgId + ", text=" + text + ", type=" + conversationType + "}";
    }

    public static class AtUser {
        public final String dingtalkId;
        public final String staffId;

        public AtUser(String dingtalkId, String staffId) {
            this.dingtalkId = dingtalkId;
            this.staffId = staffId;
        }
    }

    public static class Resource {
        public final String type;
        public final String downloadCode;
        public final String fileName;
        public final String recognition;

        public Resource(String type, String downloadCode) {
            this(type, downloadCode, "", "");
        }

        public Resource(String type, String downloadCode, String fileName, String recognition) {
            this.type = type;
            this.downloadCode = downloadCode;
            this.fileName = fileName;
            this.recognition = recognition;
        }
    }

    public static class Mention {
        public final String userId;
        public final String name;
        public final boolean isAll;

        public Mention(String userId, String name, boolean isAll) {
            this.userId = userId;
            this.name = name;
            this.isAll = isAll;
        }
    }
}
