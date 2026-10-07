package com.dingtalk.channel;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class A2UITest {
    private static final String MESSAGE = "{\"version\":\"v1.0\",\"updateDataModel\":{\"surfaceId\":\"sdk-card\",\"path\":\"/text\",\"value\":\"中文、引号\\\"与 $(echo test) `test`\"}}";
    private final List<Path> directories = new ArrayList<>();

    /** 模拟 DWS 可执行进程，只记录 argv，不调用真实服务。 */
    public static class FakeDws {
        public static void main(String[] args) throws Exception {
            Path trace = Paths.get(args[0]);
            String response = args[1], mode = args[2];
            String line = new Gson().toJson(Arrays.asList(args).subList(3, args.length)) + "\n";
            Files.write(trace, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if ("hang".equals(mode)) Thread.sleep(10000);
            if ("fail".equals(mode)) { System.err.print("不应暴露的凭据占位符"); System.exit(2); }
            System.out.print(response);
        }
    }

    private static class Fixture {
        DwsA2UIClient client;
        Path trace;

        List<JsonArray> calls() throws IOException {
            List<JsonArray> result = new ArrayList<>();
            for (String line : Files.readAllLines(trace, StandardCharsets.UTF_8)) result.add(JsonParser.parseString(line).getAsJsonArray());
            return result;
        }
    }

    private Fixture fixture(String response, String mode, long timeoutMs) throws IOException {
        Path dir = Files.createTempDirectory(Paths.get("."), ".a2ui-test-");
        directories.add(dir);
        Fixture result = new Fixture();
        result.trace = dir.resolve("模拟 dws 回执.jsonl");
        result.client = new DwsA2UIClient(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                FakeDws.class.getName(), result.trace.toString(), response, mode), "corp:user", timeoutMs);
        return result;
    }

    @After
    public void cleanup() throws IOException {
        for (Path dir : directories) {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                files.sorted(Collections.reverseOrder()).forEach(file -> { try { Files.delete(file); } catch (IOException ignored) { } });
            }
        }
    }

    @Test
    public void 消息对象和字符串均保留语义() {
        JsonObject object = JsonParser.parseString(MESSAGE).getAsJsonObject();
        JsonArray encoded = JsonParser.parseString(DwsA2UIClient.serializeMessages(Arrays.asList(object, MESSAGE))).getAsJsonArray();
        assertEquals(object, JsonParser.parseString(encoded.get(0).getAsString()));
        assertEquals(MESSAGE, encoded.get(1).getAsString());
        for (List<?> messages : Arrays.asList(Collections.emptyList(), Collections.singletonList("not-json"), Collections.singletonList(null),
                Collections.singletonList("{\"version\":\"v0.8\"}"), Collections.singletonList("{\"version\":\"v1.0\"}"),
                Collections.singletonList("{\"version\":\"v1.0\",\"deleteSurface\":{\"surfaceId\":\"\"}}"))) {
            assertThrows(IllegalArgumentException.class, () -> DwsA2UIClient.serializeMessages(messages));
        }
        object.getAsJsonObject("updateDataModel").addProperty("value", String.join("", Collections.nCopies(24000, "中")));
        assertThrows(IllegalArgumentException.class, () -> DwsA2UIClient.serializeMessages(Collections.singletonList(object)));
    }

    @Test
    public void 显式通道发送单聊群聊并完成原卡片() throws Exception {
        Fixture fixture = fixture("{\"ok\":true,\"outcome\":\"success\",\"data\":{\"success\":true,\"result\":{\"bizId\":\"server-biz\"}}}", "ok", 5000);
        DingTalkChannel ch = DingTalkChannel.create(Config.builder("unused", "unused").a2uiClient(fixture.client).build());
        A2UICardResult result = ch.sendA2UICard(A2UITarget.forUser("D-user"), Collections.singletonList(MESSAGE));
        assertEquals("server-biz", result.bizId);
        assertNull(result.updateWarning);
        assertEquals("server-biz", result.receipt.getAsJsonObject("data").getAsJsonObject("result").get("bizId").getAsString());
        ch.sendA2UICard(A2UITarget.forGroup("--group-value"), Collections.singletonList(MESSAGE));
        ch.updateA2UICard(result.bizId, Collections.singletonList(MESSAGE), "3");
        List<JsonArray> calls = fixture.calls();
        assertEquals(3, calls.size());
        assertTrue(calls.get(0).toString().contains("--open-dingtalk-id=D-user"));
        assertTrue(calls.get(1).toString().contains("--conversation-id=--group-value"));
        assertTrue(calls.get(2).toString().contains("--biz-id=server-biz"));
        assertTrue(calls.get(2).toString().contains("--flow-status=FINISH"));
        for (JsonArray args : calls) {
            assertTrue(args.toString().contains("--profile=corp:user"));
            assertTrue(args.toString().contains("--format=json"));
            assertTrue(args.toString().contains("--yes"));
            assertFalse(args.toString().contains("client-secret"));
            for (com.google.gson.JsonElement arg : args) {
                if (arg.getAsString().startsWith("--content=")) {
                    JsonArray messages = JsonParser.parseString(arg.getAsString().substring("--content=".length())).getAsJsonArray();
                    assertEquals(JsonParser.parseString(MESSAGE), JsonParser.parseString(messages.get(0).getAsString()));
                }
            }
        }
    }

    @Test
    public void 未启用或参数错误不执行子进程() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new DwsA2UIClient(Collections.singletonList("dws"), "corp:user,other:user", 5000));
        DingTalkChannel ch = DingTalkChannel.create(Config.builder("unused", "unused").build());
        assertThrows(IllegalStateException.class, () -> ch.sendA2UICard(A2UITarget.forGroup("cid"), Collections.singletonList(MESSAGE)));
        assertThrows(IllegalStateException.class, () -> ch.updateA2UICard("biz", Collections.singletonList(MESSAGE), "FINISH"));
        DwsA2UIClient client = new DwsA2UIClient(Collections.singletonList("不存在的 dws"), null, 5000);
        assertThrows(IllegalArgumentException.class, () -> A2UITarget.forUser(" "));
        assertThrows(IllegalArgumentException.class, () -> client.sendCard(null, Collections.singletonList(MESSAGE)));
        assertThrows(IllegalArgumentException.class, () -> client.updateCard("", Collections.singletonList(MESSAGE), "FINISH"));
        assertThrows(IllegalArgumentException.class, () -> client.updateCard("biz", Collections.singletonList(MESSAGE), "unknown"));
    }

    @Test
    public void 请求侧bizCardId不作为更新标识且不重发() throws Exception {
        Fixture fixture = fixture("{\"success\":true,\"result\":{\"bizCardId\":\"request-only\",\"openTaskId\":\"task\"}}", "ok", 5000);
        A2UICardResult result = fixture.client.sendCard(A2UITarget.forGroup("cid"), Collections.singletonList(MESSAGE));
        assertNull(result.bizId);
        assertTrue(result.updateWarning.contains("不要自动重发"));
        assertEquals(1, fixture.calls().size());
    }

    @Test
    public void 业务失败进程失败和非JSON回执不被接受() throws Exception {
        for (String[] item : new String[][]{{"{\"success\":false}", "ok"}, {"{\"success\":true,\"result\":{\"success\":false}}", "ok"}, {"not-json", "ok"}, {"{}", "ok"}, {"{\"result\":{\"bizId\":\"unconfirmed\"}}", "ok"}, {"{\"ok\":true,\"outcome\":\"success\",\"dry_run\":true}", "ok"}, {"{}", "fail"}}) {
            Fixture fixture = fixture(item[0], item[1], 5000);
            IOException error = assertThrows(IOException.class, () -> fixture.client.sendCard(A2UITarget.forGroup("cid"), Collections.singletonList(MESSAGE)));
            assertFalse(error.getMessage().contains("不应暴露"));
            assertEquals(1, fixture.calls().size());
        }
    }

    @Test
    public void 超时明确报告未知结果() throws Exception {
        Fixture fixture = fixture("{}", "hang", 200);
        IOException error = assertThrows(IOException.class, () -> fixture.client.sendCard(A2UITarget.forGroup("cid"), Collections.singletonList(MESSAGE)));
        assertTrue(error.getMessage().contains("结果可能未知"));
    }
}
