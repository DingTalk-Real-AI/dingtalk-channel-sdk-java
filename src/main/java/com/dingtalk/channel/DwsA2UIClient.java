package com.dingtalk.channel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 按 dingtalk-aicard 的公开 DWS 接入方式发送 A2UI；DWS 需单独安装、登录。
 * profile 决定发送身份，与机器人应用 Token 独立。始终使用 argv，不调用 shell。
 */
public final class DwsA2UIClient implements A2UIClient {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final List<String> FLOW_STATUSES = Collections.unmodifiableList(Arrays.asList(
            "PROCESSING", "INPUTTING", "FINISH", "EXECUTING", "ERROR",
            "ABORTED", "TIMEOUT", "CONFIRMING", "CONFIRMED"));
    private static final List<String> OPERATIONS = Arrays.asList(
            "createSurface", "updateComponents", "updateDataModel", "deleteSurface");
    private final List<String> command;
    private final String profile;
    private final long timeoutMs;

    public DwsA2UIClient() {
        this(Collections.singletonList("dws"), null, 30000);
    }

    public DwsA2UIClient(List<String> command, String profile, long timeoutMs) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("command 必须是非空字符串数组");
        for (String part : command) {
            if (part == null || part.isEmpty() || part.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("command 必须是非空字符串数组");
            }
        }
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs 必须大于 0");
        this.command = new ArrayList<>(command);
        this.profile = profile == null ? null : identifier(profile, "profile");
        if (this.profile != null && this.profile.contains(",")) throw new IllegalArgumentException("A2UI 发送只允许一个 DWS Profile");
        this.timeoutMs = timeoutMs;
    }

    static String identifier(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " 必须是非空标识");
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 32 || value.charAt(i) == 127) throw new IllegalArgumentException(name + " 必须是非空标识");
        }
        return value.trim();
    }

    private static JsonElement parse(String json) {
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("JSON 尾部存在多余内容");
            return value;
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("不是有效 JSON");
        }
    }

    /** 只检查消息信封；组件和完整创建状态使用 dingtalk-aicard 校验。 */
    public static String serializeMessages(List<?> messages) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("A2UI 消息必须是非空数组");
        List<String> strings = new ArrayList<>();
        for (Object message : messages) {
            String encoded;
            JsonElement value;
            try {
                encoded = message instanceof String ? (String) message : GSON.toJson(message);
                value = parse(encoded);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("A2UI 消息不是有效 JSON");
            }
            if (!value.isJsonObject()) throw new IllegalArgumentException("A2UI 消息必须是 version=v1.0 的对象");
            JsonObject object = value.getAsJsonObject();
            if (!object.has("version") || !object.get("version").isJsonPrimitive()
                    || !object.get("version").getAsJsonPrimitive().isString() || !"v1.0".equals(object.get("version").getAsString())) {
                throw new IllegalArgumentException("A2UI 消息必须是 version=v1.0 的对象");
            }
            int count = 0;
            for (String operation : OPERATIONS) {
                if (object.has(operation)) {
                    count++;
                    JsonElement op = object.get(operation);
                    if (!op.isJsonObject()) throw new IllegalArgumentException("A2UI 操作必须是 JSON 对象");
                    JsonElement surface = op.getAsJsonObject().get("surfaceId");
                    if (surface == null || !surface.isJsonPrimitive() || !surface.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("surfaceId 必须是非空标识");
                    }
                    identifier(surface.getAsString(), "surfaceId");
                }
            }
            if (count != 1) throw new IllegalArgumentException("A2UI 消息必须包含一个操作");
            strings.add(encoded);
        }
        String content = GSON.toJson(strings);
        if (content.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException("DWS A2UI content 超过 64 KiB");
        return content;
    }

    private static List<JsonObject> envelopeChain(JsonObject receipt) throws IOException {
        List<JsonObject> chain = new ArrayList<>();
        JsonObject value = receipt;
        for (int i = 0; i < 5 && value != null; i++) {
            chain.add(value);
            if (isBoolean(value, "success", false) || isBoolean(value, "ok", false) || isBoolean(value, "isError", true)
                    || isBoolean(value, "dry_run", true) || isBoolean(value, "dryRun", true)
                    || (value.has("error") && !value.get("error").isJsonNull())) throw new IOException("DWS 返回失败回执");
            JsonElement outcome = value.get("outcome");
            if (outcome != null && (!outcome.isJsonPrimitive() || !outcome.getAsJsonPrimitive().isString()
                    || (!"success".equals(outcome.getAsString()) && !"pending".equals(outcome.getAsString())))) throw new IOException("DWS 返回失败回执");
            JsonElement next = value.get("data");
            if (next == null || next.isJsonNull()) next = value.get("result");
            value = next != null && next.isJsonObject() ? next.getAsJsonObject() : null;
        }
        boolean accepted = false;
        for (JsonObject item : chain) accepted |= isBoolean(item, "success", true) || isBoolean(item, "ok", true);
        if (!accepted) throw new IOException("DWS 回执未明确确认接受请求；发送结果可能未知，请核实后再重试");
        return chain;
    }

    private static boolean isBoolean(JsonObject value, String name, boolean expected) {
        JsonElement item = value.get(name);
        return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isBoolean() && item.getAsBoolean() == expected;
    }

    private JsonObject invoke(String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(command);
        argv.addAll(Arrays.asList("chat", "message"));
        argv.addAll(Arrays.asList(args));
        argv.addAll(Arrays.asList("--format=json", "--yes"));
        if (profile != null) argv.add("--profile=" + profile);
        Process process;
        try {
            process = new ProcessBuilder(argv).start();
        } catch (IOException error) {
            throw new IOException("DWS 无法启动；请检查安装和 command 配置");
        }
        FutureTask<byte[]> output = new FutureTask<>(() -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                if (bytes.size() + count > 8 * 1024 * 1024) throw new IOException("DWS 回执超过 8 MiB");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        });
        Thread reader = new Thread(output, "dws-a2ui-output");
        reader.setDaemon(true);
        reader.start();
        Thread errors = new Thread(() -> {
            // 丢弃 stderr，避免错误文本带出命令行、业务数据或凭据。
            try (InputStream input = process.getErrorStream()) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) != -1) { }
            } catch (IOException ignored) { }
        }, "dws-a2ui-errors");
        errors.setDaemon(true);
        errors.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("DWS 执行超时；发送结果可能未知，请核实后再重试");
            }
            if (process.exitValue() != 0) throw new IOException("DWS 执行失败；发送结果可能未知，请核实后再重试");
            JsonElement value;
            try {
                value = parse(new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            } catch (ExecutionException | java.util.concurrent.TimeoutException | IllegalArgumentException error) {
                throw new IOException("DWS 输出不是有效 JSON；发送结果可能未知，请保留现场核实");
            }
            if (!value.isJsonObject()) throw new IOException("DWS 回执必须是 JSON 对象");
            JsonObject receipt = value.getAsJsonObject();
            envelopeChain(receipt);
            return receipt;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            process.getErrorStream().close();
            reader.interrupt();
            errors.interrupt();
        }
    }

    @Override
    public A2UICardResult sendCard(A2UITarget target, List<?> messages) throws IOException, InterruptedException {
        if (target == null) throw new IllegalArgumentException("A2UI target 必须恰好选择一个接收目标");
        String content = serializeMessages(messages);
        JsonObject receipt = invoke("send-a2ui-card", "--" + target.flag + "=" + target.id, "--content=" + content);
        List<JsonObject> chain = envelopeChain(receipt);
        String bizId = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            JsonElement value = chain.get(i).get("bizId");
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                try { bizId = identifier(value.getAsString(), "bizId"); break; }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return new A2UICardResult(bizId, receipt);
    }

    @Override
    public JsonObject updateCard(String bizId, List<?> messages, String flowStatus) throws IOException, InterruptedException {
        String id = identifier(bizId, "bizId");
        String status = flowStatus == null ? "" : flowStatus.trim().toUpperCase(Locale.ROOT);
        if (status.matches("[1-9]")) status = FLOW_STATUSES.get(Integer.parseInt(status) - 1);
        if (!FLOW_STATUSES.contains(status)) throw new IllegalArgumentException("不支持的 A2UI flowStatus");
        String content = serializeMessages(messages);
        return invoke("update-a2ui-card", "--biz-id=" + id, "--content=" + content, "--flow-status=" + status);
    }
}
