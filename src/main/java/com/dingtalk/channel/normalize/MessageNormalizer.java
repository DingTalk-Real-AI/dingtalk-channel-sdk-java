package com.dingtalk.channel.normalize;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.dingtalk.channel.IncomingMessage;

import java.util.ArrayList;
import java.util.List;

public final class MessageNormalizer {

    public static class ParseResult {
        public String text;
        public List<IncomingMessage.Resource> resources;
        public List<IncomingMessage.Mention> mentions;
        /** richText 段内已见过的下载码（单条消息内去重）。 */
        public java.util.Set<String> seenCodes;

        public ParseResult() {
            this.text = "";
            this.resources = new ArrayList<>();
            this.mentions = new ArrayList<>();
            this.seenCodes = new java.util.HashSet<>();
        }
    }

    /**
     * 钉钉机器人回调支持: text / richText / picture / file / audio / video / markdown / actionCard / interactiveCard / reply
     */
    public static ParseResult parseContent(String msgType, JsonObject content, List<IncomingMessage.AtUser> atUsers) {
        ParseResult result = new ParseResult();
        if (content == null) {
            return result;
        }

        if ("richText".equals(msgType)) {
            parseRichText(content, result);
        } else if ("picture".equals(msgType)) {
            parsePicture(content, result);
        } else if ("file".equals(msgType)) {
            parseFile(content, result);
        } else if ("audio".equals(msgType)) {
            parseAudio(content, result);
        } else if ("video".equals(msgType)) {
            parseVideo(content, result);
        } else if ("markdown".equals(msgType)) {
            result.text = str(content, "text");
        } else if ("actionCard".equals(msgType)) {
            parseActionCard(content, result);
        } else if ("interactiveCard".equals(msgType)) {
            parseInteractiveCard(content, result);
        } else if ("reply".equals(msgType)) {
            parseReply(content, result);
        } else {
            if (content.has("content") && !content.get("content").isJsonNull()) {
                result.text = content.get("content").getAsString();
            }
        }

        return result;
    }

    private static void parseRichText(JsonObject content, ParseResult result) {
        if (!content.has("richText") || !content.get("richText").isJsonArray()) {
            return;
        }
        JsonArray arr = content.getAsJsonArray("richText");
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject item = e.getAsJsonObject();
            String itemType = str(item, "type");
            if ("text".equals(itemType)) {
                sb.append(str(item, "text"));
            } else if ("at".equals(itemType)) {
                if (item.has("atUserIds") && item.get("atUserIds").isJsonArray()) {
                    for (JsonElement uid : item.getAsJsonArray("atUserIds")) {
                        String id = uid.getAsString();
                        result.mentions.add(new IncomingMessage.Mention(id, "", false));
                    }
                }
                if (item.has("atMobiles") && item.get("atMobiles").isJsonArray()) {
                    for (JsonElement m : item.getAsJsonArray("atMobiles")) {
                        result.mentions.add(new IncomingMessage.Mention(m.getAsString(), "", false));
                    }
                }
            } else if ("picture".equals(itemType)) {
                // 兼容 downloadCode / pictureDownloadCode / picture；
                // 仅接受非空字符串，防止脏数据；同一下载码单条消息内去重。
                String code = strictStr(item, "downloadCode");
                if (code.isEmpty()) {
                    code = strictStr(item, "pictureDownloadCode");
                }
                if (code.isEmpty()) {
                    code = strictStr(item, "picture");
                }
                if (!code.isEmpty() && result.seenCodes.add(code)) {
                    result.resources.add(new IncomingMessage.Resource("image", code));
                }
            } else if ("file".equals(itemType)) {
                String code = strictStr(item, "downloadCode");
                String fileName = strictStr(item, "fileName");
                if (!code.isEmpty() && result.seenCodes.add(code)) {
                    result.resources.add(new IncomingMessage.Resource("file", code, fileName, ""));
                }
            }
        }
        result.text = sb.toString();
    }

    private static void parsePicture(JsonObject content, ParseResult result) {
        IncomingMessage.Resource r = new IncomingMessage.Resource("image", str(content, "downloadCode"));
        result.resources.add(r);
    }

    private static void parseFile(JsonObject content, ParseResult result) {
        String downloadCode = str(content, "downloadCode");
        String fileName = str(content, "fileName");
        result.resources.add(new IncomingMessage.Resource("file", downloadCode, fileName, ""));
        result.text = "[文件: " + fileName + "]";
    }

    private static void parseAudio(JsonObject content, ParseResult result) {
        String downloadCode = str(content, "downloadCode");
        String recognition = str(content, "recognition");
        String fileName = str(content, "fileName");
        result.resources.add(new IncomingMessage.Resource("audio", downloadCode, fileName, recognition));
        result.text = recognition.isEmpty() ? "[语音消息]" : recognition;
    }

    private static void parseVideo(JsonObject content, ParseResult result) {
        String downloadCode = str(content, "downloadCode");
        String fileName = str(content, "fileName");
        result.resources.add(new IncomingMessage.Resource("video", downloadCode, fileName, ""));
        result.text = "[视频]";
    }

    private static void parseActionCard(JsonObject content, ParseResult result) {
        String title = str(content, "title");
        String body = str(content, "text");
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n").append(body);
        if (content.has("actionUrlItemList") && content.get("actionUrlItemList").isJsonArray()) {
            for (JsonElement e : content.getAsJsonArray("actionUrlItemList")) {
                if (e.isJsonObject()) {
                    String url = str(e.getAsJsonObject(), "actionUrl");
                    if (!url.isEmpty()) {
                        sb.append("\n").append(url);
                    }
                }
            }
        }
        result.text = sb.toString();
    }

    private static void parseInteractiveCard(JsonObject content, ParseResult result) {
        String url = str(content, "biz_custom_action_url");
        if (url.isEmpty()) {
            result.text = "[interactiveCard消息]";
        } else {
            result.text = "收到交互式卡片链接：" + url;
        }
    }

    private static void parseReply(JsonObject content, ParseResult result) {
        String body = str(content, "text");
        StringBuilder sb = new StringBuilder(body);
        if (content.has("repliedMsg") && !content.get("repliedMsg").isJsonNull()) {
            String quoted = extractQuotedText(content.get("repliedMsg"));
            if (!quoted.isEmpty()) {
                sb.append("\n").append(quoted);
            }
        }
        result.text = sb.toString();
    }

    private static String extractQuotedText(JsonElement repliedMsg) {
        JsonObject replied = null;
        if (repliedMsg.isJsonObject()) {
            replied = repliedMsg.getAsJsonObject();
        } else if (repliedMsg.isJsonPrimitive()) {
            try {
                replied = JsonParser.parseString(repliedMsg.getAsString()).getAsJsonObject();
            } catch (Exception e) {
                return "";
            }
        }
        if (replied == null) {
            return "";
        }
        String quoted = str(replied, "text");
        if (quoted.isEmpty() && replied.has("text") && replied.get("text").isJsonObject()) {
            quoted = str(replied.getAsJsonObject("text"), "content");
        }
        if (quoted.isEmpty()) {
            quoted = str(replied, "content");
        }
        return quoted;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 严格取字符串：数字/布尔等非字符串原始值一律视为缺失（getAsString 会把 123 强转成 "123"，不能用于防脏数据）。 */
    private static String strictStr(JsonObject o, String key) {
        if (o != null && o.has(key) && !o.get(key).isJsonNull()
                && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isString()) {
            return o.get(key).getAsString();
        }
        return "";
    }
}
