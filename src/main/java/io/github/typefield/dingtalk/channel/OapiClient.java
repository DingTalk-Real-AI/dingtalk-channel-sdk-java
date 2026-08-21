package io.github.typefield.dingtalk.channel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 媒体上传：OAPI gettoken + multipart /media/upload（对比官方 connector media/common.ts 移植，E9）。 */
public final class OapiClient {
    /** 媒体上传结果。 */
    public static final class MediaUploadResult {
        public final String mediaId;
        public final String type;
        /** AI 卡片内嵌必须用完整 URL（openclaw connector 新版 media.ts 实证），裸 mediaId 不渲染 */
        public final String downloadUrl;

        MediaUploadResult(String mediaId, String type) {
            this.mediaId = mediaId;
            this.type = type;
            this.downloadUrl = "https://down.dingtalk.com/media/" + mediaId;
        }
    }

    private static final String ALPHABET = "0123456789abcdef";

    private final Config cfg;
    private String token = "";
    private long expiresAtMs;

    OapiClient(Config cfg) {
        this.cfg = cfg;
    }

    private synchronized String getToken() throws java.io.IOException {
        if (!token.isEmpty() && System.currentTimeMillis() < expiresAtMs - 60_000) {
            return token;
        }
        String q = "appkey=" + URLEncoder.encode(cfg.clientId, "UTF-8")
                + "&appsecret=" + URLEncoder.encode(cfg.clientSecret, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(cfg.oapiBase + "/gettoken?" + q).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        int status = conn.getResponseCode();
        String text = new String(readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream()), StandardCharsets.UTF_8);
        if (status >= 400) {
            throw new java.io.IOException("oapi gettoken: http " + status + " " + text);
        }
        JsonObject out = JsonParser.parseString(text).getAsJsonObject();
        int errcode = out.has("errcode") ? out.get("errcode").getAsInt() : -1;
        String accessToken = out.has("access_token") ? out.get("access_token").getAsString() : "";
        if (errcode != 0 || accessToken.isEmpty()) {
            throw new java.io.IOException("oapi gettoken: errcode=" + errcode);
        }
        token = accessToken;
        long expiresIn = out.has("expires_in") ? out.get("expires_in").getAsLong() : 7200;
        expiresAtMs = System.currentTimeMillis() + expiresIn * 1000;
        return token;
    }

    /**
     * 上传媒体文件，返回 mediaId（去前导 @）。
     *
     * @param mediaType   image | file | video | voice
     * @param contentType 空则按类型推断
     */
    public MediaUploadResult uploadMedia(String mediaType, String filename, String contentType, byte[] data)
            throws java.io.IOException {
        String tok = getToken();
        if (contentType == null || contentType.isEmpty()) {
            contentType = "image".equals(mediaType) ? "image/jpeg" : "application/octet-stream";
        }

        String boundary = "----DingTalkChannelSDK" + randomHex(12);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, "--" + boundary + "\r\n");
        writeUtf8(body, "Content-Disposition: form-data; name=\"media\"; filename=\"" + filename + "\"\r\n");
        writeUtf8(body, "Content-Type: " + contentType + "\r\n\r\n");
        body.write(data, 0, data.length);
        writeUtf8(body, "\r\n--" + boundary + "--\r\n");

        String q = "access_token=" + URLEncoder.encode(tok, "UTF-8")
                + "&type=" + URLEncoder.encode(mediaType, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(cfg.oapiBase + "/media/upload?" + q).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream os = conn.getOutputStream()) {
            body.writeTo(os);
        }
        int status = conn.getResponseCode();
        String text = new String(readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream()), StandardCharsets.UTF_8);
        if (status >= 400) {
            throw new java.io.IOException("media/upload: http " + status + " " + text);
        }
        JsonObject out = JsonParser.parseString(text).getAsJsonObject();
        int errcode = out.has("errcode") ? out.get("errcode").getAsInt() : -1;
        String mediaId = out.has("media_id") ? out.get("media_id").getAsString() : "";
        if (errcode != 0 || mediaId.isEmpty()) {
            throw new java.io.IOException("media/upload: errcode=" + errcode);
        }
        if (mediaId.startsWith("@")) {
            mediaId = mediaId.substring(1);
        }
        String type = out.has("type") ? out.get("type").getAsString() : null;
        return new MediaUploadResult(mediaId, type);
    }

    private static void writeUtf8(OutputStream os, String s) throws java.io.IOException {
        os.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        in.close();
        return buf.toByteArray();
    }

    private static String randomHex(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt((int) (Math.random() * ALPHABET.length())));
        }
        return sb.toString();
    }
}
