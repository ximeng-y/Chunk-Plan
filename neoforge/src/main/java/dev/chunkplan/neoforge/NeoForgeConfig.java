package dev.chunkplan.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.chunkplan.common.DurationParser;
import dev.chunkplan.common.QuotaConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge SERVER 配置（TOML，位于 world/serverconfig/chunkplan-server.toml）。
 * 全部数值可配；额度线 1~4 条，非法值由 common 校验回退默认并告警。
 */
public final class NeoForgeConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<List<? extends Map<String, ?>>> LINES;
    public static final ModConfigSpec.DoubleValue FIRST_ENTRY_FEE;
    public static final ModConfigSpec.DoubleValue FAMILIAR_ENTRY_FEE;
    public static final ModConfigSpec.DoubleValue HIGH_SPEED_THRESHOLD;
    public static final ModConfigSpec.DoubleValue HIGH_SPEED_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue EXEMPT_BY_DEFAULT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXEMPT_PLAYERS;
    public static final ModConfigSpec.LongValue SAVE_INTERVAL_SEC;
    public static final ModConfigSpec.LongValue BAN_SCAN_INTERVAL_SEC;
    public static final ModConfigSpec.BooleanValue LOG_FEE_EVENTS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        LINES = b.comment("额度线：1~4 条滚动窗口线（窗口时长 + 点数上限），全部满才拒绝。"
                        + "window 支持 5h/24h/30m/7d 或纯秒数；limit 为点数上限")
                .defineList("lines",
                        List.of(
                                Map.of("window", "5h", "limit", 500.0),
                                Map.of("window", "24h", "limit", 2000.0)),
                        obj -> obj instanceof Map);
        FIRST_ENTRY_FEE = b.comment("踏入未探索区块（集合外）的费用")
                .defineInRange("firstEntryFee", 1.0, 0.0, Double.MAX_VALUE);
        FAMILIAR_ENTRY_FEE = b.comment("踏入已探索区块（集合内）的费用")
                .defineInRange("familiarEntryFee", 0.05, 0.0, Double.MAX_VALUE);
        HIGH_SPEED_THRESHOLD = b.comment("高速移动判定阈值（格/tick），超过则费用加倍")
                .defineInRange("highSpeedThreshold", 1.0, 0.0, Double.MAX_VALUE);
        HIGH_SPEED_MULTIPLIER = b.comment("高速移动费用倍率（0 = 高速不额外计费）")
                .defineInRange("highSpeedMultiplier", 2.0, 0.0, Double.MAX_VALUE);
        EXEMPT_BY_DEFAULT = b.comment("默认豁免 OP；false 时仅名单豁免（exemptByDefault=false 时全员受限即此值控制 OP）")
                .define("exemptByDefault", true);
        EXEMPT_PLAYERS = b.comment("额外豁免玩家 UUID 名单")
                .defineList("exemptPlayers", List.of(), obj -> obj instanceof String);
        SAVE_INTERVAL_SEC = b.comment("玩家数据落盘周期（秒）")
                .defineInRange("saveIntervalSec", 300L, 10L, Long.MAX_VALUE);
        BAN_SCAN_INTERVAL_SEC = b.comment("临时封禁扫描周期（秒）")
                .defineInRange("banScanIntervalSec", 30L, 5L, Long.MAX_VALUE);
        LOG_FEE_EVENTS = b.comment("扣费事件写入独立日志文件（logs/chunkplan.log）")
                .define("logFeeEvents", true);

        SPEC = b.build();
    }

    private NeoForgeConfig() {
    }

    /** 构建 common 配置；返回告警列表（非法配置回退默认时产生） */
    public static QuotaConfig toQuotaConfig(List<String> warnings) {
        List<QuotaConfig.Line> lines = new ArrayList<>();
        for (Map<String, ?> m : LINES.get()) {
            Object window = m.get("window");
            Object limit = m.get("limit");
            if (window instanceof String ws && limit instanceof Number ln) {
                try {
                    lines.add(new QuotaConfig.Line(DurationParser.parseSeconds(ws), ln.doubleValue()));
                    continue;
                } catch (IllegalArgumentException e) {
                    warnings.add("额度线窗口非法（" + ws + "）：" + e.getMessage());
                }
            }
            warnings.add("额度线缺少 window/limit 字段，已丢弃该线");
        }
        List<UUID> exempt = new ArrayList<>();
        for (String s : EXEMPT_PLAYERS.get()) {
            try {
                exempt.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                warnings.add("exemptPlayers 含非法 UUID: " + s);
            }
        }
        return QuotaConfig.builder()
                .lines(lines)
                .firstEntryFee(FIRST_ENTRY_FEE.get())
                .familiarEntryFee(FAMILIAR_ENTRY_FEE.get())
                .highSpeedThreshold(HIGH_SPEED_THRESHOLD.get())
                .highSpeedMultiplier(HIGH_SPEED_MULTIPLIER.get())
                .exemptByDefault(EXEMPT_BY_DEFAULT.get())
                .exemptPlayers(exempt)
                .saveIntervalSec(SAVE_INTERVAL_SEC.get())
                .banScanIntervalSec(BAN_SCAN_INTERVAL_SEC.get())
                .logFeeEvents(LOG_FEE_EVENTS.get())
                .build(warnings);
    }
}
