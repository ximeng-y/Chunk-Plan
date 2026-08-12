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
}
