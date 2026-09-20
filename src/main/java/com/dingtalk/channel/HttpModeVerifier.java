package com.dingtalk.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 传输验签（dispatcher 形态：验签与分发由 SDK 负责，HTTP 服务由外部提供）。
 *
 * <p>钉钉企业内部机器人 HTTP 模式回调（官方协议）：
 * <ul>
 *   <li>请求头 {@code timestamp} + {@code sign}</li>
 *   <li>{@code sign = Base64(HmacSHA256(timestamp + "\n" + appSecret, appSecret))}</li>
 *   <li>timestamp 与当前时间差应在容忍窗口内（官方默认 1 小时）</li>
 * </ul>
 */
public final class HttpModeVerifier {
    private HttpModeVerifier() {}

    /**
     * 校验签名与时间戳窗口（<=0 关闭窗口检查）。兼容秒/毫秒级时间戳，常量时间比较。
     *
     * @throws SecurityException 验签失败（缺少头/时间戳非法/超窗/签名不匹配）
     */
    public static void verifySign(String secret, String timestamp, String sign, long toleranceMs) {
        if (timestamp == null || timestamp.isEmpty() || sign == null || sign.isEmpty()) {
            throw new SecurityException("http mode: missing timestamp/sign headers");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new SecurityException("http mode: invalid timestamp header");
        }
        if (ts < 100_000_000_000L) { // 秒级时间戳统一换算为毫秒
            ts *= 1000;
        }
        if (toleranceMs > 0) {
            long age = Math.abs(System.currentTimeMillis() - ts);
            if (age > toleranceMs) {
                throw new SecurityException("http mode: timestamp outside tolerance window");
            }
        }
        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = Base64.getEncoder().encodeToString(
                    mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("http mode: hmac init failed", e);
        }
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("http mode: signature mismatch");
        }
    }
}
