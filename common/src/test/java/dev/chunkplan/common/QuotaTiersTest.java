package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 档位 → 额度线组装：预设校验、非法回退该档默认、全关回退默认两条。
 */
class QuotaTiersTest {

    /** 与双壳层配置默认一致的档位（第一二档开，第三四档关） */
    private static final List<QuotaTiers.Tier> DEFAULT_TIERS = List.of(
            new QuotaTiers.Tier(true, "5h", 500.0),
            new QuotaTiers.Tier(true, "24h", 2000.0),
            new QuotaTiers.Tier(false, "7d", 10000.0),
            new QuotaTiers.Tier(false, "30d", 40000.0));

    @Test
    void defaultTiersProduceTwoLines() {
        List<String> warnings = new ArrayList<>();
        List<QuotaConfig.Line> lines = QuotaTiers.toLines(DEFAULT_TIERS, warnings);
        assertEquals(2, lines.size());
        assertEquals(new QuotaConfig.Line(5 * 3600, 500.0), lines.get(0));
        assertEquals(new QuotaConfig.Line(24 * 3600, 2000.0), lines.get(1));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void windowOutsidePresetFallsBackToTierDefault() {
        List<String> warnings = new ArrayList<>();
        // 10h 是合法时长但不在第一档预设（30m/1h/2h/3h/5h/6h/8h/12h）内
        List<QuotaTiers.Tier> tiers = List.of(
                new QuotaTiers.Tier(true, "10h", 500.0),
                new QuotaTiers.Tier(true, "24h", 2000.0),
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0));
        List<QuotaConfig.Line> lines = QuotaTiers.toLines(tiers, warnings);
        assertEquals(2, lines.size());
        assertEquals(new QuotaConfig.Line(5 * 3600, 500.0), lines.get(0));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("第 1 档"));
    }

    @Test
    void invalidLimitFallsBackToTierDefault() {
        List<String> warnings = new ArrayList<>();
        List<QuotaTiers.Tier> tiers = List.of(
                new QuotaTiers.Tier(true, "5h", 0.0),
                new QuotaTiers.Tier(true, "24h", 2000.0),
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0));
        List<QuotaConfig.Line> lines = QuotaTiers.toLines(tiers, warnings);
        assertEquals(2, lines.size());
        assertEquals(new QuotaConfig.Line(5 * 3600, 500.0), lines.get(0));
        assertEquals(1, warnings.size());
    }

    @Test
    void allTiersDisabledFallBackToDefaultTwoLines() {
        List<String> warnings = new ArrayList<>();
        List<QuotaTiers.Tier> tiers = List.of(
                new QuotaTiers.Tier(false, "5h", 500.0),
                new QuotaTiers.Tier(false, "24h", 2000.0),
                new QuotaTiers.Tier(false, "7d", 10000.0),
                new QuotaTiers.Tier(false, "30d", 40000.0));
        List<QuotaConfig.Line> lines = QuotaTiers.toLines(tiers, warnings);
        assertEquals(QuotaConfig.defaultLines(), lines);
        assertEquals(1, warnings.size());
    }

    @Test
    void allFourTiersEnabledProduceFourLines() {
        List<String> warnings = new ArrayList<>();
        List<QuotaTiers.Tier> tiers = List.of(
                new QuotaTiers.Tier(true, "5h", 500.0),
                new QuotaTiers.Tier(true, "24h", 2000.0),
                new QuotaTiers.Tier(true, "7d", 10000.0),
                new QuotaTiers.Tier(true, "30d", 40000.0));
        List<QuotaConfig.Line> lines = QuotaTiers.toLines(tiers, warnings);
        assertEquals(4, lines.size());
        assertEquals(new QuotaConfig.Line(7 * 24 * 3600, 10000.0), lines.get(2));
        assertEquals(new QuotaConfig.Line(30 * 24 * 3600, 40000.0), lines.get(3));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingTiersUseDefaults() {
        List<String> warnings = new ArrayList<>();
        // 壳层只传两档：第三四档按默认（默认关闭 → 不产生线）
        List<QuotaTiers.Tier> tiers = List.of(
                new QuotaTiers.Tier(true, "5h", 500.0),
                new QuotaTiers.Tier(true, "24h", 2000.0));
        List<QuotaConfig.Line> lines = QuotaTiers.toLines(tiers, warnings);
        assertEquals(2, lines.size());
        assertEquals(new QuotaConfig.Line(5 * 3600, 500.0), lines.get(0));
        assertEquals(new QuotaConfig.Line(24 * 3600, 2000.0), lines.get(1));
        assertTrue(warnings.isEmpty());
        // 缺档且默认开启 → 补默认线：只传第一档（默认第二档开启）
        List<QuotaTiers.Tier> one = List.of(new QuotaTiers.Tier(true, "5h", 500.0));
        List<QuotaConfig.Line> lines2 = QuotaTiers.toLines(one, warnings);
        assertEquals(2, lines2.size());
        assertEquals(new QuotaConfig.Line(24 * 3600, 2000.0), lines2.get(1));
        assertTrue(warnings.isEmpty());
    }
}
