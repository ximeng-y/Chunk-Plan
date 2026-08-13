package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DurationParserTest {

    @Test
    void units() {
        assertEquals(5 * 3600, DurationParser.parseSeconds("5h"));
        assertEquals(24 * 3600, DurationParser.parseSeconds("24h"));
        assertEquals(30 * 60, DurationParser.parseSeconds("30m"));
        assertEquals(7 * 86400, DurationParser.parseSeconds("7d"));
        assertEquals(90, DurationParser.parseSeconds("90s"));
        assertEquals(3600, DurationParser.parseSeconds("3600"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(3600, DurationParser.parseSeconds("1H"));
        assertEquals(60, DurationParser.parseSeconds("1M"));
    }

    @Test
    void invalid() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds(""));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds(null));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("abc"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("5x"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("0h"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("-5h"));
    }

    @Test
    void overflowRejected() {
        // multiplyExact 溢出：统一转 IllegalArgumentException（调用方只捕该类，防启动崩溃）
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("9223372036854775807h"));
        // 超大合法窗口：超过 100 年上限，防止消费桶窗口计算中 windowSeconds*1000 溢出为负
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("40000d"));
    }
}
