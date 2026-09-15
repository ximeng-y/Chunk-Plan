package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link FeedbackText} 清洗规则测试（坑 #58）：首行非空提取 / 样式码剥离 / 控制字符剥离 /
 * 全空返回 null / 截断 / null 入参。
 */
class FeedbackTextTest {

    @Test
    void nullAndEmptyReturnNull() {
        assertNull(FeedbackText.sanitize(null));
        assertNull(FeedbackText.sanitize(""));
    }

    @Test
    void blankOnlyReturnsNull() {
        assertNull(FeedbackText.sanitize("   "));
        assertNull(FeedbackText.sanitize("\n\n  \r\n\t"));
    }

    @Test
    void takesFirstNonBlankLine() {
        // 首行为空行时取其后首个非空行；只要首行不再含换行（GUI 单行展示）
        assertEquals("第二行", FeedbackText.sanitize("\n\n§a第二行\n第三行"));
        assertEquals("第一行", FeedbackText.sanitize("§a第一行\n§b第二行"));
    }

    @Test
    void stripsFormattingCodes() {
        // § + 后一字符成对剥离；孤立结尾 § 亦被吞掉（按 varargs 语义防越界）
        assertEquals("已保存预设 p1", FeedbackText.sanitize("§a已保存预设 §bp1§a"));
        assertEquals("孤立", FeedbackText.sanitize("孤立§"));
        // 十六进制样式码（§x + 6 组 §r）同样剥离
        assertEquals("彩色", FeedbackText.sanitize("§x§f§f§0§0§0§0彩色"));
    }

    @Test
    void stripsControlCharactersAndTrims() {
        assertEquals("文本", FeedbackText.sanitize("  \u0001文本\u0007  "));
        assertEquals("文本", FeedbackText.sanitize("\t文本\u007F"));
    }

    @Test
    void truncatesToMaxLength() {
        String raw = "a".repeat(FeedbackText.MAX_LENGTH + 50);
        String out = FeedbackText.sanitize(raw);
        assertEquals(FeedbackText.MAX_LENGTH, out.length());
        // 恰好等于上限不截断
        String exact = "b".repeat(FeedbackText.MAX_LENGTH);
        assertEquals(exact, FeedbackText.sanitize(exact));
    }
}
