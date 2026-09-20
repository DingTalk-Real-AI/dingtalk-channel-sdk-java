package com.dingtalk.channel.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 钉钉 AI 卡片渲染器 Markdown 归一化（SPEC §7 / E10），移植自官方 connector。 */
public final class MarkdownUtil {
    private static final Pattern TABLE_DIVIDER =
            Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|?\\s*:?-+:?\\s*)+\\|?\\s*$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|?.*\\|.*\\|?\\s*$");
    private static final Pattern BLOCK_START = Pattern.compile(
            "^(\\s{0,3}(?:[-*+]|\\d+[.)])[ ])|(\\s{0,3}\\|)|(\\s{0,3}#{1,6}\\s)|(\\s{0,3}(?:[-*_])\\s*(?:[-*_])\\s*(?:[-*_]))");
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}```");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?");
    private static final Pattern CRLF = Pattern.compile("\r\n?");

    private MarkdownUtil() {}

    public static String normalizeForCard(String content) {
        return fixNewlines(ensureTableBlankLines(content));
    }

    /** 表格分隔行前若无空行则插入（否则钉钉不渲染表格）。 */
    static String ensureTableBlankLines(String text) {
        String[] lines = CRLF.matcher(text).replaceAll("\n").split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String next = i + 1 < lines.length ? lines[i + 1] : "";
            if (i > 0
                    && TABLE_ROW.matcher(lines[i]).matches()
                    && isDivider(next)
                    && !lines[i - 1].trim().isEmpty()
                    && !TABLE_ROW.matcher(lines[i - 1]).matches()) {
                out.add("");
            }
            out.add(lines[i]);
        }
        return String.join("\n", out);
    }

    private static boolean isDivider(String line) {
        return line != null && !line.isEmpty() && line.contains("|") && TABLE_DIVIDER.matcher(line).matches();
    }

    /** 单 \n → <br>，按代码块/引用/块语法行约定处理。 */
    static String fixNewlines(String text) {
        String[] lines = CRLF.matcher(text).replaceAll("\n").split("\n", -1);

        // 1. 合并连续引用行（代码块外），<br> 连接，续行去 > 前缀。
        List<String> merged = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        boolean inCode = false;
        for (String line : lines) {
            boolean isFence = FENCE.matcher(line).find();
            if (inCode) {
                flushQuote(merged, pending);
                merged.add(line);
                if (isFence) {
                    inCode = false;
                }
                continue;
            }
            if (isFence) {
                flushQuote(merged, pending);
                merged.add(line);
                inCode = true;
                continue;
            }
            Matcher q = QUOTE.matcher(line);
            if (q.find()) {
                if (pending.isEmpty()) {
                    pending.add(line);
                } else {
                    pending.add(q.replaceFirst(""));
                }
            } else {
                flushQuote(merged, pending);
                merged.add(line);
            }
        }
        flushQuote(merged, pending);

        // 2. 逐行决定分隔符。
        StringBuilder sb = new StringBuilder();
        inCode = false;
        for (int i = 0; i < merged.size(); i++) {
            String cur = merged.get(i);
            boolean nextInCode = FENCE.matcher(cur).find() ? !inCode : inCode;
            if (i < merged.size() - 1) {
                String next = merged.get(i + 1);
                boolean keepNl = nextInCode
                        || cur.isEmpty()
                        || next.isEmpty()
                        || FENCE.matcher(next).find()
                        || BLOCK_START.matcher(next).find();
                sb.append(cur).append(keepNl ? '\n' : "<br>");
            } else {
                sb.append(cur);
            }
            inCode = nextInCode;
        }
        return sb.toString();
    }

    private static void flushQuote(List<String> merged, List<String> pending) {
        if (!pending.isEmpty()) {
            merged.add(String.join("<br>", pending));
            pending.clear();
        }
    }
}
