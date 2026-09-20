package com.dingtalk.channel.outbound;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class MarkdownTest {
    @Test
    public void normalizeForCard() {
        assertEquals("a<br>b", MarkdownUtil.normalizeForCard("a\nb"));
        assertEquals("a\n\nb", MarkdownUtil.normalizeForCard("a\n\nb"));
        assertEquals("```\nx\ny\n```", MarkdownUtil.normalizeForCard("```\nx\ny\n```"));
        assertEquals("- a\n- b", MarkdownUtil.normalizeForCard("- a\n- b"));
        assertEquals("# T<br>body", MarkdownUtil.normalizeForCard("# T\nbody"));
        assertEquals("body\n# T", MarkdownUtil.normalizeForCard("body\n# T"));
        assertEquals("a<br>> q1<br>q2<br>b", MarkdownUtil.normalizeForCard("a\n> q1\n> q2\nb"));
        assertEquals(
                "x\n\n| a | b |\n| -- | -- |\n| 1 | 2 |",
                MarkdownUtil.normalizeForCard("x\n| a | b |\n| -- | -- |\n| 1 | 2 |"));
    }
}
