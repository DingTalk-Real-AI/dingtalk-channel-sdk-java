package io.github.typefield.dingtalk.channel;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 极简 HTTP + JSON（java.net 实现，唯一 JSON 依赖 Gson）。 */
final class HttpClient {
    static final Gson GSON = new Gson();

    static class ApiException extends RuntimeException {
        final int status;
        final String code;

        ApiException(int status, String code, String message) {
            super("dingtalk api error: http=" + status + " code=" + code + " " + message);
            this.status = status;
            this.code = code;
        }

        boolean isQpsLimit() {
            return status == 403 && code != null && code.contains("QpsLimit");
        }
    }

    /** 同 request，但返回原始文本（业务级 success:false 校验用）。 */
    static String requestText(String method, String url, Map<String, String> headers, Object body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            boolean hasBody = body != null;
            conn.setDoOutput(hasBody);
            if (hasBody) {
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] payload = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int status = conn.getResponseCode();
            byte[] raw = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
            if (status >= 400) {
                throw new RuntimeException("http " + status + " " + text);
            }
            return text;
        } catch (java.io.IOException e) {
            throw new RuntimeException("http io error: " + e.getMessage(), e);
        }
    }

    static JsonElement request(String method, String url, Map<String, String> headers, Object body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            boolean hasBody = body != null;
            conn.setDoOutput(hasBody);
            if (hasBody) {
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] payload = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int status = conn.getResponseCode();
            byte[] raw = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
            if (status >= 400) {
                String code = "", message = "";
                try {
                    if (!text.isEmpty()) {
                        JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                        code = obj.has("code") ? obj.get("code").getAsString() : "";
                        message = obj.has("message") ? obj.get("message").getAsString() : "";
                    }
                } catch (RuntimeException ignore) {
                    // 非 JSON 错误体
                }
                throw new ApiException(status, code, message);
            }
            return text.isEmpty() ? null : JsonParser.parseString(text);
        } catch (java.io.IOException e) {
            throw new RuntimeException("http io error: " + e.getMessage(), e);
        }
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

    private HttpClient() {}
}
