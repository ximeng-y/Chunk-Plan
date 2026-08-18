package dev.chunkplan.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaTiers;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge SERVER 配置（TOML，主位置 <serverDir>/config/chunkplan-server.toml；
 * world/serverconfig/ 为可选存档级覆盖层，存在时整体覆盖）。
 * 全部数值可配；额度线以四档呈现（每档独立开关 + 窗口预设校验，坑 #24），
 * 非法值由 common 校验回退默认并告警。
 */
public final class NeoForgeConfig {

    public static final ModConfigSpec SPEC;

    /** 第一~四档额度线：独立开关 + 窗口（预设约束）+ 点数上限 */
    public static final ModConfigSpec.BooleanValue TIER1_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TIER1_WINDOW;
    public static final ModConfigSpec.DoubleValue TIER1_LIMIT;
    public static final ModConfigSpec.BooleanValue TIER2_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TIER2_WINDOW;
    public static final ModConfigSpec.DoubleValue TIER2_LIMIT;
    public static final ModConfigSpec.BooleanValue TIER3_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TIER3_WINDOW;
    public static final ModConfigSpec.DoubleValue TIER3_LIMIT;
    public static final ModConfigSpec.BooleanValue TIER4_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TIER4_WINDOW;
    public static final ModConfigSpec.DoubleValue TIER4_LIMIT;
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

