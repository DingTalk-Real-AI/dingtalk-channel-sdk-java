package io.github.typefield.dingtalk.channel;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** access token 获取与缓存（SPEC §8）。 */
final class TokenProvider {
    private final Config cfg;
    private String token = "";
    private long expiresAtMs;

    TokenProvider(Config cfg) {
        this.cfg = cfg;
    }

    synchronized String get() {
        if (!token.isEmpty() && System.currentTimeMillis() < expiresAtMs - 60_000) {
            return token;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("appKey", cfg.clientId);
        body.put("appSecret", cfg.clientSecret);
        JsonObject out = HttpClient.request("POST", cfg.apiBase + "/v1.0/oauth2/accessToken",
                Collections.singletonMap("Content-Type", "application/json"), body).getAsJsonObject();
        String t = out.has("accessToken") ? out.get("accessToken").getAsString() : "";
        if (t.isEmpty()) {
            throw new IllegalStateException("accessToken: empty token in response");
        }
        long expireIn = out.has("expireIn") ? out.get("expireIn").getAsLong() : 7200;
        token = t;
        expiresAtMs = System.currentTimeMillis() + expireIn * 1000;
        return token;
    }
}
