package dev.chunkplan.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 额度档位（四档）→ 额度线组装。档位是"窗口受预设约束、可单独开关"的配置单位；
 * 引擎仍只消费 {@link QuotaConfig.Line} 列表，档位只存在于配置层（双壳层仅做格式转换，
 * 预设与回退逻辑唯一实现于此，避免双端漂移）。
 */
public final class QuotaTiers {

    /** 各档位窗口预设（DurationParser 语法：s/m/h/d 后缀或纯秒，"7d" 即 7 天） */
    public static final List<String> TIER1_WINDOWS = List.of("30m", "1h", "2h", "3h", "5h", "6h", "8h", "12h");
    public static final List<String> TIER2_WINDOWS = List.of("12h", "24h", "48h", "72h", "7d");
    public static final List<String> TIER3_WINDOWS = List.of("7d", "14d", "28d", "30d");
    public static final List<String> TIER4_WINDOWS = List.of("30d", "45d", "60d", "75d", "90d", "180d", "365d");

    /** 档位默认值（与双壳层配置默认一致；第三四档默认关闭；额度上限可按需调整） */
    public record TierDefault(boolean enabled, String window, double limit) {
    }

    public static final TierDefault TIER1_DEFAULT = new TierDefault(true, "5h", 500.0);
    public static final TierDefault TIER2_DEFAULT = new TierDefault(true, "24h", 2000.0);
    public static final TierDefault TIER3_DEFAULT = new TierDefault(false, "7d", 10000.0);
    public static final TierDefault TIER4_DEFAULT = new TierDefault(false, "30d", 40000.0);

    /** 单档配置（壳层从各自配置文件格式转换而来；tiers 传不足 4 个时缺档按默认处理） */
    public record Tier(boolean enabled, String window, double limit) {
    }

    private static final List<TierDefault> DEFAULTS =
            List.of(TIER1_DEFAULT, TIER2_DEFAULT, TIER3_DEFAULT, TIER4_DEFAULT);

    /** 预设窗口解析后的秒数集合（预设均为合法时长，静态预解析不会失败） */
    private static final List<List<Long>> PRESET_SECONDS = List.of(
            parsePreset(TIER1_WINDOWS), parsePreset(TIER2_WINDOWS),
            parsePreset(TIER3_WINDOWS), parsePreset(TIER4_WINDOWS));

    private QuotaTiers() {
    }

    private static List<Long> parsePreset(List<String> presets) {
        List<Long> out = new ArrayList<>(presets.size());
        for (String p : presets) {
            out.add(DurationParser.parseSeconds(p));
        }
        return out;
    }

    /**
     * 档位 → 额度线（引擎模型）：
     * - 禁用档跳过；启用档窗口须在预设内且 limit 为正，否则回退该档默认并告警
     * - 全部档无效/全关 → 回退默认两条（5h/500 + 24h/2000）并告警
     * 返回 1~4 条线，按档位顺序排列（tier 恒为 i+1，是引擎分桶键，坑 #30）。
     */
    public static List<QuotaConfig.Line> toLines(List<Tier> tiers, List<String> warnings) {
        List<String> w = warnings == null ? new ArrayList<>() : warnings;
        List<QuotaConfig.Line> lines = new ArrayList<>();
        for (int i = 0; i < DEFAULTS.size(); i++) {
            TierDefault def = DEFAULTS.get(i);
            Tier tier = tiers == null || i >= tiers.size() ? null : tiers.get(i);
            if (tier == null) {
                // 缺档：按该档默认处理（含默认开关状态）
                if (def.enabled()) {
                    lines.add(new QuotaConfig.Line(i + 1, DurationParser.parseSeconds(def.window()), def.limit()));
                }
                continue;
            }
            if (!tier.enabled()) {
                continue;
            }
            long windowSeconds;
            try {
                windowSeconds = DurationParser.parseSeconds(tier.window());
            } catch (IllegalArgumentException e) {
                windowSeconds = -1;
            }
            if (windowSeconds <= 0 || !PRESET_SECONDS.get(i).contains(windowSeconds)
                    || !Double.isFinite(tier.limit()) || tier.limit() <= 0) {
                w.add("第 " + (i + 1) + " 档额度线非法（window=" + tier.window() + ", limit=" + tier.limit()
                        + "），已回退该档默认（" + def.window() + "/" + def.limit() + "）");
                lines.add(new QuotaConfig.Line(i + 1, DurationParser.parseSeconds(def.window()), def.limit()));
                continue;
            }
            lines.add(new QuotaConfig.Line(i + 1, windowSeconds, tier.limit()));
        }
        if (lines.isEmpty()) {
            w.add("所有额度档均已禁用或非法，已回退默认两条（5h/500 + 24h/2000）");
            return QuotaConfig.defaultLines();
        }
        return lines;
    }
}
