package io.github.typefield.dingtalk.channel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** HTTP 模式传输：验签协议、幂等分发、start() 引导。 */
public class HttpModeTest {
    private static final String SECRET = "sec";

    private static String signFor(String timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));
    }

    private static String httpCallbackBody(String msgId) {
        return "{\"conversationId\":\"cid-w\",\"conversationType\":\"1\",\"msgId\":\"" + msgId
                + "\",\"senderStaffId\":\"staff-1\",\"sessionWebhook\":\"\","
                + "\"text\":{\"content\":\"hi\"},\"isInAtList\":true,\"msgtype\":\"text\"}";
    }

    @Test
    public void verifySignPerProtocol() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String tsSec = String.valueOf(System.currentTimeMillis() / 1000);
        HttpModeVerifier.verifySign(SECRET, ts, signFor(ts, SECRET), 3600_000L);
        HttpModeVerifier.verifySign(SECRET, tsSec, signFor(tsSec, SECRET), 3600_000L); // 秒级兼容
        try {
            HttpModeVerifier.verifySign(SECRET, ts, signFor(ts, "wrong"), 3600_000L);
            fail("wrong secret should fail");
        } catch (SecurityException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("signature mismatch"));
        }
        String old = String.valueOf(System.currentTimeMillis() - 2 * 3600_000L);
        try {
            HttpModeVerifier.verifySign(SECRET, old, signFor(old, SECRET), 3600_000L);
            fail("stale timestamp should fail");
        } catch (SecurityException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("tolerance"));
        }
        HttpModeVerifier.verifySign(SECRET, old, signFor(old, SECRET), 0); // <=0 关闭窗口
    }

    @Test
    public void dispatchAndDedup() throws Exception {
        DingTalkChannel ch = DingTalkChannel.create(
                Config.builder("id", SECRET).transport(Config.TRANSPORT_HTTP).build());
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        ch.onMessage((msg, reply) -> {
            assertEquals("hi", msg.text);
            calls.incrementAndGet();
            done.countDown();
        });

        String ts = String.valueOf(System.currentTimeMillis());
        String sign = signFor(ts, SECRET);
        String body = httpCallbackBody("w-1");
        ch.handleHttpCallback(body, ts, sign);
        ch.handleHttpCallback(body, ts, sign); // 重试重推 → 去重
        assertTrue("handler should fire", done.await(2, TimeUnit.SECONDS));
        Thread.sleep(100); // 等待异步 worker 完成可能的重复投递
        assertEquals(1, calls.get());

        try {
            ch.handleHttpCallback(body, ts, "bad-sign");
            fail("bad sign should throw");
        } catch (SecurityException e) {
            assertTrue(e.getMessage().contains("signature"));
        }
        try {
            ch.handleHttpCallback("{bad", ts, sign);
            fail("bad payload should throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("payload"));
        }
        ch.close();
    }

    @Test
    public void startRedirectsInWebhookMode() throws Exception {
        DingTalkChannel ch = DingTalkChannel.create(
                Config.builder("id", SECRET).transport(Config.TRANSPORT_HTTP).build());
        ch.onMessage((msg, reply) -> {});
        try {
            ch.start();
            fail("start() should redirect to handleHttpCallback");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("handleHttpCallback"));
        }
        ch.close();
    }
}
