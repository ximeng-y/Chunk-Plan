package dev.chunkplan.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.chunkplan.common.DurationParser;
import dev.chunkplan.common.QuotaConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge SERVER 配置（TOML，主位置 <serverDir>/config/chunkplan-server.toml；
 * world/serverconfig/ 为可选存档级覆盖层，存在时整体覆盖）。
 * 全部数值可配；额度线 1~4 条，非法值由 common 校验回退默认并告警。
 *
 * <p>额度线用两个平行数组（lines 窗口 + lineLimits 上限）表示：
 * NightConfig 的 TOML 写入器不支持 Map 作为列表元素（array-of-tables）。
 */
public final class NeoForgeConfig {

    public static final ModConfigSpec SPEC;

    /** 各额度线窗口（"5h"/"24h"/"30m"/"7d" 或纯秒数），与 LINE_LIMITS 一一对应 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LINES;
    /** 各额度线点数上限 */
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> LINE_LIMITS;
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

        LINES = b.comment("额度线窗口（1~4 条，与 lineLimits 一一对应），全部满才拒绝。支持 5h/24h/30m/7d 或纯秒数")
                .defineList("lines", List.of("5h", "24h"), obj -> obj instanceof String);
        LINE_LIMITS = b.comment("各额度线点数上限（与 lines 一一对应）")
                .defineList("lineLimits", List.of(500.0, 2000.0), obj -> obj instanceof Number);
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
        List<? extends String> windows = LINES.get();
        List<? extends Double> limits = LINE_LIMITS.get();
        List<QuotaConfig.Line> lines = new ArrayList<>();
        int n = Math.min(windows.size(), limits.size());
        if (windows.size() != limits.size()) {
            warnings.add("lines 与 lineLimits 数量不一致（" + windows.size() + " vs " + limits.size()
                    + "），多余项已丢弃");
        }
        for (int i = 0; i < n; i++) {
            try {
                lines.add(new QuotaConfig.Line(
                        DurationParser.parseSeconds(windows.get(i)), limits.get(i)));
            } catch (IllegalArgumentException e) {
                warnings.add("额度线 " + i + " 非法（window=" + windows.get(i) + ", limit=" + limits.get(i) + "）：" + e.getMessage());
            }
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

    /**
     * /chunkplan reload 用：直接从 TOML 文件解析（运行中修改文件后热生效）。
     * spec 内存值只在启动时加载，不感知运行中文件修改；解析失败回退 spec 内存值并告警。
     */
    public static QuotaConfig toQuotaConfigFromFile(Path file, List<String> warnings) {
        try (com.electronwill.nightconfig.core.file.FileConfig cfg =
                     com.electronwill.nightconfig.core.file.FileConfig.of(file)) {
            cfg.load();
            List<String> windows = cfg.getOrElse("lines", List.of());
            List<? extends Number> limits = cfg.getOrElse("lineLimits", List.of());
            List<QuotaConfig.Line> lines = new ArrayList<>();
            int n = Math.min(windows.size(), limits.size());
            if (windows.size() != limits.size()) {
                warnings.add("lines 与 lineLimits 数量不一致（" + windows.size() + " vs " + limits.size()
                        + "），多余项已丢弃");
            }
            for (int i = 0; i < n; i++) {
                try {
                    lines.add(new QuotaConfig.Line(
                            DurationParser.parseSeconds(windows.get(i)), limits.get(i).doubleValue()));
                } catch (IllegalArgumentException e) {
                    warnings.add("额度线 " + i + " 非法（window=" + windows.get(i) + ", limit=" + limits.get(i) + "）：" + e.getMessage());
                }
            }
            List<UUID> exempt = new ArrayList<>();
            Object exemptRaw = cfg.get("exemptPlayers");
            if (exemptRaw instanceof List<?> exemptList) {
                for (Object s : exemptList) {
                    try {
                        exempt.add(UUID.fromString(String.valueOf(s)));
                    } catch (IllegalArgumentException e) {
                        warnings.add("exemptPlayers 含非法 UUID: " + s);
                    }
                }
            }
            Object saveRaw = cfg.get("saveIntervalSec");
            Object scanRaw = cfg.get("banScanIntervalSec");
            // TOML 整数解析为 Integer（非 Double/Long），全部按 Number 统一转换，避免 ClassCastException
            // 导致整次 reload 回退（原 bug：只有 Long 字段做了转换，Double 字段写整数即整次失败）
            Object firstRaw = cfg.get("firstEntryFee");
            Object familiarRaw = cfg.get("familiarEntryFee");
            Object speedRaw = cfg.get("highSpeedThreshold");
            Object multRaw = cfg.get("highSpeedMultiplier");
            return QuotaConfig.builder()
                    .lines(lines)
                    .firstEntryFee(firstRaw instanceof Number fe ? fe.doubleValue() : FIRST_ENTRY_FEE.get())
                    .familiarEntryFee(familiarRaw instanceof Number fm ? fm.doubleValue() : FAMILIAR_ENTRY_FEE.get())
                    .highSpeedThreshold(speedRaw instanceof Number sp ? sp.doubleValue() : HIGH_SPEED_THRESHOLD.get())
                    .highSpeedMultiplier(multRaw instanceof Number mu ? mu.doubleValue() : HIGH_SPEED_MULTIPLIER.get())
                    .exemptByDefault(cfg.getOrElse("exemptByDefault", EXEMPT_BY_DEFAULT.get()))
                    .exemptPlayers(exempt)
                    .saveIntervalSec(saveRaw instanceof Number sv ? sv.longValue() : SAVE_INTERVAL_SEC.get())
                    .banScanIntervalSec(scanRaw instanceof Number sc ? sc.longValue() : BAN_SCAN_INTERVAL_SEC.get())
                    .logFeeEvents(cfg.getOrElse("logFeeEvents", LOG_FEE_EVENTS.get()))
                    .build(warnings);
        } catch (Exception e) {
            warnings.add("配置文件解析失败（" + e.getMessage() + "），回退为已加载配置");
            return toQuotaConfig(warnings);
        }
    }

    /**
     * /chunkplan config exemptByDefault 用：原子改写 TOML 文件中的该字段（重启后保留）。
     * 不用 NightConfig 写器：其非原子保存可能被 NeoForge watcher 半读，触发 .bak + 静默重置（坑 #12）。
     * 文本替换 + AtomicFile 原子写（同目录 .tmp + rename），watcher 只会看到完整文件。
     */
    public static void writeExemptByDefault(Path file, boolean value) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String replaced = text.replaceAll("(?m)^exemptByDefault\\s*=\\s*(true|false)\\s*$", "exemptByDefault = " + value);
        if (replaced.equals(text)) {
            // 文件中没有该字段（异常情况）：追加一行，保持文件合法
            replaced = text + (text.endsWith("\n") ? "" : "\n") + "exemptByDefault = " + value + "\n";
        }
        dev.chunkplan.common.AtomicFile.write(file, replaced);
    }
}
