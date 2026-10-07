package com.dingtalk.channel;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;

/** 可注入 A2UI 发送通道；消息接受对象或 JSON 字符串的非空列表。 */
public interface A2UIClient {
    A2UICardResult sendCard(A2UITarget target, List<?> messages) throws IOException, InterruptedException;
    JsonObject updateCard(String bizId, List<?> messages, String flowStatus) throws IOException, InterruptedException;
}
