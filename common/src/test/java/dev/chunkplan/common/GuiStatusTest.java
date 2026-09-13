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

    /** v2 形状的便捷构造（v3 维度字段给默认空值；isAdmin=false 便于验证 dimConfig 不下发） */
    private static GuiStatus v2Shape(int dimensionMode, String currentDim, List<String> dims,
                                     List<GuiStatus.DimLines> dimLines, GuiStatus.DimConfigStatus dimConfig) {
        return new GuiStatus(
                1.0, 0.05, 0.5, 2.0,
                true, false, true, false,
                List.of(
                        new QuotaTiers.Tier(true, "5h", 500.0),
                        new QuotaTiers.Tier(true, "24h", 2000.0),
                        new QuotaTiers.Tier(false, "7d", 10000.0),
                        new QuotaTiers.Tier(false, "30d", 40000.0)),
                List.of(
                        new QuotaEngine.LineStatus(5 * 3600, 500.0, 250.0, 1234567890000L),
                        new QuotaEngine.LineStatus(24 * 3600, 2000.0, 900.5, 1234567890000L)),
                false, -1, 50,
                List.of("pvp", "relax"), "pvp",
                dimensionMode, currentDim, dims, dimLines, dimConfig,
                "0.3.0", false);
    }

    private static GuiStatus sample() {
        return v2Shape(0, null, List.of(), List.of(), null);
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
        assertEquals(original.presets(), decoded.presets());
        assertEquals(original.playerPreset(), decoded.playerPreset());
    }

    @Test
    void v2PresetFieldsRoundTrip() {
        // v2 新增字段：presets 列表 + playerPreset（null = default，编解码归一空串）
        GuiStatus withNull = new GuiStatus(1.0, 0.05, 0.5, 2.0,
                false, false, false, false,
                List.of(new QuotaTiers.Tier(false, "5h", 500.0)),
                List.of(), true, 999L, -1,
                List.of(), null,
                0, null, List.of(), List.of(), null,
                "0.3.0", false);
        GuiStatus d = GuiStatus.decode(withNull.encode());
        assertNotNull(d);
        assertTrue(d.presets().isEmpty());
        assertNull(d.playerPreset());

        GuiStatus withNames = new GuiStatus(1.0, 0.05, 0.5, 2.0,
                true, false, true, true,
                List.of(), List.of(), false, -1, -1,
                List.of("pvp", "relax"), "pvp",
                0, null, List.of(), List.of(), null,
                "0.3.0", false);
        GuiStatus d2 = GuiStatus.decode(withNames.encode());
        assertNotNull(d2);
        assertEquals(List.of("pvp", "relax"), d2.presets());
        assertEquals("pvp", d2.playerPreset());
    }

    @Test
    void v3DimensionFieldsRoundTripForAllPlayers() {
        // v3（issue #3）：维度模式/当前维度/live 维度/逐维度状态——全体下发
        GuiStatus s = v2Shape(1, "minecraft:overworld",
                List.of("minecraft:overworld", "minecraft:the_nether"),
                List.of(new GuiStatus.DimLines("minecraft:overworld",
                        List.of(new QuotaEngine.LineStatus(60, 2.0, 1.0, 123L)), -1, 50),
                        new GuiStatus.DimLines("minecraft:the_nether", List.of(), -1, -1)),
                null);
        GuiStatus d = GuiStatus.decode(s.encode());
        assertNotNull(d);
        assertEquals(1, d.dimensionMode());
        assertEquals("minecraft:overworld", d.currentDim());
        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"), d.dimensions());
        assertEquals(2, d.dimLines().size());
        assertEquals("minecraft:overworld", d.dimLines().get(0).dim());
        assertEquals(1, d.dimLines().get(0).lines().size());
        assertEquals(60, d.dimLines().get(0).lines().get(0).windowSeconds());
        assertNull(d.dimConfig()); // 非管理员不下发维度配置（isAdmin=false）
    }

    @Test
    void v3DimConfigOnlyForAdmin() {
        // v3：dimConfig 仅管理员下发；redirectOrder 空槽归一 null（Arrays.asList 允许 null 元素）
        GuiStatus.DimConfigStatus cfg = new GuiStatus.DimConfigStatus(true,
                java.util.Arrays.asList("minecraft:the_nether", null, null),
                List.of(new GuiStatus.DimEntry("minecraft:overworld", false, true, 1.5, 64.0, -3.0,
                        List.of(new QuotaTiers.Tier(true, "5h", 100.0),
                                new QuotaTiers.Tier(false, "24h", 2000.0),
                                new QuotaTiers.Tier(false, "7d", 10000.0),
                                new QuotaTiers.Tier(false, "30d", 40000.0))),
                        new GuiStatus.DimEntry("minecraft:the_nether", true, false, 0, 0, 0, List.of())));
        GuiStatus admin = new GuiStatus(1.0, 0.05, 0.5, 2.0,
                true, false, false, true,
                List.of(), List.of(), false, -1, -1, List.of(), null,
                1, "minecraft:overworld", List.of("minecraft:overworld"), List.of(), cfg,
                "0.3.0", false);
        GuiStatus d = GuiStatus.decode(admin.encode());
        assertNotNull(d);
        assertNotNull(d.dimConfig());
        assertTrue(d.dimConfig().redirectOnExhaust());
        assertEquals("minecraft:the_nether", d.dimConfig().redirectOrder().get(0));
        assertNull(d.dimConfig().redirectOrder().get(1));
        assertEquals(2, d.dimConfig().dims().size());
        assertFalse(d.dimConfig().dims().get(0).billing());
        assertTrue(d.dimConfig().dims().get(0).hasSpawn());
        assertEquals(1.5, d.dimConfig().dims().get(0).x(), 1e-9);
        assertFalse(d.dimConfig().dims().get(1).hasSpawn());
        assertEquals(4, d.dimConfig().dims().get(0).tiers().size());

        // 非管理员：同数据但 isAdmin=false，dimConfig 不编入
        GuiStatus plain = new GuiStatus(1.0, 0.05, 0.5, 2.0,
                true, false, false, false,
                List.of(), List.of(), false, -1, -1, List.of(), null,
                1, "minecraft:overworld", List.of("minecraft:overworld"), List.of(), cfg,
                "0.3.0", false);
        GuiStatus d2 = GuiStatus.decode(plain.encode());
        assertNotNull(d2);
        assertNull(d2.dimConfig());
    }

    @Test
    void v3CurrentDimNullNormalizesFromEmpty() {
        GuiStatus s = v2Shape(0, "", List.of(), List.of(), null);
        GuiStatus d = GuiStatus.decode(s.encode());
        assertNotNull(d);
        assertNull(d.currentDim());
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
    void decodeWrongVersionReturnsVersionBanner() {
        byte[] full = sample().encode();
        // 首个 int 是协议版本，篡改 +1 模拟版本不符 → 返回版本横幅（携带服务端 mod 版本号）
        full[0] += 1;
        GuiStatus banner = GuiStatus.decode(full);
        assertNotNull(banner);
        assertTrue(banner.versionMismatch());
        assertEquals("0.3.0", banner.serverModVersion());
    }

    @Test
    void roundTripCarriesServerModVersion() {
        // 正常状态 decode：serverModVersion 透传、versionMismatch=false
        GuiStatus d = GuiStatus.decode(sample().encode());
        assertNotNull(d);
        assertEquals("0.3.0", d.serverModVersion());
        assertFalse(d.versionMismatch());
    }

    @Test
    void versionBannerFactoryCarriesModVersionOnly() {
        GuiStatus banner = GuiStatus.versionBanner("0.4.0");
        assertTrue(banner.versionMismatch());
        assertEquals("0.4.0", banner.serverModVersion());
        assertTrue(banner.tiers().isEmpty());
        assertTrue(banner.dimLines().isEmpty());
        assertFalse(banner.isAdmin());
    }

    @Test
    void decodeForeignVersionBannerIgnoresTrailingBytes() {
        // 未来服务端的横幅响应带未知新字段：mismatch 客户端只读冻结头两字段，尾随内容忽略
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
            out.writeInt(GuiStatus.PROTOCOL_VERSION + 1);
            out.writeUTF("0.4.0");
            out.writeUTF("future-unknown-field");
            out.writeInt(-42);
            out.flush();
            GuiStatus banner = GuiStatus.decode(bos.toByteArray());
            assertNotNull(banner);
            assertTrue(banner.versionMismatch());
            assertEquals("0.4.0", banner.serverModVersion());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void zeroLineAndEmptyFlagsRoundTrip() {
        GuiStatus s = new GuiStatus(1.0, 0.05, 0.5, 2.0,
                false, false, false, false,
                List.of(new QuotaTiers.Tier(false, "5h", 500.0)),
                List.of(), true, 999L, -1,
                List.of(), null,
                0, null, List.of(), List.of(), null,
                "0.3.0", false);
        GuiStatus d = GuiStatus.decode(s.encode());
        assertNotNull(d);
        assertTrue(d.lines().isEmpty());
        assertTrue(d.allExceeded());
        assertEquals(-1, d.worstPercent());
        assertFalse(d.isAdmin());
    }

    @Test
    void decodeRejectsInvalidTierCount() {
        assertNull(GuiStatus.decode(craftHeader(17, 0)));
    }

    @Test
    void decodeRejectsNegativeLineCount() {
        assertNull(GuiStatus.decode(craftHeader(0, -1)));
    }

    /** 构造合法头部（版本 + 服务端版本 + 4 double + 4 boolean）后写入指定的 tierCount/lineCount */
    private static byte[] craftHeader(int tierCount, int lineCount) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
            out.writeInt(GuiStatus.PROTOCOL_VERSION);
            out.writeUTF("0.3.0");
            out.writeDouble(1.0);
            out.writeDouble(0.05);
            out.writeDouble(0.5);
            out.writeDouble(2.0);
            out.writeBoolean(true);
            out.writeBoolean(false);
            out.writeBoolean(true);
            out.writeBoolean(true);
            out.writeInt(tierCount);
            out.writeInt(lineCount);
            out.flush();
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
