package com.dingtalk.channel;

import com.google.gson.JsonObject;
import com.dingtalk.channel.normalize.MessageNormalizer;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 对齐 lark channel-sdk 新功能：richText 附件资源提取 + downloadFileToFile 流式落盘。 */
public class LarkPortFeatureTest {

    // ── richText 资源提取（lark post 附件区对等能力） ──

    @Test
    public void richTextExtractsPictureAndFileResources() {
        String json = "{\"richText\":["
                + "{\"type\":\"text\",\"text\":\"图1 \"},"
                + "{\"type\":\"picture\",\"picture\":\"dc-1\"},"
                + "{\"type\":\"picture\",\"picture\":\"dc-1\"},"
                + "{\"type\":\"picture\",\"picture\":\"dc-2\"},"
                + "{\"type\":\"file\",\"downloadCode\":\"dc-3\",\"fileName\":\"report.pdf\"},"
                + "{\"type\":\"text\",\"text\":\" 图2\"}]}";
        JsonObject content = JsonParser.parseString(json).getAsJsonObject();
        MessageNormalizer.ParseResult r = MessageNormalizer.parseContent("richText", content, null);

        assertEquals("图1  图2", r.text);
        assertEquals("重复 dc-1 应去重", 3, r.resources.size());
        assertEquals("image", r.resources.get(0).type);
        assertEquals("dc-1", r.resources.get(0).downloadCode);
        assertEquals("file", r.resources.get(2).type);
        assertEquals("dc-3", r.resources.get(2).downloadCode);
        assertEquals("report.pdf", r.resources.get(2).fileName);
    }

    @Test
    public void richTextDirtySegmentsSkipped() {
        String json = "{\"richText\":["
                + "{\"type\":\"picture\",\"picture\":123},"
                + "{\"type\":\"picture\",\"picture\":\"\"},"
                + "{\"type\":\"picture\"},"
                + "{\"type\":\"file\",\"downloadCode\":42},"
                + "{\"type\":\"text\",\"text\":\"ok\"}]}";
        JsonObject content = JsonParser.parseString(json).getAsJsonObject();
        MessageNormalizer.ParseResult r = MessageNormalizer.parseContent("richText", content, null);

        assertEquals("ok", r.text);
        assertTrue("脏段不得产生资源，实际: " + r.resources, r.resources.isEmpty());
    }

    // ── downloadFileToFile（流式落盘） ──

    @Test
    public void downloadFileToFileStreamsAndAtomicallyRenames() throws Exception {
        byte[] media = new byte[1024 * 9 + 7];
        Arrays.fill(media, (byte) 'x');
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/v1.0/oauth2/accessToken")) {
                respond(ex, "{\"accessToken\":\"new-tok\",\"expireIn\":7200}");
            } else if (path.equals("/v1.0/robot/robotInfo")) {
                respond(ex, "{\"robotCode\":\"ding-test\",\"robotName\":\"TestBot\"}");
            } else if (path.equals("/v1.0/robot/messageFiles/download")) {
                respond(ex, "{\"downloadUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/media.bin\"}");
            } else if (path.equals("/media.bin")) {
                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, media.length);
                java.io.OutputStream os = ex.getResponseBody();
                os.write(media);
                os.close();
            } else {
                respond(ex, 404, "{}");
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            DingTalkChannel ch = DingTalkChannel.create(Config.builder("ding-test", "s")
                    .apiBase(base).oapiBase(base).streamThrottleMs(10).cardQps(100)
                    .ssrfAllowlist(Arrays.asList("127.0.0.1")).build());

            Path dest = Files.createTempDirectory("larkport").resolve("media.bin");
            long n = ch.downloadFileToFile("dc-1", "m-1", "file", dest);
            assertEquals(media.length, n);
            assertTrue("文件内容不一致", Arrays.equals(media, Files.readAllBytes(dest)));

            // 原有内存下载语义保持不变
            byte[] bytes = ch.downloadFile("dc-1", "m-1", "file");
            assertTrue("downloadFile 被重构破坏", Arrays.equals(media, bytes));

            // 父目录不存在：报错且不落半截文件、不残留临时文件
            Path missing = dest.getParent().resolve("no-such-dir").resolve("m.bin");
            try {
                ch.downloadFileToFile("dc-1", "m-1", "file", missing);
                fail("expected error for missing parent dir");
            } catch (RuntimeException expected) {
                // expected
            }
            try (java.util.stream.Stream<Path> list = Files.list(dest.getParent())) {
                assertEquals("失败后父目录不应残留临时文件", 1, list.count());
            }
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        respond(ex, 200, body);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, raw.length == 0 ? -1 : raw.length);
        if (raw.length > 0) {
            ex.getResponseBody().write(raw);
        }
        ex.close();
    }
}
