package dev.chunkplan.common;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiStatusTest {

    private static GuiStatus sample() {
        return new GuiStatus(
                1.0, 0.05, 0.5, 2.0,
                true, false, true, true,
                List.of(
                        new QuotaTiers.Tier(true, "5h", 500.0),
                        new QuotaTiers.Tier(true, "24h", 2000.0),
                        new QuotaTiers.Tier(false, "7d", 10000.0),
                        new QuotaTiers.Tier(false, "30d", 40000.0)),
                List.of(
                        new QuotaEngine.LineStatus(5 * 3600, 500.0, 250.0, 1234567890000L),
                        new QuotaEngine.LineStatus(24 * 3600, 2000.0, 900.5, 1234567890000L)),
                false, -1, 50);
    }

    @Test
    void roundTripPreservesAllFields() {
        GuiStatus original = sample();
        GuiStatus decoded = GuiStatus.decode(original.encode());
        assertNotNull(decoded);
        assertEquals(original.firstEntryFee(), decoded.firstEntryFee());
        assertEquals(original.familiarEntryFee(), decoded.familiarEntryFee());
        assertEquals(original.highSpeedThreshold(), decoded.highSpeedThreshold());
        assertEquals(original.highSpeedMultiplier(), decoded.highSpeedMultiplier());
        assertEquals(original.exemptByDefault(), decoded.exemptByDefault());
        assertEquals(original.isExempt(), decoded.isExempt());
        assertEquals(original.inExemptList(), decoded.inExemptList());
        assertEquals(original.isAdmin(), decoded.isAdmin());
        assertEquals(original.tiers(), decoded.tiers());
        assertEquals(original.lines(), decoded.lines());
        assertEquals(original.allExceeded(), decoded.allExceeded());
        assertEquals(original.recoveryMillis(), decoded.recoveryMillis());
        assertEquals(original.worstPercent(), decoded.worstPercent());
    }

    @Test
    void encodeIsDeterministic() {
        GuiStatus s = sample();
        assertArrayEquals(s.encode(), s.encode());
    }

    @Test
    void decodeNullReturnsNull() {
        assertNull(GuiStatus.decode(null));
    }

    @Test
    void decodeEmptyReturnsNull() {
        assertNull(GuiStatus.decode(new byte[0]));
    }

    @Test
    void decodeTruncatedReturnsNull() {
        byte[] full = sample().encode();
        byte[] truncated = new byte[full.length - 1];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        assertNull(GuiStatus.decode(truncated));
    }

    @Test
    void decodeWrongVersionReturnsNull() {
        byte[] full = sample().encode();
        // 首个 int 是协议版本，篡改 +1 模拟版本不符
        full[0] += 1;
        assertNull(GuiStatus.decode(full));
    }

    @Test
    void zeroLineAndEmptyFlagsRoundTrip() {
        GuiStatus s = new GuiStatus(1.0, 0.05, 0.5, 2.0,
                false, false, false, false,
                List.of(new QuotaTiers.Tier(false, "5h", 500.0)),
                List.of(), true, 999L, -1);
        GuiStatus d = GuiStatus.decode(s.encode());
        assertNotNull(d);
        assertTrue(d.lines().isEmpty());
        assertTrue(d.allExceeded());
        assertEquals(-1, d.worstPercent());
        assertFalse(d.isAdmin());
    }
}