        TIER1_ENABLED = b.comment("第一档额度线开关（默认开）")
                .define("tier1Enabled", true);
        TIER1_WINDOW = b.comment("第一档窗口（可选: 30m/1h/2h/3h/5h/6h/8h/12h；s/m/h/d 后缀或纯秒，7d 即 7 天）")
                .define("tier1Window", "5h");
        TIER1_LIMIT = b.comment("第一档点数上限")
                .defineInRange("tier1Limit", 500.0, 0.0, Double.MAX_VALUE);
        TIER2_ENABLED = b.comment("第二档额度线开关（默认开）")
                .define("tier2Enabled", true);
        TIER2_WINDOW = b.comment("第二档窗口（可选: 12h/24h/48h/72h/7d）")
                .define("tier2Window", "24h");
        TIER2_LIMIT = b.comment("第二档点数上限")
                .defineInRange("tier2Limit", 2000.0, 0.0, Double.MAX_VALUE);
        TIER3_ENABLED = b.comment("第三档额度线开关（默认关）")
                .define("tier3Enabled", false);
        TIER3_WINDOW = b.comment("第三档窗口（可选: 7d/14d/28d/30d）")
                .define("tier3Window", "7d");
        TIER3_LIMIT = b.comment("第三档点数上限")
                .defineInRange("tier3Limit", 10000.0, 0.0, Double.MAX_VALUE);
        TIER4_ENABLED = b.comment("第四档额度线开关（默认关）")
                .define("tier4Enabled", false);
        TIER4_WINDOW = b.comment("第四档窗口（可选: 30d/45d/60d/75d/90d/180d/365d）")
                .define("tier4Window", "30d");
        TIER4_LIMIT = b.comment("第四档点数上限")
                .defineInRange("tier4Limit", 40000.0, 0.0, Double.MAX_VALUE);
        FIRST_ENTRY_FEE = b.comment("踏入未探索区块（集合外）的费用")
                .defineInRange("firstEntryFee", 1.0, 0.0, Double.MAX_VALUE);
        FAMILIAR_ENTRY_FEE = b.comment("踏入已探索区块（集合内）的费用")
                .defineInRange("familiarEntryFee", 0.05, 0.0, Double.MAX_VALUE);
        HIGH_SPEED_THRESHOLD = b.comment("高速移动判定阈值（格/tick，超过则费用加倍）；默认 0.5 即创造模式飞行速度（~0.54），地面疾跑（~0.28）不触发")
                .defineInRange("highSpeedThreshold", 0.5, 0.0, Double.MAX_VALUE);
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
        List<QuotaTiers.Tier> tiers = List.of(
                new QuotaTiers.Tier(TIER1_ENABLED.get(), TIER1_WINDOW.get(), TIER1_LIMIT.get()),
                new QuotaTiers.Tier(TIER2_ENABLED.get(), TIER2_WINDOW.get(), TIER2_LIMIT.get()),
                new QuotaTiers.Tier(TIER3_ENABLED.get(), TIER3_WINDOW.get(), TIER3_LIMIT.get()),
                new QuotaTiers.Tier(TIER4_ENABLED.get(), TIER4_WINDOW.get(), TIER4_LIMIT.get()));
        List<UUID> exempt = new ArrayList<>();
        for (String s : EXEMPT_PLAYERS.get()) {
            try {
                exempt.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                warnings.add("exemptPlayers 含非法 UUID: " + s);
            }
        }
        return QuotaConfig.builder()
                .lines(QuotaTiers.toLines(tiers, warnings))
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
            List<QuotaTiers.Tier> tiers = List.of(
                    new QuotaTiers.Tier(readBool(cfg.get("tier1Enabled"), TIER1_ENABLED.get()),
                            String.valueOf(cfg.getOrElse("tier1Window", TIER1_WINDOW.get())),
                            readLimit(cfg.get("tier1Limit"), TIER1_LIMIT.get())),
                    new QuotaTiers.Tier(readBool(cfg.get("tier2Enabled"), TIER2_ENABLED.get()),
                            String.valueOf(cfg.getOrElse("tier2Window", TIER2_WINDOW.get())),
                            readLimit(cfg.get("tier2Limit"), TIER2_LIMIT.get())),
                    new QuotaTiers.Tier(readBool(cfg.get("tier3Enabled"), TIER3_ENABLED.get()),
                            String.valueOf(cfg.getOrElse("tier3Window", TIER3_WINDOW.get())),
                            readLimit(cfg.get("tier3Limit"), TIER3_LIMIT.get())),
                    new QuotaTiers.Tier(readBool(cfg.get("tier4Enabled"), TIER4_ENABLED.get()),
                            String.valueOf(cfg.getOrElse("tier4Window", TIER4_WINDOW.get())),
                            readLimit(cfg.get("tier4Limit"), TIER4_LIMIT.get())));
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
                    .lines(QuotaTiers.toLines(tiers, warnings))
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
     * 读取四档原始配置（含禁用档的窗口/上限，供客户端管理页展示与编辑）。
     * 与 toQuotaConfigFromFile 同源（FileConfig 直接读文件，绕过可能滞后的 spec 内存，坑 #12）；
     * 解析失败回退 spec 内存值。恒返回 4 项（tier1~tier4）。
     */
    public static List<QuotaTiers.Tier> readRawTiers(Path file) {
        try (com.electronwill.nightconfig.core.file.FileConfig cfg =
                     com.electronwill.nightconfig.core.file.FileConfig.of(file)) {
            cfg.load();
            return List.of(
                    new QuotaTiers.Tier(readBool(cfg.get("tier1Enabled"), TIER1_ENABLED.get()),
                            String.valueOf(cfg.getOrElse("tier1Window", TIER1_WINDOW.get())),
                            readLimit(cfg.get("tier1Limit"), TIER1_LIMIT.get())),
                    new QuotaTiers.Tier(readBool(cfg.get("tier2Enabled"), TIER2_ENABLED.get()),
                            String.valueOf(cfg.getOrElse("tier2Window", TIER2_WINDOW.get())),
                            readLimit(cfg.get("tier2Limit"), TIER2_LIMIT.get())),
                    new QuotaTiers.Tier(readBool(cfg.get("tier3Enabled"), TIER3_ENABLED.get()),
                            String.valueOf(cfg.getOrElse("tier3Window", TIER3_WINDOW.get())),
                            readLimit(cfg.get("tier3Limit"), TIER3_LIMIT.get())),
                    new QuotaTiers.Tier(readBool(cfg.get("tier4Enabled"), TIER4_ENABLED.get()),
                            String.valueOf(cfg.getOrElse("tier4Window", TIER4_WINDOW.get())),
                            readLimit(cfg.get("tier4Limit"), TIER4_LIMIT.get())));
        } catch (Exception e) {
            return List.of(
                    new QuotaTiers.Tier(TIER1_ENABLED.get(), TIER1_WINDOW.get(), TIER1_LIMIT.get()),
                    new QuotaTiers.Tier(TIER2_ENABLED.get(), TIER2_WINDOW.get(), TIER2_LIMIT.get()),
                    new QuotaTiers.Tier(TIER3_ENABLED.get(), TIER3_WINDOW.get(), TIER3_LIMIT.get()),
                    new QuotaTiers.Tier(TIER4_ENABLED.get(), TIER4_WINDOW.get(), TIER4_LIMIT.get()));
        }
    }

