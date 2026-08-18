package dev.chunkplan.fabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.chunkplan.common.AtomicFile;
import dev.chunkplan.common.GsonHolder;
import dev.chunkplan.common.QuotaConfig;
import dev.chunkplan.common.QuotaTiers;

/**
 * Fabric JSON 配置（config/chunkplan.json）。
 * 结构与 NeoForge TOML 一致，全部数值可配；非法值由 common 校验回退默认并告警。
 */
public final class FabricConfig {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPlan");

    /** 默认配置（与 NeoForge TOML 一致：第一二档开、第三四档关；阈值 0.5 即创造飞行） */
    private static final String DEFAULT_JSON = """
            {
              "tier1Enabled": true,
              "tier1Window": "5h",
              "tier1Limit": 500.0,
              "tier2Enabled": true,
              "tier2Window": "24h",
              "tier2Limit": 2000.0,
              "tier3Enabled": false,
              "tier3Window": "7d",
              "tier3Limit": 10000.0,
              "tier4Enabled": false,
              "tier4Window": "30d",
              "tier4Limit": 40000.0,
              "firstEntryFee": 1.0,
              "familiarEntryFee": 0.05,
              "highSpeedThreshold": 0.5,
              "highSpeedMultiplier": 2.0,
              "exemptByDefault": true,
              "exemptPlayers": [],
              "saveIntervalSec": 300,
              "banScanIntervalSec": 30,
              "logFeeEvents": true
            }
            """;

    private static final class Dto {
        Boolean tier1Enabled;
        String tier1Window;
        Double tier1Limit;
        Boolean tier2Enabled;
        String tier2Window;
        Double tier2Limit;
        Boolean tier3Enabled;
        String tier3Window;
        Double tier3Limit;
        Boolean tier4Enabled;
        String tier4Window;
        Double tier4Limit;
        Double firstEntryFee;
        Double familiarEntryFee;
        Double highSpeedThreshold;
        Double highSpeedMultiplier;
        Boolean exemptByDefault;
        List<String> exemptPlayers;
        Long saveIntervalSec;
        Long banScanIntervalSec;
        Boolean logFeeEvents;
    }

    private FabricConfig() {
    }

    /**
     * 读取配置文件（不存在则写入默认配置），构建 common 配置。
     *
     * @param warnings 配置告警输出
     */
    public static QuotaConfig load(Path configFile, List<String> warnings) {
        Dto dto;
        try {
            if (!Files.exists(configFile)) {
                if (configFile.getParent() != null) {
                    Files.createDirectories(configFile.getParent());
                }
                // 首跑生成默认配置走原子写（防半写；无现有文件故不产生 .bak）
                AtomicFile.write(configFile, DEFAULT_JSON);
                dto = GsonHolder.GSON.fromJson(DEFAULT_JSON, Dto.class);
            } else {
                // 坑 #27：损坏时从 .bak 兜底恢复（管理员改过的配置不丢）；
                // IO/parse 失败不再中断启动，主与 .bak 均失败时回退默认并告警（与 NeoForge reload 语义对齐）
                dto = AtomicFile.readJson(configFile, Dto.class, "配置文件", LOG);
            }
        } catch (IOException e) {
            warnings.add("配置文件 IO 失败（" + e.getMessage() + "），已回退默认配置");
            dto = null;
        }
        if (dto == null) {
            warnings.add("配置文件为空或损坏且无可用备份，已回退默认配置");
            dto = GsonHolder.GSON.fromJson(DEFAULT_JSON, Dto.class);
        }

        List<QuotaTiers.Tier> tiers = tiersOf(dto);

        List<UUID> exempt = new ArrayList<>();
        if (dto.exemptPlayers != null) {
            for (String s : dto.exemptPlayers) {
                try {
                    exempt.add(UUID.fromString(s));
                } catch (IllegalArgumentException e) {
                    warnings.add("exemptPlayers 含非法 UUID: " + s);
                }
            }
        }

        return QuotaConfig.builder()
                .lines(QuotaTiers.toLines(tiers, warnings))
                .firstEntryFee(dto.firstEntryFee == null ? 1.0 : dto.firstEntryFee)
                .familiarEntryFee(dto.familiarEntryFee == null ? 0.05 : dto.familiarEntryFee)
                .highSpeedThreshold(dto.highSpeedThreshold == null ? 0.5 : dto.highSpeedThreshold)
                .highSpeedMultiplier(dto.highSpeedMultiplier == null ? 2.0 : dto.highSpeedMultiplier)
                .exemptByDefault(dto.exemptByDefault == null ? true : dto.exemptByDefault)
                .exemptPlayers(exempt)
                .saveIntervalSec(dto.saveIntervalSec == null ? 300 : dto.saveIntervalSec)
                .banScanIntervalSec(dto.banScanIntervalSec == null ? 30 : dto.banScanIntervalSec)
                .logFeeEvents(dto.logFeeEvents == null ? true : dto.logFeeEvents)
                .build(warnings);
    }

