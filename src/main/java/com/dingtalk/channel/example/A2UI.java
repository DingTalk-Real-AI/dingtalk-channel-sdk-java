package com.dingtalk.channel.example;

import com.dingtalk.channel.A2UICardResult;
import com.dingtalk.channel.A2UITarget;
import com.dingtalk.channel.DwsA2UIClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 使用 DWS 的指定 Profile 发送示例卡片并完成原卡片，不需要机器人凭据。 */
public class A2UI {
    private static List<JsonElement> read(String file) throws Exception {
        List<JsonElement> messages = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(Paths.get(file), StandardCharsets.UTF_8)) {
            for (JsonElement value : JsonParser.parseReader(reader).getAsJsonArray()) messages.add(value);
        }
        return messages;
    }

    public static void main(String[] args) throws Exception {
        String profile = System.getenv("DWS_PROFILE"), recipient = System.getenv("DWS_OPEN_DINGTALK_ID");
        if (profile == null || profile.isEmpty() || recipient == null || recipient.isEmpty()) {
            throw new IllegalArgumentException("请设置 DWS_PROFILE 和 DWS_OPEN_DINGTALK_ID");
        }
        String executable = System.getenv("DWS_BIN");
        if (executable == null || executable.isEmpty()) executable = "dws";
        DwsA2UIClient client = new DwsA2UIClient(Collections.singletonList(executable), profile, 30000);
        A2UICardResult result = client.sendCard(A2UITarget.forUser(recipient), read("example/a2ui-card.json"));
        if (result.bizId == null) throw new IllegalStateException(result.updateWarning);
        client.updateCard(result.bizId, read("example/a2ui-update.json"), "FINISH");
        System.out.println("示例卡片已完成，bizId=" + result.bizId);
    }
}