    /** TOML 布尔读取（类型不符回退默认；键缺失时 cfg.get 返回 null） */
    private static boolean readBool(Object raw, boolean def) {
        return raw instanceof Boolean b ? b : def;
    }

    /** TOML 数值读取（整数解析为 Integer，统一按 Number 转换；类型不符回退默认） */
    private static double readLimit(Object raw, double def) {
        return raw instanceof Number n ? n.doubleValue() : def;
    }

    /**
     * /chunkplan config 用：原子改写 TOML 文件中的单个键（重启后保留）。
     * 不用 NightConfig 写器：其非原子保存可能被 NeoForge watcher 半读，触发 .bak + 静默重置（坑 #12）。
     * 文本替换 + AtomicFile 原子写（同目录 .tmp + rename），watcher 只会看到完整文件。
     *
     * @param value 格式化好的字面量（"true" / "500.0" / "\"5h\""）
     */
    public static void writeKey(Path file, String key, String value) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*.*$").matcher(text);
        if (!m.find()) {
            // 文件中没有该字段（异常情况）：追加一行，保持文件合法
            text = text + (text.endsWith("\n") ? "" : "\n") + key + " = " + value + "\n";
        } else {
            // 显式按匹配区间替换：与原文相同（幂等写）不算"键不存在"，绝不追加重复键
            text = text.substring(0, m.start()) + key + " = " + value + text.substring(m.end());
        }
        dev.chunkplan.common.AtomicFile.write(file, text);
    }

    /** /chunkplan config exemptByDefault 用：委托通用写键（重启后保留） */
    public static void writeExemptByDefault(Path file, boolean value) throws IOException {
        writeKey(file, "exemptByDefault", String.valueOf(value));
    }

    /** /chunkplan config window 用：改写档位开关 */
    public static void writeTierEnabled(Path file, int tier, boolean enabled) throws IOException {
        writeKey(file, "tier" + tier + "Enabled", String.valueOf(enabled));
    }

    /** /chunkplan config windowTime 用：改写档位窗口时长（TOML 字符串需引号） */
    public static void writeTierWindow(Path file, int tier, String window) throws IOException {
        writeKey(file, "tier" + tier + "Window", "\"" + window + "\"");
    }

    /** /chunkplan config windowLimit 用：改写档位额度上限 */
    public static void writeTierLimit(Path file, int tier, double limit) throws IOException {
        writeKey(file, "tier" + tier + "Limit", String.valueOf(limit));
    }

    /** /chunkplan config highSpeedMultiplier 用：改写高速移动倍率 */
    public static void writeHighSpeedMultiplier(Path file, double multiplier) throws IOException {
        writeKey(file, "highSpeedMultiplier", String.valueOf(multiplier));
    }

    /** /chunkplan config firstEntryFee 用：改写踏入未探索区块的费用 */
    public static void writeFirstEntryFee(Path file, double fee) throws IOException {
        writeKey(file, "firstEntryFee", String.valueOf(fee));
    }

    /** /chunkplan config familiarEntryFee 用：改写踏入已探索区块的费用 */
    public static void writeFamiliarEntryFee(Path file, double fee) throws IOException {
        writeKey(file, "familiarEntryFee", String.valueOf(fee));
    }
}