    /**
     * 读取四档原始配置（含禁用档的窗口/上限，供客户端管理页展示与编辑）。
     * 直接读 JSON 文件（复用 .bak 兜底）；失败回退默认。恒返回 4 项（tier1~tier4）。
     */
    public static List<QuotaTiers.Tier> readRawTiers(Path configFile) {
        Dto dto;
        if (!Files.exists(configFile)) {
            dto = GsonHolder.GSON.fromJson(DEFAULT_JSON, Dto.class);
        } else {
            dto = AtomicFile.readJson(configFile, Dto.class, "配置文件", LOG);
        }
        if (dto == null) {
            dto = GsonHolder.GSON.fromJson(DEFAULT_JSON, Dto.class);
        }
        return tiersOf(dto);
    }

    /** 四档原始配置（缺字段回退该档默认，与 DEFAULT_JSON 一致） */
    private static List<QuotaTiers.Tier> tiersOf(Dto dto) {
        return List.of(
                new QuotaTiers.Tier(dto.tier1Enabled == null ? true : dto.tier1Enabled,
                        dto.tier1Window == null ? "5h" : dto.tier1Window,
                        dto.tier1Limit == null ? 500.0 : dto.tier1Limit),
                new QuotaTiers.Tier(dto.tier2Enabled == null ? true : dto.tier2Enabled,
                        dto.tier2Window == null ? "24h" : dto.tier2Window,
                        dto.tier2Limit == null ? 2000.0 : dto.tier2Limit),
                new QuotaTiers.Tier(dto.tier3Enabled == null ? false : dto.tier3Enabled,
                        dto.tier3Window == null ? "7d" : dto.tier3Window,
                        dto.tier3Limit == null ? 10000.0 : dto.tier3Limit),
                new QuotaTiers.Tier(dto.tier4Enabled == null ? false : dto.tier4Enabled,
                        dto.tier4Window == null ? "30d" : dto.tier4Window,
                        dto.tier4Limit == null ? 40000.0 : dto.tier4Limit));
    }

    /**
     * /chunkplan config exemptByDefault 用：改 JSON 字段并原子写回（重启后保留）。
     * 用 GsonHolder（compact 单行格式），写回后格式与原文件一致。
     */
    public static void writeExemptByDefault(Path configFile, boolean value) throws IOException {
        com.google.gson.JsonObject root = readRoot(configFile);
        root.addProperty("exemptByDefault", value);
        dev.chunkplan.common.AtomicFile.write(configFile, GsonHolder.GSON.toJson(root));
    }

    /** /chunkplan config window 用：改写档位开关 */
    public static void writeTierEnabled(Path configFile, int tier, boolean enabled) throws IOException {
        com.google.gson.JsonObject root = readRoot(configFile);
        root.addProperty("tier" + tier + "Enabled", enabled);
        dev.chunkplan.common.AtomicFile.write(configFile, GsonHolder.GSON.toJson(root));
    }

    /** /chunkplan config windowTime 用：改写档位窗口时长 */
    public static void writeTierWindow(Path configFile, int tier, String window) throws IOException {
        com.google.gson.JsonObject root = readRoot(configFile);
        root.addProperty("tier" + tier + "Window", window);
        dev.chunkplan.common.AtomicFile.write(configFile, GsonHolder.GSON.toJson(root));
    }

    /** /chunkplan config windowLimit 用：改写档位额度上限（数值类型，勿用字符串 addProperty） */
    public static void writeTierLimit(Path configFile, int tier, double limit) throws IOException {
        com.google.gson.JsonObject root = readRoot(configFile);
        root.addProperty("tier" + tier + "Limit", limit);
        dev.chunkplan.common.AtomicFile.write(configFile, GsonHolder.GSON.toJson(root));
    }

    /** /chunkplan config highSpeedMultiplier 用：改写高速移动倍率 */
    public static void writeHighSpeedMultiplier(Path configFile, double multiplier) throws IOException {
        com.google.gson.JsonObject root = readRoot(configFile);
        root.addProperty("highSpeedMultiplier", multiplier);
        dev.chunkplan.common.AtomicFile.write(configFile, GsonHolder.GSON.toJson(root));
    }

    /** /chunkplan config firstEntryFee 用：改写踏入未探索区块的费用 */
    public static void writeFirstEntryFee(Path configFile, double fee) throws IOException {
        com.google.gson.JsonObject root = readRoot(configFile);
        root.addProperty("firstEntryFee", fee);
        dev.chunkplan.common.AtomicFile.write(configFile, GsonHolder.GSON.toJson(root));
    }

    /** /chunkplan config familiarEntryFee 用：改写踏入已探索区块的费用 */
    public static void writeFamiliarEntryFee(Path configFile, double fee) throws IOException {
        com.google.gson.JsonObject root = readRoot(configFile);
        root.addProperty("familiarEntryFee", fee);
        dev.chunkplan.common.AtomicFile.write(configFile, GsonHolder.GSON.toJson(root));
    }

    private static com.google.gson.JsonObject readRoot(Path configFile) throws IOException {
        // 坑 #31：运行时配置损坏（Gson 抛非受检 JsonSyntaxException，原实现会穿透命令层
        // catch(IOException)）→ 复用 AtomicFile.readJson 的 .bak 兜底（恢复后写回主文件修复现场，
        // 坑 #27 语义）；主与 .bak 均坏则抛 IOException，由命令层转固定反馈并拒绝写入（不覆盖损坏文件）
        com.google.gson.JsonObject root = AtomicFile.readJson(configFile, com.google.gson.JsonObject.class,
                "配置文件", LOG);
        if (root == null) {
            throw new IOException("配置文件与备份均损坏");
        }
        return root;
    }
}
