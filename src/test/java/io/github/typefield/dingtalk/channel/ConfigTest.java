package io.github.typefield.dingtalk.channel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Config.transport 预留字段：默认 stream、webhook 预留未实现、未知值报错。 */
public class ConfigTest {
    @Test
    public void transportDefaultsToStream() {
        Config cfg = Config.builder("id", "sec").build();
        assertEquals(Config.TRANSPORT_STREAM, cfg.transport);
    }

    @Test
    public void transportWebhookAccepted() {
        Config cfg = Config.builder("id", "sec").transport(Config.TRANSPORT_HTTP).build();
        assertEquals(Config.TRANSPORT_HTTP, cfg.transport);
        assertEquals(3600_000L, cfg.httpTimestampToleranceMs);
    }

    @Test
    public void transportUnknownRejected() {
        try {
            Config.builder("id", "sec").transport("grpc").build();
            fail("unknown transport should be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unknown transport"));
        }
    }

    private static void assertTrue(String msg, boolean cond) {
        if (!cond) throw new AssertionError(msg);
    }
}
