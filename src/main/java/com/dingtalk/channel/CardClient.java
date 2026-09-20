package com.dingtalk.channel;

import com.google.gson.JsonObject;

import com.dingtalk.channel.outbound.MarkdownUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** AI 卡片五步协议客户端（SPEC §5）+ 全局限流（SPEC §6）。 */
final class CardClient {
    static final String FLOW_INPUTING = "2";
    static final String FLOW_FINISHED = "3";
    static final String FLOW_FAILED = "5";

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    final Config cfg;
    private final TokenProvider tokens;
    private final TokenBucket bucket;

    CardClient(Config cfg, TokenProvider tokens) {
        this.cfg = cfg;
        this.tokens = tokens;
        this.bucket = new TokenBucket(cfg.cardQps);
    }

    void call(String method, String path, Map<String, Object> body) throws InterruptedException {
        callRaw(method, path, body);
    }

    /** 业务级校验（dws 生产实证）：卡片 API 会在 HTTP 200 里返回 success:false，必须视为失败。 */
    void callChecked(String method, String path, Map<String, Object> body) throws InterruptedException {
        String raw = callRaw(method, path, body);
        if (raw != null && raw.contains("\"success\":false")) {
            throw new HttpClient.ApiException(200, "BusinessFailure", raw);
        }
    }

    private String callRaw(String method, String path, Map<String, Object> body) throws InterruptedException {
        try {
            bucket.waitFor();
            return doCall(method, path, body);
        } catch (HttpClient.ApiException e) {
            if (e.isQpsLimit()) {
                bucket.triggerBackoff();
                bucket.waitFor();
                return doCall(method, path, body);
            }
            throw e;
        }
    }

    private String doCall(String method, String path, Map<String, Object> body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-acs-dingtalk-access-token", tokens.get());
        return HttpClient.requestText(method, cfg.apiBase + path, headers, body);
    }

    /** 卡片投放目标（E5：群聊/单聊自动选择 openSpace）。 */
    static Map<String, Object> target(boolean isGroup, String conversationId, String userId, String robotCode) {
        Map<String, Object> t = new HashMap<>();
        t.put("isGroup", isGroup);
        t.put("conversationId", conversationId);
        t.put("userId", userId);
        t.put("robotCode", robotCode);
        return t;
    }

    static class CardInstance {
        final String outTrackId;
        boolean inputingStarted;

        CardInstance(String outTrackId) {
            this.outTrackId = outTrackId;
        }
    }

    CardInstance createAndDeliver(Map<String, Object> target) throws InterruptedException {
        String outTrackId = "card_" + System.currentTimeMillis() + "_" + randSuffix(8);

        Map<String, Object> cardParamMap = new HashMap<>();
        cardParamMap.put("config", "{\"autoLayout\":true}");
        Map<String, Object> cardData = new HashMap<>();
        cardData.put("cardParamMap", cardParamMap);
        Map<String, Object> createBody = new HashMap<>();
        createBody.put("cardTemplateId", cfg.cardTemplateId);
        createBody.put("outTrackId", outTrackId);
        createBody.put("cardData", cardData);
        createBody.put("callbackType", "STREAM");
        createBody.put("imGroupOpenSpaceModel", Collections3.map("supportForward", true));
        createBody.put("imRobotOpenSpaceModel", Collections3.map("supportForward", true));
        call("POST", "/v1.0/card/instances", createBody);

        Map<String, Object> deliver = new HashMap<>();
        deliver.put("outTrackId", outTrackId);
        deliver.put("userIdType", 1);
        boolean isGroup = (Boolean) target.get("isGroup");
        if (isGroup) {
            deliver.put("openSpaceId", "dtv1.card//IM_GROUP." + target.get("conversationId"));
            deliver.put("imGroupOpenDeliverModel", Collections3.map("robotCode", target.get("robotCode")));
        } else {
            deliver.put("openSpaceId", "dtv1.card//IM_ROBOT." + target.get("userId"));
            Map<String, Object> robotModel = new HashMap<>();
            robotModel.put("spaceType", "IM_ROBOT");
            robotModel.put("robotCode", target.get("robotCode"));
            robotModel.put("extension", Collections3.map("dynamicSummary", "true"));
            deliver.put("imRobotOpenDeliverModel", robotModel);
        }
        callChecked("POST", "/v1.0/card/instances/deliver", deliver);
        return new CardInstance(outTrackId);
    }

    void setStatus(CardInstance card, String status, String content) throws InterruptedException {
        Map<String, Object> body = statusBody(card, status, content);
        if (FLOW_FINISHED.equals(status)) {
            body.put("cardUpdateOptions", Collections3.map("updateCardDataByKey", true));
        }
        call("PUT", "/v1.0/card/instances", body);
    }

    void stream(CardInstance card, String content, boolean finalize) throws InterruptedException {
        String norm = MarkdownUtil.normalizeForCard(content);
        if (!finalize) {
            int end = norm.length();
            while (end > 0 && norm.charAt(end - 1) == '\n') {
                end--;
            }
            norm = norm.substring(0, end);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("outTrackId", card.outTrackId);
        body.put("guid", System.currentTimeMillis() + "_" + randSuffix(6));
        body.put("key", "msgContent");
        body.put("content", norm);
        body.put("isFull", true);
        body.put("isFinalize", finalize);
        body.put("isError", false);
        call("PUT", "/v1.0/card/streaming", body);
    }

    private Map<String, Object> statusBody(CardInstance card, String status, String content) {
        Map<String, Object> cardParamMap = new HashMap<>();
        cardParamMap.put("flowStatus", status);
        cardParamMap.put("msgContent", content);
        cardParamMap.put("staticMsgContent", "");
        cardParamMap.put("sys_full_json_obj", "{\"order\":[\"msgContent\"]}");
        cardParamMap.put("config", "{\"autoLayout\":true}");
        Map<String, Object> cardData = new HashMap<>();
        cardData.put("cardParamMap", cardParamMap);
        Map<String, Object> body = new HashMap<>();
        body.put("outTrackId", card.outTrackId);
        body.put("cardData", cardData);
        return body;
    }

    static String randSuffix(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 小工具：单键 map。 */
    static final class Collections3 {
        static Map<String, Object> map(String k, Object v) {
            Map<String, Object> m = new HashMap<>();
            m.put(k, v);
            return m;
        }

        private Collections3() {}
    }
}
