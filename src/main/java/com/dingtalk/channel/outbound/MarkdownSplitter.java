package com.dingtalk.channel.outbound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 分片：尊重代码围栏，优先在标题前断行。
 */
public final class MarkdownSplitter {

    private static final Pattern FENCE = Pattern.compile("^```(\\w*)$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s");

    private MarkdownSplitter() {}

    public static List<String> splitWithCodeFences(String text, int limit) {
        if (text.length() <= limit) {
            return Collections.singletonList(text);
        }

        String[] lines = text.split("\n", -1);
        List<String> chunks = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        int bufferLen = 0;
        boolean inFence = false;
        String fenceLang = "";

        for (String line : lines) {
            if (HEADING.matcher(line).find() && bufferLen > 0 && bufferLen > limit * 3 / 4) {
                String chunk = String.join("\n", buffer);
                if (inFence) {
                    chunk += "\n```";
                }
                chunks.add(chunk);
                buffer.clear();
                bufferLen = 0;
                if (inFence) {
                    String reopen = "```" + fenceLang;
                    buffer.add(reopen);
                    bufferLen += reopen.length() + 1;
                }
            }

            Matcher fm = FENCE.matcher(line);
            if (fm.matches()) {
                if (!inFence) {
                    inFence = true;
                    fenceLang = fm.group(1);
                } else {
                    inFence = false;
                    fenceLang = "";
                }
            }

            buffer.add(line);
            bufferLen += line.length() + 1;

            if (bufferLen > limit) {
                String chunk = String.join("\n", buffer);
                if (inFence) {
                    chunk += "\n```";
                }
                chunks.add(chunk);
                buffer.clear();
                bufferLen = 0;
                if (inFence) {
                    String reopen = "```" + fenceLang;
                    buffer.add(reopen);
                    bufferLen += reopen.length() + 1;
                }
            }
        }

        if (!buffer.isEmpty()) {
            String chunk = String.join("\n", buffer);
            if (inFence) {
                chunk += "\n```";
            }
            chunks.add(chunk);
        }

        return chunks;
    }
}
