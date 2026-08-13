package dev.chunkplan.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class QuotaConfigTest {

    @Test
    void defaults() {
        QuotaConfig cfg = QuotaConfig.builder().build(new ArrayList<>());
        assertEquals(2, cfg.lines().size());
        assertEquals(5 * 3600, cfg.lines().get(0).windowSeconds());
        assertEquals(500, cfg.lines().get(0).limit());
        assertEquals(24 * 3600, cfg.lines().get(1).windowSeconds());
        assertEquals(2000, cfg.lines().get(1).limit());
        assertEquals(1.0, cfg.firstEntryFee());
        assertEquals(0.05, cfg.familiarEntryFee());
        assertEquals(0.5, cfg.highSpeedThreshold());
        assertEquals(2.0, cfg.highSpeedMultiplier());
        assertTrue(cfg.exemptByDefault());
        assertTrue(cfg.exemptPlayers().isEmpty());
        assertEquals(300, cfg.saveIntervalSec());
        assertEquals(30, cfg.banScanIntervalSec());
        assertTrue(cfg.logFeeEvents());
    }

    @Test
    void acceptsOneToFourLines() {
        for (int n = 1; n <= 4; n++) {
            List<QuotaConfig.Line> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                lines.add(new QuotaConfig.Line((i + 1) * 3600, (i + 1) * 100));
            }
            List<String> warnings = new ArrayList<>();
            QuotaConfig cfg = QuotaConfig.builder().lines(lines).build(warnings);
            assertEquals(n, cfg.lines().size());
            assertTrue(warnings.isEmpty(), "1~4 条线不应告警: " + warnings);
        }
    }

    @Test
    void zeroLinesFallbackToDefault() {
        List<String> warnings = new ArrayList<>();
        QuotaConfig cfg = QuotaConfig.builder().lines(List.of()).build(warnings);
        assertEquals(2, cfg.lines().size());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void fiveLinesFallbackToDefault() {
        List<String> warnings = new ArrayList<>();
        List<QuotaConfig.Line> lines = List.of(
                new QuotaConfig.Line(3600, 100),
                new QuotaConfig.Line(7200, 200),
                new QuotaConfig.Line(10800, 300),
                new QuotaConfig.Line(14400, 400),
                new QuotaConfig.Line(18000, 500));
        QuotaConfig cfg = QuotaConfig.builder().lines(lines).build(warnings);
        assertEquals(2, cfg.lines().size());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void invalidLineDropped() {
        List<String> warnings = new ArrayList<>();
        List<QuotaConfig.Line> lines = List.of(
                new QuotaConfig.Line(0, 100),
                new QuotaConfig.Line(3600, -1),
                new QuotaConfig.Line(7200, 200));
        QuotaConfig cfg = QuotaConfig.builder().lines(lines).build(warnings);
        assertEquals(1, cfg.lines().size());
        assertEquals(7200, cfg.lines().get(0).windowSeconds());
    }

    @Test
    void invalidNumbersFallback() {
        List<String> warnings = new ArrayList<>();
        QuotaConfig cfg = QuotaConfig.builder()
                .firstEntryFee(-1)
                .familiarEntryFee(Double.NaN)
                .highSpeedThreshold(-5)
                .highSpeedMultiplier(0) // 非负即合法（0 = 高速不额外计费），保持
                .saveIntervalSec(0)
                .banScanIntervalSec(-3)
                .build(warnings);
        assertEquals(1.0, cfg.firstEntryFee());
        assertEquals(0.05, cfg.familiarEntryFee());
        assertEquals(0.5, cfg.highSpeedThreshold());
        assertEquals(0.0, cfg.highSpeedMultiplier());
        assertEquals(300, cfg.saveIntervalSec());
        assertEquals(30, cfg.banScanIntervalSec());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void infinityFallback() {
        // ±Infinity 会静默禁用计费/额度线（如 TOML inf / JSON 1e400），必须回退默认
        List<String> warnings = new ArrayList<>();
        QuotaConfig cfg = QuotaConfig.builder()
                .firstEntryFee(Double.POSITIVE_INFINITY)
                .familiarEntryFee(Double.NEGATIVE_INFINITY)
                .build(warnings);
        assertEquals(1.0, cfg.firstEntryFee());
        assertEquals(0.05, cfg.familiarEntryFee());
        assertFalse(warnings.isEmpty());
    }
}
