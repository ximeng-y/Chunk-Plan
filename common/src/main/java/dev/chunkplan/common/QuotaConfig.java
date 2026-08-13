package dev.chunkplan.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ChunkPlan 配置模型（common 定义，双壳映射）。
 * 数值高度可配置：额度线 1~4 条，非法值回退默认并产生告警。
 */
public final class QuotaConfig {

    public record Line(long windowSeconds, double limit) {
    }

    private final List<Line> lines;
    private final double firstEntryFee;
    private final double familiarEntryFee;
    private final double highSpeedThreshold;
    private final double highSpeedMultiplier;
    private final boolean exemptByDefault;
    private final Set<UUID> exemptPlayers;
    private final long saveIntervalSec;
    private final long banScanIntervalSec;
    private final boolean logFeeEvents;

    private QuotaConfig(Builder b) {
        this.lines = b.lines;
        this.firstEntryFee = b.firstEntryFee;
        this.familiarEntryFee = b.familiarEntryFee;
        this.highSpeedThreshold = b.highSpeedThreshold;
        this.highSpeedMultiplier = b.highSpeedMultiplier;
        this.exemptByDefault = b.exemptByDefault;
        this.exemptPlayers = b.exemptPlayers;
        this.saveIntervalSec = b.saveIntervalSec;
        this.banScanIntervalSec = b.banScanIntervalSec;
        this.logFeeEvents = b.logFeeEvents;
    }

    public List<Line> lines() {
        return lines;
    }

    public double firstEntryFee() {
        return firstEntryFee;
    }

    public double familiarEntryFee() {
        return familiarEntryFee;
    }

    public double highSpeedThreshold() {
        return highSpeedThreshold;
    }

    public double highSpeedMultiplier() {
        return highSpeedMultiplier;
    }

    public boolean exemptByDefault() {
        return exemptByDefault;
    }

    public Set<UUID> exemptPlayers() {
        return exemptPlayers;
    }

    public long saveIntervalSec() {
        return saveIntervalSec;
    }

    public long banScanIntervalSec() {
        return banScanIntervalSec;
    }

    public boolean logFeeEvents() {
        return logFeeEvents;
    }

    /** 默认两条额度线：5h ≤ 500 点 + 24h ≤ 2000 点 */
    public static List<Line> defaultLines() {
        return List.of(
                new Line(5 * 3600, 500),
                new Line(24 * 3600, 2000));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<Line> lines = defaultLines();
        private double firstEntryFee = 1.0;
        private double familiarEntryFee = 0.05;
        // 默认 0.5 格/tick（=10 格/秒）：略低于创造模式飞行速度（~10.8 格/秒 ≈ 0.54 格/tick），
        // 达到创造飞行即视为高速；地面疾跑（~5.6 格/秒 ≈ 0.28 格/tick）不触发
        private double highSpeedThreshold = 0.5;
        private double highSpeedMultiplier = 2.0;
        private boolean exemptByDefault = true;
        private Set<UUID> exemptPlayers = new HashSet<>();
        private long saveIntervalSec = 300;
        private long banScanIntervalSec = 30;
        private boolean logFeeEvents = true;

        public Builder lines(List<Line> lines) {
            this.lines = lines;
            return this;
        }

        public Builder firstEntryFee(double v) {
            this.firstEntryFee = v;
            return this;
        }

        public Builder familiarEntryFee(double v) {
            this.familiarEntryFee = v;
            return this;
        }

        public Builder highSpeedThreshold(double v) {
            this.highSpeedThreshold = v;
            return this;
        }

        public Builder highSpeedMultiplier(double v) {
            this.highSpeedMultiplier = v;
            return this;
        }

        public Builder exemptByDefault(boolean v) {
            this.exemptByDefault = v;
            return this;
        }

        public Builder exemptPlayers(Collection<UUID> v) {
            this.exemptPlayers = v == null ? new HashSet<>() : new HashSet<>(v);
            return this;
        }

        public Builder saveIntervalSec(long v) {
            this.saveIntervalSec = v;
            return this;
        }

        public Builder banScanIntervalSec(long v) {
            this.banScanIntervalSec = v;
            return this;
        }

        public Builder logFeeEvents(boolean v) {
            this.logFeeEvents = v;
            return this;
        }

        /**
         * 校验并构建配置；非法值回退默认并写入告警（不抛异常）。
         * 规则：额度线 1~4 条（越界回退默认两条）；窗口/上限必须为正；费率非负；周期为正。
         */
        public QuotaConfig build(List<String> warnings) {
            List<String> w = warnings == null ? new ArrayList<>() : warnings;
            List<Line> normalized = normalizeLines(this.lines, w);
            return new QuotaConfig(new Builder()
                    .lines(normalized)
                    .firstEntryFee(validateNonNegative("firstEntryFee", firstEntryFee, 1.0, w))
                    .familiarEntryFee(validateNonNegative("familiarEntryFee", familiarEntryFee, 0.05, w))
                    .highSpeedThreshold(validateNonNegative("highSpeedThreshold", highSpeedThreshold, 0.5, w))
                    .highSpeedMultiplier(validateNonNegative("highSpeedMultiplier", highSpeedMultiplier, 2.0, w))
                    .exemptByDefault(exemptByDefault)
                    .exemptPlayers(exemptPlayers)
                    .saveIntervalSec(validatePositive("saveIntervalSec", saveIntervalSec, 300, w))
                    .banScanIntervalSec(validatePositive("banScanIntervalSec", banScanIntervalSec, 30, w))
                    .logFeeEvents(logFeeEvents));
        }

        private static List<Line> normalizeLines(List<Line> raw, List<String> w) {
            if (raw == null || raw.isEmpty() || raw.size() > 4) {
                w.add("额度线数量必须为 1~4 条（当前 " + (raw == null ? 0 : raw.size()) + "），已回退默认两条");
                return defaultLines();
            }
            List<Line> ok = new ArrayList<>();
            for (Line line : raw) {
                if (line == null || line.windowSeconds() <= 0 || line.limit() <= 0) {
                    w.add("存在非法额度线（窗口或上限必须为正），已丢弃该线");
                    continue;
                }
                ok.add(line);
            }
            if (ok.isEmpty()) {
                w.add("无有效额度线，已回退默认两条");
                return defaultLines();
            }
            return ok;
        }

        private static double validateNonNegative(String name, double v, double def, List<String> w) {
            // isFinite 同时拦截 NaN 与 ±Infinity（Infinity 会静默禁用计费/额度线，须回退默认）
            if (!Double.isFinite(v) || v < 0) {
                w.add("配置 " + name + " 非法（" + v + "），已回退默认 " + def);
                return def;
            }
            return v;
        }

        private static long validatePositive(String name, long v, long def, List<String> w) {
            if (v <= 0) {
                w.add("配置 " + name + " 非法（" + v + "），已回退默认 " + def);
                return def;
            }
            return v;
        }
    }
}
